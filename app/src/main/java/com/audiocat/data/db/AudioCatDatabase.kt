package com.audiocat.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

class FolderModeConverter {
    @TypeConverter fun toInt(mode: FolderMode): Int = mode.ordinal
    @TypeConverter fun fromInt(value: Int): FolderMode = FolderMode.entries[value]
}

@Dao
interface ScanFolderDao {

    @Query("SELECT * FROM scan_folders ORDER BY mode ASC, path COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ScanFolder>>

    @Query("SELECT * FROM scan_folders WHERE mode = :mode")
    suspend fun byMode(mode: FolderMode): List<ScanFolder>

    @Query("SELECT COUNT(*) FROM scan_folders")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: ScanFolder): Long

    @Query("UPDATE scan_folders SET file_count = :count WHERE path = :path")
    suspend fun updateFileCount(path: String, count: Int)

    @Query("DELETE FROM scan_folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scan_folders WHERE path = :path")
    suspend fun deleteByPath(path: String)
}

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrack::class, ScanFolder::class],
    // Bumped whenever the schema changes. The app has never shipped, so a
    // destructive fallback is safe and keeps migration code from being carried
    // for schemas no user ever had.
    version = 3,
    exportSchema = true,
)
@TypeConverters(FolderModeConverter::class)
abstract class AudioCatDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun scanFolderDao(): ScanFolderDao

    companion object {
        private const val NAME = "audiocat.db"

        @Volatile
        private var instance: AudioCatDatabase? = null

        fun get(context: Context): AudioCatDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AudioCatDatabase =
            Room.databaseBuilder(context, AudioCatDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
