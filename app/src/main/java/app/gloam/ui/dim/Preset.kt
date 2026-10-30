package app.gloam.ui.dim

import androidx.annotation.StringRes
import app.gloam.R

/**
 * The three presets Gloam ships with, and **the defaults they come back as**: their ids, values,
 * labels and hints. What the row shows is the stored list ([app.gloam.data.StoredPreset]), where the
 * user can rename, re-value or delete these like any of their own; this enum is what an absent list
 * reads as and what *Restore to default* brings back.
 *
 * **Which preset is lit is never stored.** Tapping one writes its three values into the same keys
 * the controls write, and the lit chips are worked out from those values at every read.
 *
 * Kotlin note for a JS reader: an `enum class` with constructor parameters is a closed set of
 * singleton objects, each carrying its own fields — closer to a frozen object of frozen objects than
 * to a TypeScript string enum. `entries` is the ordered list of them, in declaration order, which is
 * also the order the chips start in.
 */
enum class Preset(
    /** Stored, so never renamed: it is how a list on disk finds its built-ins again. */
    val id: String,
    val dimLevel: Int,
    val warmth: Int,
    val warmthColor: Int,
    @StringRes val label: Int,
    @StringRes val hint: Int,
) {
    /** Dark, and barely tinted: a tint costs contrast, and text is what is being looked at. */
    Reading(
        "reading",
        dimLevel = 70,
        warmth = 15,
        warmthColor = 50,
        R.string.preset_reading,
        R.string.preset_reading_hint,
    ),

    /** Light dimming with strong warmth, for the hours before the room goes dark. */
    Evening(
        "evening",
        dimLevel = 35,
        warmth = 70,
        warmthColor = 40,
        R.string.preset_evening,
        R.string.preset_evening_hint,
    ),

    /** Nearly dark and deep red, for a room with the lights already off. */
    InBed("in_bed", dimLevel = 85, warmth = 60, warmthColor = 80, R.string.preset_in_bed, R.string.preset_in_bed_hint),
}
