package app.gloam.ui.dim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which chip is lit is worked out from the stored values rather than stored itself, so the two ways
 * that can go wrong are both here: a preset that does not recognise its own values, and a chip that
 * stays lit after the user has moved off it.
 */
class PresetTest {
    @Test
    fun `every preset is recognised from its own values`() {
        for (preset in Preset.entries) {
            assertEquals(preset, Preset.matching(preset.dimLevel, preset.warmth, preset.warmthColor))
        }
    }

    @Test
    fun `a value nudged by one unlights the chip`() {
        for (p in Preset.entries) {
            assertNull("$p dim level", Preset.matching(p.dimLevel + 1, p.warmth, p.warmthColor))
            assertNull("$p warmth", Preset.matching(p.dimLevel, p.warmth - 1, p.warmthColor))
            assertNull("$p colour", Preset.matching(p.dimLevel, p.warmth, p.warmthColor + 1))
        }
    }

    @Test
    fun `no two presets share all three values, and all are in range`() {
        val triples = Preset.entries.map { Triple(it.dimLevel, it.warmth, it.warmthColor) }
        assertEquals(triples.size, triples.toSet().size)
        for (p in Preset.entries) {
            for (v in listOf(p.dimLevel, p.warmth, p.warmthColor)) assert(v in 0..100) { "$p: $v" }
        }
    }
}
