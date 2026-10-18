package com.audiocat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Playlist?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(playlist: Playlist): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = :playlistId")
    fun observeTrackCount(playlistId: Long): Flow<Int>

    /** Track count per playlist id, in a single query for the whole list. */
    @Query("SELECT playlist_id AS playlistId, COUNT(*) AS count FROM playlist_tracks GROUP BY playlist_id")
    fun observeTrackCounts(): Flow<List<PlaylistTrackCount>>

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int
}

/** Projection for [PlaylistDao.observeTrackCounts]. */
data class PlaylistTrackCount(
    val playlistId: Long,
    val count: Int,
)

@Dao
interface PlaylistTrackDao {

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.track_path = t.path
        WHERE pt.playlist_id = :playlistId
        ORDER BY pt.position ASC
        """,
    )
    fun observeTracks(playlistId: Long): Flow<List<Track>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.track_path = t.path
        WHERE pt.playlist_id = :playlistId
        ORDER BY pt.position ASC
        """,
    )
    suspend fun tracksOnce(playlistId: Long): List<Track>

    @Query("SELECT IFNULL(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = :playlistId AND track_path = :path")
    suspend fun contains(playlistId: Long, path: String): Int

    @Query("SELECT * FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY position ASC")
    suspend fun members(playlistId: Long): List<PlaylistTrack>

    /** Every playlist that contains [path], used when a file is re-keyed. */
    @Query("SELECT * FROM playlist_tracks WHERE track_path = :path")
    suspend fun membershipsFor(path: String): List<PlaylistTrack>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(membership: PlaylistTrack): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(memberships: List<PlaylistTrack>)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_path = :path")
    suspend fun remove(playlistId: Long, path: String)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun clear(playlistId: Long)

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlist_id = :playlistId AND track_path = :path")
    suspend fun setPosition(playlistId: Long, path: String, position: Int)

    /** Appends a track to the end of a playlist, ignoring duplicates. */
    @Transaction
    suspend fun append(playlistId: Long, path: String): Boolean {
        if (contains(playlistId, path) > 0) return false
        return insert(PlaylistTrack(playlistId, path, nextPosition(playlistId))) != -1L
    }

    /**
     * Removes a track and closes the gap it leaves behind so that [position]
     * stays a contiguous 0-based range.
     */
    @Transaction
    suspend fun removeAndCompact(playlistId: Long, path: String) {
        remove(playlistId, path)
        compact(playlistId)
    }

    /** Rewrites positions from the given ordering, used by drag-to-reorder. */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedPaths: List<String>) {
        orderedPaths.forEachIndexed { index, path ->
            setPosition(playlistId, path, index)
        }
    }

    @Transaction
    suspend fun compact(playlistId: Long) {
        members(playlistId).forEachIndexed { index, membership ->
            if (membership.position != index) {
                setPosition(playlistId, membership.trackPath, index)
            }
        }
    }
}
