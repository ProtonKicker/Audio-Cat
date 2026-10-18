package com.audiocat.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for batch renaming.
 *
 * Renaming is the most destructive feature in the app, so the planner is pure
 * and tested exhaustively: collisions, ordering hazards and illegal characters
 * are all resolved before anything touches the disk.
 */
class RenamePlannerTest {

    private fun target(path: String, title: String = "", artist: String? = null) =
        RenameTarget(path = path, title = title.ifEmpty { path.substringAfterLast('/') }, artist = artist)

    private fun names(items: List<RenamePlanItem>) = items.map { it.fileName }

    // ------------------------------------------------------------------
    // Sanitising
    // ------------------------------------------------------------------

    @Test
    fun `illegal filename characters are replaced`() {
        assertEquals("a_b", RenamePlanner.sanitize("a/b"))
        assertEquals("a_b_c", RenamePlanner.sanitize("a:b?c"))
        assertEquals("a_b", RenamePlanner.sanitize("a\\b"))
        assertEquals("a_b", RenamePlanner.sanitize("a*b"))
    }

    @Test
    fun `trailing dots and spaces are stripped`() {
        assertEquals("song", RenamePlanner.sanitize("song..."))
        assertEquals("song", RenamePlanner.sanitize("song   "))
        assertEquals("song", RenamePlanner.sanitize("  song  "))
    }

    @Test
    fun `an empty name falls back to a placeholder`() {
        assertEquals("untitled", RenamePlanner.sanitize(""))
        assertEquals("untitled", RenamePlanner.sanitize("..."))
    }

    // ------------------------------------------------------------------
    // Prefix / suffix
    // ------------------------------------------------------------------

