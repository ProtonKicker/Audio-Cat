package com.audiocat.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A picture that can stand in for a track's cover, and how to open it. */
data class Cover(val baseName: String, val uri: Uri)

/**
 * Finds cover art that sits next to a track instead of inside it.
 *
 * Downloads and rips ship their artwork as image files in the same folder -
 * `cover.jpg` for a whole album, or a poster named after the video - and none of
 * it is visible through the tag APIs.
 *
 * Two sources are tried, in order. A direct directory read is free and is the only
 * way to see an image the media store has never indexed, but on many phones an app
 * may not list folders it does not own (it can still stat a known file name and
 * open it through a media uri). The media store query covers that case.
 *
 * Results are cached per folder: an album's tracks share one directory, and a
 * thousand songs must not cost a thousand listings or queries.
 */
class CoverFinder(private val context: Context) {

    private val cache = ConcurrentHashMap<String, List<Cover>>()

    /** Cover candidates inside [directory], possibly empty. */
    @WorkerThread
    fun coversIn(directory: File): List<Cover> {
        cache[directory.path]?.let { return it }
        if (cache.size >= CACHE_LIMIT) cache.clear()
        val covers = fromFileSystem(directory).ifEmpty { fromMediaStore(directory) }
        cache[directory.path] = covers
        return covers
    }

    /** Drops the remembered folders, e.g. after the thumbnail cache is cleared. */
    fun invalidate() {
        cache.clear()
    }

    private fun fromFileSystem(directory: File): List<Cover> = runCatching {
        directory.listFiles()?.mapNotNull { file ->
            if (!file.isFile || file.length() <= 0) return@mapNotNull null
            val extension = file.extension.lowercase()
            if (extension !in IMAGE_EXTENSIONS) return@mapNotNull null
            Cover(file.name.removeSuffix(".${file.extension}"), Uri.fromFile(file))
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun fromMediaStore(directory: File): List<Cover> {
        val prefix = "${directory.path}/%"
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val rows = ArrayList<Cover>()
        runCatching {
            context.contentResolver.query(
                IMAGE_COLLECTION,
                projection,
                "${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf(prefix),
                null,
            )
        }.getOrNull()?.use { cursor: Cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (idIdx < 0 || nameIdx < 0) return@use
            while (cursor.moveToNext()) {
                val cover = coverAt(
                    name = cursor.getString(nameIdx),
                    id = cursor.getLong(idIdx),
                )
                if (cover != null) rows += cover
            }
        }.also { Log.v(TAG, "media store covers for $directory: ${rows.size}") }
        return rows
    }

    /**
     * The cover described by a media-store row, or null when the file is not an
     * image that could stand in as one.
     */
    private fun coverAt(name: String?, id: Long): Cover? {
        val file = name ?: return null
        val extension = file.substringAfterLast('.', "")
        return if (extension.lowercase() in IMAGE_EXTENSIONS) {
            Cover(file.removeSuffix(".$extension"), ContentUris.withAppendedId(IMAGE_COLLECTION, id))
        } else {
            null
        }
    }

    private companion object {
        const val TAG = "CoverFinder"

        /** Where a folder's pictures are looked up. */
        val IMAGE_COLLECTION = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        /** Image types accepted as cover art. */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")

        /** Folders remembered before the cache is dropped. */
        const val CACHE_LIMIT = 128
    }
}
