package com.audiocat.playback

import android.app.PendingIntent
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.audiocat.MainActivity
import com.audiocat.data.AppSettings
import com.audiocat.data.RepeatMode
import com.audiocat.data.SettingsStore
import com.audiocat.data.db.AudioCatDatabase
import com.audiocat.data.db.Track
import com.audiocat.effects.EffectsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground playback service.
 *
 * Audio Cat attaches **no video renderer**: the player is built with
 * `DefaultRenderersFactory` (audio and text only) and no surface is ever set.
 * A video container therefore costs exactly the same as an audio file - the
 * video track is parsed for track metadata and then never decoded.
 *
 * The service owns the queue, the audio effects, and the media session that
 * backs the lockscreen, notification, Bluetooth and headset controls.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    /** Direct binder so the UI can reach the audio effects engine. */
    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsStore
    private lateinit var database: AudioCatDatabase
    private lateinit var stateStore: PlaybackStateStore

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /** Authoritative queue state; every mutation flows through [updateQueue]. */
    private var queue: PlaybackQueue = PlaybackQueue()

    /** Audio effects bound to the player's output session. */
    val effects = EffectsEngine()

    /** True while the player is ramping volume down for a crossfade. */
    private var fadingOut = false

    /**
     * Set when playback was stopped because the output device went away.
     *
     * It gates the "resume when headphones connect" preference so the app never
     * starts playing after a user-initiated pause.
     */
    private var pausedByDeviceDisconnect = false

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (settings.current.pauseOnHeadphonesDisconnect) {
                        pausedByDeviceDisconnect = true
                        player?.pause()
                    }
                }
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val plugged = intent.getIntExtra("state", 0) == 1
                    if (plugged) resumeAfterDeviceReturn()
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED,
                    )
                    if (state == BluetoothProfile.STATE_CONNECTED) resumeAfterDeviceReturn()
                }
            }
        }
    }

    /**
     * Resumes playback once a wired or Bluetooth output returns.
     *
     * Only fires when the earlier stop was caused by the device disappearing, so
     * a deliberate pause is never overridden.
     */
    private fun resumeAfterDeviceReturn() {
        if (!pausedByDeviceDisconnect) return
        if (!settings.current.resumeOnHeadphones) return
        pausedByDeviceDisconnect = false
        player?.play()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        database = AudioCatDatabase.get(this)
        stateStore = PlaybackStateStore(this)

        val exoPlayer = buildPlayer()
        player = exoPlayer
        applySettings(settings.current)

        session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(openAppIntent())
            .setCallback(SessionCallback())
            .build()

        registerDeviceReceiver()
        restoreQueue()
    }

    /**
     * Listens for output-device changes so the headphone preferences take effect.
     *
     * The system only broadcasts becoming-noisy, so headset and Bluetooth
     * connection state are observed as well to support resuming.
     */
    private fun registerDeviceReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            deviceReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == ACTION_LOCAL_BIND) binder else super.onBind(intent)

    override fun onDestroy() {
        persistState()
        runCatching { unregisterReceiver(deviceReceiver) }
        session?.release()
        session = null
        player?.release()
        player = null
        effects.release()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the user swipes the app away, but persist first so
        // the queue survives a later process death.
        persistState()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Builds an audio-only player.
     *
     * [androidx.media3.exoplayer.DefaultRenderersFactory] is used in its default
     * configuration, which installs audio and text renderers. No video renderer
     * and no surface means the decoder never touches a video stream, and
     * `setVideoEffects` is intentionally never called.
     */
    private fun buildPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(this)
            // Required for real-time speed and pitch changes.
            .setEnableAudioTrackPlaybackParams(true)
            .setEnableAudioOutputPlaybackParameters(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val loadControl = DefaultLoadControl.Builder()
            // Audio-only streams are cheap; a shallow buffer starts playback
            // almost instantly while still tolerating a brief stall.
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .build()

        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        exoPlayer.addListener(PlayerListener())
        // Effects live on the raw audio session id.
        effects.attach(exoPlayer.audioSessionId)
        return exoPlayer
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ---------------------------------------------------------------------
    // Queue API - the only public surface the UI uses besides the session.
    // ---------------------------------------------------------------------

    /** Current queue snapshot, for the queue sheet and now-playing bar. */
    fun currentQueue(): PlaybackQueue = queue

    /**
     * Replaces the queue and starts playing [startIndex].
     *
     * This is the single entry point for "play this list": shuffle and repeat
     * defaults are taken from settings so the behaviour matches the UI toggles.
     */
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        val useShuffle = shuffle ?: settings.current.shuffleByDefault
        queue = QueueEngine.replace(
            tracks = tracks,
            startIndex = startIndex,
            shuffle = useShuffle,
            repeat = settings.current.repeatMode,
        )
        pushQueueToPlayer(startPositionMs = 0L, autoPlay = true)
    }

    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queue = QueueEngine.playNext(queue, tracks)
        pushQueueToPlayer(preservePosition = true)
    }

    fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (queue.isEmpty) {
            play(tracks)
            return
        }
        queue = QueueEngine.add(queue, tracks)
        pushQueueToPlayer(preservePosition = true)
    }

    /**
     * Jumps to a position within the play order, which is what the queue sheet
     * displays and what the player's media item indices correspond to.
     *
     * @return true when the service handled the jump.
     */
    fun jumpToQueuePosition(playOrderPosition: Int): Boolean {
        val trackIndex = queue.order.getOrNull(playOrderPosition) ?: return false
        val target = queue.tracks.getOrNull(trackIndex) ?: return false
        val player = player ?: return false
        queue = queue.copy(cursor = playOrderPosition)
        if (player.currentMediaItem?.mediaId == target.path) {
            player.seekTo(0L)
            player.play()
            return true
        }
        player.seekTo(playOrderPosition, 0L)
        player.play()
        onTrackChanged()
        return true
    }

    fun skipNext() {
        val next = QueueEngine.next(queue, userInitiated = true)
        applyCursor(next)
    }

    fun skipPrevious() {
        val current = player ?: return
        // Match the usual convention: restart the track first, then step back.
        if (current.currentPosition > RESTART_THRESHOLD_MS) {
            current.seekTo(0)
            return
        }
        applyCursor(QueueEngine.previous(queue))
    }

    fun setShuffle(enabled: Boolean) {
        queue = QueueEngine.setShuffle(queue, enabled)
        val player = player ?: return
        val position = player.currentPosition
        pushQueueToPlayer(startPositionMs = position, preservePosition = true, autoPlay = player.isPlaying)
    }

    fun setRepeat(mode: RepeatMode) {
        queue = QueueEngine.setRepeat(queue, mode)
        player?.repeatMode = when (mode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }
    }

    fun removeFromQueue(playOrderPosition: Int) {
        val removedPath = queue.order.getOrNull(playOrderPosition)?.let { queue.tracks.getOrNull(it) }?.path
        queue = QueueEngine.removeAt(queue, playOrderPosition)
        if (queue.isEmpty) {
            player?.clearMediaItems()
            player?.stop()
            return
        }
        // Must be read before the items are replaced: afterwards the player is
        // already showing the track that took the removed one's slot.
        val removedWasPlaying = removedPath != null && player?.currentMediaItem?.mediaId == removedPath
        pushQueueToPlayer(preservePosition = true)
        if (removedWasPlaying) {
            // Deleting what is playing means "move on", so the switch also plays.
            player?.seekTo(queue.cursor.coerceAtLeast(0), 0L)
            player?.play()
            onTrackChanged()
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        queue = QueueEngine.move(queue, from, to)
        pushQueueToPlayer(preservePosition = true)
    }

    fun clearQueue() {
        queue = QueueEngine.clear()
        player?.clearMediaItems()
        player?.stop()
    }

    /** Applies playback speed and pitch in real time. */
    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        player?.playbackParameters = PlaybackParameters(speed, pitch)
    }

    /** Re-reads settings and applies everything that can change live. */
    fun applySettings(settings: AppSettings) {
        setRepeat(settings.repeatMode)
        setSpeedAndPitch(settings.playbackSpeed, settings.playbackPitch)
        effects.setEnabled(settings.effectsEnabled)
        if (settings.crossfadeMs <= 0) {
            // Clear any half-finished fade so volume is never stuck low.
            player?.volume = 1f
            fadingOut = false
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Mirrors [queue] onto the player's media items.
     *
     * The player's item order always matches the queue's play order, so
     * "next"/"previous" stay correct even while shuffled.
     *
     * Playback state is preserved by default: only an explicit request to start
     * (a new queue, a skip, a jump) begins audio. Editing a queue the user is not
     * listening to must never make noise.
     */
    private fun pushQueueToPlayer(
        startPositionMs: Long = 0L,
        preservePosition: Boolean = false,
        autoPlay: Boolean = false,
    ) {
        val player = player ?: return
        val previousPosition = if (preservePosition) player.currentPosition else startPositionMs
        // playWhenReady as well as isPlaying: a track that is still buffering is
        // on its way to playing and must not be knocked back out of it.
        val wasPlaying = player.isPlaying || player.playWhenReady

        val items = queue.orderedTracks.map { it.toMediaItem() }
        player.setMediaItems(items, queue.cursor.coerceAtLeast(0), previousPosition)
        player.prepare()
        if (autoPlay || wasPlaying) player.play()
    }

    /** Moves the player to the queue's current cursor. */
    private fun applyCursor(next: PlaybackQueue) {
        val player = player ?: return
        val changed = next.cursor != queue.cursor
        queue = next
        if (!changed) return
        if (player.mediaItemCount == 0) return
        player.seekTo(queue.cursor.coerceIn(0, (player.mediaItemCount - 1).coerceAtLeast(0)), 0L)
        player.play()
        onTrackChanged()
    }

    private fun onTrackChanged() {
        persistState()
        // A new track resets the fade state; the actual fade starts in
        // onPlaybackStateChanged(STATE_READY) once audio is flowing.
        fadingOut = false
    }

    /**
     * Removes the queue slot at [position] and starts whatever takes its place.
     *
     * The queue and the player share one index space, so dropping the entry from
     * both leaves the following track exactly where the broken one was - which is
     * what "skip past it and keep playing" means.
     */
    private fun skipBrokenItem(position: Int, player: ExoPlayer) {
        if (position in 0 until player.mediaItemCount) player.removeMediaItem(position)
        queue = QueueEngine.removeAt(queue, position)
        if (queue.isEmpty || player.mediaItemCount == 0) {
            clearQueue()
            return
        }
        player.seekTo(queue.cursor.coerceIn(0, player.mediaItemCount - 1), 0L)
        player.play()
        onTrackChanged()
    }

    /** Drops a library row whose file has disappeared, so lists stop offering it. */
    private fun forgetTrack(path: String) {
        scope.launch {
            runCatching { database.trackDao().deleteByPaths(listOf(path)) }
                .onFailure { Log.w(TAG, "failed to forget $path", it) }
        }
    }

    /**
     * Fades volume in when a track starts.
     *
     * ExoPlayer cannot cross-fade two decoders, so a true crossfade is not
     * possible without dropping to a custom audio sink. When the user enables
     * crossfade the app instead uses the configured duration as an in/out fade,
     * which is the behaviour that is actually deliverable on the platform.
     *
     * Messages are scheduled relative to the *end* of the buffered window rather
     * than the current position, so the fade always has room to complete.
     */
    private fun applyCrossfadeIn() {
        val player = player ?: return
        val duration = settings.current.crossfadeMs
        if (duration <= 0) {
            player.volume = 1f
            return
        }
        player.volume = 0f
        val stepMs = (duration.toLong() / 2 / FADE_STEPS).coerceAtLeast(1L)
        val base = player.currentPosition
        for (step in 1..FADE_STEPS) {
            player.createMessage { _, _ ->
                player.volume = step.toFloat() / FADE_STEPS
            }.setLooper(android.os.Looper.getMainLooper())
                .setPosition(base + stepMs * step)
                .send()
        }
    }

    /** Fades volume to silence before a manual pause, when crossfade is enabled. */
    private fun applyCrossfadeOut() {
        val player = player ?: return
        val duration = settings.current.crossfadeMs
        if (duration <= 0) return
        fadingOut = true
        val stepMs = (duration.toLong() / 2 / FADE_STEPS).coerceAtLeast(1L)
        val base = player.currentPosition
        for (step in 1..FADE_STEPS) {
            val target = 1f - step.toFloat() / FADE_STEPS
            player.createMessage { _, _ ->
                player.volume = target
            }.setLooper(android.os.Looper.getMainLooper())
                .setPosition(base + stepMs * step)
                .send()
        }
    }

    /** Whether the UI should pause playback instead of calling the player directly. */
    fun pauseWithFade() {
        val player = player ?: return
        val duration = settings.current.crossfadeMs
        if (duration <= 0 || !player.isPlaying) {
            player.pause()
            return
        }
        applyCrossfadeOut()
        player.createMessage { _, _ ->
            player.pause()
            // Restore full volume so a later resume is not silent; the fade-in
            // scheduled on STATE_READY takes over from here.
            player.volume = 1f
            fadingOut = false
        }
            .setLooper(android.os.Looper.getMainLooper())
            .setPosition(player.currentPosition + duration / 2)
                .send()
    }

    private fun persistState() {
        val player = player ?: return
        if (queue.isEmpty) {
            stateStore.clear()
            return
        }
        stateStore.save(queue, player.currentPosition)
    }

    /**
     * Rebuilds the previous queue after the service is recreated.
     *
     * The player is left paused: restoring into a playing state would surprise
     * the user and is also blocked by background-start restrictions.
     */
    private fun restoreQueue() {
        val snapshot = stateStore.load() ?: return
        scope.launch {
            val tracks = database.trackDao().byPaths(snapshot.paths)
            if (tracks.isEmpty()) return@launch
            // Preserve the saved order, which may be a shuffle permutation.
            val byPath = tracks.associateBy { it.path }
            val ordered = snapshot.paths.mapNotNull { byPath[it] }
            if (ordered.isEmpty()) return@launch
            val restored = PlaybackQueue(
                tracks = ordered,
                order = ordered.indices.toList(),
                cursor = snapshot.cursor.coerceIn(0, ordered.lastIndex),
                shuffle = snapshot.shuffle,
                repeat = runCatching { RepeatMode.valueOf(snapshot.repeat) }
                    .getOrDefault(RepeatMode.OFF),
            )
            queue = restored
            val player = player ?: return@launch
            player.setMediaItems(ordered.map { it.toMediaItem() }, restored.cursor, snapshot.positionMs)
            player.prepare()
            // Keep the restored queue's repeat mode consistent with settings.
            setRepeat(settings.current.repeatMode)
        }
    }

    /** Applies user preferences, e.g. after the settings screen changes them. */
    fun onSettingsChanged(settings: AppSettings) {
        applySettings(settings)
    }

    fun saveState() = persistState()

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(path)
        .setUri(uri)
        // Metadata is supplied by the app (from the library DB), so the player
        // never has to parse it for the notification.
        .setMediaMetadata(NotificationMetadata.of(this))
        .build()

    /**
     * Player callbacks: keeps queue-based effects and persistence in sync with
     * what the player actually does.
     */
    private inner class PlayerListener : Player.Listener {

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            effects.attach(audioSessionId)
            if (audioSessionId != 0) {
                val preset = settings.current.activePreset
                if (preset != null) applyPresetByName(preset)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                handleTrackEnded()
            }
            if (playbackState == Player.STATE_READY) {
                // Start the fade only once audio is actually flowing, otherwise
                // the ramp would run before the first sample.
                if (!fadingOut) applyCrossfadeIn()
                persistState()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                fadingOut = false
            } else {
                persistState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // An unreadable file would otherwise leave the player in IDLE with the
            // queue pointing at it: every later switch either re-fails or, at the
            // end of the queue, stops dead. Drop the item and keep playing.
            val player = player ?: return
            val failed = player.currentMediaItem?.mediaId
            Log.w(TAG, "playback failed for $failed", error)
            val position = player.currentMediaItemIndex.coerceAtMost(queue.order.lastIndex)
            if (failed != null && !File(failed).exists()) forgetTrack(failed)
            skipBrokenItem(position, player)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                // The player moved on its own (repeat-all or a seek), so the
                // cursor must follow or the queue sheet would lie.
                val mediaIndex = newPosition.mediaItemIndex
                if (mediaIndex != queue.cursor && mediaIndex in queue.order.indices) {
                    queue = queue.copy(cursor = mediaIndex)
                }
                onTrackChanged()
            }
        }
    }

    /**
     * Decides what happens when a track finishes.
     *
     * Repeat-one is delegated to ExoPlayer's own repeat mode; everything else is
     * queue arithmetic so that "repeat all" wraps and "off" stops at the end. The
     * explicit repeat-one branch matters when the player's repeat mode has been
     * reset, for example after the queue was rebuilt.
     */
    private fun handleTrackEnded() {
        val player = player ?: return
        if (queue.repeat == RepeatMode.ONE) {
            player.seekTo(0)
            player.play()
            return
        }
        val next = QueueEngine.next(queue, userInitiated = false)
        if (next.cursor == queue.cursor) {
            // End of the queue with no repeat: stop instead of looping silently.
            player.pause()
            player.seekTo(0)
        } else {
            applyCursor(next)
        }
    }

    private fun applyPresetByName(name: String) {
        val preset = com.audiocat.effects.EffectsEngine.builtInPresets()
            .firstOrNull { it.name == name } ?: return
        effects.applyPreset(preset)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        /** Intent action used for direct local binding from the UI. */
        const val ACTION_LOCAL_BIND = "com.audiocat.action.LOCAL_BIND"

        /** Tapping "previous" after this long restarts the current track. */
        private const val RESTART_THRESHOLD_MS = 3_000L

        private const val FADE_STEPS = 10

        /**
         * Set while the service is alive. The app runs the service in its own
         * process, so this is only ever touched from the main thread.
         */
        @Volatile
        private var instance: PlaybackService? = null

        /** The running service, or null when playback has been torn down. */
        fun peek(): PlaybackService? = instance
    }
}

/** Small helper so the wrapper above stays focused on playback concerns. */
private object NotificationMetadata {
    fun of(track: Track): androidx.media3.common.MediaMetadata =
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
}
