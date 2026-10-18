package com.audiocat.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.audiocat.AudioCatApp
import com.audiocat.data.RepeatMode
import com.audiocat.data.db.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single bridge between the UI and [PlaybackService].
 *
 * A [MediaController] talks to the service's media session, so playback state is
 * shared with the notification, the lockscreen and any other controller.
 * Features the session protocol does not model - the equaliser and
 * drag-to-reorder queue - go through the service's local binder, which is safe
 * because the service always runs in this process.
 */
@AndroidXOptIn(UnstableApi::class)
class PlayerConnection(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app: AudioCatApp? get() = context.applicationContext as? AudioCatApp

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** The track the player is pointed at, or null when nothing is loaded. */
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    /** The full queue in play order, mirrored from the session's media items. */
    private val _queue = MutableStateFlow(PlaybackQueue())
    val queue: StateFlow<PlaybackQueue> = _queue.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    /** The running service, when it has been created in this process. */
    val service: PlaybackService? get() = PlaybackService.peek()

    private val playerListener = PlayerListener()

    init {
        connect()
        startPositionTicker()
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val resolved = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = resolved
                resolved.addListener(playerListener)
                _isConnected.value = true
                syncAll()
            },
            // MediaController requires the callback on the main looper.
            { runnable -> scope.launch { runnable.run() } },
        )
    }

    /**
     * Polls the playback position.
     *
     * A [Player.Listener] fires on every buffered position update, which is far
     * more often than a seek bar needs; polling twice a second keeps the UI
     * smooth at a negligible cost.
     */
    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                // A throw here would otherwise kill the loop silently and the
                // seek bar would freeze at its last value forever.
                runCatching {
                    val player = controller ?: return@runCatching
                    _positionMs.value = player.currentPosition
                }
            }
        }
    }

    private fun syncAll() {
        val player = controller ?: return
        _isPlaying.value = player.isPlaying
        _shuffle.value = player.shuffleModeEnabled
        _repeat.value = player.repeatMode.toRepeatMode()
        _durationMs.value = player.duration.coerceAtLeast(0L)
        _positionMs.value = player.currentPosition
        refreshQueue()
    }

    /**
     * Re-reads the library rows behind the queue.
     *
     * The UI binds title, artist and cover from the database row rather than from
     * the media item, so anything that changes a track outside playback - a cover
     * the user just set - has to ask for this to become visible.
     */
    fun refreshCurrentTrack() {
        if (controller != null) refreshQueue()
    }

    /**
     * Rebuilds the queue snapshot from the session's media items, resolving each
     * item back to its library row so the UI has full metadata and artwork.
     */
    private fun refreshQueue() {
        val player = controller ?: return
        val paths = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (paths.isEmpty()) {
            _queue.value = PlaybackQueue()
            _currentTrack.value = null
            return
        }
        scope.launch {
            val tracks = app?.database?.trackDao()?.byPaths(paths).orEmpty()
            val byPath = tracks.associateBy { it.path }
            val ordered = paths.mapNotNull { byPath[it] }
            if (ordered.isEmpty()) {
                _queue.value = PlaybackQueue()
                _currentTrack.value = null
                return@launch
            }
            val index = player.currentMediaItemIndex.coerceIn(0, ordered.lastIndex)
            _queue.value = PlaybackQueue(
                tracks = ordered,
                order = ordered.indices.toList(),
                cursor = index,
                shuffle = player.shuffleModeEnabled,
                repeat = player.repeatMode.toRepeatMode(),
            )
            _currentTrack.value = ordered.getOrNull(index)
        }
    }

    // ---------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------

    fun playPause() {
        if (_isPlaying.value) pause() else play()
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        // Route through the service so crossfade is honoured.
        service?.pauseWithFade() ?: controller?.pause()
    }

    fun next() {
        // The service owns the queue arithmetic; the session fallback has to
        // start playback itself because seekTo*MediaItem never touches
        // playWhenReady, which would leave a paused player on a new track.
        val local = service
        if (local != null) {
            local.skipNext()
        } else {
            controller?.apply {
                seekToNextMediaItem()
                play()
            }
        }
    }

    fun previous() {
        val local = service
        if (local != null) {
            local.skipPrevious()
        } else {
            controller?.apply {
                seekToPreviousMediaItem()
                play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        _positionMs.value = positionMs.coerceAtLeast(0L)
    }

    fun seekToFraction(fraction: Float) {
        val duration = controller?.duration ?: return
        if (duration <= 0) return
        seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun setShuffle(enabled: Boolean) {
        // The service owns the queue model, so shuffle goes through it to keep
        // the sheet and the player's item order in agreement.
        service?.setShuffle(enabled) ?: run { controller?.shuffleModeEnabled = enabled }
        _shuffle.value = enabled
    }

    fun setRepeat(mode: RepeatMode) {
        service?.setRepeat(mode) ?: run { controller?.repeatMode = mode.toPlayerMode() }
        _repeat.value = mode
    }

    fun cycleRepeat() {
        setRepeat(
            when (_repeat.value) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            },
        )
    }

    /** Replaces the queue and starts playing [startIndex]. */
    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean? = null) {
        val local = service
        if (local != null) {
            local.play(tracks, startIndex, shuffle)
        } else {
            controller?.apply {
                setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
                prepare()
                play()
            }
        }
        shuffle?.let { _shuffle.value = it }
    }

    /**
     * Jumps to [playOrderPosition] within the play order shown by the queue
     * sheet. Falls back to the raw session index when the service is not up.
     */
    fun jumpToQueuePosition(playOrderPosition: Int) {
        if (service?.jumpToQueuePosition(playOrderPosition) == true) return
        controller?.seekTo(playOrderPosition, 0L)
        controller?.play()
    }

    fun playQueue(queue: PlaybackQueue) {
        playTracks(queue.tracks, queue.currentTrackIndex.coerceAtLeast(0), queue.shuffle)
    }

    fun enqueue(tracks: List<Track>) {
        service?.enqueue(tracks) ?: controller?.addMediaItems(tracks.map { it.toMediaItem() })
    }

    fun playNext(tracks: List<Track>) {
        val local = service
        if (local != null) {
            local.playNext(tracks)
        } else {
            val at = (controller?.currentMediaItemIndex ?: 0) + 1
            controller?.addMediaItems(at, tracks.map { it.toMediaItem() })
        }
    }

    fun clearQueue() {
        service?.clearQueue() ?: controller?.clearMediaItems()
    }

    fun removeFromQueue(playOrderPosition: Int) = service?.removeFromQueue(playOrderPosition)

    fun moveInQueue(from: Int, to: Int) = service?.moveInQueue(from, to)

    /** Persists the queue immediately, e.g. when the app is backgrounded. */
    fun saveState() = service?.saveState()

    fun release() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _isConnected.value = false
    }

    private inner class PlayerListener : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) _positionMs.value = controller?.currentPosition ?: 0L
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The new item starts from zero; publishing that immediately keeps
            // the seek bar from showing the previous track's position for a
            // tick (or forever, if the poll raced the transition).
            _positionMs.value = 0L
            _durationMs.value = controller?.duration?.coerceAtLeast(0L) ?: 0L
            refreshQueue()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _durationMs.value = controller?.duration?.coerceAtLeast(0L) ?: 0L
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffle.value = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeat.value = repeatMode.toRepeatMode()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refreshQueue()
        }
    }

    private fun Int.toRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    private fun RepeatMode.toPlayerMode(): Int = when (this) {
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(path)
        .setUri(uri)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build(),
        )
        .build()

    private companion object {
        /** Seek-bar refresh interval. */
        const val POSITION_TICK_MS = 500L
    }
}
