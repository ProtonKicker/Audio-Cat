package com.audiocat.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which tab the app should open on next launch. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What to do with files discovered by a scan that are not in the library yet. */
enum class NewFileBehaviour { ADD_SILENTLY, ADD_AND_NOTIFY, UNLIST }

/** Repeat behaviour for the play queue. */
enum class RepeatMode { OFF, ALL, ONE }

/**
 * Snapshot of every user-facing preference.
 *
 * Kept as a single immutable value so the UI can render from one StateFlow
 * instead of subscribing to a dozen individual keys.
 */
data class AppSettings(
    // --- Scanning ---------------------------------------------------------
    /** When false, only an explicit manual scan updates the library. */
    val autoScan: Boolean = true,
    /** Milliseconds between automatic rescans; 0 disables the periodic sweep. */
    val autoScanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,

    // --- Library ----------------------------------------------------------
    /** 0 = small, 1 = medium, 2 = large. Maps onto ArtworkStore.maxPxForQuality. */
    val artQuality: Int = 1,
    val newFileBehaviour: NewFileBehaviour = NewFileBehaviour.ADD_SILENTLY,
    val defaultSort: String = "DATE_ADDED_DESC",

    // --- Playback ---------------------------------------------------------
    val shuffleByDefault: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val crossfadeMs: Int = 0,
    val resumeOnHeadphones: Boolean = true,
    val pauseOnHeadphonesDisconnect: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,

    // --- Audio effects ----------------------------------------------------
    val effectsEnabled: Boolean = true,
    /** Name of the preset applied when playback starts; null means "flat". */
    val activePreset: String? = null,

    // --- Appearance -------------------------------------------------------
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    // --- Diagnostics ------------------------------------------------------
    val lastScanAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_SCAN_INTERVAL_MS = 15 * 60 * 1000L
    }
}

/**
 * Persists [AppSettings] in a single [SharedPreferences] file and exposes it as
 * a [StateFlow] so both the UI and background services observe one source of
 * truth.
 *
 * SharedPreferences (rather than DataStore) is used deliberately: reads are
 * synchronous and already in memory, which keeps service startup - the most
 * latency-sensitive path in a music player - free of coroutine setup cost.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    /** Registers a listener so widget/service consumers can react to changes. */
    fun observe(listener: (AppSettings) -> Unit) {
        // Immediate emission plus updates; callers can unsubscribe by removing
        // the listener via the returned handle.
        listener(_settings.value)
        listeners.add(listener)
    }

    fun unobserve(listener: (AppSettings) -> Unit) {
        listeners.remove(listener)
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        persist(next)
        _settings.value = next
        listeners.toList().forEach { it(next) }
    }

    /** Restores every preference to its default value. */
    fun reset() {
        prefs.edit().clear().commit()
        val defaults = AppSettings()
        _settings.value = defaults
        listeners.toList().forEach { it(defaults) }
    }

    /** Records the completion time of the most recent successful scan. */
    fun markScanned(atMillis: Long = System.currentTimeMillis()) {
        update { it.copy(lastScanAt = atMillis) }
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            autoScan = prefs.getBoolean(KEY_AUTO_SCAN, defaults.autoScan),
            autoScanIntervalMs = prefs.getLong(KEY_SCAN_INTERVAL, defaults.autoScanIntervalMs),
            artQuality = prefs.getInt(KEY_ART_QUALITY, defaults.artQuality).coerceIn(0, 2),
            newFileBehaviour = prefs.enum(KEY_NEW_FILE, defaults.newFileBehaviour),
            defaultSort = prefs.getString(KEY_SORT, defaults.defaultSort) ?: defaults.defaultSort,
            shuffleByDefault = prefs.getBoolean(KEY_SHUFFLE_DEFAULT, defaults.shuffleByDefault),
            repeatMode = prefs.enum(KEY_REPEAT, defaults.repeatMode),
            crossfadeMs = prefs.getInt(KEY_CROSSFADE, defaults.crossfadeMs).coerceIn(0, MAX_CROSSFADE_MS),
            resumeOnHeadphones = prefs.getBoolean(KEY_RESUME, defaults.resumeOnHeadphones),
            pauseOnHeadphonesDisconnect =
                prefs.getBoolean(KEY_PAUSE_DISCONNECT, defaults.pauseOnHeadphonesDisconnect),
            playbackSpeed = prefs.getFloat(KEY_SPEED, defaults.playbackSpeed).coerceIn(0.25f, 4f),
            playbackPitch = prefs.getFloat(KEY_PITCH, defaults.playbackPitch).coerceIn(0.5f, 2f),
            effectsEnabled = prefs.getBoolean(KEY_EFFECTS, defaults.effectsEnabled),
            activePreset = prefs.getString(KEY_ACTIVE_PRESET, defaults.activePreset),
            themeMode = prefs.enum(KEY_THEME, defaults.themeMode),
            lastScanAt = prefs.getLong(KEY_LAST_SCAN, defaults.lastScanAt),
        )
    }

    private fun persist(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_SCAN, settings.autoScan)
            .putLong(KEY_SCAN_INTERVAL, settings.autoScanIntervalMs)
            .putInt(KEY_ART_QUALITY, settings.artQuality)
            .putString(KEY_NEW_FILE, settings.newFileBehaviour.name)
            .putString(KEY_SORT, settings.defaultSort)
            .putBoolean(KEY_SHUFFLE_DEFAULT, settings.shuffleByDefault)
            .putString(KEY_REPEAT, settings.repeatMode.name)
            .putInt(KEY_CROSSFADE, settings.crossfadeMs)
            .putBoolean(KEY_RESUME, settings.resumeOnHeadphones)
            .putBoolean(KEY_PAUSE_DISCONNECT, settings.pauseOnHeadphonesDisconnect)
            .putFloat(KEY_SPEED, settings.playbackSpeed)
            .putFloat(KEY_PITCH, settings.playbackPitch)
            .putBoolean(KEY_EFFECTS, settings.effectsEnabled)
            .putString(KEY_ACTIVE_PRESET, settings.activePreset)
            .putString(KEY_THEME, settings.themeMode.name)
            .putLong(KEY_LAST_SCAN, settings.lastScanAt)
            // apply() keeps writes off the critical path; the in-memory
            // StateFlow is already updated by the caller.
            .apply()
    }

    private val listeners = mutableListOf<(AppSettings) -> Unit>()

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, fallback: T): T {
        val raw = getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    companion object {
        private const val FILE = "audiocat_settings"
        private const val KEY_AUTO_SCAN = "auto_scan"
        private const val KEY_SCAN_INTERVAL = "scan_interval"
        private const val KEY_ART_QUALITY = "art_quality"
        private const val KEY_NEW_FILE = "new_file_behaviour"
        private const val KEY_SORT = "default_sort"
        private const val KEY_SHUFFLE_DEFAULT = "shuffle_default"
        private const val KEY_REPEAT = "repeat_default"
        private const val KEY_CROSSFADE = "crossfade_ms"
        private const val KEY_RESUME = "resume_headphones"
        private const val KEY_PAUSE_DISCONNECT = "pause_disconnect"
        private const val KEY_SPEED = "playback_speed"
        private const val KEY_PITCH = "playback_pitch"
        private const val KEY_EFFECTS = "effects_enabled"
        private const val KEY_ACTIVE_PRESET = "active_preset"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LAST_SCAN = "last_scan_at"

        const val MAX_CROSSFADE_MS = 12_000
    }
}
