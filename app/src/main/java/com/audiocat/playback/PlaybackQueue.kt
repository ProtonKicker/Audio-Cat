package com.audiocat.playback

import com.audiocat.data.RepeatMode
import com.audiocat.data.db.Track

/**
 * Immutable snapshot of what is playing and what comes next.
 *
 * [order] holds indices into [tracks] and encodes shuffle; when shuffle is off
 * it is simply `0, 1, 2, ...`. Keeping the order as a separate permutation means
 * enabling shuffle never mutates the user's visible list.
 */
data class PlaybackQueue(
    val tracks: List<Track> = emptyList(),
    val order: List<Int> = emptyList(),
    val cursor: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
) {
    /**
     * True when there is nothing to play.
     *
     * This is deliberately based on [order] rather than [tracks]: removing the
     * last entry empties the play order while the (now unreferenced) track list
     * may still be populated, and callers must treat that as "nothing playing".
     */
    val isEmpty: Boolean get() = order.isEmpty()
    val size: Int get() = tracks.size

    /** The track currently pointed at, if any. */
    val current: Track?
        get() = tracks.getOrNull(order.getOrNull(cursor) ?: -1)

    /** Index into [tracks] of the current track. */
    val currentTrackIndex: Int get() = order.getOrNull(cursor) ?: -1

    /** Tracks in play order, which is what the queue sheet displays. */
    val orderedTracks: List<Track> get() = order.mapNotNull { tracks.getOrNull(it) }

    /** Tracks in the order they were added, ignoring shuffle. */
    val originalTracks: List<Track> get() = tracks
}

/**
 * Pure queue arithmetic.
 *
 * All transitions are expressed as functions from one [PlaybackQueue] to the
 * next so they can be tested without a player, a service, or a device.
 */
object QueueEngine {

    fun replace(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false, repeat: RepeatMode): PlaybackQueue {
        if (tracks.isEmpty()) return PlaybackQueue(repeat = repeat)
        val order = if (shuffle) shuffledOrder(tracks.size, first = startIndex) else tracks.indices.toList()
        val cursor = if (shuffle) 0 else startIndex.coerceIn(0, tracks.lastIndex)
        return PlaybackQueue(tracks, order, cursor, shuffle, repeat)
    }

    /**
     * Appends tracks without disturbing the current position.
     *
     * In shuffle mode the new entries are appended to the tail of the play order
     * so that the currently playing item and everything after it stay put.
     */
    fun add(queue: PlaybackQueue, tracks: List<Track>): PlaybackQueue {
        if (tracks.isEmpty()) return queue
        val offset = queue.tracks.size
        val merged = queue.tracks + tracks
        val appendIndices = tracks.indices.map { offset + it }
        val order = queue.order + appendIndices
        val cursor = if (queue.cursor < 0 && order.isNotEmpty()) 0 else queue.cursor
        return queue.copy(tracks = merged, order = order, cursor = cursor)
    }

    /** Inserts [tracks] directly after the current item ("play next"). */
    fun playNext(queue: PlaybackQueue, tracks: List<Track>): PlaybackQueue {
        if (tracks.isEmpty()) return queue
        if (queue.cursor < 0) return add(queue, tracks)
        val offset = queue.tracks.size
        val merged = queue.tracks + tracks
        val insertAt = (queue.cursor + 1).coerceAtMost(queue.order.size)
        val order = buildList {
            addAll(queue.order.take(insertAt))
            addAll(tracks.indices.map { offset + it })
            addAll(queue.order.drop(insertAt))
        }
        return queue.copy(tracks = merged, order = order)
    }

    fun removeAt(queue: PlaybackQueue, playOrderPosition: Int): PlaybackQueue {
        if (playOrderPosition !in queue.order.indices) return queue
        val order = queue.order.toMutableList().apply { removeAt(playOrderPosition) }
        var cursor = queue.cursor
        if (order.isEmpty()) return queue.copy(order = order, cursor = -1)
        if (playOrderPosition < queue.cursor) cursor--
        if (cursor >= order.size) cursor = order.size - 1
        return queue.copy(order = order, cursor = cursor)
    }

    fun clear(): PlaybackQueue = PlaybackQueue()

