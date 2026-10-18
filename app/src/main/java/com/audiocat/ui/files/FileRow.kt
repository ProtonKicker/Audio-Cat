package com.audiocat.ui.files

import com.audiocat.data.db.Track
import com.audiocat.ui.common.Formatters

/**
 * Presentation model for one row of the Files list.
 *
 * Wraps the stored [Track] and derives its display strings on demand, so a
 * library of thousands of rows costs nothing until a row is actually bound.
 * This is deliberately not the database entity: the list never writes it back.
 */
data class FileRow(val track: Track) {

    /** Stable identity, used for selection because titles can repeat. */
    val path: String get() = track.path

    val title: String get() = track.displayTitle

    /** "Artist · 12.3 MB", or just the size when the file carries no artist. */
    val subtitle: String get() = buildString {
        track.artist?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
        append(Formatters.fileSize(track.fileSize))
    }

    val duration: String get() = Formatters.duration(track.durationMs)
}
