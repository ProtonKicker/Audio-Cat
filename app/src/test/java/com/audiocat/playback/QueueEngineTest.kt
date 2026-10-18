package com.audiocat.playback

import com.audiocat.data.RepeatMode
import com.audiocat.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the queue arithmetic.
 *
 * These cover the behaviour a user actually notices: what plays next, what
 * happens at the end of the queue, and that moving rows never changes the track
 * that is currently playing.
 */
class QueueEngineTest {

    private fun track(id: String) = Track(
        path = "/music/$id.mp3",
        uri = "file:///music/$id.mp3",
        title = "Title $id",
    )

    private fun queueOf(size: Int): PlaybackQueue {
        val tracks = (0 until size).map { track("t$it") }
        return QueueEngine.replace(tracks, startIndex = 0, shuffle = false, repeat = RepeatMode.OFF)
    }

    private fun PlaybackQueue.currentPath(): String? = current?.path

    @Test
    fun `replace builds sequential order and starts at the requested index`() {
        val tracks = (0 until 4).map { track("t$it") }
        val queue = QueueEngine.replace(tracks, startIndex = 2, shuffle = false, repeat = RepeatMode.OFF)

        assertEquals(listOf(0, 1, 2, 3), queue.order)
        assertEquals(2, queue.cursor)
        assertEquals("/music/t2.mp3", queue.currentPath())
    }

    @Test
    fun `replace clamps an out of range start index`() {
        val tracks = (0 until 3).map { track("t$it") }
        val queue = QueueEngine.replace(tracks, startIndex = 99, shuffle = false, repeat = RepeatMode.OFF)
        assertEquals(2, queue.cursor)
    }

    @Test
    fun `replace on an empty list produces an empty queue`() {
        val queue = QueueEngine.replace(emptyList(), startIndex = 0, shuffle = false, repeat = RepeatMode.OFF)
        assertTrue(queue.isEmpty)
        assertEquals(-1, queue.cursor)
    }

    @Test
    fun `next advances within the queue`() {
        val queue = queueOf(3)
        val advanced = QueueEngine.next(queue)
        assertEquals(1, advanced.cursor)
        assertEquals("/music/t1.mp3", advanced.currentPath())
    }

    @Test
    fun `automatic advance at the end without repeat stays put so playback can stop`() {
        val queue = queueOf(2).copy(cursor = 1)
        val advanced = QueueEngine.next(queue, userInitiated = false)
        assertEquals(1, advanced.cursor)
        assertTrue(QueueEngine.isAtEndWithoutRepeat(advanced))
    }

    @Test
    fun `automatic advance at the end wraps when repeating all`() {
        val queue = queueOf(2).copy(cursor = 1, repeat = RepeatMode.ALL)
        val advanced = QueueEngine.next(queue, userInitiated = false)
        assertEquals(0, advanced.cursor)
    }

    @Test
    fun `automatic advance at the end with repeat one does not move the queue`() {
        val queue = queueOf(3).copy(cursor = 2, repeat = RepeatMode.ONE)
        val advanced = QueueEngine.next(queue, userInitiated = false)
        assertEquals(2, advanced.cursor)
    }

    @Test
    fun `repeat one is enforced by the player not the queue arithmetic`() {
        // ExoPlayer's own REPEAT_MODE_ONE loops the current item, so the queue
        // only ever advances when a *different* track should start. Asserting
        // the mid-queue behaviour documents that division of responsibility.
        val queue = queueOf(3).copy(cursor = 0, repeat = RepeatMode.ONE)
        assertEquals(1, QueueEngine.next(queue, userInitiated = false).cursor)
    }

    @Test
    fun `user pressing next on the last track wraps to the top`() {
        val queue = queueOf(3).copy(cursor = 2)
        assertEquals(0, QueueEngine.next(queue, userInitiated = true).cursor)
    }

    @Test
    fun `previous steps back and clamps at the start without repeat`() {
        val queue = queueOf(3).copy(cursor = 1)
        assertEquals(0, QueueEngine.previous(queue).cursor)
        assertEquals(0, QueueEngine.previous(queueOf(3).copy(cursor = 0)).cursor)
    }

    @Test
    fun `previous wraps to the end when repeating all`() {
        val queue = queueOf(3).copy(cursor = 0, repeat = RepeatMode.ALL)
        assertEquals(2, QueueEngine.previous(queue).cursor)
    }

    @Test
    fun `shuffle keeps the current track at the head of the order`() {
        val tracks = (0 until 10).map { track("t$it") }
        val queue = QueueEngine.replace(tracks, startIndex = 4, shuffle = true, repeat = RepeatMode.OFF)

        assertEquals(10, queue.order.size)
        assertEquals(10, queue.order.toSet().size)
        assertEquals(4, queue.order.first())
        assertEquals(0, queue.cursor)
        assertEquals("/music/t4.mp3", queue.currentPath())
    }

    @Test
    fun `enabling shuffle preserves the currently playing track`() {
        val queue = queueOf(6).copy(cursor = 3)
        val shuffled = QueueEngine.setShuffle(queue, enabled = true)

        assertTrue(shuffled.shuffle)
        assertEquals("/music/t3.mp3", shuffled.currentPath())
        assertEquals(6, shuffled.order.size)
    }

