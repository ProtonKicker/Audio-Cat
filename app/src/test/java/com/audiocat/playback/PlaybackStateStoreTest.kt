package com.audiocat.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audiocat.data.RepeatMode
import com.audiocat.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Snapshot persistence tests: the queue must survive process death through the
 * JSON file, and a damaged file must degrade to "nothing to restore" rather
 * than to a broken queue.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackStateStoreTest {

    private lateinit var store: PlaybackStateStore
    private lateinit var stateFile: File
    private lateinit var backupFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = PlaybackStateStore(context)
        stateFile = File(context.filesDir, "playback_state.json")
        backupFile = File(context.filesDir, "playback_state.json.bak")
        store.clear()
    }

    private fun queue(): PlaybackQueue {
        val tracks = (0 until 3).map {
            Track(path = "/m/t$it.mp3", uri = "content://media/$it", title = "T$it")
        }
        return PlaybackQueue(
            tracks = tracks,
            order = listOf(2, 0, 1),
            cursor = 1,
            shuffle = true,
            repeat = RepeatMode.ALL,
        )
    }

    @Test
    fun `save then load round-trips the visible state`() {
        store.save(queue(), positionMs = 4_321L)

        val snapshot = store.load()!!
        assertEquals(listOf("/m/t2.mp3", "/m/t0.mp3", "/m/t1.mp3"), snapshot.paths)
        assertEquals(1, snapshot.cursor)
        assertEquals(4_321L, snapshot.positionMs)
        assertTrue(snapshot.shuffle)
        assertEquals("ALL", snapshot.repeat)
    }

    @Test
    fun `the snapshot keeps the media uri so the queue can be restored without the database`() {
        store.save(queue(), positionMs = 0L)

        val snapshot = store.load()!!
        assertEquals(
            listOf("content://media/2", "content://media/0", "content://media/1"),
            snapshot.items.map { it.uri },
        )
    }

    @Test
    fun `the snapshot keeps the title and artist so the first frame shows real metadata`() {
        store.save(queue(), positionMs = 0L)

        val snapshot = store.load()!!
        assertEquals(listOf("T2", "T0", "T1"), snapshot.items.map { it.title })
        assertEquals(listOf(null, null, null), snapshot.items.map { it.artist })
    }

    @Test
    fun `load returns null when nothing was saved`() {
        assertNull(store.load())
    }

    @Test
    fun `load returns null on a corrupt file`() {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText("{ this is not json")
        assertNull(store.load())
    }

    @Test
    fun `a damaged file falls back to the previous snapshot`() {
        store.save(queue(), positionMs = 1L)
        // A second save keeps the first one as the backup.
        store.save(queue(), positionMs = 2L)
        stateFile.writeText("{ truncated")

        val snapshot = store.load()
        assertNotNull(snapshot)
        assertEquals(3, snapshot!!.items.size)
    }

    @Test
    fun `the snapshot file is never left half-written`() {
        store.save(queue(), positionMs = 1L)

        // The write goes through a temp file that is renamed over the real
        // one, so no temp file is left behind and the snapshot is complete.
        val temp = File(stateFile.parentFile, "playback_state.json.tmp")
        assertTrue(!temp.exists())
        assertTrue(stateFile.length() > 0)
    }

    @Test
    fun `a snapshot written by an older build still loads`() {
        // The legacy format stored bare paths and looked the uri up in the
        // library database.
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(
            """{"paths":["/m/t0.mp3","/m/t1.mp3"],"cursor":1,"positionMs":900,"shuffle":false,"repeat":"OFF"}""",
        )

        val snapshot = store.load()!!
        assertEquals(listOf("/m/t0.mp3", "/m/t1.mp3"), snapshot.paths)
        assertEquals(1, snapshot.cursor)
        assertEquals(900L, snapshot.positionMs)
        assertEquals("", snapshot.items.first().uri)
    }

    @Test
    fun `clear removes the snapshot`() {
        store.save(queue(), positionMs = 1L)
        store.clear()
        assertNull(store.load())
        assertTrue(!backupFile.exists())
    }
}
