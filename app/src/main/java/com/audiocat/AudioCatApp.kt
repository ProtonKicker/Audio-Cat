package com.audiocat

import android.app.Application
import com.audiocat.data.SettingsStore
import com.audiocat.data.db.AudioCatDatabase
import com.audiocat.files.FileOperations
import com.audiocat.playback.PlaybackStateStore
import com.audiocat.scanner.ArtworkStore
import com.audiocat.scanner.LibraryScanner
import com.audiocat.scanner.ScanCoordinator
import com.audiocat.scanner.TrackMetadataReader
import com.audiocat.ui.common.ThemeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container.
 *
 * A DI framework would add a build-time processor and runtime cost for a graph
 * this small, so services are created lazily here instead.
 */
class AudioCatApp : Application() {

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: SettingsStore by lazy { SettingsStore(this) }

    val database: AudioCatDatabase by lazy { AudioCatDatabase.get(this) }

    val artwork: ArtworkStore by lazy { ArtworkStore(this) }

    val metadata: TrackMetadataReader by lazy { TrackMetadataReader(this) }

    val scanner: LibraryScanner by lazy {
        LibraryScanner(this, database, metadata, artwork)
    }

    val fileOperations: FileOperations by lazy { FileOperations(this, database) }

    val scanCoordinator: ScanCoordinator by lazy {
        ScanCoordinator(scanner, database, settings)
    }

    /** Snapshot parsed in the background at process start, before the UI loads. */
    @Volatile
    private var warmSnapshot: PlaybackStateStore.Snapshot? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Apply the stored theme before any activity inflates a layout.
        ThemeController.apply(settings.current.themeMode)
        // Kick off the auto-scan policy as early as possible so the library is
        // ready by the time the first screen appears.
        scanCoordinator.onAppStart()
        warmUpSnapshot()
    }

    /**
     * Parses the saved playback snapshot once, on an IO thread, while the
     * first frame is still being produced.
     *
     * The first frame of a cold start must never pay for reading and parsing
     * the snapshot file - both the player connection's provisional state and
     * the service's queue restore hand off to this warmed copy, falling back
     * to reading the file themselves only if it is not ready yet.
     */
    private fun warmUpSnapshot() {
        startupScope.launch {
            warmSnapshot = PlaybackStateStore(this@AudioCatApp).load()
        }
    }

    /** The snapshot parsed at startup, or null when there is none to restore. */
    fun snapshotAtStartup(): PlaybackStateStore.Snapshot? = warmSnapshot

    companion object {
        @Volatile
        private var instance: AudioCatApp? = null

        /**
         * The running application. Every caller runs inside the app process, so
         * this is only null when the process is being torn down.
         */
        fun get(): AudioCatApp = instance ?: error("AudioCatApp is not initialised")
    }
}
