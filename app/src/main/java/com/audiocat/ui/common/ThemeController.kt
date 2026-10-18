package com.audiocat.ui.common

import androidx.appcompat.app.AppCompatDelegate
import com.audiocat.data.ThemeMode

/**
 * Applies the theme preference to AppCompat.
 *
 * Using `AppCompatDelegate` means a change takes effect without recreating the
 * activity manually, and "follow system" keeps working when the OS switches.
 */
object ThemeController {

    fun apply(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }
}
