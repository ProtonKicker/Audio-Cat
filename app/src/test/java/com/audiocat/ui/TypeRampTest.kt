package com.audiocat.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audiocat.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ramp hygiene: a forgotten weight renders hairline text (the variable-font
 * default is Light), and a style without a bundled family silently falls back
 * to the platform face — both fail here instead of on a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
class TypeRampTest {

    private val ramp = intArrayOf(
        R.style.TextAppearance_AudioCat_Display,
        R.style.TextAppearance_AudioCat_Title,
        R.style.TextAppearance_AudioCat_RowTitle,
        R.style.TextAppearance_AudioCat_Body,
        R.style.TextAppearance_AudioCat_Micro,
        R.style.TextAppearance_AudioCat_Caption,
        R.style.TextAppearance_AudioCat_Ledger,
        R.style.TextAppearance_AudioCat_Readout,
    )

    private val attrs = intArrayOf(android.R.attr.fontWeight, android.R.attr.fontFamily)

    @Test
    fun `every ramp style declares an explicit weight and a bundled family`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ramp.forEach { style ->
            val ta = ctx.obtainStyledAttributes(null, attrs, 0, style)
            val weight = ta.getInt(0, 0)
            val hasFamily = ta.hasValue(1)
            ta.recycle()
            assertTrue("style $style: weight=$weight", weight in 100..1000)
            assertTrue("style $style: no fontFamily", hasFamily)
        }
    }
}