    /**
     * Moves the item at [from] to [to] within the play order.
     *
     * The cursor follows the currently playing track rather than staying at a
     * fixed offset, so dragging rows never changes what is playing.
     */
    fun move(queue: PlaybackQueue, from: Int, to: Int): PlaybackQueue {
        if (from !in queue.order.indices || to !in queue.order.indices || from == to) return queue
        val currentOrderIndex = queue.cursor
        val order = queue.order.toMutableList()
        val moved = order.removeAt(from)
        order.add(to, moved)
        val cursor = when {
            currentOrderIndex < 0 -> currentOrderIndex
            from == currentOrderIndex -> to
            from < currentOrderIndex && to >= currentOrderIndex -> currentOrderIndex - 1
            from > currentOrderIndex && to <= currentOrderIndex -> currentOrderIndex + 1
            else -> currentOrderIndex
        }
        return queue.copy(order = order, cursor = cursor)
    }

    fun setShuffle(queue: PlaybackQueue, enabled: Boolean): PlaybackQueue {
        if (enabled == queue.shuffle) return queue
        if (queue.isEmpty) return queue.copy(shuffle = enabled)
        val playingIndex = queue.currentTrackIndex
        val order = if (enabled) {
            shuffledOrder(queue.size, first = playingIndex)
        } else {
            queue.tracks.indices.toList()
        }
        val cursor = if (enabled) 0 else order.indexOf(playingIndex).coerceAtLeast(0)
        return queue.copy(order = order, cursor = if (playingIndex < 0) 0 else cursor, shuffle = enabled)
    }

    fun setRepeat(queue: PlaybackQueue, mode: RepeatMode): PlaybackQueue = queue.copy(repeat = mode)

    /** Jumps to an absolute index in [PlaybackQueue.tracks] (user tapped a row). */
    fun jumpToTrack(queue: PlaybackQueue, trackIndex: Int): PlaybackQueue {
        if (trackIndex !in queue.tracks.indices) return queue
        val position = queue.order.indexOf(trackIndex)
        if (position < 0) return queue
        return queue.copy(cursor = position)
    }

    /**
     * Advances for an explicit "next" press.
     *
     * Wraps at the end when repeating everything. When the queue ends without
     * repeat, a user press wraps to the top (matching mainstream players) while
     * an automatic track-end transition returns the queue unchanged so the
     * caller can stop playback.
     */
    fun next(queue: PlaybackQueue, userInitiated: Boolean = true): PlaybackQueue {
        if (queue.isEmpty) return queue
        if (queue.cursor < queue.order.lastIndex) return queue.copy(cursor = queue.cursor + 1)
        if (!userInitiated) {
            // Repeat-one is handled by seeking to the start, not by moving.
            return if (queue.repeat == RepeatMode.ALL) queue.copy(cursor = 0) else queue
        }
        return queue.copy(cursor = 0)
    }

    /** Advances for an explicit "previous" press, wrapping when repeating all. */
    fun previous(queue: PlaybackQueue): PlaybackQueue {
        if (queue.isEmpty) return queue
        if (queue.cursor > 0) return queue.copy(cursor = queue.cursor - 1)
        return if (queue.repeat == RepeatMode.ALL) {
            queue.copy(cursor = queue.order.lastIndex)
        } else {
            queue
        }
    }

    /** True when "next" should stop playback instead of moving on. */
    fun isAtEndWithoutRepeat(queue: PlaybackQueue): Boolean =
        queue.repeat == RepeatMode.OFF && queue.cursor >= queue.order.lastIndex

    /**
     * Produces a random permutation while keeping [first] as the head, so
     * toggling shuffle never interrupts the current track.
     */
    fun shuffledOrder(size: Int, first: Int, random: kotlin.random.Random = kotlin.random.Random.Default): List<Int> {
        val indices = ArrayList<Int>(size)
        for (index in 0 until size) if (index != first) indices.add(index)
        // Fisher-Yates; the whole library can be shuffled in O(n).
        for (i in indices.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = indices[i]
            indices[i] = indices[j]
            indices[j] = tmp
        }
        return if (first in 0 until size) listOf(first) + indices else indices
    }
}
