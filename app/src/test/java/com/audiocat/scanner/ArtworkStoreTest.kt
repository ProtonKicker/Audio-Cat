package com.audiocat.scanner

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cover art that is not embedded in the file.
 *
 * Most of the artwork users expect to see is a picture sitting next to the
 * audio, not inside it: a poster named after a downloaded video, or the
 * `cover.jpg` an album rip ships with. These lock the ranking down so a folder
 * of files never resolves the wrong picture.
 */
@RunWith(RobolectricTestRunner::class)
class ArtworkStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: ArtworkStore

    @Before
    fun setUp() {
        store = ArtworkStore(ApplicationProvider.getApplicationContext())
    }

    private fun file(directory: File, name: String): File =
        File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun coverFor(track: File): Uri? = store.sidecarFor(track.path)?.uri

    @Test
    fun `an image named after the track is preferred over the folder cover`() {
        val directory = folder.newFolder("album")
        val poster = file(directory, "movie.mkv.jpg")
        file(directory, "cover.png")

        assertEquals(Uri.fromFile(poster), coverFor(File(directory, "movie.mkv")))
    }

    @Test
    fun `a plain stem match also counts as the track's own poster`() {
        val directory = folder.newFolder("stem")
        val poster = file(directory, "song.jpg")

        assertEquals(Uri.fromFile(poster), coverFor(File(directory, "song.mp3")))
    }

    @Test
    fun `the name match ignores case`() {
        val directory = folder.newFolder("upper")
        val poster = file(directory, "Song.JPG")

        assertEquals(Uri.fromFile(poster), coverFor(File(directory, "song.mp3")))
    }

    @Test
    fun `a folder cover is used when nothing matches the track name`() {
        val directory = folder.newFolder("rip")
        val cover = file(directory, "cover.jpg")
        file(directory, "note.txt")

        assertEquals(Uri.fromFile(cover), coverFor(File(directory, "01 - Intro.flac")))
    }

    @Test
    fun `cover beats folder beats front`() {
        val directory = folder.newFolder("many")
        val cover = file(directory, "Cover.jpeg")
        file(directory, "folder.jpg")
        file(directory, "front.jpg")

        assertEquals(Uri.fromFile(cover), coverFor(File(directory, "track.m4a")))
    }

    @Test
    fun `an unrelated image is not treated as cover art`() {
        val directory = folder.newFolder("photos")
        file(directory, "sunset.png")

        assertNull(coverFor(File(directory, "song.mp3")))
    }

    @Test
    fun `an empty directory has no sidecar art`() {
        val directory = folder.newFolder("bare")

        assertNull(coverFor(File(directory, "song.mp3")))
    }

    @Test
    fun `a zero-byte image is not treated as art`() {
        val directory = folder.newFolder("empty-art")
        File(directory, "cover.jpg").apply { writeBytes(ByteArray(0)) }

        assertNull(coverFor(File(directory, "song.mp3")))
    }

    @Test
    fun `a path without a directory is handled`() {
        assertNull(store.sidecarFor("song.mp3"))
    }

    // --- Where a stored cover lives ------------------------------------------

    private fun stored(name: String, durable: Boolean): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(if (durable) context.filesDir else context.cacheDir, "artwork")
        root.mkdirs()
        return File(root, name).apply { writeBytes(byteArrayOf(9, 8, 7)) }
    }

    @Test
    fun `a user name resolves to durable storage`() {
        val expected = stored("user-abc.jpg", durable = true)

        assertEquals(expected, store.fileByName("user-abc.jpg"))
        assertTrue(store.isUserOwned("user-abc.jpg"))
    }

    @Test
    fun `an extracted name resolves to the cache`() {
        val expected = stored("abc.jpg", durable = false)

        assertEquals(expected, store.fileByName("abc.jpg"))
        assertFalse(store.isUserOwned("abc.jpg"))
    }

    @Test
    fun `clearing the cache keeps the covers the user chose`() {
        val extracted = stored("abc.jpg", durable = false)
        val chosen = stored("user-abc.jpg", durable = true)

        assertEquals(1, store.clear())

        assertFalse(extracted.exists())
        assertTrue(chosen.exists())
    }

    @Test
    fun `removing a custom cover cannot touch an extracted one`() {
        val extracted = stored("abc.jpg", durable = false)
        val chosen = stored("user-abc.jpg", durable = true)

        store.deleteUser("abc.jpg")
        assertTrue(extracted.exists())

        store.deleteUser("user-abc.jpg")
        assertFalse(chosen.exists())
    }
}
