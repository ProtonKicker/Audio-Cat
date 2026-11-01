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

    /** Reads the persisted session, used to seed the first frame of a cold start. */
    private val snapshotStore by lazy { PlaybackStateStore(context.applicationContext) }

    private val playerListener = PlayerListener()

    init {
        // A cold start has a restore window: the controller takes a moment to
        // connect and the service then rebuilds the saved queue through a
        // database lookup. Publishing the last session's snapshot up front means
        // the first frame never renders "Nothing playing" ahead of the restored
        // track - the authoritative state replaces it once the controller lands.
        preseedFromSnapshot()
        connect()
        startPositionTicker()
    }

    /**
     * Shows the previous session's queue before the service has restored it.
     *
     * Works off the persisted snapshot alone: the snapshot carries the media URI
     * of every queue entry, so no database lookup is needed to know what was
     * playing. Titles fall back to the file name until the library row arrives.
     */
    private fun preseedFromSnapshot() {
        // Prefer the snapshot parsed on an IO thread at process start so the
        // first frame never parses the JSON itself; fall back to reading the
        // file here only when the warm-up has not landed yet.
        val snapshot = app?.snapshotAtStartup() ?: snapshotStore.load() ?: return
        val provisional = QueueRestore.provisional(snapshot) ?: return
        _queue.value = provisional
        _currentTrack.value = provisional.current
        _shuffle.value = provisional.shuffle
        _repeat.value = provisional.repeat
        _positionMs.value = snapshot.positionMs
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
        // While a cold-start restore is in flight the player reports an empty
        // timeline and a zero position; the provisional snapshot published by
        // preseedFromSnapshot() stays up until the restore lands, so the real
        // (empty) state is not read over it yet.
        if (service?.restoringSnapshot == true && player.mediaItemCount == 0) return
        // The app manages shuffle by reordering the player's items (never by
        // setting native shuffleMode), so player.shuffleModeEnabled is always
        // false. Read the real state from the service's queue and only fall back
        // to the player when the service is unavailable.
        _shuffle.value = service?.queue?.shuffle ?: player.shuffleModeEnabled
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
     *
     * A media item with no library row is still shown: the media item carries
     * the title and artist the app put there, so the player never falls back
     * to "nothing playing" just because the catalogue lost a file.
     */
    private fun refreshQueue() {
        val player = controller ?: return
        val paths = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (paths.isEmpty()) {
            // The timeline is also empty while the service is still rebuilding
            // the saved session; keep the provisional snapshot so the UI does
            // not flash "Nothing playing" before the restore arrives.
            if (service?.restoringSnapshot != true) {
                _queue.value = PlaybackQueue()
                _currentTrack.value = null
            }
            return
        }
        // Read shuffle from the service's queue model when available — the app
        // manages shuffle by reordering items (player.shuffleModeEnabled is never
        // set), so the player always reports shuffle=false. The service's queue is
        // the source of truth; fall back to the player when the service is down.
        val shuffleState = service?.queue?.shuffle ?: player.shuffleModeEnabled
        scope.launch {
            val tracks = app?.database?.trackDao()?.byPaths(paths).orEmpty()
            val byPath = tracks.associateBy { it.path }
            val ordered = paths.mapIndexed { index, path ->
                byPath[path] ?: placeholderTrack(path, index)
            }
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
                shuffle = shuffleState,
                repeat = player.repeatMode.toRepeatMode(),
            )
            _currentTrack.value = ordered.getOrNull(index)
        }
    }

    /**
     * Builds a stand-in track for a media item the library has no row for.
     *
     * The metadata comes from the media item itself, which the app populated
     * when the queue was built, so the UI keeps showing what is playing.
     */
    private fun placeholderTrack(path: String, index: Int): Track {
        val item = controller?.getMediaItemAt(index)
        val metadata = item?.mediaMetadata
        return Track(
            path = path,
            uri = item?.localConfiguration?.uri?.toString().orEmpty(),
            title = metadata?.title?.toString()?.takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/'),
            artist = metadata?.artist?.toString(),
            album = metadata?.albumTitle?.toString(),
        )
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
            // Only trust the player callback when the service is not in control.
            // The app manages its own shuffle by reordering items — it never sets
            // native shuffleMode — so this callback always reports false and would
            // wipe out the real state read from the service's queue.
            if (service == null) _shuffle.value = shuffleModeEnabled
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
