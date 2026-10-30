package app.gloam.data

import app.gloam.ui.dim.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredPresetTest {
    private val labelOf: (Preset) -> String = { it.name }
    private val reading = DEFAULT_PRESETS[0]
    private val evening = DEFAULT_PRESETS[1]
    private val livingRoom = StoredPreset("a", "Living room", dimLevel = 50, warmth = 20, warmthColor = 30)
    private val bedroom = StoredPreset("b", "Bedroom", dimLevel = 90, warmth = 70, warmthColor = 90)
    private val presets = DEFAULT_PRESETS + livingRoom

    private fun List<StoredPreset>.save(
        name: String,
        dimLevel: Int,
        warmth: Int = 0,
        warmthColor: Int = 0,
    ) = saving(name, dimLevel, warmth, warmthColor, labelOf, newId = { "new" })

    @Test
    fun `nothing stored reads as the three built-ins`() {
        assertEquals(DEFAULT_PRESETS, decodePresets(null, legacy = null))
        assertEquals(Preset.entries.map { it.id }, DEFAULT_PRESETS.map { it.id })
        assertEquals(listOf(null, null, null), DEFAULT_PRESETS.map { it.name })
    }

    @Test
    fun `a list survives the round trip, and an empty one stays empty`() {
        val list = listOf(evening.copy(name = "Late"), livingRoom, bedroom)
        assertEquals(list, decodePresets(encodePresets(list), legacy = null))
        assertEquals(emptyList<StoredPreset>(), decodePresets("[]", legacy = null))
    }

    @Test
    fun `an unreadable list reads as absent rather than deleting every preset`() {
        for (text in listOf("", "not json", "{}")) {
            assertEquals(text, DEFAULT_PRESETS, decodePresets(text, legacy = null))
        }
    }

    @Test
    fun `an unreadable row loses only itself, and so does a nameless one that is no built-in`() {
        val text =
            """[{"id":"reading","dimLevel":70,"warmth":15,"warmthColor":50},
            {"id":"x","dimLevel":1,"warmth":1,"warmthColor":1},
            {"id":"y","name":"  ","dimLevel":1,"warmth":1,"warmthColor":1},
            {"name":"no id","dimLevel":1,"warmth":1,"warmthColor":1},
            null, "oops",
            {"id":"a","name":" Living room ","dimLevel":50,"warmth":20,"warmthColor":30,"icon":"moon"},
            {"id":"a","name":"Twice","dimLevel":1,"warmth":1,"warmthColor":1}]"""
        assertEquals(listOf(reading, livingRoom), decodePresets(text, legacy = null))
    }

    @Test
    fun `values are coerced on read`() {
        val text = """[{"id":"h","name":"Hot","dimLevel":150,"warmth":-5,"warmthColor":101}]"""
        assertEquals(listOf(StoredPreset("h", "Hot", 100, 0, 100)), decodePresets(text, legacy = null))
    }

    @Test
    fun `the old key is read after the built-ins until the new one exists, with stable ids`() {
        val legacy =
            """[{"name":"Living room","dimLevel":50,"warmth":20,"warmthColor":30},
            {"name":"x"},
            {"name":"Bedroom","dimLevel":90,"warmth":70,"warmthColor":90}]"""
        val read = decodePresets(null, legacy)
        assertEquals(DEFAULT_PRESETS + livingRoom.copy(id = "legacy-0") + bedroom.copy(id = "legacy-2"), read)
        assertEquals(read, decodePresets(null, legacy))
        assertEquals(listOf(livingRoom), decodePresets(encodePresets(listOf(livingRoom)), legacy))
    }

    @Test
    fun `old duplicates still read, and a built-in saved under another name lights beside it`() {
        val legacy = """[{"name":"Mine","dimLevel":70,"warmth":15,"warmthColor":50}]"""
        val read = decodePresets(null, legacy)
        assertEquals(listOf("reading", "legacy-0"), read.filter { it.matches(70, 15, 50) }.map { it.id })
    }

    @Test
    fun `an unrenamed built-in shows its label, a renamed one its name`() {
        assertEquals("Reading", reading.displayName(labelOf))
        assertEquals("Book", reading.copy(name = "Book").displayName(labelOf))
    }

    @Test
    fun `saving under a new name appends with a fresh id`() {
        assertEquals(presets + StoredPreset("new", "Sofa", 10, 0, 0), presets.save("  Sofa ", 10))
    }

    @Test
    fun `saving under a taken name replaces that preset's values in place, built-ins included`() {
        assertEquals(
            listOf(reading, evening, DEFAULT_PRESETS[2], livingRoom.copy(dimLevel = 10, warmth = 0, warmthColor = 0)),
            presets.save("living ROOM", 10),
        )
        assertEquals(reading.copy(dimLevel = 10, warmth = 0, warmthColor = 0), presets.save("reading", 10)!!.first())
    }

    @Test
    fun `saving values some preset already carries is refused, whatever the name`() {
        assertNull(presets.save("New", 50, 20, 30))
        assertNull(presets.save("Living room", 50, 20, 30))
        assertNull(presets.save("Evening", reading.dimLevel, reading.warmth, reading.warmthColor))
        assertNull(presets.save("   ", 10))
    }

    @Test
    fun `renaming refuses a name another preset shows, and allows its own in another case`() {
        assertNull(presets.renaming("a", "evening", labelOf))
        assertNull(presets.renaming("reading", "Living Room", labelOf))
        assertNull(presets.renaming("a", "  ", labelOf))
        assertNull(presets.renaming("missing", "Sofa", labelOf))
        assertEquals("living ROOM", presets.renaming("a", "living ROOM", labelOf)!!.last().name)
    }

    @Test
    fun `a built-in renamed keeps its id, and renamed back to its label follows the language again`() {
        val renamed = presets.renaming("reading", "Book", labelOf)!!
        assertEquals(reading.copy(name = "Book"), renamed.first())
        assertEquals(reading, renamed.renaming("reading", "Reading", labelOf)!!.first())
    }

    @Test
    fun `deleting goes by id, built-ins included`() {
        assertEquals(listOf(evening, DEFAULT_PRESETS[2], livingRoom), presets.deleting("reading"))
        assertEquals(DEFAULT_PRESETS, presets.deleting("a"))
    }
}
