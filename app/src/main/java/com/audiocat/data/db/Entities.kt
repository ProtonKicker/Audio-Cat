package com.audiocat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-created playlist. Unlimited playlists are supported. */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ordered membership of a track inside a playlist.
 *
 * [position] is a contiguous 0-based index maintained by the repository on
 * every insert/remove/move so that reordering never has to re-sort the table.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "track_path"],
    indices = [
        Index(value = ["track_path"]),
        Index(value = ["playlist_id", "position"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Track::class,
            parentColumns = ["path"],
            childColumns = ["track_path"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistTrack(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    @ColumnInfo(name = "track_path")
    val trackPath: String,

    @ColumnInfo(name = "position")
    val position: Int,
)

/** Role of a folder entry in the scan configuration. */
enum class FolderMode { INCLUDE, EXCLUDE }

/**
 * A folder that participates in library scanning.
 *
 * Excluded folders always win over included ones, which lets a user scan a
 * whole volume while skipping, for example, a voice-memo directory.
 */
@Entity(
    tableName = "scan_folders",
    indices = [Index(value = ["path"], unique = true)],
)
data class ScanFolder(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "path")
    val path: String,

    /** Null means "every volume root", used by the default configuration. */
    @ColumnInfo(name = "volume")
    val volume: String? = null,

    @ColumnInfo(name = "mode")
    val mode: FolderMode = FolderMode.INCLUDE,

    /**
     * Number of media files found under this folder by the last scan. Shown in
     * Settings and refreshed with [updateFileCount].
     */
    @ColumnInfo(name = "file_count", defaultValue = "0")
    val fileCount: Int = 0,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
)
