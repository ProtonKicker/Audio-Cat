package com.audiocat.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audiocat.R
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Night Set contrast gate.
 *
 * Every text pair must clear WCAG AA (4.5:1) and every UI/state pair the 3:1
 * floor, in BOTH themes. This is the cheapest zero-bug insurance in the repo:
 * a token edit that breaks legibility fails here, not on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeContrastTest {

    private fun contextFor(night: Boolean): Context {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        return ApplicationProvider.getApplicationContext()
    }

    private fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color shr 16 and 0xFF) +
            0.7152 * channel(color shr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertText(fg: Int, bg: Int, label: String) {
        val r = ratio(fg, bg)
        assertTrue("$label: ${"%.2f".format(r)} < 4.5", r >= 4.5)
    }

    private fun assertUi(fg: Int, bg: Int, label: String) {
        val r = ratio(fg, bg)
        assertTrue("$label: ${"%.2f".format(r)} < 3.0", r >= 3.0)
    }

    private fun check(night: Boolean) {
        val ctx = contextFor(night)
        val tag = if (night) "night" else "day"
        fun color(id: Int) = ctx.getColor(id)
        val bg = color(R.color.bg)
        val surface = color(R.color.surface)
        val plate = color(R.color.plate)
        val ink = color(R.color.ink)
        val inkSoft = color(R.color.ink_soft)
        val inkMeta = color(R.color.ink_meta)
        val inkTertiary = color(R.color.ink_tertiary)
        val onVariant = color(R.color.on_surface_variant)
        val onAlarm = color(R.color.on_alarm)
        val alarm = color(R.color.alarm)
        val carrier = color(R.color.carrier)
        val dotLit = color(R.color.dot_lit)

        assertText(ink, bg, "$tag ink/bg")
        assertText(ink, surface, "$tag ink/surface")
        assertText(ink, plate, "$tag ink/plate")
        assertText(inkSoft, bg, "$tag ink_soft/bg")
        assertText(inkSoft, surface, "$tag ink_soft/surface")
        assertText(inkMeta, bg, "$tag ink_meta/bg")
        assertText(inkMeta, surface, "$tag ink_meta/surface")
        assertText(onVariant, bg, "$tag onSurfaceVariant/bg")
        assertText(onVariant, surface, "$tag onSurfaceVariant/surface")
        assertText(inkTertiary, plate, "$tag ink_tertiary/plate")
        assertText(onAlarm, alarm, "$tag on_alarm/alarm")
        assertUi(carrier, bg, "$tag carrier/bg (UI)")
        assertUi(dotLit, bg, "$tag dot_lit/bg (UI)")
    }

    @Test
    fun `day palette clears the contrast floors`() = check(night = false)

    @Test
    fun `night palette clears the contrast floors`() = check(night = true)

    @Test
    fun `red never appears as a text color in layouts`() {
        val layouts = java.io.File("src/main/res/layout").listFiles()
            ?.filter { it.extension == "xml" } ?: emptyList()
        val offenders = layouts.filter { file ->
            val text = file.readText()
            Regex("""android:textColor="[^"]*@(color/carrier|color/alarm)"""").containsMatchIn(text) ||
                Regex("""android:textColor="[^"]*\?attr/colorPrimaryContainer"""").containsMatchIn(text)
        }.map { it.name }
        assertTrue("red-as-text in: $offenders", offenders.isEmpty())
    }
}
