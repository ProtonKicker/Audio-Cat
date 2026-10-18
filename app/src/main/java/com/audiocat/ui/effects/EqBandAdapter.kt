package com.audiocat.ui.effects

import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.SeekBar
import com.audiocat.databinding.ItemEqBandBinding
import com.audiocat.effects.EqBand
import com.audiocat.effects.MAX_GAIN_MB
import com.audiocat.effects.MIN_GAIN_MB
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Owns the bank of vertical equaliser sliders.
 *
 * The bands are a handful of sliders inside a horizontal scroller, so a
 * RecyclerView would add recycling machinery for no benefit. This helper inflates
 * [ItemEqBandBinding] rows straight into the given container and keeps their
 * labels in step with the platform values.
 */
class EqBandAdapter(
    private val container: LinearLayout,
    private val onGainChanged: (bandIndex: Int, gainDb: Float) -> Unit,
) {

    private class BandRow(val binding: ItemEqBandBinding, val band: EqBand)

    private val rows = mutableListOf<BandRow>()

    val bandCount: Int get() = rows.size

    /** Builds one slider per band, in the engine's own order. */
    fun bind(bands: List<EqBand>) {
        container.removeAllViews()
        rows.clear()
        val inflater = LayoutInflater.from(container.context)
        bands.forEachIndexed { position, band ->
            val binding = ItemEqBandBinding.inflate(inflater, container, false)
            binding.freqLabel.text = formatFrequency(band.frequencyHz)
            binding.gainLabel.text = formatGain(band.gainDb)
            // Progress is set before the listener is attached so no callback fires.
            binding.gainSeek.progress = progressOf(binding.gainSeek, band.gainDb)
            binding.gainSeek.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val gainDb = gainOf(bar, progress)
                        binding.gainLabel.text = formatGain(gainDb)
                        // The engine indexes bands by position, not by EqBand.index.
                        if (fromUser) onGainChanged(position, gainDb)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
            container.addView(binding.root)
            rows += BandRow(binding, band)
        }
    }

    /** Pushes gains onto the existing sliders, e.g. straight after a preset. */
    fun updateGains(gains: List<Float>) {
        rows.forEachIndexed { position, row ->
            val gainDb = gains.getOrNull(position) ?: return@forEachIndexed
            row.binding.gainSeek.progress = progressOf(row.binding.gainSeek, gainDb)
        }
    }

    /**
     * True when [bands] describes the same layout as the bound descriptors.
     * Rebuilding on an unchanged layout would reset the scroll position and break
     * a drag in progress, so callers only rebuild when this is false.
     */
    fun matches(bands: List<EqBand>): Boolean =
        bands.size == rows.size && bands.indices.all { position ->
            bands[position].index == rows[position].band.index &&
                bands[position].frequencyHz == rows[position].band.frequencyHz
        }

    fun setEnabled(enabled: Boolean) {
        rows.forEach { it.binding.gainSeek.isEnabled = enabled }
    }
}

/** Formats a band centre frequency the way equaliser labels expect it. */
internal fun formatFrequency(hz: Int): String = when {
    hz <= 0 -> ""
    hz >= 1_000 -> {
        val thousands = hz / 1_000f
        if (thousands % 1f == 0f) {
            "${thousands.toInt()} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", thousands)
        }
    }
    else -> "$hz Hz"
}

private fun formatGain(gainDb: Float): String = String.format(Locale.US, "%+.1f", gainDb)

/** `item_eq_band.xml` parks 0 dB at half travel, so the centre is `max / 2`. */
private fun gainOf(bar: SeekBar, progress: Int): Float = (progress - bar.max / 2f) / DB_PER_STEP

private fun progressOf(bar: SeekBar, gainDb: Float): Int =
    (gainDb.coerceIn(MIN_GAIN_MB / 100f, MAX_GAIN_MB / 100f) * DB_PER_STEP).roundToInt() +
        bar.max / 2

/** Slider steps per decibel; the 300-step track therefore spans ±15 dB. */
private const val DB_PER_STEP = 10f
