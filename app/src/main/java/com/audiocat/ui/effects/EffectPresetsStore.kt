package com.audiocat.ui.effects

import android.content.Context
import android.content.SharedPreferences
import com.audiocat.effects.EffectPreset
import com.audiocat.effects.EffectsEngine
import com.audiocat.effects.ReverbPreset
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user-saved effect presets as JSON in [SharedPreferences].
 *
 * The built-in presets ship with [EffectsEngine] and are never written to disk,
 * so a corrupt file or a "reset everything" can only ever lose custom presets.
 * Reading is defensive by design: unreadable or malformed JSON degrades to an
 * empty custom list rather than throwing on a screen the user just opened.
 */
class EffectPresetsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Presets shipped with the app; always present and never deletable. */
    fun builtIn(): List<EffectPreset> = EffectsEngine.builtInPresets()

    /** User-saved presets, newest first. */
    fun custom(): List<EffectPreset> = read()

    /** Everything the chooser shows: built-ins followed by custom presets. */
    fun all(): List<EffectPreset> = builtIn() + custom()

    /** Adds [preset], replacing any custom preset of the same name. */
    fun save(preset: EffectPreset) {
        val stored = preset.copy(builtIn = false)
        write(listOf(stored) + custom().filterNot { it.name == stored.name })
    }

    /** @return true when a custom preset was removed; built-ins always report false. */
    fun delete(name: String): Boolean {
        val existing = custom()
        val remaining = existing.filterNot { it.name == name }
        if (remaining.size == existing.size) return false
        write(remaining)
        return true
    }

    /** Looks a preset up by name, preferring the built-in set. */
    fun find(name: String): EffectPreset? = all().firstOrNull { it.name == name }

    private fun read(): List<EffectPreset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toPreset() }
        }.getOrDefault(emptyList())
    }

    private fun write(presets: List<EffectPreset>) {
        val array = JSONArray()
        presets.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
    }

    private fun JSONObject.toPreset(): EffectPreset? {
        val presetName = optString(KEY_NAME).trim()
        if (presetName.isEmpty()) return null
        return EffectPreset(
            name = presetName,
            bandGainsDb = optJSONArray(KEY_GAINS).gains(),
            bassBoost = optDouble(KEY_BASS, 0.0).toFloat(),
            reverb = runCatching { ReverbPreset.valueOf(optString(KEY_REVERB)) }
                .getOrDefault(ReverbPreset.OFF),
            speed = optDouble(KEY_SPEED, 1.0).toFloat(),
            pitch = optDouble(KEY_PITCH, 1.0).toFloat(),
            builtIn = false,
        )
    }

    private fun JSONArray?.gains(): List<Float> {
        if (this == null) return emptyList()
        return (0 until length()).map { optDouble(it, 0.0).toFloat() }
    }

    private fun EffectPreset.toJson(): JSONObject = JSONObject()
        .put(KEY_NAME, name)
        .put(KEY_GAINS, JSONArray().apply { bandGainsDb.forEach { put(it.toDouble()) } })
        .put(KEY_BASS, bassBoost.toDouble())
        .put(KEY_REVERB, reverb.name)
        .put(KEY_SPEED, speed.toDouble())
        .put(KEY_PITCH, pitch.toDouble())

    private companion object {
        const val FILE = "audiocat_effect_presets"
        const val KEY_PRESETS = "custom_presets"
        const val KEY_NAME = "name"
        const val KEY_GAINS = "bandGainsDb"
        const val KEY_BASS = "bassBoost"
        const val KEY_REVERB = "reverb"
        const val KEY_SPEED = "speed"
        const val KEY_PITCH = "pitch"
    }
}
