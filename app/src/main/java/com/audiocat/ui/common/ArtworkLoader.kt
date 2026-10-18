package com.audiocat.ui.common

import android.net.Uri
import android.widget.ImageView
import com.audiocat.AudioCatApp
import com.audiocat.R

/**
 * Resolves cached artwork to a loadable [Uri], and paints image views with it.
 *
 * Centralised so every screen shows the same placeholder behaviour and no screen
 * has to know how the cache is laid out.
 */
object ArtworkLoader {

    /** @return a file uri for the cached image, or null when there is none. */
    fun uri(artPath: String?): Uri? =
        AudioCatApp.get().artwork.fileByName(artPath)?.let(Uri::fromFile)

    /**
     * Paints [view] with the cover for [artPath], or with the default plate.
     *
     * Branching on the resolved uri rather than on the column matters: a row can
     * remember a thumbnail that the cache no longer holds, and setting a null uri
     * leaves the view empty with nothing to show in its place.
     */
    fun load(view: ImageView, artPath: String?) {
        val resolved = uri(artPath)
        if (resolved != null) {
            view.setImageURI(resolved)
        } else {
            view.setImageResource(R.drawable.art_default)
        }
    }
}
