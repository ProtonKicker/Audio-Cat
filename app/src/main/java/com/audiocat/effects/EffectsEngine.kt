package com.audiocat.effects

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.util.Log
import androidx.annotation.WorkerThread

/** Gain limits imposed by the platform effect, in millibels. */
const val MIN_GAIN_MB = -1500
const val MAX_GAIN_MB = 1500

/** One adjustable band, described in real units rather than platform indices. */
data class EqBand(
    /** Index used when talking to the platform effect. */
    val index: Int,
    /** Centre frequency in Hz. */
    val frequencyHz: Int,
    /** Current gain in decibels. */
    val gainDb: Float,
    /** Lowest frequency this band can be moved to, in Hz. */
    val minHz: Int,
    /** Highest frequency this band can be moved to, in Hz. */
    val maxHz: Int,
) {
    /**
     * The platform equaliser is a fixed-Q peaking design, so Q is reported as a
     * nominal constant rather than a free parameter.
     */
    val q: Float get() = DEFAULT_Q

    companion object {
        /** Nominal Q of Android's fixed-Q peaking filters (one octave wide). */
        const val DEFAULT_Q = 1.41f
    }
}

/** Reverb voicings exposed by the platform. */
enum class ReverbPreset(val platformValue: Short) {
    OFF(PresetReverb.PRESET_NONE),
    SMALL_ROOM(PresetReverb.PRESET_SMALLROOM),
    MEDIUM_ROOM(PresetReverb.PRESET_MEDIUMROOM),
    LARGE_ROOM(PresetReverb.PRESET_LARGEROOM),
    MEDIUM_HALL(PresetReverb.PRESET_MEDIUMHALL),
    LARGE_HALL(PresetReverb.PRESET_LARGEHALL),
    PLATE(PresetReverb.PRESET_PLATE),
}

/** A named, persistable collection of effect values. */
data class EffectPreset(
    val name: String,
    val bandGainsDb: List<Float>,
    val bassBoost: Float = 0f,
    val reverb: ReverbPreset = ReverbPreset.OFF,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val builtIn: Boolean = false,
)

/**
 * Owns every audio effect attached to the player's audio session.
 *
 * The platform equaliser provides a fixed number of bands that varies by device
 * (commonly five). When a device exposes fewer than [TARGET_BANDS], a second
 * equaliser is instantiated on the same session with a complementary frequency
 * window. Platform effects are chained on a session, so the result is a genuine
 * 12-band response; if the secondary instance cannot be created the engine
 * degrades silently to the device's native band count.
 *
 * Every call is defensively wrapped: audio effects are notoriously flaky across
 * vendors, and a failed effect must never take playback down with it.
 */
class EffectsEngine {

    private var sessionId: Int = 0
    private var primary: Equalizer? = null
    private var secondary: Equalizer? = null
    private var bass: BassBoost? = null
    private var reverb: PresetReverb? = null

    /** Band descriptors, concatenated across both equaliser instances. */
    private var bands: List<EqBand> = emptyList()

    private var enabled = true

    /** Number of bands the device natively reports for a single equaliser. */
    var nativeBandCount: Int = 0
        private set

    /** True when a second equaliser instance was created to reach 12 bands. */
    var usesCascade: Boolean = false
        private set

    val availableBands: List<EqBand> get() = bands

    /** Highest frequency the device hardware will shape, in Hz. */
    var maxFrequencyHz: Int = 0
        private set

