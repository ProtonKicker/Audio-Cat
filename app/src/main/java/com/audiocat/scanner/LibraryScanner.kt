package com.audiocat.scanner

import androidx.room.withTransaction
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.audiocat.data.db.AudioCatDatabase
import com.audiocat.data.db.FolderMode
import com.audiocat.data.db.ScanFolder
import com.audiocat.data.db.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Directories that never contain music the user wants in the library. */
private val IGNORED_SEGMENTS = setOf(
    "Android/data",
    "Android/obb",
    "Android/media",
    ".thumbnails",
    "notifications",
    "ringtones",
    "alarms",
    "Recordings/Voice",
)

/**
 * Extensions the scanner treats as playable. Video containers are included on
 * purpose: Audio Cat pulls only their audio track.
 */
val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "aac", "m4a", "wav", "ogg", "oga", "opus", "alac", "aiff",
    "aif", "wma", "amr", "mka", "ape", "wv", "mp2", "mpga", "ac3", "dts", "m4b",
)

val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "3gp", "mpg",
    "mpeg", "ts", "m2ts", "vob", "ogv", "rmvb", "divx", "f4v", "asf",
)

private const val TAG = "LibraryScanner"

/** What the scanner does with a file it has never seen before. */
enum class NewFilePolicy {
    /** Index it and show it in the library. */
    ADD,

    /** Index it but mark it unlisted, so it only appears in File Management. */
    ADD_UNLISTED,
}

/** One playable file discovered during a scan. */
data class ScanCandidate(
    val path: String,
    val uri: String,
    val displayName: String,
    val size: Long,
    val lastModified: Long,
    val durationMs: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val mimeType: String?,
    val isVideo: Boolean,
)

/** What the planner decided to do with a candidate. */
sealed interface ScanDecision {
    /** New file, or an existing one whose size/mtime changed -> (re)index it. */
    data class Index(val candidate: ScanCandidate) : ScanDecision

    /** Already indexed and unchanged -> skip the expensive metadata work. */
    data class Skip(val candidate: ScanCandidate) : ScanDecision
}

data class ScanPlan(
    val toIndex: List<ScanCandidate>,
    val unchanged: List<ScanCandidate>,
)

/**
 * Pure scan planning logic, deliberately free of Android dependencies so it can
 * be covered by fast JVM unit tests.
 */
object ScanPlanner {

    /**
     * The lower-case extension of a file name, without the dot, or empty.
     *
     * One rule for the whole app, because MediaStore's mime column cannot be
     * trusted for filtering: on the test phone every `.m4a` is reported as
     * `audio/mp4a-latm` while `.mp3` rows are `audio/mpeg`, so a mime-based
     * filter splits one format across two keys.
     */
    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isPlayable(displayName: String): Boolean {
        val ext = extensionOf(displayName)
        return ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS
    }

    fun isVideo(displayName: String): Boolean = extensionOf(displayName) in VIDEO_EXTENSIONS

    /** Normalises a path for prefix comparison (no trailing slash). */
    fun normalize(path: String): String =
        path.trimEnd('/', '\\').ifEmpty { "/" }

    /**
     * True when [path] lives inside [folder].
     *
     * Comparison happens on whole path segments so that excluding `/Music/Live`
     * never accidentally excludes `/Music/LiveBootlegs`.
     */
    fun isInside(path: String, folder: String): Boolean {
        val p = normalize(path)
        val f = normalize(folder)
        if (f == "/" || f.isEmpty()) return true
        if (p == f) return true
        return p.startsWith("$f/")
    }

    fun isExcluded(path: String, excluded: List<String>): Boolean =
        excluded.any { isInside(path, it) }

    fun isIncluded(path: String, included: List<String>): Boolean {
        // An empty include list means "the whole volume".
        if (included.isEmpty()) return true
        return included.any { isInside(path, it) }
    }

    fun shouldIgnorePath(path: String): Boolean {
        val lower = path.lowercase()
        return IGNORED_SEGMENTS.any { lower.contains(it.lowercase()) }
    }

    /**
     * True when [path] is on a mounted user volume, where the app can actually read
     * the file.
     *
     * The media store also indexes a device's factory media (`/hw_product/...` on
     * Huawei phones), and those rows are playable for the system sound picker but
     * not for a third-party app: the player hangs in buffering on them forever,
     * which looks exactly like a song that refuses to start.
     */
    fun isOnUserVolume(path: String): Boolean = path.startsWith("/storage/")

