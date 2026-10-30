package app.gloam.data

import app.gloam.ui.dim.Preset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

const val PRESET_NAME_MAX_LENGTH = 30

/**
 * One chip in the preset row, built-in or saved by the user: **all of them are one stored list**, so
 * the three that ship can be renamed, given new values and deleted like any other.
 *
 * Stored as JSON under a single DataStore key rather than in a database: a few dozen rows at most,
 * read whole and written whole, with nothing to query (ADR-0007's amendment). The [id] is the
 * identity every write goes by; names and values are both unique across the list, names compared
 * without case, and the writes below refuse anything that would break either.
 *
 * @param id `reading`, `evening` or `in_bed` for the built-ins ([Preset.id]), a generated one for the
 *   user's own. Never shown and never changed.
 * @param name `null` for a built-in the user has not renamed, so its chip shows the translated label
 *   and follows the app's language. Never `null` for one the user saved.
 *
 * Kotlin note for a JS reader: `@Serializable` makes the compiler plugin generate the
 * `JSON.parse`/`JSON.stringify` mapping for this class at build time, with no reflection. A property
 * with only a getter, like [builtIn], has no backing field and is not part of that mapping.
 */
@Serializable
data class StoredPreset(
    val id: String,
    val name: String? = null,
    val dimLevel: Int,
    val warmth: Int,
    val warmthColor: Int,
) {
    val builtIn: Preset?
        get() = Preset.entries.firstOrNull { it.id == id }

    /** Whether a built-in still carries the values it shipped with, so its own hint still describes it. */
    val isAsShipped: Boolean
        get() = builtIn?.let { matches(it.dimLevel, it.warmth, it.warmthColor) } ?: false

    fun matches(
        dimLevel: Int,
        warmth: Int,
        warmthColor: Int,
    ): Boolean = this.dimLevel == dimLevel && this.warmth == warmth && this.warmthColor == warmthColor

    /** The name on the chip. [labelOf] resolves a built-in's translated label, which needs a `Context`. */
    fun displayName(labelOf: (Preset) -> String): String = name ?: builtIn?.let(labelOf) ?: id
}

/** What an absent key reads as: the three built-ins, unrenamed and at the values they ship with. */
val DEFAULT_PRESETS: List<StoredPreset> =
    Preset.entries.map { StoredPreset(it.id, name = null, it.dimLevel, it.warmth, it.warmthColor) }

// A field added later needs a default, and an older build reading a newer file must not fail on
// the field it does not know: those two are the whole migration story.
private val json = Json { ignoreUnknownKeys = true }

fun encodePresets(presets: List<StoredPreset>): String = json.encodeToString(presets)

/**
 * The stored list, or [DEFAULT_PRESETS] when there is none.
 *
 * Decoded row by row, so one unreadable row loses only itself: the writes read this list back and
 * store it whole, and an all-or-nothing read would turn one bad row into deleting every preset. A
 * file that does not parse at all reads as absent rather than empty, so it cannot delete them either.
 *
 * Duplicates are not removed here: a file holding two presets with the same values still reads, both
 * light, and the next write simply refuses to add a third.
 */
fun decodePresets(text: String?): List<StoredPreset> {
    val rows = text?.let(::parseRows) ?: return DEFAULT_PRESETS
    return rows
        .mapNotNull { decodeRow<StoredPreset>(it) }
        .mapNotNull { it.tidied() }
        .distinctBy { it.id }
}

private fun StoredPreset.tidied(): StoredPreset? {
    val name = name?.trim()?.take(PRESET_NAME_MAX_LENGTH)?.ifBlank { null }
    if (id.isBlank() || (name == null && builtIn == null)) return null
    return copy(
        name = name,
        dimLevel = dimLevel.coerceIn(0, 100),
        warmth = warmth.coerceIn(0, 100),
        warmthColor = warmthColor.coerceIn(0, 100),
    )
}

// SerializationException extends IllegalArgumentException, so each catch covers both.
private fun parseRows(text: String): List<JsonElement>? =
    try {
        json.decodeFromString<List<JsonElement>>(text)
    } catch (_: IllegalArgumentException) {
        null
    }

// Kotlin note: `reified` keeps the type argument at run time, which an ordinary generic erases —
// it is what lets `decodeFromJsonElement<T>` know which class to build without being passed one.
private inline fun <reified T> decodeRow(row: JsonElement): T? =
    try {
        json.decodeFromJsonElement<T>(row)
    } catch (_: IllegalArgumentException) {
        null
    }

/** The preset already showing [name] on its chip, ignoring case and the preset [exceptId]. */
fun List<StoredPreset>.named(
    name: String,
    labelOf: (Preset) -> String,
    exceptId: String? = null,
): StoredPreset? = firstOrNull { it.id != exceptId && it.displayName(labelOf).equals(name.trim(), ignoreCase = true) }

/** The first preset carrying exactly these values: the one the save dialog names as the same settings. */
fun List<StoredPreset>.matching(
    dimLevel: Int,
    warmth: Int,
    warmthColor: Int,
): StoredPreset? = firstOrNull { it.matches(dimLevel, warmth, warmthColor) }

// Kotlin note: the three below are extension functions — like adding a method to `Array.prototype`,
// except resolved at compile time, visible only where imported, and returning a new list rather
// than mutating this one. Each returns `null` for a write the uniqueness rules refuse.

/**
 * These values saved under [name]: into the preset already called that, which keeps its id and its
 * name, or as a new one at the end. Refused while any preset carries the values already, because
 * the result would be two chips that do the same thing — or, replacing a preset with its own
 * values, a write that changes nothing.
 */
fun List<StoredPreset>.saving(
    name: String,
    dimLevel: Int,
    warmth: Int,
    warmthColor: Int,
    labelOf: (Preset) -> String,
    newId: () -> String,
): List<StoredPreset>? {
    val trimmed = name.trim().take(PRESET_NAME_MAX_LENGTH)
    if (trimmed.isEmpty() || matching(dimLevel, warmth, warmthColor) != null) return null
    val target = named(trimmed, labelOf) ?: return this + StoredPreset(newId(), trimmed, dimLevel, warmth, warmthColor)
    return map {
        if (it.id ==
            target.id
        ) {
            it.copy(dimLevel = dimLevel, warmth = warmth, warmthColor = warmthColor)
        } else {
            it
        }
    }
}

/**
 * Preset [id] renamed to [to], refused when another preset already shows that name. A built-in
 * renamed back to its own label goes back to having no stored name, so it follows the language again.
 */
fun List<StoredPreset>.renaming(
    id: String,
    to: String,
    labelOf: (Preset) -> String,
): List<StoredPreset>? {
    val trimmed = to.trim().take(PRESET_NAME_MAX_LENGTH)
    val preset = firstOrNull { it.id == id }
    if (trimmed.isEmpty() || preset == null || named(trimmed, labelOf, exceptId = id) != null) return null
    val name = trimmed.takeUnless { preset.builtIn?.let(labelOf) == it }
    return map { if (it.id == id) it.copy(name = name) else it }
}

fun List<StoredPreset>.deleting(id: String): List<StoredPreset> = filterNot { it.id == id }
