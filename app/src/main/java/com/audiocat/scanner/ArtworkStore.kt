package com.audiocat.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk cache for per-track artwork.
 *
 * Three sources are supported, cheapest first:
 *  1. the cover image embedded in the container's tags;
 *  2. an image file next to the track, found by [CoverFinder] - a poster named
 *     after a downloaded video, or the `cover.jpg` a rip ships with an album;
 *  3. the first decoded video frame, used only for video files and only when
 *     artwork is actually requested.
 *
 * A fourth kind is never extracted, only given: a cover the user picked from the
 * gallery. Those live under `filesDir`, because the original may be deleted from
 * the gallery the moment the pick is made, and the cache directory is fair game
 * for the system at any point. Both kinds are named by the same column, so
 * [fileByName] routes on the name prefix.
 *
 * Files are written as small JPEGs keyed by a hash of the track path, so the
 * cache survives renames of unrelated tracks and can be cleared wholesale.
 */
class ArtworkStore(
    private val context: Context,
    private val covers: CoverFinder = CoverFinder(context),
) {

    private val root: File
        get() = File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    /** Durable home for covers the user chose themselves. */
    private val userRoot: File
        get() = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Returns the cached art file for [path], or null when none exists. */
    fun cached(path: String): File? {
        val file = fileFor(path)
        return if (file.isFile && file.length() > 0) file else null
    }

    /**
     * Ensures artwork exists for [uri], extracting it when missing.
     *
     * @param isVideo enables the video-frame fallback.
     * @param maxPx longest edge of the stored bitmap; controlled by the
     *   "track art quality" setting.
     * @return the relative cache path to store on the track row, or null.
     */
    @WorkerThread
    fun ensure(
        path: String,
        uri: String,
        isVideo: Boolean,
        maxPx: Int = DEFAULT_MAX_PX,
        metadata: TrackMetadataReader,
    ): String? {
        cached(path)?.let { return it.name }

        val bytes = metadata.embeddedPicture(uri)
        val embedded = bytes?.let { decodeScaled(it, maxPx) }
        val sidecar = if (embedded == null) sidecarFor(path) else null
        val result = embedded
            ?: sidecar?.let { decodeCover(it, maxPx) }
            ?: if (isVideo) firstVideoFrame(uri, maxPx) else null
        result ?: return null

        return try {
            val target = fileFor(path)
            FileOutputStream(target).use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            result.recycle()
            target.name
        } catch (e: Exception) {
            Log.w(TAG, "failed to cache artwork for $path", e)
            null
        }
    }

    /** Absolute file for a value previously returned by [ensure] or [putUser]. */
    fun fileByName(name: String?): File? {
        if (name.isNullOrBlank()) return null
        val file = if (isUserOwned(name)) File(userRoot, name) else File(root, name)
        return if (file.isFile) file else null
    }

    /** True for a cover the user picked, which no scan or cache sweep may touch. */
    fun isUserOwned(name: String?): Boolean = name?.startsWith(USER_PREFIX) == true

    /**
     * Copies a user-chosen image into durable storage as [path]'s cover.
     *
     * The bytes are read at this moment on purpose: the gallery uri may be revoked
     * or the picture deleted later, and the cover must survive that.
     *
     * @return the value to store on the track row, or null when the image could
     *   not be read or decoded.
     */
    @WorkerThread
    fun putUser(path: String, uri: Uri, maxPx: Int = DEFAULT_MAX_PX): String? {
        val bitmap = decodeUriScaled(uri, maxPx) ?: return null
        return try {
            val target = File(userRoot, "$USER_PREFIX${hash(path)}.jpg")
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            target.name
        } catch (e: Exception) {
            Log.w(TAG, "failed to store cover for $path", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Drops a cover the user picked, including the copy in storage. */
    fun deleteUser(name: String?) {
        if (!isUserOwned(name)) return
        File(userRoot, requireNotNull(name)).delete()
    }

    /** Removes one cached thumbnail, when a track that used it leaves the library. */
    fun deleteFile(name: String?) {
        if (name.isNullOrBlank()) return
        fileByName(name)?.delete()
    }

    /**
     * The subset of [artByPath] (track path to cached file name, straight from the
     * library) whose file is no longer on disk.
     *
     * The system evicts the cache directory under storage pressure and "clear
     * covers" empties it, while the rows keep pointing at the missing thumbnails.
     * Those rows are already marked enriched, so without this check their art
     * would never come back.
     */
    @WorkerThread
    fun orphaned(artByPath: Map<String, String>): Set<String> =
        artByPath.entries.filterTo(ArrayList(artByPath.size)) { fileByName(it.value) == null }
            .mapTo(HashSet()) { it.key }

    /**
     * Deletes every extracted thumbnail. Returns the number of files removed.
     *
     * Covers the user chose are not touched: they cannot be recovered, while an
     * extracted one is rebuilt by the next scan.
     */
    fun clear(): Int {
        covers.invalidate()
        val files = File(context.cacheDir, DIR).listFiles() ?: return 0
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return removed
    }

    private fun fileFor(path: String): File = File(root, "${hash(path)}.jpg")

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    /**
     * The cover picture that stands in for [path], picked from the images in its
     * folder.
     *
     * Internal rather than private so the ranking rules are testable without a
     * media store or a decoder.
     */
    internal fun sidecarFor(path: String): Cover? {
        val source = File(path)
        val directory = source.parentFile ?: return null
        var best: Cover? = null
        var bestRank = Int.MAX_VALUE
        for (cover in covers.coversIn(directory)) {
            val rank = rankOfCover(cover.baseName, source.name, source.nameWithoutExtension)
            if (rank < bestRank) {
                bestRank = rank
                best = cover
            }
        }
        return best
    }

    /**
     * How well a picture named [baseName] stands in as cover art, lower being
     * better.
     *
     * A picture named after this exact file (`movie.mkv.jpg` or `movie.jpg`) beats
     * the folder's shared cover, and the conventional folder names win over each
     * other in the order players use them. Returns [Int.MAX_VALUE] for an image
     * that is nobody's cover.
     */
    private fun rankOfCover(baseName: String, trackName: String, stem: String): Int {
        if (baseName.equals(trackName, ignoreCase = true) || baseName.equals(stem, ignoreCase = true)) {
            return 0
        }
        val folderRank = COVER_NAMES.indexOfFirst { it.equals(baseName, ignoreCase = true) }
        return if (folderRank >= 0) folderRank + 1 else Int.MAX_VALUE
    }

    private fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, optionsFor(bounds.outWidth, bounds.outHeight, maxPx),
        ) ?: return null
        trimmedTo(decoded, maxPx)
    } catch (e: Exception) {
        Log.w(TAG, "embedded picture decode failed", e)
        null
    }

    /** Decodes a cover found next to the track, sized down to the budget. */
    private fun decodeCover(cover: Cover, maxPx: Int): Bitmap? = decodeUriScaled(cover.uri, maxPx)

    /**
     * Decodes the image at [uri] down to [maxPx] on its longest edge.
     *
     * A gallery picture is often several megapixels; the two passes keep the peak
     * allocation near the stored size instead of near the original.
     */
    private fun decodeUriScaled(uri: Uri, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeUri(uri, bounds)
        val decoded = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            decodeUri(uri, optionsFor(bounds.outWidth, bounds.outHeight, maxPx))
        } else {
            null
        }
        return decoded?.let { trimmedTo(it, maxPx) }
    }

    private fun decodeUri(uri: Uri, options: BitmapFactory.Options): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    private fun optionsFor(width: Int, height: Int, maxPx: Int): BitmapFactory.Options =
        BitmapFactory.Options().apply { inSampleSize = sampleSize(width, height, maxPx) }

    /**
     * Takes [bitmap] to the pixel budget exactly, and recycles what it replaces.
     *
     * Integer subsampling alone can leave a 600px cover under a 512px budget, which
     * would then be stored - and held in memory on every screen - at the wrong size.
     */
    private fun trimmedTo(bitmap: Bitmap, maxPx: Int): Bitmap {
        val scaled = scaleDown(bitmap, maxPx)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Decodes a representative frame from a video container.
     *
     * Called off the scan path so a library of thousands of videos never pays
     * for frame decoding up front.
     */
    private fun firstVideoFrame(uri: String, maxPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            trimmedTo(frame, maxPx)
        } catch (e: Exception) {
            Log.w(TAG, "frame extraction failed for $uri", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val ratio = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxPx) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    companion object {
        private const val TAG = "ArtworkStore"
        private const val DIR = "artwork"
        private const val JPEG_QUALITY = 82

        /** Marks a stored name as the user's own pick, kept out of the cache. */
        private const val USER_PREFIX = "user-"

        /** Longest edge of a cached thumbnail at the default quality setting. */
        const val DEFAULT_MAX_PX = 512

        /** Folder cover names, in the order they win over each other. */
        private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart", "cd", "art")

        /** Maps the "art quality" preference onto a pixel budget. */
        fun maxPxForQuality(quality: Int): Int = when (quality) {
            0 -> 256
            2 -> 1024
            else -> DEFAULT_MAX_PX
        }
    }
}
