package app.gloam.ui.dim

import app.gloam.R
import app.gloam.data.DEFAULT_PRESETS
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetTest {
    @Test
    fun `no two built-ins share an id or all three values, and all are in range`() {
        assertEquals(
            Preset.entries.size,
            Preset.entries
                .map { it.id }
                .toSet()
                .size,
        )
        val triples = Preset.entries.map { Triple(it.dimLevel, it.warmth, it.warmthColor) }
        assertEquals(triples.size, triples.toSet().size)
        for (p in Preset.entries) {
            for (v in listOf(p.dimLevel, p.warmth, p.warmthColor)) assert(v in 0..100) { "$p: $v" }
        }
    }

    @Test
    fun `a built-in at its shipped values keeps its own hint, whatever it is called`() {
        for (stored in DEFAULT_PRESETS) {
            val hint = stored.builtIn!!.hint
            assertEquals(hint, presetHint(stored, anyPresets = true))
            assertEquals(hint, presetHint(stored.copy(name = "Mine"), anyPresets = true))
        }
    }

    @Test
    fun `a built-in given new values, or one the user saved, gets the saved hint`() {
        val reading = DEFAULT_PRESETS.first()
        assertEquals(R.string.preset_saved_hint, presetHint(reading.copy(dimLevel = 1), anyPresets = true))
        assertEquals(R.string.preset_saved_hint, presetHint(reading.copy(id = "abc", name = "Mine"), anyPresets = true))
    }

    @Test
    fun `nothing lit explains the row, or the ＋ chip once there is no preset left`() {
        assertEquals(R.string.presets_hint, presetHint(null, anyPresets = true))
        assertEquals(R.string.preset_save_body, presetHint(null, anyPresets = false))
    }
}