    @Test
    fun `prefix and suffix wrap the original name and preserve the extension`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/song.mp3")),
            RenamePattern(prefix = "01 - ", suffix = " (live)"),
        )
        assertEquals(listOf("01 - song (live).mp3"), names(plan))
        assertTrue(plan.single().changed)
    }

    @Test
    fun `a no-op pattern reports nothing changed`() {
        val plan = RenamePlanner.plan(listOf(target("/m/song.mp3")), RenamePattern.NONE)
        assertFalse(plan.single().changed)
        assertEquals(listOf("song.mp3"), names(plan))
    }

    @Test
    fun `an empty find is treated as a no-op when nothing else is set`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/song.mp3")),
            RenamePattern(find = "", replaceWith = "x"),
        )
        assertFalse(plan.single().changed)
    }

    // ------------------------------------------------------------------
    // Find and replace
    // ------------------------------------------------------------------

    @Test
    fun `find and replace rewrites the stem only`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/01 - song.mp3")),
            RenamePattern(find = "01 - ", replaceWith = ""),
        )
        assertEquals(listOf("song.mp3"), names(plan))
    }

    @Test
    fun `replace is case sensitive when requested`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/Song.mp3")),
            RenamePattern(find = "song", replaceWith = "Track", matchCase = true),
        )
        // Nothing matched, so the name is unchanged.
        assertFalse(plan.single().changed)

        val insensitive = RenamePlanner.plan(
            listOf(target("/m/Song.mp3")),
            RenamePattern(find = "song", replaceWith = "Track", matchCase = false),
        )
        assertEquals(listOf("Track.mp3"), names(insensitive))
    }

    @Test
    fun `replaceAll controls whether later occurrences are replaced`() {
        val all = RenamePlanner.plan(
            listOf(target("/m/a-a-a.mp3")),
            RenamePattern(find = "a", replaceWith = "b", replaceAll = true),
        )
        assertEquals(listOf("b-b-b.mp3"), names(all))

        val firstOnly = RenamePlanner.plan(
            listOf(target("/m/a-a-a.mp3")),
            RenamePattern(find = "a", replaceWith = "b", replaceAll = false),
        )
        assertEquals(listOf("b-a-a.mp3"), names(firstOnly))
    }

    // ------------------------------------------------------------------
    // Numbering
    // ------------------------------------------------------------------

    @Test
    fun `sequential numbering is applied with padding`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/a.mp3"), target("/m/b.mp3"), target("/m/c.mp3")),
            RenamePattern(numbering = true, numberStart = 1, numberPadding = 3),
        )
        assertEquals(listOf("a_001.mp3", "b_002.mp3", "c_003.mp3"), names(plan))
    }

    @Test
    fun `numbering honours a custom start`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/a.mp3"), target("/m/b.mp3")),
            RenamePattern(numbering = true, numberStart = 10, numberPadding = 2),
        )
        assertEquals(listOf("a_10.mp3", "b_11.mp3"), names(plan))
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    @Test
    fun `tag based naming uses artist and title`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/xyz.mp3", title = "Song", artist = "Band")),
            RenamePattern(useTags = true),
        )
        assertEquals(listOf("Band - Song.mp3"), names(plan))
    }

    @Test
    fun `tag based naming omits a missing artist`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/xyz.mp3", title = "Song", artist = null)),
            RenamePattern(useTags = true),
        )
        assertEquals(listOf("Song.mp3"), names(plan))
    }

    // ------------------------------------------------------------------
    // Collisions
    // ------------------------------------------------------------------

    @Test
    fun `two targets renamed to the same name are given distinct results`() {
        val plan = RenamePlanner.plan(
            listOf(
                RenameTarget("/m/a.mp3", "Same", null),
                RenameTarget("/m/b.mp3", "Same", null),
            ),
            RenamePattern(useTags = true),
        )
        assertEquals(listOf("/m/Same.mp3", "/m/Same (2).mp3"), plan.map { it.to })
    }

    @Test
    fun `a swapped pair of names never loses a file`() {
        // a.mp3 wants b's name and vice versa. Two files cannot share one name,
        // so the planner must still produce two distinct destinations and must
        // not be rejected by validation (both originals are being vacated).
        val plan = RenamePlanner.plan(
            listOf(
                RenameTarget("/m/a.mp3", "b", null),
                RenameTarget("/m/b.mp3", "a", null),
            ),
            RenamePattern(useTags = true),
        )
        val targets = plan.map { it.to }
        assertEquals(2, targets.toSet().size)
        // The first target takes the freed name; the second is displaced.
        assertEquals("/m/b.mp3", targets[0])
        assertNull(RenamePlanner.validate(plan, setOf("/m/a.mp3", "/m/b.mp3")))
    }

    @Test
    fun `a rename onto a name that is not being vacated is refused`() {
        val plan = RenamePlanner.plan(
            listOf(RenameTarget("/m/a.mp3", "taken", null)),
            RenamePattern(useTags = true),
        )
        assertNotNull(RenamePlanner.validate(plan, setOf("/m/a.mp3", "/m/taken.mp3")))
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    fun `validate rejects renaming onto an existing untouched file`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/a.mp3")),
            RenamePattern(prefix = "x"),
        )
        val error = RenamePlanner.validate(plan, setOf("/m/xa.mp3"))
        assertNotNull(error)
    }

    @Test
    fun `validate allows renaming onto a file that is itself being moved`() {
        val swapped = listOf(
            RenameTarget("/m/a.mp3", "b", null),
            RenameTarget("/m/b.mp3", "a", null),
        )
        val plan = RenamePlanner.plan(swapped, RenamePattern(useTags = true))
        val error = RenamePlanner.validate(plan, setOf("/m/a.mp3", "/m/b.mp3"))
        assertNull(error)
    }

    @Test
    fun `validate rejects duplicate targets`() {
        val plan = listOf(
            RenamePlanItem("/m/a.mp3", "/m/same.mp3", changed = true),
            RenamePlanItem("/m/b.mp3", "/m/same.mp3", changed = true),
        )
        assertNotNull(RenamePlanner.validate(plan, emptySet()))
    }

    @Test
    fun `validate accepts a clean plan`() {
        val plan = RenamePlanner.plan(
            listOf(target("/m/a.mp3"), target("/m/b.mp3")),
            RenamePattern(prefix = "new "),
        )
        assertNull(RenamePlanner.validate(plan, setOf("/m/a.mp3", "/m/b.mp3")))
    }

    @Test
    fun `an empty pattern on an empty selection produces an empty plan`() {
        assertTrue(RenamePlanner.plan(emptyList(), RenamePattern(prefix = "x")).isEmpty())
    }

    @Test
    fun `rename targets keep their directory`() {
        val plan = RenamePlanner.plan(
            listOf(target("/storage/emulated/0/Music/deep/song.mp3")),
            RenamePattern(prefix = "p_"),
        )
        assertEquals("/storage/emulated/0/Music/deep/p_song.mp3", plan.single().to)
    }
}
