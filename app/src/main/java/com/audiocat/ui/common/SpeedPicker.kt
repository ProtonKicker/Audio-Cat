package com.audiocat.ui.common

import android.content.Context
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import com.audiocat.AudioCatApp
import com.audiocat.R
import com.audiocat.playback.PlaybackService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shared playback-speed picker.
 *
 * Used by the Now Playing sheet chip and the Playing tab quick controls so
 * both entry points offer the same presets and apply through the same path:
 * persist to settings, then push to the live service.
 */
@AndroidXOptIn(UnstableApi::class)
object SpeedPicker {

    /** Presets offered by the picker; the Effects slider covers the full 0.25-4x range. */
    @Suppress("MagicNumber") // the speed ladder is a design constant, not math
    private val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

    private fun matches(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < TOLERANCE

    fun show(context: Context) {
        val app = AudioCatApp.get()
        val labels = SPEED_OPTIONS.map { format(it) }.toTypedArray()
        val checked = SPEED_OPTIONS.indexOfFirst {
            matches(it, app.settings.current.playbackSpeed)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.speed_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val speed = SPEED_OPTIONS[which]
                app.settings.update { it.copy(playbackSpeed = speed) }
                PlaybackService.peek()?.setSpeedAndPitch(speed, app.settings.current.playbackPitch)
                dialog.dismiss()
            }
            .show()
    }

    fun format(speed: Float): String =
        speed.toString().trimEnd('0').trimEnd('.') + "×"

    private const val TOLERANCE = 0.01f
}
