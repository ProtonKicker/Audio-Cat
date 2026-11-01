package com.audiocat.playback

import com.audiocat.data.RepeatMode
import com.audiocat.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the mapping from a saved snapshot back to a playable queue.
 *
 * These are the rules that decide what the user gets back when the app is
 * reopened: the queue must come back even when the library has lost a row,
 * and it must come back on the *same track* it was playing.
 */
class QueueRestoreTest {

    private fun item(path: String, uri: String = "content://$path") =
        PlaybackStateStore.Item(path, uri)

    private fun row(path: String, title: String = "T", uri: String = "content://$path") =
        Track(path = path, uri = uri, title = title)

    @Test
    fun `a complete snapshot restores in order with the saved position`() {
        val items = listOf(item("/m/a.mp3"), item("/m/b.mp3"), item("/m/c.mp3"))
        val rows = items.associate { it.path to row(it.path) }

        val plan = QueueRestore.plan(items, cursor = 1, rows = rows)!!

        assertEquals(listOf("/m/a.mp3", "/m/b.mp3", "/m/c.mp3"), plan.tracks.map { it.path })
        assertEquals(1, plan.cursor)
    }

    @Test
    fun `a queue entry missing from the library is still restored`() {
        // The file was renamed or deleted, so the library has no row for it,
        // but the snapshot remembers the path and the uri.
        val items = listOf(item("/m/a.mp3"), item("/m/gone.mp3"), item("/m/c.mp3"))
        val rows = mapOf("/m/a.mp3" to row("/m/a.mp3"), "/m/c.mp3" to row("/m/c.mp3"))

        val plan = QueueRestore.plan(items, cursor = 2, rows = rows)!!

        assertEquals(3, plan.tracks.size)
        assertEquals("/m/gone.mp3", plan.tracks[1].path)
        assertEquals(2, plan.cursor)
    }

    @Test
    fun `a missing library row does not move the position onto another track`() {
        // The entry at index 0 is gone from the library and carries no uri of
        // its own, so it cannot come back and the queue is one shorter. The
        // position still has to point at the same track it was on.
        val items = listOf(item("/m/gone.mp3", uri = ""), item("/m/b.mp3"), item("/m/c.mp3"))
        val rows = mapOf("/m/b.mp3" to row("/m/b.mp3"), "/m/c.mp3" to row("/m/c.mp3"))

        val plan = QueueRestore.plan(items, cursor = 2, rows = rows)!!

        assertEquals(2, plan.tracks.size)
        assertEquals("/m/c.mp3", plan.tracks[plan.cursor].path)
    }

    @Test
    fun `a legacy entry without a uri is dropped when the library lost it`() {
        // A snapshot written by an older build has no uri, so without a
        // library row there is nothing to play.
        val items = listOf(item("/m/gone.mp3", uri = ""), item("/m/b.mp3"))
        val rows = mapOf("/m/b.mp3" to row("/m/b.mp3"))

        val plan = QueueRestore.plan(items, cursor = 1, rows = rows)!!

        assertEquals(listOf("/m/b.mp3"), plan.tracks.map { it.path })
        assertEquals(0, plan.cursor)
    }

    @Test
    fun `the position falls back to a valid track when the playing one is gone`() {
        val items = listOf(item("/m/a.mp3"), item("/m/gone.mp3"))
        val rows = mapOf("/m/a.mp3" to row("/m/a.mp3"))

        val plan = QueueRestore.plan(items, cursor = 1, rows = rows)!!

        assertNotNull(plan)
        assertTrue(plan.cursor in plan.tracks.indices)
    }

    @Test
    fun `the library row wins over the saved uri so a rename keeps working`() {
        val items = listOf(item("/m/a.mp3", uri = "content://old/1"))
        val rows = mapOf("/m/a.mp3" to row("/m/a.mp3", uri = "content://new/2"))

        val plan = QueueRestore.plan(items, cursor = 0, rows = rows)!!

        assertEquals("content://new/2", plan.tracks.first().uri)
    }

    @Test
    fun `a snapshot with nothing playable restores nothing`() {
        val items = listOf(item("/m/gone.mp3", uri = ""))
        val plan = QueueRestore.plan(items, cursor = 0, rows = emptyMap())

        assertNull(plan)
    }

    @Test
    fun `an empty snapshot restores nothing`() {
        assertNull(QueueRestore.plan(emptyList(), cursor = 0, rows = emptyMap()))
    }

    // ---------------------------------------------------------------------
    // Provisional queue used to seed the first frame of a cold start
    // ---------------------------------------------------------------------

    @Test
    fun `a provisional queue is built from the snapshot alone, without the database`() {
        val snapshot = PlaybackStateStore.Snapshot(
            items = listOf(
                item("/m/a.mp3"),
                item("/m/b.mp3"),
                PlaybackStateStore.Item("/m/c.mp3", "content:///m/c.mp3", title = "C Song", artist = "C Artist"),
            ),
            cursor = 2,
            positionMs = 15_000L,
            shuffle = true,
            repeat = "ALL",
        )

        val queue = QueueRestore.provisional(snapshot)!!

        assertEquals(3, queue.size)
        assertEquals("/m/c.mp3", queue.current?.path)
        // The snapshot duplicates the title so the first frame already shows it.
        assertEquals("C Song", queue.current?.title)
        assertEquals("C Artist", queue.current?.artist)
        assertTrue(queue.shuffle)
        assertEquals(RepeatMode.ALL, queue.repeat)
    }

    @Test
    fun `the snapshot title fills a queue entry that lost its library row`() {
        val items = listOf(PlaybackStateStore.Item("/m/gone.mp3", "content://gone", title = "Gone Song"))
        val plan = QueueRestore.plan(items, cursor = 0, rows = emptyMap())!!

        assertEquals("Gone Song", plan.tracks.first().title)
    }

    @Test
    fun `a provisional queue is empty when the snapshot has nothing playable`() {
        val snapshot = PlaybackStateStore.Snapshot(
            items = listOf(item("/m/gone.mp3", uri = "")),
            cursor = 0,
            positionMs = 0L,
            shuffle = false,
            repeat = "OFF",
        )

        assertNull(QueueRestore.provisional(snapshot))
    }
}
