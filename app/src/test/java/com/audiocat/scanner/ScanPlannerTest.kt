package com.audiocat.scanner

import com.audiocat.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the scan planner, which is the part of scanning that decides what
 * enters the library.
 *
 * These run on the JVM because the planner has no Android dependencies - that is
 * the reason it was separated from the media store query in the first place.
 */
class ScanPlannerTest {

    private fun candidate(
        path: String,
        size: Long = 100L,
        modified: Long = 1_000L,
        duration: Long = 60_000L,
    ) = ScanCandidate(
        path = path,
        uri = "file://$path",
        displayName = path.substringAfterLast('/'),
        size = size,
        lastModified = modified,
        durationMs = duration,
        title = null,
        artist = null,
        album = null,
        mimeType = null,
        isVideo = ScanPlanner.isVideo(path),
    )

    private fun existing(path: String, size: Long = 100L, modified: Long = 1_000L) = Track(
        path = path,
        uri = "file://$path",
        title = path.substringAfterLast('/'),
        fileSize = size,
        lastModified = modified,
    )

    // ------------------------------------------------------------------
    // Extension handling
    // ------------------------------------------------------------------

    @Test
    fun `audio and video containers are both playable`() {
        listOf("a.mp3", "a.flac", "a.m4a", "a.ogg", "a.opus", "a.wav", "a.aac", "a.wma").forEach {
            assertTrue("$it should be playable", ScanPlanner.isPlayable(it))
        }
        listOf("v.mp4", "v.mkv", "v.avi", "v.mov", "v.webm", "v.3gp", "v.ts").forEach {
            assertTrue("$it should be playable", ScanPlanner.isPlayable(it))
        }
    }

    @Test
    fun `non media files are rejected`() {
        listOf("notes.txt", "cover.jpg", "archive.zip", "noextension", "sheet.pdf").forEach {
            assertFalse("$it should be rejected", ScanPlanner.isPlayable(it))
        }
    }

    /**
     * A phone's media index also lists its factory sounds, which a third-party app
     * cannot stream; they must never reach the library.
     */
    @Test
    fun `only paths on a mounted user volume are reachable`() {
        listOf(
            "/storage/emulated/0/Music/a.mp3",
            "/storage/ABCD-1234/Download/a.mp3",
        ).forEach { assertTrue("$it should be reachable", ScanPlanner.isOnUserVolume(it)) }

        listOf(
            "/hw_product/region_comm/china/media/Pre-loaded/Music/a.mp3",
            "/system/media/audio/notifications/a.mp3",
            "storage/emulated/0/Music/a.mp3",
            "",
        ).forEach { assertFalse("$it should be rejected", ScanPlanner.isOnUserVolume(it)) }
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertTrue(ScanPlanner.isPlayable("SONG.MP3"))
        assertTrue(ScanPlanner.isPlayable("Movie.MKV"))
        assertTrue(ScanPlanner.isVideo("Movie.MKV"))
    }

    /**
     * The extension is the app's only reliable format key - MediaStore reports an
     * .m4a as `audio/mp4a-latm` - so the rule the filter and the UI share has to be
     * the one the scanner already uses.
     */
    @Test
    fun `extensionOf returns the lower-case container`() {
        assertEquals("mp3", ScanPlanner.extensionOf("Song.MP3"))
        assertEquals("m4a", ScanPlanner.extensionOf("/storage/emulated/0/a b.m4a"))
        assertEquals("mkv", ScanPlanner.extensionOf("VID_001.mkv"))
        assertEquals("", ScanPlanner.extensionOf("noextension"))
        assertEquals("", ScanPlanner.extensionOf(""))
        // A dot in a directory name must not be mistaken for a container.
        assertEquals("mp3", ScanPlanner.extensionOf("/storage/v1.2/Music/Song.mp3"))
    }

    @Test
    fun `video detection distinguishes containers from audio`() {
        assertTrue(ScanPlanner.isVideo("movie.mp4"))
        assertFalse(ScanPlanner.isVideo("song.mp3"))
        // m4a and m4v differ only by a letter and must not be confused.
        assertFalse(ScanPlanner.isVideo("audio.m4a"))
        assertTrue(ScanPlanner.isVideo("video.m4v"))
    }

    // ------------------------------------------------------------------
    // Folder containment
    // ------------------------------------------------------------------

    @Test
    fun `isInside matches whole path segments only`() {
        assertTrue(ScanPlanner.isInside("/music/live/a.mp3", "/music/live"))
        assertTrue(ScanPlanner.isInside("/music/live/a.mp3", "/music/live/"))
        assertTrue(ScanPlanner.isInside("/music/live", "/music/live"))
        // The classic prefix bug: LiveBootlegs must not match Live.
        assertFalse(ScanPlanner.isInside("/music/LiveBootlegs/a.mp3", "/music/live"))
        assertFalse(ScanPlanner.isInside("/music/a.mp3", "/music/live"))
    }

    @Test
    fun `a root folder contains everything`() {
        assertTrue(ScanPlanner.isInside("/anything/at/all.mp3", "/"))
        assertTrue(ScanPlanner.isInside("/anything/at/all.mp3", ""))
    }

    @Test
    fun `normalize strips trailing separators`() {
        assertEquals("/music", ScanPlanner.normalize("/music/"))
        assertEquals("/music", ScanPlanner.normalize("/music"))
        assertEquals("/", ScanPlanner.normalize(""))
    }