    /**
     * Filters candidates and splits them into work that must be (re)indexed and
     * work that can be skipped because size and modification time are unchanged.
     *
     * @param existing map of known track path -> already indexed track.
     */
    fun plan(
        candidates: List<ScanCandidate>,
        existing: Map<String, Track>,
        includedFolders: List<String>,
        excludedFolders: List<String>,
        force: Boolean,
    ): ScanPlan {
        val index = ArrayList<ScanCandidate>()
        val unchanged = ArrayList<ScanCandidate>()
        val seen = HashSet<String>(candidates.size)

        for (candidate in candidates) {
            val path = candidate.path
            if (!seen.add(path)) continue
            if (!isPlayable(candidate.displayName)) continue
            if (shouldIgnorePath(path)) continue
            if (isExcluded(path, excludedFolders)) continue
            if (!isIncluded(path, includedFolders)) continue

            val previous = existing[path]
            val changed = force || previous == null ||
                previous.fileSize != candidate.size ||
                previous.lastModified != candidate.lastModified

            if (changed) index += candidate else unchanged += candidate
        }
        return ScanPlan(toIndex = index, unchanged = unchanged)
    }
}

/** Outcome of a scan run, surfaced in the UI and in tests. */
data class ScanResult(
    val discovered: Int,
    val indexed: Int,
    val skipped: Int,
    val removed: Int,
    val durationMs: Long,
    val cancelled: Boolean = false,
    /** Files for which artwork/tags were extracted during this run. */
    val enriched: Int = 0,
)

/**
 * Reads the media store and folds its contents into the local library.
 *
 * Nothing here touches the network and no file is ever copied: only metadata and
 * small cached thumbnails are stored.
 */
