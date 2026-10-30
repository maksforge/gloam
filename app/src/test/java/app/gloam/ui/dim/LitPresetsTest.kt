package app.gloam.ui.dim

import app.gloam.data.SavedPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class LitPresetsTest {
    private val reading = Preset.Reading

    private fun saved(
        name: String,
        dimLevel: Int = reading.dimLevel,
    ) = SavedPreset(name, dimLevel, reading.warmth, reading.warmthColor)

    @Test
    fun `a preset saved from a lit built-in lights beside it`() {
        val mine = saved("Mine")
        val lit = LitPresets.of(reading.dimLevel, reading.warmth, reading.warmthColor, listOf(mine))
        assertEquals(LitPresets(builtIn = reading, saved = listOf(mine)), lit)
    }

    @Test
    fun `every saved preset carrying the values lights, and no other`() {
        val first = saved("First", dimLevel = 42)
        val second = saved("Second", dimLevel = 42)
        val other = saved("Other", dimLevel = 43)
        val lit = LitPresets.of(42, reading.warmth, reading.warmthColor, listOf(first, other, second))
        assertEquals(LitPresets(builtIn = null, saved = listOf(first, second)), lit)
    }
}
