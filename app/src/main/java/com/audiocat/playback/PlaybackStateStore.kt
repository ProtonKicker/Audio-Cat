package com.audiocat.playback

import android.content.Context
import android.util.Log
import com.audiocat.data.RepeatMode
import com.audiocat.data.db.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the queue and current position so that playback survives the service
 * being killed and recreated by the system.
 *
 * The snapshot stores the *path and the media URI* of every queue entry rather
 * than full track rows: paths and URIs are tiny, and they are everything
 * playback needs. The library database is consulted only to enrich a restored
 * track with its title, artist and cover, never to decide whether the queue
 * can come back at all - a queue entry whose row has gone missing (a renamed
 * or deleted file) must not stop the rest of the queue from being restored.
 */
class PlaybackStateStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
    private val backupFile = File(context.filesDir, "$FILE_NAME.bak")

    /** One queue entry as stored on disk. */
    data class Item(
        val path: String,
        val uri: String,
        val title: String = "",
        val artist: String? = null,
    )

    /** Snapshot of a previous session. */
    data class Snapshot(
        val items: List<Item>,
        val cursor: Int,
        val positionMs: Long,
        val durationMs: Long = 0L,
        val shuffle: Boolean,
        val repeat: String,
    ) {
        /** The paths of the queue, in play order. */
        val paths: List<String> get() = items.map { it.path }

        /** The path of the track that was playing, or null when unknown. */
        val currentPath: String? get() = items.getOrNull(cursor)?.path
    }

    fun save(queue: PlaybackQueue, positionMs: Long, durationMs: Long = 0L) {
        val payload = JSONObject().apply {
            put(
                KEY_ITEMS,
                JSONArray(queue.orderedTracks.map { track ->
                    JSONObject()
                        .put(KEY_ITEM_PATH, track.path)
                        .put(KEY_ITEM_URI, track.uri)
                        // Duplicated so a cold start can show the real title in
                        // the first frame instead of falling back to the file
                        // name while the restore looks the library row up.
                        .put(KEY_ITEM_TITLE, track.title)
                        .apply { track.artist?.let { put(KEY_ITEM_ARTIST, it) } }
                }),
            )
            // The cursor is stored as a position within the *saved* order so a
            // shuffled queue resumes on the same track.
            put(KEY_POSITION_IN_QUEUE, queue.cursor)
            put(KEY_POSITION_MS, positionMs)
            // The duration is persisted so the cold-start first frame can place
            // the seek bar exactly where playback was, instead of starting from
            // zero until the player finishes restoring and reports its length.
            put(KEY_DURATION_MS, durationMs)
            put(KEY_SHUFFLE, queue.shuffle)
            put(KEY_REPEAT, queue.repeat.name)
        }
        val text = payload.toString()
        // The snapshot is written to a temp file and renamed over the real one.
        // A rename is atomic, so a process killed in the middle of a write can
        // never leave a half-written file behind - which used to lose the
        // queue for good, because a truncated file does not parse.
        val result = runCatching {
            tempFile.writeText(text)
            if (file.exists()) {
                // Keep the last good snapshot as a fallback.
                runCatching { file.copyTo(backupFile, overwrite = true) }
            }
            if (!tempFile.renameTo(file)) {
                // Rename can fail where the destination already exists; fall
                // back to a direct write rather than losing the snapshot.
                file.writeText(text)
                tempFile.delete()
            }
        }
        if (result.isFailure) {
            Log.w(TAG, "could not write the playback snapshot", result.exceptionOrNull())
        }
    }

    /**
     * Reads the saved snapshot.
     *
     * The backup is tried when the main file is missing or unreadable, so a
     * snapshot damaged by an older build still recovers.
     */
    fun load(): Snapshot? = readSnapshot(file) ?: readSnapshot(backupFile)

    private fun readSnapshot(source: File): Snapshot? = runCatching {
        if (!source.exists()) return null
        val json = JSONObject(source.readText())
        val items = readItems(json)
        if (items.isEmpty()) return null
        Snapshot(
            items = items,
            cursor = json.optInt(KEY_POSITION_IN_QUEUE, 0),
            positionMs = json.optLong(KEY_POSITION_MS, 0L),
            durationMs = json.optLong(KEY_DURATION_MS, 0L),
            shuffle = json.optBoolean(KEY_SHUFFLE, false),
            repeat = json.optString(KEY_REPEAT, "OFF"),
        )
    }.getOrNull()

    /**
     * Reads the queue entries, accepting both the current format and the
     * legacy one that stored bare paths and looked the URI up in the library
     * database.
     */
    private fun readItems(json: JSONObject): List<Item> {
        val items = ArrayList<Item>()
        json.optJSONArray(KEY_ITEMS)?.let { array ->
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val path = entry.optString(KEY_ITEM_PATH)
                if (path.isNotBlank()) {
                    items += Item(
                        path = path,
                        uri = entry.optString(KEY_ITEM_URI),
                        title = entry.optString(KEY_ITEM_TITLE),
                        artist = entry.optString(KEY_ITEM_ARTIST).ifBlank { null },
                    )
                }
            }
            return items
        }
        json.optJSONArray(KEY_PATHS)?.let { array ->
            for (index in 0 until array.length()) {
                val path = array.optString(index)
                if (path.isNotBlank()) items += Item(path, "")
            }
        }
        return items
    }

    fun clear() {
        runCatching { file.delete() }
        runCatching { tempFile.delete() }
        runCatching { backupFile.delete() }
    }

    private companion object {
        const val TAG = "PlaybackStateStore"
        const val FILE_NAME = "playback_state.json"
        const val KEY_ITEMS = "items"
        const val KEY_ITEM_PATH = "path"
        const val KEY_ITEM_URI = "uri"
        const val KEY_ITEM_TITLE = "title"
        const val KEY_ITEM_ARTIST = "artist"
        const val KEY_PATHS = "paths"
        const val KEY_POSITION_IN_QUEUE = "cursor"
        const val KEY_POSITION_MS = "positionMs"
        const val KEY_DURATION_MS = "durationMs"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}

