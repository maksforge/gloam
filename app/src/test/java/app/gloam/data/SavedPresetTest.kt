package app.gloam.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPresetTest {
    private val livingRoom = SavedPreset("Living room", dimLevel = 50, warmth = 20, warmthColor = 30)
    private val bedroom = SavedPreset("Bedroom", dimLevel = 90, warmth = 70, warmthColor = 90)

    @Test
    fun `a list survives the round trip`() {
        val presets = listOf(livingRoom, bedroom)
        assertEquals(presets, decodeSavedPresets(encodeSavedPresets(presets)))
    }

    @Test
    fun `nothing stored, or something unreadable, reads as no presets`() {
        for (text in listOf(null, "", "not json", "{}", """[{"name":"x"}]""")) {
            assertEquals("$text", emptyList<SavedPreset>(), decodeSavedPresets(text))
        }
    }

    @Test
    fun `an unreadable row loses only itself`() {
        val text =
            """[{"name":"Living room","dimLevel":50,"warmth":20,"warmthColor":30},
            {"name":null,"dimLevel":1,"warmth":1,"warmthColor":1},
            {"name":"x"},
            null,
            "oops",
            {"name":"Bedroom","dimLevel":90,"warmth":70,"warmthColor":90}]"""
        assertEquals(listOf(livingRoom, bedroom), decodeSavedPresets(text))
    }

    @Test
    fun `a field from a newer build is ignored rather than failing the read`() {
        val text = """[{"name":"Bedroom","dimLevel":90,"warmth":70,"warmthColor":90,"icon":"moon"}]"""
        assertEquals(listOf(bedroom), decodeSavedPresets(text))
    }

    @Test
    fun `values are coerced on read and blank names dropped`() {
        val text = """[{"name":"  ","dimLevel":1,"warmth":1,"warmthColor":1},
            {"name":" Hot ","dimLevel":150,"warmth":-5,"warmthColor":101}]"""
        assertEquals(listOf(SavedPreset("Hot", 100, 0, 100)), decodeSavedPresets(text))
    }

    @Test
    fun `saving under an existing name replaces it in place, ignoring case`() {
        val updated = SavedPreset("living ROOM", dimLevel = 10, warmth = 0, warmthColor = 0)
        assertEquals(listOf(updated, bedroom), listOf(livingRoom, bedroom).withSaved(updated))
    }

    @Test
    fun `saving under a new name appends`() {
        assertEquals(listOf(livingRoom, bedroom), listOf(livingRoom).withSaved(bedroom))
    }

    @Test
    fun `rename keeps the order and delete removes only the named one`() {
        val presets = listOf(livingRoom, bedroom)
        assertEquals(listOf(livingRoom.copy(name = "Sofa"), bedroom), presets.renamed("living room", "Sofa"))
        assertEquals(listOf(bedroom), presets.without("LIVING ROOM"))
    }
}