    @Test
    fun `disabling shuffle restores the natural order and keeps the track`() {
        val tracks = (0 until 5).map { track("t$it") }
        val shuffled = QueueEngine.replace(tracks, startIndex = 2, shuffle = true, repeat = RepeatMode.OFF)
        val restored = QueueEngine.setShuffle(shuffled, enabled = false)

        assertFalse(restored.shuffle)
        assertEquals(listOf(0, 1, 2, 3, 4), restored.order)
        assertEquals("/music/t2.mp3", restored.currentPath())
    }

    @Test
    fun `add appends without disturbing the cursor`() {
        val queue = queueOf(2).copy(cursor = 1)
        val added = QueueEngine.add(queue, listOf(track("new")))

        assertEquals(3, added.tracks.size)
        assertEquals(listOf(0, 1, 2), added.order)
        assertEquals(1, added.cursor)
        assertEquals("/music/t1.mp3", added.currentPath())
    }

    @Test
    fun `playNext inserts directly after the current track`() {
        val queue = queueOf(3).copy(cursor = 0)
        val added = QueueEngine.playNext(queue, listOf(track("x"), track("y")))

        assertEquals(5, added.tracks.size)
        assertEquals(listOf(0, 3, 4, 1, 2), added.order)
        assertEquals(0, added.cursor)
    }

    @Test
    fun `removeAt before the cursor keeps the same track playing`() {
        val queue = queueOf(4).copy(cursor = 2)
        val playing = queue.currentPath()
        val removed = QueueEngine.removeAt(queue, playOrderPosition = 0)

        assertEquals(1, removed.cursor)
        assertEquals(playing, removed.currentPath())
    }

    @Test
    fun `removeAt after the cursor does not move the cursor`() {
        val queue = queueOf(4).copy(cursor = 1)
        val removed = QueueEngine.removeAt(queue, playOrderPosition = 3)

        assertEquals(1, removed.cursor)
        assertEquals("/music/t1.mp3", removed.currentPath())
    }

    /**
     * The invariant playback recovery leans on: when the unreadable item is
     * dropped from the queue, the cursor already points at its successor, so the
     * service can simply play the slot again instead of skipping two tracks.
     */
    @Test
    fun `dropping the playing slot leaves the following track in its place`() {
        val queue = queueOf(4).copy(cursor = 1)
        val removed = QueueEngine.removeAt(queue, playOrderPosition = 1)

        assertEquals(1, removed.cursor)
        assertEquals("/music/t2.mp3", removed.currentPath())
    }

    @Test
    fun `removing the last remaining track empties the queue`() {
        val queue = queueOf(1)
        val removed = QueueEngine.removeAt(queue, playOrderPosition = 0)

        assertTrue(removed.isEmpty)
        assertEquals(-1, removed.cursor)
    }

    @Test
    fun `removing the playing track keeps the cursor in range`() {
        val queue = queueOf(3).copy(cursor = 2)
        val removed = QueueEngine.removeAt(queue, playOrderPosition = 2)

        assertEquals(1, removed.cursor)
        assertEquals("/music/t1.mp3", removed.currentPath())
    }

    @Test
    fun `move keeps the currently playing track playing`() {
        val queue = queueOf(4).copy(cursor = 1)
        val playing = queue.currentPath()
        // Drag the playing row to the end.
        val moved = QueueEngine.move(queue, from = 1, to = 3)

        assertEquals(playing, moved.currentPath())
        assertEquals(3, moved.cursor)
    }

    @Test
    fun `move of an unrelated row shifts the cursor to follow the track`() {
        val queue = queueOf(4).copy(cursor = 2)
        // Move the first row to the end; the playing track shifts up by one.
        val moved = QueueEngine.move(queue, from = 0, to = 3)

        assertEquals("/music/t2.mp3", moved.currentPath())
        assertEquals(1, moved.cursor)
    }

    @Test
    fun `move is a no-op for identical or invalid positions`() {
        val queue = queueOf(3).copy(cursor = 1)
        assertEquals(queue, QueueEngine.move(queue, from = 1, to = 1))
        assertEquals(queue, QueueEngine.move(queue, from = -1, to = 0))
        assertEquals(queue, QueueEngine.move(queue, from = 0, to = 9))
    }

    @Test
    fun `shuffledOrder is a permutation and short lists are handled`() {
        val order = QueueEngine.shuffledOrder(size = 8, first = 3)
        assertEquals((0 until 8).toList().toSet(), order.toSet())
        assertEquals(3, order.first())

        assertEquals(emptyList<Int>(), QueueEngine.shuffledOrder(size = 0, first = 0))
        assertEquals(listOf(0), QueueEngine.shuffledOrder(size = 1, first = 0))
        // A first index outside the range must not corrupt the permutation.
        assertEquals((0 until 3).toSet(), QueueEngine.shuffledOrder(size = 3, first = 9).toSet())
    }

    @Test
    fun `orderedTracks reflects shuffle order`() {
        val tracks = (0 until 4).map { track("t$it") }
        val queue = PlaybackQueue(
            tracks = tracks,
            order = listOf(2, 0, 3, 1),
            cursor = 0,
            shuffle = true,
            repeat = RepeatMode.OFF,
        )
        assertEquals(
            listOf("/music/t2.mp3", "/music/t0.mp3", "/music/t3.mp3", "/music/t1.mp3"),
            queue.orderedTracks.map { it.path },
        )
    }

    @Test
    fun `clear empties everything`() {
        val cleared = QueueEngine.clear()
        assertTrue(cleared.isEmpty)
        assertEquals(-1, cleared.cursor)
    }
}