/**
 * Pure mapping from a saved snapshot back to a playable queue.
 *
 * Kept free of Android and database calls so the rules that decide what a
 * restored queue looks like can be tested directly.
 */
object QueueRestore {

    /** The queue a snapshot maps to, and the position within it. */
    data class Plan(val tracks: List<Track>, val cursor: Int)

    /**
     * Rebuilds the queue from a snapshot.
     *
     * [rows] holds the library rows for the snapshot's paths, keyed by path.
     * A path with no row is still restored from the snapshot itself, using
     * the URI that was saved with it, because the snapshot is the record of
     * what was playing and the database is only a catalogue.
     *
     * The position is matched **by track, not by index**: a queue entry that
     * no longer exists must not slide the position onto a different track.
     */
    fun plan(
        items: List<PlaybackStateStore.Item>,
        cursor: Int,
        rows: Map<String, Track>,
    ): Plan? {
        val tracks = items.mapNotNull { item ->
            val row = rows[item.path]
            val uri = row?.uri ?: item.uri
            if (uri.isBlank()) return@mapNotNull null
            row ?: Track(
                path = item.path,
                uri = uri,
                title = item.title.ifBlank { item.path.substringAfterLast('/') },
                artist = item.artist,
            )
        }
        if (tracks.isEmpty()) return null
        val restoredCursor = items.getOrNull(cursor)?.path
            ?.let { current -> tracks.indexOfFirst { it.path == current } }
            ?.takeIf { it >= 0 }
            ?: cursor.coerceIn(0, tracks.lastIndex)
        return Plan(tracks, restoredCursor)
    }

    /**
     * Builds the queue to display before the service has finished restoring.
     *
     * The restore goes through a database lookup that takes a moment, so on a
     * cold start the UI would otherwise render "Nothing playing" and then flip
     * to the real track once the restore lands. The snapshot alone is enough to
     * show what was playing - titles fall back to the file name until the
     * library row replaces them.
     */
    fun provisional(snapshot: PlaybackStateStore.Snapshot): PlaybackQueue? =
        plan(snapshot.items, snapshot.cursor, emptyMap())
            ?.takeIf { it.tracks.isNotEmpty() }
            ?.let { plan ->
                PlaybackQueue(
                    tracks = plan.tracks,
                    order = plan.tracks.indices.toList(),
                    cursor = plan.cursor,
                    shuffle = snapshot.shuffle,
                    repeat = runCatching { RepeatMode.valueOf(snapshot.repeat) }
                        .getOrDefault(RepeatMode.OFF),
                )
            }
}