    @Test
    fun `exclusion always wins over inclusion`() {
        val excluded = listOf("/storage/emulated/0/Music/Voice")
        assertTrue(ScanPlanner.isExcluded("/storage/emulated/0/Music/Voice/a.mp3", excluded))
        assertFalse(ScanPlanner.isExcluded("/storage/emulated/0/Music/a.mp3", excluded))
    }

    @Test
    fun `an empty include list means every volume`() {
        assertTrue(ScanPlanner.isIncluded("/anywhere/a.mp3", emptyList()))
        assertTrue(ScanPlanner.isIncluded("/music/a.mp3", listOf("/music")))
        assertFalse(ScanPlanner.isIncluded("/podcasts/a.mp3", listOf("/music")))
    }

    @Test
    fun `system folders are ignored`() {
        assertTrue(ScanPlanner.shouldIgnorePath("/storage/emulated/0/Android/data/com.x/files/a.mp3"))
        assertTrue(ScanPlanner.shouldIgnorePath("/storage/emulated/0/Android/obb/a.mp3"))
        assertFalse(ScanPlanner.shouldIgnorePath("/storage/emulated/0/Music/a.mp3"))
    }

    // ------------------------------------------------------------------
    // Incremental planning
    // ------------------------------------------------------------------

    @Test
    fun `new files are indexed and known unchanged files are skipped`() {
        val candidates = listOf(
            candidate("/m/known.mp3"),
            candidate("/m/new.mp3"),
        )
        val plan = ScanPlanner.plan(
            candidates = candidates,
            existing = mapOf("/m/known.mp3" to existing("/m/known.mp3")),
            includedFolders = emptyList(),
            excludedFolders = emptyList(),
            force = false,
        )

        assertEquals(listOf("/m/new.mp3"), plan.toIndex.map { it.path })
        assertEquals(listOf("/m/known.mp3"), plan.unchanged.map { it.path })
    }

    @Test
    fun `a changed size or modification time triggers reindexing`() {
        val grown = candidate("/m/grown.mp3", size = 200L)
        val touched = candidate("/m/touched.mp3", modified = 5_000L)
        val plan = ScanPlanner.plan(
            candidates = listOf(grown, touched),
            existing = mapOf(
                "/m/grown.mp3" to existing("/m/grown.mp3", size = 100L),
                "/m/touched.mp3" to existing("/m/touched.mp3", modified = 1_000L),
            ),
            includedFolders = emptyList(),
            excludedFolders = emptyList(),
            force = false,
        )

        assertEquals(2, plan.toIndex.size)
        assertTrue(plan.unchanged.isEmpty())
    }

    @Test
    fun `force reindexes everything that is otherwise unchanged`() {
        val plan = ScanPlanner.plan(
            candidates = listOf(candidate("/m/a.mp3")),
            existing = mapOf("/m/a.mp3" to existing("/m/a.mp3")),
            includedFolders = emptyList(),
            excludedFolders = emptyList(),
            force = true,
        )

        assertEquals(1, plan.toIndex.size)
        assertTrue(plan.unchanged.isEmpty())
    }

    @Test
    fun `excluded folders are filtered out of the plan entirely`() {
        val plan = ScanPlanner.plan(
            candidates = listOf(
                candidate("/m/keep.mp3"),
                candidate("/m/Voice/memo.mp3"),
            ),
            existing = emptyMap(),
            includedFolders = emptyList(),
            excludedFolders = listOf("/m/Voice"),
            force = false,
        )

        assertEquals(listOf("/m/keep.mp3"), plan.toIndex.map { it.path })
    }

    @Test
    fun `files outside the included folders are not indexed`() {
        val plan = ScanPlanner.plan(
            candidates = listOf(
                candidate("/m/Music/song.mp3"),
                candidate("/m/Podcasts/ep.mp3"),
            ),
            existing = emptyMap(),
            includedFolders = listOf("/m/Music"),
            excludedFolders = emptyList(),
            force = false,
        )

        assertEquals(listOf("/m/Music/song.mp3"), plan.toIndex.map { it.path })
    }

    @Test
    fun `duplicate candidate paths are collapsed`() {
        val plan = ScanPlanner.plan(
            candidates = listOf(candidate("/m/a.mp3"), candidate("/m/a.mp3", size = 999L)),
            existing = emptyMap(),
            includedFolders = emptyList(),
            excludedFolders = emptyList(),
            force = false,
        )

        assertEquals(1, plan.toIndex.size)
        // The first occurrence wins, so scanning is order-stable.
        assertEquals(100L, plan.toIndex.first().size)
    }

    @Test
    fun `an empty library plans everything as new`() {
        val candidates = (1..5).map { candidate("/m/$it.mp3") }
        val plan = ScanPlanner.plan(
            candidates = candidates,
            existing = emptyMap(),
            includedFolders = emptyList(),
            excludedFolders = emptyList(),
            force = false,
        )
        assertEquals(5, plan.toIndex.size)
        assertTrue(plan.unchanged.isEmpty())
    }

    @Test
    fun `video containers are planned like audio files`() {
        val plan = ScanPlanner.plan(
            candidates = listOf(candidate("/m/movie.mkv")),
            existing = emptyMap(),
            includedFolders = emptyList(),
            excludedFolders = emptyList(),
            force = false,
        )
        assertTrue(plan.toIndex.single().isVideo)
    }
}
