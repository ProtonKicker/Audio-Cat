package com.audiocat.scanner

import com.audiocat.data.AppSettings
import com.audiocat.data.NewFileBehaviour
import com.audiocat.data.SettingsStore
import com.audiocat.data.db.AudioCatDatabase
import com.audiocat.data.db.FolderMode
import com.audiocat.data.db.ScanFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Progress of the current or most recent scan. */
data class ScanStatus(
    val running: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val lastResult: ScanResult? = null,
    val lastError: String? = null,
) {
    val progress: Float
        get() = if (total <= 0) 0f else (scanned.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Single owner of scanning.
 *
 * Everything that wants a scan goes through here so that two entry points (the
 * auto-scan policy and the manual button) can never run concurrently and fight
 * over the database.
 */
class ScanCoordinator(
    private val scanner: LibraryScanner,
    private val database: AudioCatDatabase,
    private val settings: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    /** Folders as configured by the user, observed by the Settings screen. */
    val folders = database.scanFolderDao().observeAll()

    /** Timer that drives periodic auto-scans while the app is alive. */
    private var autoScanJob: Job? = null

    /**
     * Applies the auto-scan policy at startup.
     *
     * When auto-scan is enabled the library is refreshed immediately into the
     * background and then periodically; when it is disabled only the user-facing
     * manual trigger scans.
     */
    fun onAppStart() {
        if (!settings.current.autoScan) {
            autoScanJob?.cancel()
            return
        }
        scan(force = false)
        schedulePeriodicScan()
    }

    /** Re-evaluates the auto-scan policy after the setting changes. */
    fun onAutoScanSettingChanged(enabled: Boolean) {
        autoScanJob?.cancel()
        if (enabled) {
            scan(force = false)
            schedulePeriodicScan()
        }
    }

    private fun schedulePeriodicScan() {
        val interval = settings.current.autoScanIntervalMs
        if (interval <= 0) return
        autoScanJob = scope.launch {
            while (isActive) {
                delay(interval)
                // Settings may have changed while sleeping.
                if (!settings.current.autoScan) return@launch
                scan(force = false)
            }
        }
    }

    /**
     * Runs a scan.
     *
     * @param force re-reads files whose size and modification time are unchanged.
     * @param foldersOverride explicit folder selection, used by "scan this folder".
     * @param onFinished called with the result **on the main thread** - it exists
     *   for the UI, and handing it back on the scan's worker thread lets callers
     *   touch main-thread-only APIs (a media controller asserts on this) and crash.
     */
    fun scan(
        force: Boolean = false,
        foldersOverride: List<String>? = null,
        onFinished: ((ScanResult) -> Unit)? = null,
    ) {
        scope.launch {
            val result = runScan(force, foldersOverride)
            if (result != null && onFinished != null) {
                withContext(Dispatchers.Main.immediate) { onFinished(result) }
            }
        }
    }

    /** Suspending variant, used by tests and by callers that need the result. */
    suspend fun scanNow(force: Boolean = false, foldersOverride: List<String>? = null): ScanResult? =
        runScan(force, foldersOverride)

    private suspend fun runScan(force: Boolean, foldersOverride: List<String>?): ScanResult? {
        // A second scan request while one is running is dropped rather than
        // queued: the running scan already sees the latest filesystem state.
        if (mutex.isLocked) return null
        return mutex.withLock {
            _status.value = ScanStatus(running = true)
            try {
                val config = resolveFolders(foldersOverride)
                val result = scanner.scan(
                    includeFolders = config.first,
                    excludeFolders = config.second,
                    force = force,
                    // Art quality is a user preference; the scanner needs it to
                    // decide how large the cached artwork should be.
                    artMaxPx = ArtworkStore.maxPxForQuality(settings.current.artQuality),
                    newFilePolicy = when (settings.current.newFileBehaviour) {
                        NewFileBehaviour.UNLIST -> NewFilePolicy.ADD_UNLISTED
                        else -> NewFilePolicy.ADD
                    },
                ) { scanned, total ->
                    _status.value = _status.value.copy(scanned = scanned, total = total)
                }
                settings.markScanned()
                _status.value = ScanStatus(
                    running = false,
                    scanned = result.indexed,
                    total = result.discovered,
                    lastResult = result,
                )
                result
            } catch (e: Exception) {
                _status.value = ScanStatus(running = false, lastError = e.message ?: "Scan failed")
                null
            }
        }
    }

    /**
     * Resolves the effective include/exclude lists.
     *
     * No configured folders means "every volume", which is the zero-setup
     * default. Excludes always win, and `Android/data` style system folders are
     * filtered by the scanner itself.
     */
    private suspend fun resolveFolders(override: List<String>?): Pair<List<String>, List<String>> {
        if (override != null) return override to currentExcludes()
        val included = currentIncludes()
        return included to currentExcludes()
    }

    private suspend fun currentIncludes(): List<String> =
        database.scanFolderDao().byMode(FolderMode.INCLUDE).map { it.path }.filter { it.isNotBlank() }

    private suspend fun currentExcludes(): List<String> =
        database.scanFolderDao().byMode(FolderMode.EXCLUDE).mapNotNull { folder ->
            // A volume-less exclusion is meaningless; ignore it rather than
            // excluding the entire library.
            folder.path.takeIf { it.isNotBlank() }
        }

    // ---------------------------------------------------------------------
    // Folder management
    // ---------------------------------------------------------------------

    suspend fun addFolder(path: String, mode: FolderMode): Boolean {
        val normalized = ScanPlanner.normalize(path)
        if (normalized.isEmpty() || normalized == "/") return false
        // Re-adding as a different mode simply replaces the previous entry.
        database.scanFolderDao().deleteByPath(normalized)
        database.scanFolderDao().insert(
            ScanFolder(
                path = normalized,
                mode = mode,
                fileCount = countMediaFiles(normalized),
            ),
        )
        return true
    }

    /** Adds the default "all volumes" entry that a fresh install uses. */
    suspend fun ensureDefaultFolder() {
        if (database.scanFolderDao().count() > 0) return
        database.scanFolderDao().insert(ScanFolder(path = "", mode = FolderMode.INCLUDE))
    }

    suspend fun removeFolder(id: Long) = database.scanFolderDao().delete(id)

    suspend fun setFolderMode(folder: ScanFolder, mode: FolderMode) {
        database.scanFolderDao().insert(folder.copy(mode = mode))
    }

    /** Recounts media files for every configured folder, for the settings list. */
    suspend fun refreshFolderCounts() {
        database.scanFolderDao().byMode(FolderMode.INCLUDE).forEach { folder ->
            if (folder.path.isBlank()) return@forEach
            database.scanFolderDao().updateFileCount(folder.path, countMediaFiles(folder.path))
        }
    }

    /**
     * Counts playable files under [path].
     *
     * Bounded so that adding a huge folder cannot make the settings screen hang;
     * the displayed count is capped at [FILE_COUNT_LIMIT].
     */
    private fun countMediaFiles(path: String): Int {
        val root = File(path)
        if (!root.isDirectory) return 0
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && count < FILE_COUNT_LIMIT) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name != "." && child.name != "..") stack.addLast(child)
                } else if (ScanPlanner.isPlayable(child.name)) {
                    count++
                    if (count >= FILE_COUNT_LIMIT) break
                }
            }
        }
        return count
    }

    private companion object {
        /** Upper bound for the folder file count shown in Settings. */
        const val FILE_COUNT_LIMIT = 5_000
    }
}

/** Convenience for screens that only care whether a scan is in flight. */
val ScanStatus.isIdle: Boolean get() = !running

/** Human-readable summary of a finished scan. */
fun ScanResult.summary(): String = buildString {
    append(indexed).append(" indexed")
    if (skipped > 0) append(", ").append(skipped).append(" unchanged")
    if (removed > 0) append(", ").append(removed).append(" removed")
    // The count of files whose tags and cover were opened, which is how a user
    // can tell a full refresh from an incremental scan that skipped the rest.
    if (enriched > 0) append(", ").append(enriched).append(" read")
}

/** Reads the current settings without depending on a whole AppSettings import. */
internal fun SettingsStore.snapshot(): AppSettings = current
