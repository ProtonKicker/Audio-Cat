package com.audiocat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapInfo
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Sort orders exposed by the library UI. */
enum class TrackSort(val sql: String) {
    DATE_ADDED_DESC("date_added DESC, title COLLATE NOCASE ASC"),
    TITLE_ASC("title COLLATE NOCASE ASC"),
    ARTIST_ASC("artist COLLATE NOCASE ASC, title COLLATE NOCASE ASC"),
    DURATION_DESC("duration_ms DESC"),
    SIZE_DESC("file_size DESC"),
    ;

    companion object {
        fun fromKey(key: String?): TrackSort =
            entries.firstOrNull { it.name == key } ?: DATE_ADDED_DESC
    }
}

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE unlisted = 0 ORDER BY date_added DESC, title COLLATE NOCASE ASC")
    fun observeVisible(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY date_added DESC, title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE unlisted = 0")
    suspend fun visibleOnce(): List<Track>

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    suspend fun allOnce(): List<Track>

    @Query("SELECT * FROM tracks WHERE path = :path LIMIT 1")
    suspend fun byPath(path: String): Track?

    @Query("SELECT * FROM tracks WHERE path IN (:paths)")
    suspend fun byPaths(paths: List<String>): List<Track>

    @Query(
        """
        SELECT * FROM tracks
        WHERE unlisted = 0
          AND (title LIKE '%' || :query || '%' COLLATE NOCASE
               OR artist LIKE '%' || :query || '%' COLLATE NOCASE
               OR album LIKE '%' || :query || '%' COLLATE NOCASE
               OR path LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun search(query: String): Flow<List<Track>>

    /** Every path currently known to the library, used to detect deleted files. */
    @Query("SELECT path FROM tracks")
    suspend fun allPaths(): List<String>

    @Query("SELECT COUNT(*) FROM tracks WHERE unlisted = 0")
    fun observeVisibleCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT IFNULL(SUM(file_size), 0) FROM tracks")
    fun observeTotalSize(): Flow<Long>

    @Query("SELECT IFNULL(SUM(file_size), 0) FROM tracks WHERE unlisted = 1")
    fun observeUnlistedSize(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: Track): Long

    /**
     * Writes scanned metadata while preserving user-owned state.
     *
     * The scan pipeline merges [Track.unlisted] and [Track.dateAdded] from the
     * existing row before calling this, because a blind REPLACE would reset
     * them on every rescan.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: Track)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<Track>): LongArray

    @Update
    suspend fun update(track: Track)

    @Query("UPDATE tracks SET unlisted = :unlisted WHERE path IN (:paths)")
    suspend fun setUnlisted(paths: List<String>, unlisted: Boolean)

    @Query("UPDATE tracks SET art_path = :artPath WHERE path = :path")
    suspend fun setArtPath(path: String, artPath: String?)

    /**
     * Re-queues one track for extraction.
     *
     * Used when a custom cover is removed: the file the user chose is gone, and
     * whatever the container itself carries should be found again.
     */
    @Query("UPDATE tracks SET enrichment_attempted = 0 WHERE path = :path")
    suspend fun requeueEnrichment(path: String)

    /** Paths whose artwork/tags have never been extracted yet. */
    @Query("SELECT path FROM tracks WHERE enrichment_attempted = 0")
    suspend fun pathsPendingEnrichment(): List<String>

    /**
     * Every row that remembers a cached cover, as track path to cover file name.
     *
     * Used to detect thumbnails the cache no longer holds; see
     * [com.audiocat.scanner.ArtworkStore.orphaned].
     */
    @MapInfo(keyColumn = "path", valueColumn = "art_path")
    @Query("SELECT path, art_path FROM tracks WHERE art_path IS NOT NULL")
    suspend fun artPaths(): Map<String, String>

    /** Re-queues every track for enrichment; used by a forced full rescan. */
    @Query("UPDATE tracks SET enrichment_attempted = 0")
    suspend fun resetEnrichment()

    @Query(
        """
        UPDATE tracks SET
            art_path = CASE
                WHEN substr(art_path, 1, 5) = 'user-' THEN art_path
                ELSE COALESCE(:artPath, art_path)
            END,
            sample_rate = CASE WHEN :sampleRate > 0 THEN :sampleRate ELSE sample_rate END,
            bit_rate = CASE WHEN :bitRate > 0 THEN :bitRate ELSE bit_rate END,
            channel_count = CASE WHEN :channelCount > 0 THEN :channelCount ELSE channel_count END,
            lyrics = COALESCE(:lyrics, lyrics),
            enrichment_attempted = 1
        WHERE path = :path
        """,
    )
    suspend fun applyEnrichment(
        path: String,
        artPath: String?,
        sampleRate: Int,
        bitRate: Int,
        channelCount: Int,
        lyrics: String?,
    )

    @Query("DELETE FROM tracks WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM tracks")
    suspend fun clear()
}
