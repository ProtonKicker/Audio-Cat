package com.audiocat.ui.common

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

/** Shown when a folder picked with `OpenDocumentTree` has no filesystem path. */
internal const val FOLDER_UNAVAILABLE_MESSAGE =
    "This folder cannot be scanned directly. Pick a folder on shared storage."

/**
 * Display formatting shared by every screen.
 *
 * Kept free of Android resources so the strings can be produced from any
 * thread; the only exception is [treeUriToPath], which is a platform query and
 * lives here because both the Files and Settings tabs need the same mapping.
 */
object Formatters {

    /** Clock time, "3:45" for anything under an hour and "1:02:03" above it. */
    fun duration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    /** Binary size with the precision each unit deserves: "845 KB", "12.3 MB". */
    fun fileSize(bytes: Long): String = when {
        bytes < KB -> String.format(Locale.getDefault(), "%d B", bytes)
        bytes < MB -> String.format(Locale.getDefault(), "%.0f KB", bytes / KB.toDouble())
        bytes < GB -> String.format(Locale.getDefault(), "%.1f MB", bytes / MB.toDouble())
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / GB.toDouble())
    }

    /** Sample rate in the compact form audio tools use: "60", "1k", "44.1k". */
    fun frequency(hz: Int): String {
        if (hz <= 0) return "—"
        if (hz < 1000) return hz.toString()
        val thousands = hz / 1000.0
        return if (thousands % 1.0 == 0.0) {
            "${thousands.toInt()}k"
        } else {
            String.format(Locale.getDefault(), "%.1fk", thousands)
        }
    }

    /**
     * Coarse "time since" label: "just now", "5 min ago", "3 days ago".
     *
     * A zero timestamp means no scan has ever completed, which reads as
     * "Never scanned" - the same text as `R.string.settings_never_scanned`,
     * duplicated here to keep this helper resource-free.
     */
    fun relativeTime(epochMs: Long): String {
        if (epochMs <= 0L) return "Never scanned"
        val elapsed = System.currentTimeMillis() - epochMs
        if (elapsed < MINUTE) return "just now"
        val minutes = elapsed / MINUTE
        if (minutes < 60) return "$minutes min ago"
        val hours = minutes / 60
        if (hours < 24) return plural(hours, "hour")
        val days = hours / 24
        if (days < 30) return plural(days, "day")
        val months = days / 30
        if (months < 12) return plural(months, "month")
        return plural(months / 12, "year")
    }

    /**
     * Maps a folder picked with `OpenDocumentTree` onto a filesystem path.
     *
     * A tree id encodes the volume and the volume-relative path
     * ("primary:Music/Videos"), which is exactly what the scanner needs because
     * it walks paths with [File] rather than through the document provider.
     *
     * @return the absolute path, or null when the volume is not local storage or
     *   the directory is not readable by this app.
     */
    @Suppress("DEPRECATION")
    fun treeUriToPath(context: Context, uri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val separator = documentId.indexOf(':')
        if (separator < 0) return null
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1).trim('/')
        val root = when {
            volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory()?.absolutePath
            volume.isBlank() || volume.contains('/') -> null
            else -> "/storage/$volume".takeIf { File(it).isDirectory }
        } ?: return null
        val path = if (relative.isEmpty()) root else "$root/$relative"
        return path.takeIf { File(it).isDirectory }
    }

    private fun plural(count: Long, unit: String): String =
        if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"

    private const val MINUTE = 60_000L
    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024
}
