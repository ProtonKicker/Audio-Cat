package com.audiocat.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Database behaviour tests.
 *
 * These run under Robolectric so the real Room/SQLite stack is exercised,
 * including foreign keys and cascades - the parts most likely to break silently.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryDatabaseTest {

    private lateinit var db: AudioCatDatabase

    private val tracks get() = db.trackDao()
    private val playlists get() = db.playlistDao()
    private val members get() = db.playlistTrackDao()
    private val folders get() = db.scanFolderDao()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AudioCatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun track(
        path: String,
        title: String = path.substringAfterLast('/'),
        artist: String? = null,
        unlisted: Boolean = false,
        size: Long = 1_000L,
        modified: Long = 10L,
        dateAdded: Long = 1L,
    ) = Track(
        path = path,
        uri = "file://$path",
        title = title,
        artist = artist,
        album = null,
        durationMs = 30_000L,
        fileSize = size,
        isVideo = path.endsWith(".mp4"),
        unlisted = unlisted,
        lastModified = modified,
        dateAdded = dateAdded,
    )

    // ------------------------------------------------------------------
    // Tracks
    // ------------------------------------------------------------------

    @Test
    fun `upsert inserts then replaces by path`() = runTest {
        tracks.upsert(track("/m/a.mp3", size = 10))
        tracks.upsert(track("/m/a.mp3", size = 20))

        val all = tracks.allOnce()
        assertEquals(1, all.size)
        assertEquals(20L, all.single().fileSize)
    }

    @Test
    fun `unlisted tracks are excluded from the visible list`() = runTest {
        tracks.upsert(track("/m/listed.mp3"))
        tracks.upsert(track("/m/hidden.mp3", unlisted = true))

        assertEquals(1, tracks.visibleOnce().size)
        assertEquals(2, tracks.allOnce().size)
        assertEquals(1, tracks.observeVisibleCount().first())
        assertEquals(2, tracks.observeTotalCount().first())
    }

    @Test
    fun `setUnlisted can hide and restore tracks`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.upsert(track("/m/b.mp3"))

        tracks.setUnlisted(listOf("/m/a.mp3"), true)
        assertEquals(listOf("/m/b.mp3"), tracks.visibleOnce().map { it.path })

        tracks.setUnlisted(listOf("/m/a.mp3"), false)
        assertEquals(2, tracks.visibleOnce().size)
    }

    @Test
    fun `total size observes the sum of file sizes`() = runTest {
        tracks.upsert(track("/m/a.mp3", size = 100))
        tracks.upsert(track("/m/b.mp3", size = 250))
        assertEquals(350L, tracks.observeTotalSize().first())
    }

    @Test
    fun `search matches title artist album and path case insensitively`() = runTest {
        tracks.upsert(track("/m/one.mp3", title = "Hello World", artist = "The Band"))
        tracks.upsert(track("/m/two.mp3", title = "Other", artist = "Someone"))

        assertEquals(1, tracks.search("hello").first().size)
        assertEquals(1, tracks.search("THE BAND").first().size)
        assertEquals(2, tracks.search("m/").first().size)
        assertEquals(0, tracks.search("nomatch").first().size)
    }

    @Test
    fun `deleteByPaths removes only the requested tracks`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.upsert(track("/m/b.mp3"))

        tracks.deleteByPaths(listOf("/m/a.mp3"))
        assertEquals(listOf("/m/b.mp3"), tracks.allPaths())
    }

    @Test
    fun `byPaths returns matching rows and tolerates unknown paths`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.upsert(track("/m/b.mp3"))

        val found = tracks.byPaths(listOf("/m/a.mp3", "/m/missing.mp3"))
        assertEquals(1, found.size)
        assertEquals("/m/a.mp3", found.single().path)
    }

    // ------------------------------------------------------------------
    // Playlists
    // ------------------------------------------------------------------

    @Test
    fun `playlist membership is ordered by position`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.upsert(track("/m/b.mp3"))
        tracks.upsert(track("/m/c.mp3"))
        val id = playlists.insert(Playlist(name = "Mix"))

        members.append(id, "/m/a.mp3")
        members.append(id, "/m/b.mp3")
        members.append(id, "/m/c.mp3")

        assertEquals(
            listOf("/m/a.mp3", "/m/b.mp3", "/m/c.mp3"),
            members.tracksOnce(id).map { it.path },
        )
    }

    @Test
    fun `append ignores duplicates`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        val id = playlists.insert(Playlist(name = "Mix"))

        assertTrue(members.append(id, "/m/a.mp3"))
        assertEquals(false, members.append(id, "/m/a.mp3"))
        assertEquals(1, members.tracksOnce(id).size)
    }

    @Test
    fun `removeAndCompact closes the gap in positions`() = runTest {
        listOf("/m/a.mp3", "/m/b.mp3", "/m/c.mp3").forEach { tracks.upsert(track(it)) }
        val id = playlists.insert(Playlist(name = "Mix"))
        listOf("/m/a.mp3", "/m/b.mp3", "/m/c.mp3").forEach { members.append(id, it) }

        members.removeAndCompact(id, "/m/b.mp3")

        assertEquals(listOf("/m/a.mp3", "/m/c.mp3"), members.tracksOnce(id).map { it.path })
        assertEquals(listOf(0, 1), members.members(id).map { it.position })
    }

    @Test
    fun `reorder rewrites positions from the supplied ordering`() = runTest {
        listOf("/m/a.mp3", "/m/b.mp3", "/m/c.mp3").forEach { tracks.upsert(track(it)) }
        val id = playlists.insert(Playlist(name = "Mix"))
        listOf("/m/a.mp3", "/m/b.mp3", "/m/c.mp3").forEach { members.append(id, it) }

        members.reorder(id, listOf("/m/c.mp3", "/m/a.mp3", "/m/b.mp3"))

        assertEquals(
            listOf("/m/c.mp3", "/m/a.mp3", "/m/b.mp3"),
            members.tracksOnce(id).map { it.path },
        )
    }

    @Test
    fun `deleting a playlist cascades to its memberships but keeps the tracks`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        val id = playlists.insert(Playlist(name = "Mix"))
        members.append(id, "/m/a.mp3")

        playlists.delete(id)

        assertEquals(0, playlists.count())
        assertEquals(0, members.members(id).size)
        // The file record must survive: only the playlist was deleted.
        assertEquals(1, tracks.allOnce().size)
    }

    @Test
    fun `deleting a track cascades to its playlist memberships`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        val id = playlists.insert(Playlist(name = "Mix"))
        members.append(id, "/m/a.mp3")

        tracks.deleteByPaths(listOf("/m/a.mp3"))

        assertEquals(0, members.members(id).size)
        // The playlist itself stays.
        assertEquals(1, playlists.count())
    }

    @Test
    fun `track counts are grouped per playlist`() = runTest {
        listOf("/m/a.mp3", "/m/b.mp3").forEach { tracks.upsert(track(it)) }
        val first = playlists.insert(Playlist(name = "One"))
        val second = playlists.insert(Playlist(name = "Two"))
        members.append(first, "/m/a.mp3")
        members.append(first, "/m/b.mp3")
        members.append(second, "/m/a.mp3")

        val counts = playlists.observeTrackCounts().first().associate { it.playlistId to it.count }
        assertEquals(2, counts[first])
        assertEquals(1, counts[second])
    }

    @Test
    fun `membershipsFor lists every playlist containing a track`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.upsert(track("/m/b.mp3"))
        val first = playlists.insert(Playlist(name = "One"))
        val second = playlists.insert(Playlist(name = "Two"))
        members.append(first, "/m/a.mp3")
        members.append(second, "/m/a.mp3")
        members.append(first, "/m/b.mp3")

        val memberships = members.membershipsFor("/m/a.mp3")
        assertEquals(setOf(first, second), memberships.map { it.playlistId }.toSet())
    }

    // ------------------------------------------------------------------
    // Rename / re-key behaviour
    // ------------------------------------------------------------------

    @Test
    fun `re-keying a track preserves playlist membership`() = runTest {
        tracks.upsert(track("/m/old.mp3", title = "Song"))
        val id = playlists.insert(Playlist(name = "Mix"))
        members.append(id, "/m/old.mp3")

        // Mirrors what the file-rename path does: capture memberships, delete
        // the old row (which cascades), re-insert under the new path, restore.
        val memberships = members.membershipsFor("/m/old.mp3")
        val existing = tracks.byPath("/m/old.mp3")!!
        tracks.deleteByPaths(listOf("/m/old.mp3"))
        tracks.insert(existing.copy(path = "/m/new.mp3", uri = "file:///m/new.mp3"))
        memberships.forEach { members.insert(it.copy(trackPath = "/m/new.mp3")) }

        assertNull(tracks.byPath("/m/old.mp3"))
        assertNotNull(tracks.byPath("/m/new.mp3"))
        assertEquals(listOf("/m/new.mp3"), members.tracksOnce(id).map { it.path })
    }

    // ------------------------------------------------------------------
    // Scan folders
    // ------------------------------------------------------------------

    @Test
    fun `folders are stored per mode and can be removed`() = runTest {
        folders.insert(ScanFolder(path = "/m/Music", mode = FolderMode.INCLUDE, fileCount = 5))
        folders.insert(ScanFolder(path = "/m/Voice", mode = FolderMode.EXCLUDE))

        assertEquals(1, folders.byMode(FolderMode.INCLUDE).size)
        assertEquals(1, folders.byMode(FolderMode.EXCLUDE).size)
        assertEquals(5, folders.byMode(FolderMode.INCLUDE).single().fileCount)

        folders.deleteByPath("/m/Voice")
        assertEquals(0, folders.byMode(FolderMode.EXCLUDE).size)
    }

    @Test
    fun `the folder path is unique so re-adding replaces the entry`() = runTest {
        folders.insert(ScanFolder(path = "/m/Music", mode = FolderMode.INCLUDE))
        folders.insert(ScanFolder(path = "/m/Music", mode = FolderMode.EXCLUDE))

        assertEquals(1, folders.observeAll().first().size)
        assertEquals(FolderMode.EXCLUDE, folders.observeAll().first().single().mode)
    }

    @Test
    fun `folder file counts can be refreshed`() = runTest {
        folders.insert(ScanFolder(path = "/m/Music", mode = FolderMode.INCLUDE, fileCount = 1))
        folders.updateFileCount("/m/Music", 42)
        assertEquals(42, folders.byMode(FolderMode.INCLUDE).single().fileCount)
    }

    // ------------------------------------------------------------------
    // Sorting
    // ------------------------------------------------------------------

    @Test
    fun `observeAll returns newest additions first`() = runTest {
        tracks.upsert(track("/m/old.mp3", dateAdded = 100))
        tracks.upsert(track("/m/new.mp3", dateAdded = 500))
        tracks.upsert(track("/m/mid.mp3", dateAdded = 300))

        assertEquals(
            listOf("/m/new.mp3", "/m/mid.mp3", "/m/old.mp3"),
            tracks.observeAll().first().map { it.path },
        )
    }

    @Test
    fun `TrackSort resolves unknown keys to the default`() {
        assertEquals(TrackSort.DATE_ADDED_DESC, TrackSort.fromKey(null))
        assertEquals(TrackSort.DATE_ADDED_DESC, TrackSort.fromKey("garbage"))
        assertEquals(TrackSort.ARTIST_ASC, TrackSort.fromKey("ARTIST_ASC"))
    }

    // ------------------------------------------------------------------
    // Enrichment backlog
    // ------------------------------------------------------------------

    @Test
    fun `new rows are pending enrichment and leave the backlog once applied`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.upsert(track("/m/b.mp4"))
        assertEquals(setOf("/m/a.mp3", "/m/b.mp4"), tracks.pathsPendingEnrichment().toSet())

        tracks.applyEnrichment("/m/a.mp3", artPath = null, sampleRate = 44100, bitRate = 320, channelCount = 2, lyrics = null)
        assertEquals(listOf("/m/b.mp4"), tracks.pathsPendingEnrichment())
    }

    @Test
    fun `applyEnrichment keeps existing values when the new read is empty`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.applyEnrichment("/m/a.mp3", artPath = "/cache/a.jpg", sampleRate = 44100, bitRate = 0, channelCount = 0, lyrics = "la la")

        // A later re-read that finds nothing must not clear what was already stored.
        tracks.applyEnrichment("/m/a.mp3", artPath = null, sampleRate = 0, bitRate = 0, channelCount = 0, lyrics = null)

        val row = tracks.byPaths(listOf("/m/a.mp3")).first()
        assertEquals("/cache/a.jpg", row.artPath)
        assertEquals(44100, row.sampleRate)
        assertEquals("la la", row.lyrics)
    }

    /**
     * A cover the user picked shares the column with extracted ones, so this guard
     * is the only thing that keeps a scan from writing over their choice.
     */
    @Test
    fun `applyEnrichment never replaces a cover the user chose`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.setArtPath("/m/a.mp3", "user-abc123.jpg")

        tracks.applyEnrichment(
            "/m/a.mp3",
            artPath = "deadbeef.jpg",
            sampleRate = 0,
            bitRate = 0,
            channelCount = 0,
            lyrics = null,
        )

        assertEquals("user-abc123.jpg", tracks.byPaths(listOf("/m/a.mp3")).first().artPath)
    }

    @Test
    fun `applyEnrichment still writes an extracted cover for an ordinary row`() = runTest {
        tracks.upsert(track("/m/a.mp3"))

        tracks.applyEnrichment(
            "/m/a.mp3",
            artPath = "deadbeef.jpg",
            sampleRate = 0,
            bitRate = 0,
            channelCount = 0,
            lyrics = null,
        )

        assertEquals("deadbeef.jpg", tracks.byPaths(listOf("/m/a.mp3")).first().artPath)
    }

    @Test
    fun `requeueEnrichment puts one track back in the backlog`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.upsert(track("/m/b.mp3"))
        tracks.applyEnrichment("/m/a.mp3", artPath = null, sampleRate = 0, bitRate = 0, channelCount = 0, lyrics = null)
        tracks.applyEnrichment("/m/b.mp3", artPath = null, sampleRate = 0, bitRate = 0, channelCount = 0, lyrics = null)
        assertTrue(tracks.pathsPendingEnrichment().isEmpty())

        tracks.requeueEnrichment("/m/a.mp3")

        assertEquals(listOf("/m/a.mp3"), tracks.pathsPendingEnrichment())
    }

    @Test
    fun `resetEnrichment re-queues every track`() = runTest {
        tracks.upsert(track("/m/a.mp3"))
        tracks.upsert(track("/m/b.mp3"))
        tracks.applyEnrichment("/m/a.mp3", artPath = null, sampleRate = 0, bitRate = 0, channelCount = 0, lyrics = null)
        tracks.applyEnrichment("/m/b.mp3", artPath = null, sampleRate = 0, bitRate = 0, channelCount = 0, lyrics = null)
        assertTrue(tracks.pathsPendingEnrichment().isEmpty())

        tracks.resetEnrichment()
        assertEquals(setOf("/m/a.mp3", "/m/b.mp3"), tracks.pathsPendingEnrichment().toSet())
    }
}
