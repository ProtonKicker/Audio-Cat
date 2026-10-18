package com.audiocat.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audiocat.data.RepeatMode
import com.audiocat.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Snapshot persistence tests: the queue must survive process death through the
 * JSON file, and a damaged file must degrade to "nothing to restore".
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackStateStoreTest {

    private lateinit var store: PlaybackStateStore
    private lateinit var stateFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = PlaybackStateStore(context)
        stateFile = File(context.filesDir, "playback_state.json")
        store.clear()
    }

    private fun queue(): PlaybackQueue {
        val tracks = (0 until 3).map {
            Track(path = "/m/t$it.mp3", uri = "file:///m/t$it.mp3", title = "T$it")
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
    fun `clear removes the snapshot`() {
        store.save(queue(), positionMs = 1L)
        store.clear()
        assertNull(store.load())
    }
}
