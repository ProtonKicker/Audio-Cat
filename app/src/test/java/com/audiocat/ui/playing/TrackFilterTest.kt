package com.audiocat.ui.playing

import com.audiocat.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library filter's predicate, which decides what the list under the lane
 * shows and is the only part of it that can be tested without a screen.
 */
class TrackFilterTest {

    private fun track(path: String, video: Boolean) = Track(
        path = path,
        uri = "content://media/external/audio/media/1",
        title = path.substringAfterLast('/'),
        isVideo = video,
    )

    @Test
    fun `all keeps everything`() {
        val tracks = listOf(track("/m/a.mp3", false), track("/m/b.mp4", true))
        assertEquals(2, tracks.count { TrackFilter.All.matches(it) })
    }

    @Test
    fun `audio and video split on the container flag, not the extension`() {
        // A .mp3 the scanner classified as video must follow the flag: the flag is
        // what decides whether the app decodes video at all.
        assertTrue(TrackFilter.Video.matches(track("/m/a.mp3", true)))
        assertFalse(TrackFilter.Video.matches(track("/m/b.m4a", false)))
        assertTrue(TrackFilter.Audio.matches(track("/m/b.m4a", false)))
    }

    @Test
    fun `a format matches the extension case-insensitively`() {
        assertTrue(TrackFilter.Format("mp3").matches(track("/m/SONG.MP3", false)))
        assertFalse(TrackFilter.Format("mp3").matches(track("/m/song.m4a", false)))
    }

    @Test
    fun `a format never matches a file without an extension`() {
        assertFalse(TrackFilter.Format("mp3").matches(track("/storage/emulated/0/noisefile", false)))
    }
}