class LibraryScanner(
    private val context: Context,
    private val db: AudioCatDatabase,
    private val metadata: TrackMetadataReader = TrackMetadataReader(context),
    private val artwork: ArtworkStore = ArtworkStore(context),
) {

    /**
     * Runs an incremental scan.
     *
     * @param force re-reads files whose size and modification time are unchanged,
     *   and re-extracts artwork for every one of them; an incremental run only
     *   pays for what changed and spreads the rest over later runs.
     */
    suspend fun scan(
        includeFolders: List<String>,
        excludeFolders: List<String>,
        force: Boolean = false,
        artBudget: Int = DEFAULT_ART_BUDGET,
        artMaxPx: Int = ArtworkStore.DEFAULT_MAX_PX,
        /** Applies to files seen for the first time; see [NewFilePolicy]. */
        newFilePolicy: NewFilePolicy = NewFilePolicy.ADD,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        // Two reality checks the media store cannot answer for itself: the file has
        // to exist (it keeps rows for files deleted outside the app, which then fail
        // the moment something tries to play them), and it has to be somewhere the
        // app is allowed to read (a device's factory media is indexed but not
        // streamable, and the player hangs buffering on those forever).
        val candidates = queryMediaStore().filter {
            File(it.path).exists() && ScanPlanner.isOnUserVolume(it.path)
        }
        onProgress(0, candidates.size)

        val included = includeFolders.map(ScanPlanner::normalize).filter { it.isNotEmpty() }
        val excluded = excludeFolders.map(ScanPlanner::normalize).filter { it.isNotEmpty() }

        val existing = db.trackDao().allOnce().associateBy { it.path }
        val plan = ScanPlanner.plan(candidates, existing, included, excluded, force)

        var indexed = 0
        // Upsert in chunks so a very large library never holds one giant
        // transaction in memory.
        plan.toIndex.chunked(UPSERT_CHUNK).forEach { chunk ->
            val rows = chunk.map { toTrack(it, existing[it.path], newFilePolicy) }
            db.withTransaction { db.trackDao().upsertAll(rows) }
            indexed += chunk.size
            onProgress(indexed, plan.toIndex.size)
        }

        // Must come after the upserts: re-indexed rows carry their old enrichment
        // flag forward (that is what keeps an unchanged file cheap), so clearing
        // the backlog first would have it written straight back and a full refresh
        // would read no files at all.
        if (force) db.trackDao().resetEnrichment()

        // A full refresh is the user asking for every file to be re-read, so it
        // ignores the per-run budget that keeps background scans cheap.
        val enriched = enrichBacklog(plan, if (force) Int.MAX_VALUE else artBudget, artMaxPx)
        val removed = pruneMissing(existing.keys)
        ScanResult(
            discovered = candidates.size,
            indexed = indexed,
            skipped = plan.unchanged.size,
            removed = removed,
            enriched = enriched,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /**
     * Drains as much of the enrichment backlog as [artBudget] allows.
     *
     * The backlog is every library row whose tags/cover were never extracted, plus
     * every row whose cached cover has disappeared, so files that were indexed on
     * an earlier run but never reached (or that genuinely have no cover art) are
     * retried instead of being stuck out, while already-enriched files cost
     * nothing. Newly indexed files are prioritised so fresh additions get artwork
     * first.
     */
    private suspend fun enrichBacklog(
        plan: ScanPlan,
        artBudget: Int,
        maxPx: Int,
    ): Int {
        if (artBudget <= 0) return 0
        val pending = db.trackDao().pathsPendingEnrichment().toHashSet()
        // The cache directory is the first thing the system evicts under storage
        // pressure, and "clear covers" empties it outright.
        pending += artwork.orphaned(db.trackDao().artPaths())
        if (pending.isEmpty()) return 0
        // Filter first, then let enrich() apply the budget: taking here and again
        // inside enrich would double-throttle the per-run cap, starving large
        // libraries of artwork over many incremental runs.
        val backlog = (plan.toIndex + plan.unchanged)
            .filter { it.path in pending }
        return enrich(backlog, artBudget, maxPx)
    }

    private suspend fun enrich(
        candidates: List<ScanCandidate>,
        artBudget: Int,
        maxPx: Int,
    ): Int {
        if (candidates.isEmpty() || artBudget <= 0) return 0
        val selected = candidates.take(artBudget)
        val permits = Semaphore(ART_CONCURRENCY)
        val completed = AtomicInteger()

        coroutineScope {
            selected.forEach { candidate ->
                launch(Dispatchers.IO) {
                    permits.withPermit { enrichOne(candidate, maxPx) }
                    completed.incrementAndGet()
                }
            }
        }
        return completed.get()
    }

    /**
     * Reads tags and audio-stream details for a single file and writes them on.
     *
     * Artwork extraction is attempted first because it is the expensive half;
     * the metadata reads below are cheap by comparison.
     */
    private suspend fun enrichOne(candidate: ScanCandidate, maxPx: Int) {
        val artPath = runCatching {
            artwork.ensure(
                path = candidate.path,
                uri = candidate.uri,
                isVideo = candidate.isVideo,
                maxPx = maxPx,
                metadata = metadata,
            )
        }.getOrNull()

        val audio = runCatching { metadata.readAudioInfo(candidate.uri) }
            .getOrDefault(AudioTrackInfo())
        val lyrics = runCatching { metadata.readTags(candidate.uri)?.lyrics }.getOrNull()

        db.trackDao().applyEnrichment(
            path = candidate.path,
            artPath = artPath,
            sampleRate = audio.sampleRate,
            bitRate = audio.bitRate,
            channelCount = audio.channelCount,
            lyrics = lyrics,
        )
    }

    /**
     * Drops rows whose file no longer exists.
     *
     * This also covers a file deleted outside the app while the media store still
     * reports its row: trusting the media store alone leaves such tracks in the
     * library, in the queue, and permanently failing to play. A volume where every
     * known file is missing is treated as unmounted rather than emptied, so a
     * pulled SD card never wipes the library.
     */
    private suspend fun pruneMissing(known: Set<String>): Int {
        val missing = known.filter { it.startsWith("/") && !File(it).exists() }
        val aliveVolumes = known.asSequence()
            .filterNot { it in missing }
            .mapTo(HashSet()) { volumeOf(it) }
        // A path the app can never open is dropped on sight; a file that is merely
        // missing counts only when its volume still holds something else, so an
        // unmounted card cannot empty the library.
        val gone = known.filterNot { ScanPlanner.isOnUserVolume(it) } +
            missing.filter { volumeOf(it) in aliveVolumes }
        if (gone.isEmpty()) return 0
        // Their thumbnails would otherwise be orphaned in the cache forever.
        val artByName = db.trackDao().artPaths()
        gone.forEach { path -> artwork.deleteFile(artByName[path]) }
        db.trackDao().deleteByPaths(gone)
        return gone.size
    }

    /** The mount point of a path, e.g. `/storage/emulated/0` or `/storage/ABCD-1234`. *
     *  Internal storage always nests under /storage/emulated/<index>;
     *  removable cards live at /storage/<uuid>. The previous check
     *  ("segments.size >= 3" + taking [1] and [2]) broke for deeply-nested
     *  SD card paths and for the 2-segment removable case. */
    private fun volumeOf(path: String): String {
        if (!path.startsWith("/storage/")) return "/"
        val rest = path.removePrefix("/storage/")
        val parts = rest.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "/"
        // Internal: /storage/emulated/0
        if (parts.first() == "emulated" && parts.size >= 2) {
            return "/storage/emulated/${parts[1]}"
        }
        // Removable: /storage/XXXX-XXXX (single component, no trailing slash)
        return "/storage/${parts.first()}"
    }

    /**
     * Merges a scanned candidate with the row it replaces, keeping the two fields
     * the user owns: the unlisted flag and the original "date added".
     *
     * [newFilePolicy] is applied only to files that are not already in the
     * library, so a rescan never re-hides something the user relisted.
     */
    private fun toTrack(
        candidate: ScanCandidate,
        previous: Track?,
        newFilePolicy: NewFilePolicy,
    ): Track {
        val folder = candidate.path.substringBeforeLast('/', "")
        val unlisted = previous?.unlisted
            ?: (newFilePolicy == NewFilePolicy.ADD_UNLISTED)
        return Track(
            path = candidate.path,
            uri = candidate.uri,
            title = candidate.title?.takeIf { it.isNotBlank() }
                ?: candidate.displayName.substringBeforeLast('.'),
            artist = candidate.artist,
            album = candidate.album,
            durationMs = candidate.durationMs,
            fileSize = candidate.size,
            isVideo = candidate.isVideo,
            unlisted = unlisted,
            lastModified = candidate.lastModified,
            dateAdded = previous?.dateAdded?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            mimeType = candidate.mimeType,
            folder = folder,
            artPath = previous?.artPath,
            lyrics = previous?.lyrics,
            // A re-indexed (changed) file keeps its enrichment status; only
            // genuinely new files enter the backlog.
            enrichmentAttempted = previous?.enrichmentAttempted ?: false,
        )
    }

    /**
     * Reads the audio and video collections.
     *
     * Two queries rather than one on the combined `files` view: that view returns
     * NULL for artist and album on some phones (measured on the test device) even
     * though the audio collection holds the real tags, which silently turned every
     * song in the library into "Unknown artist". Querying each collection also lets
     * a row's content uri be built from the collection it actually came from.
     */
    private fun queryMediaStore(): List<ScanCandidate> {
        val results = LinkedHashMap<String, ScanCandidate>()
        listOf(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).forEach { collection -> readCollection(collection, results) }
        return results.values.toList()
    }

    private fun readCollection(collection: Uri, into: MutableMap<String, ScanCandidate>) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
        )

        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val durIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            // Audio-only columns: absent from the video collection, which is why
            // every read below goes through the index-guarding getStringOrNull.
            val artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                if (!ScanPlanner.isPlayable(name)) continue

                val path = dataIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    ?: cursor.getStringOrNull(relIdx)?.let { rel ->
                        File(volumeRoot(), rel + name).absolutePath
                    }
                    ?: continue

                into[path] = ScanCandidate(
                    path = path,
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idIdx)).toString(),
                    displayName = name,
                    size = cursor.getLong(sizeIdx),
                    // MediaStore reports seconds; normalise to millis.
                    lastModified = cursor.getLong(modIdx) * 1000L,
                    durationMs = durationIdx(durIdx, cursor),
                    title = cursor.getStringOrNull(titleIdx),
                    artist = cursor.getStringOrNull(artistIdx)?.takeIf { it != "<unknown>" },
                    album = cursor.getStringOrNull(albumIdx)?.takeIf { it != "<unknown>" },
                    mimeType = cursor.getStringOrNull(mimeIdx),
                    isVideo = ScanPlanner.isVideo(name),
                )
            }
        }
    }

    private fun durationIdx(index: Int, cursor: android.database.Cursor): Long =
        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L

    private companion object {
        const val UPSERT_CHUNK = 200

        /** Files processed for artwork/tags per scan; the rest follow next run. */
        const val DEFAULT_ART_BUDGET = 300

        /** Concurrent metadata readers: keeps the disk busy without contention. */
        const val ART_CONCURRENCY = 4

        fun volumeRoot(): String = "/storage/emulated/0"
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (index >= 0 && !isNull(index)) getString(index) else null
