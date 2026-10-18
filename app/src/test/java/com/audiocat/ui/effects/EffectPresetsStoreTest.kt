package com.audiocat.ui.effects

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audiocat.effects.EffectPreset
import com.audiocat.effects.ReverbPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Custom preset persistence tests. Built-ins must stay untouched on disk and
 * malformed storage must degrade to an empty custom list, never throw.
 */
@RunWith(RobolectricTestRunner::class)
class EffectPresetsStoreTest {

    private lateinit var store: EffectPresetsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = EffectPresetsStore(context)
        store.custom().forEach { store.delete(it.name) }
    }

    private fun preset(
        name: String,
        gains: List<Float> = listOf(1f, -2f, 3.5f),
        bass: Float = 0.4f,
        reverb: ReverbPreset = ReverbPreset.SMALL_ROOM,
    ) = EffectPreset(name = name, bandGainsDb = gains, bassBoost = bass, reverb = reverb)

    @Test
    fun `saved preset round-trips through storage`() {
        store.save(preset("My Curve"))

        val loaded = store.find("My Curve")!!
        assertEquals(listOf(1f, -2f, 3.5f), loaded.bandGainsDb)
        assertEquals(0.4f, loaded.bassBoost, 0.0001f)
        assertEquals(ReverbPreset.SMALL_ROOM, loaded.reverb)
        assertFalse("stored presets are never marked built-in", loaded.builtIn)
    }

    @Test
    fun `saving the same name replaces instead of duplicating`() {
        store.save(preset("Dup", gains = listOf(1f)))
        store.save(preset("Dup", gains = listOf(9f)))

        assertEquals(1, store.custom().count { it.name == "Dup" })
        assertEquals(listOf(9f), store.find("Dup")!!.bandGainsDb)
    }

    @Test
    fun `delete removes custom presets but never built-ins`() {
        store.save(preset("Mine"))
        assertTrue(store.delete("Mine"))
        assertFalse(store.delete("Flat"))
        assertNull(store.find("Mine"))
        val firstBuiltIn = store.builtIn().first().name
        assertFalse(store.delete(firstBuiltIn))
        assertTrue(store.all().containsAll(store.builtIn()))
    }

    @Test
    fun `newest custom preset comes first and follows the built-ins`() {
        store.save(preset("Old"))
        store.save(preset("New"))

        val custom = store.custom().map { it.name }
        assertEquals(listOf("New", "Old"), custom)
        val all = store.all().map { it.name }
        assertTrue(all.indexOf("New") > all.indexOf(store.builtIn().first().name))
    }
}
