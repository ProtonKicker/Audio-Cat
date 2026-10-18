package com.audiocat

import android.app.Application
import com.audiocat.data.SettingsStore
import com.audiocat.data.db.AudioCatDatabase
import com.audiocat.files.FileOperations
import com.audiocat.scanner.ArtworkStore
import com.audiocat.scanner.LibraryScanner
import com.audiocat.scanner.ScanCoordinator
import com.audiocat.scanner.TrackMetadataReader
import com.audiocat.ui.common.ThemeController

/**
 * Manual dependency container.
 *
 * A DI framework would add a build-time processor and runtime cost for a graph
 * this small, so services are created lazily here instead.
 */
class AudioCatApp : Application() {

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Apply the stored theme before any activity inflates a layout.
        ThemeController.apply(settings.current.themeMode)
        // Kick off the auto-scan policy as early as possible so the library is
        // ready by the time the first screen appears.
        scanCoordinator.onAppStart()
    }

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
