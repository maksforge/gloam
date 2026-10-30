package app.gloam.ui.dim

import androidx.annotation.StringRes
import app.gloam.R

/**
 * A built-in named starting point for the dim level, warmth and warmth colour — **chosen at build
 * time and translated**. The ones the user names are [app.gloam.data.SavedPreset].
 *
 * **Nothing about which preset is lit is stored.** Tapping one writes the three values it carries
 * into the same keys the controls already write, and which preset is "on" is worked out from those
 * values by [matching] at every read. So there is no `current_preset` key to back up, to go stale
 * when the user nudges a slider afterwards, or to migrate — the user's own values are the only
 * truth, and a preset is a way of typing them.
 *
 * Kotlin note for a JS reader: an `enum class` with constructor parameters is a closed set of
 * singleton objects, each carrying its own fields — closer to a frozen object of frozen objects than
 * to a TypeScript string enum. `entries` is the ordered list of them, in declaration order, which is
 * also the order the chips are drawn in.
 */
enum class Preset(
    val dimLevel: Int,
    val warmth: Int,
    val warmthColor: Int,
    @StringRes val label: Int,
    @StringRes val hint: Int,
) {
    /** Dark, and barely tinted: a tint costs contrast, and text is what is being looked at. */
    Reading(dimLevel = 70, warmth = 15, warmthColor = 50, R.string.preset_reading, R.string.preset_reading_hint),

    /** Light dimming with strong warmth, for the hours before the room goes dark. */
    Evening(dimLevel = 35, warmth = 70, warmthColor = 40, R.string.preset_evening, R.string.preset_evening_hint),

    /** Nearly dark and deep red, for a room with the lights already off. */
    InBed(dimLevel = 85, warmth = 60, warmthColor = 80, R.string.preset_in_bed, R.string.preset_in_bed_hint),
    ;

    companion object {
        /**
         * The preset these values are exactly, or `null` once any of them has been moved off it.
         *
         * Exact equality rather than "nearest": a chip that stays lit after the user has dragged
         * the column somewhere else would be claiming a choice they have since undone.
         */
        fun matching(
            dimLevel: Int,
            warmth: Int,
            warmthColor: Int,
        ): Preset? =
            entries.firstOrNull {
                it.dimLevel == dimLevel && it.warmth == warmth && it.warmthColor == warmthColor
            }
    }
}
