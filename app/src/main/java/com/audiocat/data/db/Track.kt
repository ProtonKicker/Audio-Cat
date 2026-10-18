package com.audiocat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single playable audio source on disk.
 *
 * [path] is the stable identity of a track. MediaStore ids are recycled and
 * change across renames, so the absolute filesystem path (or SAF document URI
 * for content:// sources) is used as the primary key. Renaming is handled by
 * [com.audiocat.data.LibraryRepository.renameTrack], which moves every
 * dependent row inside one transaction.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["unlisted"]),
        Index(value = ["date_added"]),
    ],
)
data class Track(
    @PrimaryKey
    @ColumnInfo(name = "path")
    val path: String,

    /** content:// URI when the file is reached through MediaStore or SAF. */
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String? = null,

    @ColumnInfo(name = "album")
    val album: String? = null,

    /** Track duration in milliseconds; 0 when unknown. */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0L,

    /** True for video containers - these are still decoded audio-only. */
    @ColumnInfo(name = "is_video")
    val isVideo: Boolean = false,

    /** True when the user removed the track from the visible library. */
    @ColumnInfo(name = "unlisted")
    val unlisted: Boolean = false,

    /** Epoch millis of the last observed modification, used for incremental scans. */
    @ColumnInfo(name = "last_modified")
    val lastModified: Long = 0L,

    @ColumnInfo(name = "date_added")
    val dateAdded: Long = 0L,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "sample_rate")
    val sampleRate: Int = 0,

    /** Audio bit rate in bits/second; 0 when unknown. */
    @ColumnInfo(name = "bit_rate")
    val bitRate: Int = 0,

    @ColumnInfo(name = "channel_count")
    val channelCount: Int = 0,

    /** Owning folder, used for folder grouping and scan bookkeeping. */
    @ColumnInfo(name = "folder")
    val folder: String? = null,

    /** Relative path of the cached artwork inside the app cache dir, if any. */
    @ColumnInfo(name = "art_path")
    val artPath: String? = null,

    /** Embedded/synced lyrics when the container ships them. */
    @ColumnInfo(name = "lyrics")
    val lyrics: String? = null,

    /**
     * True once artwork and tags have been extracted for this file.
     *
     * Without this, a file that simply has no embedded cover would be re-read on
     * every single scan, permanently consuming the per-scan artwork budget.
     */
    @ColumnInfo(name = "enrichment_attempted", defaultValue = "0")
    val enrichmentAttempted: Boolean = false,
) {
    /** Display name with a sensible fallback chain. */
    val displayTitle: String get() = title.ifBlank { path.substringAfterLast('/') }

    /**
     * Lower-case container of the file, e.g. `mp3`. Empty when the name has none.
     *
     * Derived from the path rather than [mimeType], which MediaStore fills with
     * codec-specific values (`audio/mp4a-latm` for an .m4a), so it cannot group a
     * format consistently.
     */
    val fileExtension: String get() = path.substringAfterLast('.', "").lowercase()
}