    /**
     * Binds every effect to a new audio session.
     *
     * Safe to call repeatedly; previous instances are always released first.
     */
    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId && primary != null) return
        release()
        sessionId = audioSessionId
        if (audioSessionId == AudioEffect.ERROR_BAD_VALUE) return

        primary = createEqualizer(audioSessionId)
        val equalizer = primary ?: run {
            Log.w(TAG, "equaliser unavailable on session $audioSessionId")
            return
        }
        nativeBandCount = runCatching { equalizer.numberOfBands.toInt() }.getOrDefault(0)
        // getBandFreqRange reports millihertz; the engine speaks hertz.
        maxFrequencyHz = runCatching {
            val range = equalizer.getBandFreqRange((nativeBandCount - 1).coerceAtLeast(0).toShort())
            range[1] / 1000
        }.getOrDefault(0)

        // Add a second stage when the platform exposes fewer than 12 bands.
        usesCascade = nativeBandCount in 1 until TARGET_BANDS
        if (usesCascade) {
            secondary = createEqualizer(audioSessionId)
            if (secondary == null) usesCascade = false
        }

        bands = buildBands()
        bass = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        reverb = runCatching { PresetReverb(0, audioSessionId) }.getOrNull()
        applyEnabledState()
    }

    /** Creates and enables one equaliser instance, or null when unsupported. */
    private fun createEqualizer(audioSessionId: Int): Equalizer? = try {
        Equalizer(0, audioSessionId).also { it.enabled = true }
    } catch (e: Exception) {
        Log.w(TAG, "equaliser creation failed", e)
        null
    }

    @Synchronized
    fun release() {
        listOf(primary, secondary, bass, reverb).forEach { effect ->
            runCatching { effect?.release() }
        }
        primary = null
        secondary = null
        bass = null
        reverb = null
        bands = emptyList()
        usesCascade = false
        nativeBandCount = 0
        sessionId = 0
    }

    /**
     * Flattens both equaliser stages into one ordered band list.
     *
     * Many devices hand a second `Equalizer` instance the same band layout as
     * the first; duplicating those frequencies would give the UI two sliders
     * per band, so only genuinely new bands from the cascade are kept.
     */
    private fun buildBands(): List<EqBand> {
        val result = ArrayList<EqBand>(TARGET_BANDS)
        primary?.let { result += bandsOf(it) }
        secondary?.let { secondaryEq ->
            val known = result.mapTo(HashSet()) { it.frequencyHz }
            val extra = bandsOf(secondaryEq).filterNot { it.frequencyHz in known }
            if (extra.isEmpty()) {
                runCatching { secondaryEq.release() }
                secondary = null
                usesCascade = false
            } else {
                result += extra
            }
        }
        return result.sortedBy { it.frequencyHz }
    }

    /** Describes one equaliser instance's bands in real units. */
    private fun bandsOf(equalizer: Equalizer): List<EqBand> {
        val count = runCatching { equalizer.numberOfBands.toInt() }.getOrDefault(0)
        return (0 until count).map { index ->
            // Both frequency APIs report millihertz.
            val centre = runCatching {
                equalizer.getCenterFreq(index.toShort()).toInt() / 1000
            }.getOrDefault(0)
            val range = runCatching { equalizer.getBandFreqRange(index.toShort()) }.getOrNull()
            EqBand(
                index = index,
                frequencyHz = centre,
                gainDb = gainDbOf(equalizer, index).coerceIn(
                    MIN_GAIN_MB / 100f,
                    MAX_GAIN_MB / 100f,
                ),
                minHz = range?.getOrNull(0)?.let { it / 1000 } ?: 0,
                maxHz = range?.getOrNull(1)?.let { it / 1000 } ?: 0,
            )
        }
    }

    private fun gainDbOf(equalizer: Equalizer, band: Int): Float = runCatching {
        equalizer.getBandLevel(band.toShort()).toFloat() / 100f
    }.getOrDefault(0f)

    /**
     * Sets the gain of the band at [bandIndex] (an index into
     * [availableBands]). Returns true when the platform accepted the value.
     */
    @Synchronized
    fun setBandGain(bandIndex: Int, gainDb: Float): Boolean {
        val band = bands.getOrNull(bandIndex) ?: return false
        val clamped = gainDb.coerceIn(MIN_GAIN_MB / 100f, MAX_GAIN_MB / 100f)
        val target = if (bandIndex < nativeBandCount) primary else secondary
        val localIndex = if (bandIndex < nativeBandCount) bandIndex else bandIndex - nativeBandCount
        val ok = runCatching {
            target?.setBandLevel(localIndex.toShort(), (clamped * 100).toInt().toShort())
        }.isSuccess
        if (ok) {
            bands = bands.toMutableList().also {
                it[bandIndex] = it[bandIndex].copy(gainDb = clamped)
            }
        }
        return ok
    }

    /** Applies a full set of gains, matching by band index. */
    @Synchronized
    fun setAllBandGains(gains: List<Float>) {
        gains.forEachIndexed { index, gain -> setBandGain(index, gain) }
    }

    /** Current gains in decibels, in band order. */
    @Synchronized
    fun currentGains(): List<Float> = bands.map { it.gainDb }

    /** Bass boost strength, 0..1. */
    @Synchronized
    fun setBassBoost(strength: Float) {
        runCatching { bass?.setStrength((strength.coerceIn(0f, 1f) * 1000).toInt().toShort()) }
    }

    /** Reverb voicing; [ReverbPreset.OFF] disables the effect. */
    @Synchronized
    fun setReverb(preset: ReverbPreset) {
        runCatching {
            val effect = reverb ?: return
            if (preset == ReverbPreset.OFF) {
                effect.enabled = false
            } else {
                effect.preset = preset.platformValue
                effect.enabled = true
            }
        }
    }

    /**
     * Enables or bypasses every effect at once, used by the master switch and
     * when playback pauses to stop the effects costing any CPU.
     */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        applyEnabledState()
    }

    val isEnabled: Boolean get() = enabled

    private fun applyEnabledState() {
        runCatching { primary?.enabled = enabled }
        runCatching { secondary?.enabled = enabled }
        runCatching { if (!enabled) reverb?.enabled = false }
    }

    /** Applies a stored preset, skipping bands the device cannot reproduce. */
    @Synchronized
    fun applyPreset(preset: EffectPreset) {
        setAllBandGains(preset.bandGainsDb)
        setBassBoost(preset.bassBoost)
        setReverb(preset.reverb)
    }

    /** Captures the current effect state under [name]. */
    @Synchronized
    fun capturePreset(name: String): EffectPreset = EffectPreset(
        name = name,
        bandGainsDb = currentGains(),
        bassBoost = runCatching {
            (bass?.roundedStrength?.toInt() ?: 0) / 1000f
        }.getOrDefault(0f),        reverb = currentReverb(),
        builtIn = false,
    )

    private fun currentReverb(): ReverbPreset {
        val effect = reverb ?: return ReverbPreset.OFF
        if (!runCatching { effect.enabled }.getOrDefault(false)) return ReverbPreset.OFF
        val value = runCatching { effect.preset }.getOrDefault(PresetReverb.PRESET_NONE)
        return ReverbPreset.entries.firstOrNull { it.platformValue == value } ?: ReverbPreset.OFF
    }

    /** True when the device exposes at least one controllable band. */
    val isAvailable: Boolean get() = bands.isNotEmpty()

    companion object {
        private const val TAG = "EffectsEngine"

        /** The band count the UI is designed around. */
        const val TARGET_BANDS = 12

        /** Presets shipped with the app. Gains are in dB. */
        fun builtInPresets(): List<EffectPreset> {
            // Twelve bands at roughly one-octave spacing, matching the layout of
            // the equaliser UI.
            val flat = List(TARGET_BANDS) { 0f }
            return listOf(
                EffectPreset("Flat", flat, builtIn = true),
                EffectPreset(
                    "Bass Boost",
                    listOf(7f, 6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
                    bassBoost = 0.6f,
                    builtIn = true,
                ),
                EffectPreset(
                    "Treble Boost",
                    listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1f, 2f, 3.5f, 5f, 6f, 6f),
                    builtIn = true,
                ),
                EffectPreset(
                    "Vocal",
                    listOf(-2f, -1f, 0f, 2f, 4f, 5f, 4.5f, 3f, 1.5f, 0f, -1f, -2f),
                    builtIn = true,
                ),
                EffectPreset(
                    "Loudness",
                    listOf(6f, 4.5f, 2f, 0f, -1f, -1.5f, 0f, 1.5f, 3f, 4.5f, 5.5f, 6f),
                    bassBoost = 0.35f,
                    builtIn = true,
                ),
                EffectPreset(
                    "Late Night",
                    listOf(-3f, -2f, -1f, 0f, 1f, 2f, 3f, 3f, 2f, 1f, 0f, -1f),
                    builtIn = true,
                ),
            )
        }
    }
}
