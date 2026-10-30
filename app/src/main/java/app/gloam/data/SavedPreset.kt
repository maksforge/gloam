package app.gloam.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

const val PRESET_NAME_MAX_LENGTH = 30

/**
 * A preset the user saved under their own name — the one list in Gloam that grows at run time.
 *
 * Stored as JSON under a single DataStore key rather than in a database: a few dozen rows at most,
 * read whole and written whole, with nothing to query (ADR-0007's amendment). Names are the identity,
 * compared without case, so saving under a name that exists replaces that preset's values.
 *
 * Kotlin note for a JS reader: `@Serializable` makes the compiler plugin generate the
 * `JSON.parse`/`JSON.stringify` mapping for this class at build time, with no reflection.
 */
@Serializable
data class SavedPreset(
    val name: String,
    val dimLevel: Int,
    val warmth: Int,
    val warmthColor: Int,
) {
    fun matches(
        dimLevel: Int,
        warmth: Int,
        warmthColor: Int,
    ): Boolean = this.dimLevel == dimLevel && this.warmth == warmth && this.warmthColor == warmthColor

    fun isNamed(other: String): Boolean = name.equals(other.trim(), ignoreCase = true)
}

// A field added later needs a default, and an older build reading a newer file must not fail on
// the field it does not know: those two are the whole migration story.
private val json = Json { ignoreUnknownKeys = true }

fun encodeSavedPresets(presets: List<SavedPreset>): String = json.encodeToString(presets)

/**
 * Decoded row by row, so one unreadable row loses only itself: the writes read this list back and
 * store it whole, and an all-or-nothing read would turn one bad row into deleting every preset.
 */
fun decodeSavedPresets(text: String?): List<SavedPreset> {
    if (text == null) return emptyList()
    val rows =
        try {
            json.decodeFromString<List<JsonElement>>(text)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
    return rows
        .mapNotNull(::decodeRow)
        .filter { it.name.isNotBlank() }
        .map {
            it.copy(
                name = it.name.trim().take(PRESET_NAME_MAX_LENGTH),
                dimLevel = it.dimLevel.coerceIn(0, 100),
                warmth = it.warmth.coerceIn(0, 100),
                warmthColor = it.warmthColor.coerceIn(0, 100),
            )
        }
}

// SerializationException extends IllegalArgumentException, so this one catch covers both.
private fun decodeRow(row: JsonElement): SavedPreset? =
    try {
        json.decodeFromJsonElement<SavedPreset>(row)
    } catch (_: IllegalArgumentException) {
        null
    }

// Kotlin note: these three are extension functions — like adding a method to `Array.prototype`,
// except resolved at compile time, visible only where imported, and returning a new list rather
// than mutating this one.
fun List<SavedPreset>.withSaved(preset: SavedPreset): List<SavedPreset> =
    if (any { it.isNamed(preset.name) }) {
        map { if (it.isNamed(preset.name)) preset else it }
    } else {
        this + preset
    }

fun List<SavedPreset>.renamed(
    from: String,
    to: String,
): List<SavedPreset> = map { if (it.isNamed(from)) it.copy(name = to) else it }

fun List<SavedPreset>.without(name: String): List<SavedPreset> = filterNot { it.isNamed(name) }
