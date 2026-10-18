package com.audiocat.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the queue and current position so that playback survives the service
 * being killed and recreated by the system.
 *
 * Stored as the *file paths* of the queue rather than full track rows: the
 * library database is the source of truth for metadata, and paths are tiny.
 */
class PlaybackStateStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** Snapshot of a previous session. */
    data class Snapshot(
        val paths: List<String>,
        val cursor: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeat: String,
    )

    fun save(queue: PlaybackQueue, positionMs: Long) {
        val payload = JSONObject().apply {
            put(KEY_PATHS, JSONArray(queue.orderedTracks.map { it.path }))
            // The cursor is stored as a position within the *saved* order so a
            // shuffled queue resumes on the same track.
            put(KEY_POSITION_IN_QUEUE, queue.cursor)
            put(KEY_POSITION_MS, positionMs)
            put(KEY_SHUFFLE, queue.shuffle)
            put(KEY_REPEAT, queue.repeat.name)
        }
        runCatching { file.writeText(payload.toString()) }
    }

    fun load(): Snapshot? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val array = json.optJSONArray(KEY_PATHS) ?: return null
        val paths = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(paths::add)
        }
        if (paths.isEmpty()) return null
        Snapshot(
            paths = paths,
            cursor = json.optInt(KEY_POSITION_IN_QUEUE, 0),
            positionMs = json.optLong(KEY_POSITION_MS, 0L),
            shuffle = json.optBoolean(KEY_SHUFFLE, false),
            repeat = json.optString(KEY_REPEAT, "OFF"),
        )
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "playback_state.json"
        const val KEY_PATHS = "paths"
        const val KEY_POSITION_IN_QUEUE = "cursor"
        const val KEY_POSITION_MS = "positionMs"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}
