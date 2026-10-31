package app.gloam.shade

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import android.util.TypedValue

/**
 * Everything the app can learn about the user's own backlight, and the one number it computes from
 * it — `backlightTop`, the float the shade's window override must start from so that engaging it
 * does not change what is on screen.
 *
 * **Why this is a whole data class rather than a function returning a `Float`.** Every input is
 * optional on some device, and the one outcome that must never happen is a confident number that is
 * *too high*: the window override replaces the user's brightness outright, so an overestimate
 * brightens a screen the user was deliberately keeping dark, on a value their own slider can no
 * longer change. So each ingredient is carried separately and [top] is null the moment any of them
 * is missing — and [note] says which one, because "it did nothing" with no reason attached is the
 * hardest bug there is to chase on someone else's phone.
 *
 * Kotlin note: this is a `data class`, so it gets `toString()` free — which is exactly what the
 * debug readout and logcat print. No JS analogue is needed; think of it as an object literal whose
 * shape is checked.
 */
data class BacklightReading(
    /** `Settings.System.SCREEN_BRIGHTNESS` — the user's stored level, in the device's own integer. */
    val raw: Int?,
    /** `SCREEN_BRIGHTNESS_MODE == 1`. Adaptive means [raw] may not be what is on screen at all. */
    val adaptive: Boolean?,
    /** `SCREEN_BRIGHTNESS_FLOAT`, where the ROM keeps one. When it exists there is nothing to decode. */
    val settingFloat: Float?,
    /** `config_screenBrightnessSettingMinimum` — the bottom of [raw]'s scale. */
    val minSetting: Int?,
    /** `config_screenBrightnessSettingMaximum` — the top of it. Not documented to be 255. */
    val maxSetting: Int?,
    /** `config_screenBrightnessSettingMinimumFloat`, or null when the device leaves it unconfigured. */
    val minFloat: Float?,
    /** `config_screenBrightnessSettingMaximumFloat`, same. This is the one that decides everything. */
    val maxFloat: Float?,
    /** The computed starting point for the override, or null on any doubt at all. */
    val top: Float?,
    /** Why [top] is what it is. Developer-facing only; never shown to a user. */
    val note: String,
)

/**
 * Read the user's backlight and decode it into the window override's float space.
 *
 * **What the phone said, and why the decode is this short** (Phase 1, checkpoint B — R1 and R3).
 * The window override and `Settings.System.SCREEN_BRIGHTNESS` turn out to share a top: an override
 * of `1.0` and a setting of its maximum both land on exactly the same panel state — 500 nits on the
 * development phone, which is that display's non-HBM maximum rather than the `1.0` its float range
 * claims. Both are normalised onto the same range by the framework before the panel sees either.
 * So `backlightTop` is a **ratio, not a brightness**: what fraction of their own maximum the user is
 * currently at. Nothing has to be known about nits, gamma, or where this panel's HBM knee sits.
 *
 * **The integer range's bottom is 1, not the configured minimum.** `config_screenBrightnessSetting-
 * Minimum` is 10 on this phone, but the framework's own conversion (`BrightnessSynchronizer`) maps
 * from `BRIGHTNESS_OFF + 1`, and the measurement agrees to a tenth of a percent across the range:
 * decoding from 10 instead is 48% low at the bottom of the slider — the safe direction, but a
 * visible halving of the screen the instant the override engages.
 *
 * The two float-range resources are read and honoured where a device configures them, because a
 * device that maps its setting onto some *other* float range is exactly the one this would get
 * wrong. Reading them is a **resource lookup by name**, not a hidden Java member, so the non-SDK
 * blocklist does not reach it — the name may simply not exist, which `getIdentifier` reports as 0
 * rather than by throwing, and AOSP ships both as `-2` meaning "not configured", in which case the
 * framework uses `BRIGHTNESS_MIN … BRIGHTNESS_MAX` and so does this.
 *
 * **Never fall back to `1.0f` for a failed read.** A fallback that brightens the screen is the
 * mirror image of the failure the shade's constants exist to prevent — which is why a missing [raw]
 * or an unknown integer scale is null rather than "assume they are at maximum".
 */
fun readBacklight(context: Context): BacklightReading {
    val resolver = context.contentResolver
    val raw = systemInt(context, Settings.System.SCREEN_BRIGHTNESS)
    val adaptive =
        systemInt(context, Settings.System.SCREEN_BRIGHTNESS_MODE)?.let { mode -> mode == 1 }
    // Not a constant in the SDK: the key exists from Android 12 but is not in `Settings.System`'s
    // published fields, so it is spelled out. Absent on many ROMs, which is not an error.
    val settingFloat =
        runCatching { Settings.System.getFloat(resolver, "screen_brightness_float") }.getOrNull()

    val minSetting = frameworkInt("config_screenBrightnessSettingMinimum")
    val maxSetting = frameworkInt("config_screenBrightnessSettingMaximum")
    val minFloat = frameworkFloat("config_screenBrightnessSettingMinimumFloat")
    val maxFloat = frameworkFloat("config_screenBrightnessSettingMaximumFloat")

    val (top, note) =
        when {
            settingFloat != null && settingFloat in 0f..1f ->
                settingFloat to "read directly from screen_brightness_float; nothing to decode"

            raw == null -> null to "no SCREEN_BRIGHTNESS to read"

            maxSetting == null || maxSetting <= SETTING_FLOOR ->
                null to "the integer scale is unknown (max=$maxSetting)"

            else -> {
                val fraction =
                    ((raw - SETTING_FLOOR).toFloat() / (maxSetting - SETTING_FLOOR))
                        .coerceIn(0f, 1f)
                val lo = minFloat ?: 0f
                val hi = maxFloat ?: 1f
                val decoded = (lo + fraction * (hi - lo)) * SAFETY_TRIM
                decoded.coerceIn(0f, 1f) to
                    "$raw of $maxSetting is ${(fraction * 100).toInt()}% of the user's own maximum"
            }
        }

    return BacklightReading(
        raw = raw,
        adaptive = adaptive,
        settingFloat = settingFloat,
        minSetting = minSetting,
        maxSetting = maxSetting,
        minFloat = minFloat,
        maxFloat = maxFloat,
        top = top,
        note = note,
    )
}

/** The one number the ramp wants. Null means the backlight half does nothing this session. */
fun readBacklightTop(context: Context): Float? = readBacklight(context).top

/**
 * The bottom of the setting's integer scale, which is **not**
 * `config_screenBrightnessSettingMinimum` — see [readBacklight].
 */
private const val SETTING_FLOOR = 1

/**
 * Five percent off the decoded value, and the number is measured rather than superstitious.
 *
 * The override's map has a small positive offset against the setting's, so an untrimmed decode
 * reproduces the user's brightness to within a tenth of a percent in the middle of the range but
 * lands **3.7% high at the bottom of it** — the one direction that matters. Five percent covers
 * that with margin on ROMs nobody has measured; it costs a step down of a twentieth at the moment
 * the override engages, which is under the ramp's own first step and about at the threshold of what
 * an eye can see. It is what makes *"engaging the override never brightens the screen"* true by
 * construction rather than true by luck.
 */
private const val SAFETY_TRIM = 0.95f

private fun systemInt(
    context: Context,
    key: String,
): Int? = runCatching { Settings.System.getInt(context.contentResolver, key) }.getOrNull()

private fun frameworkInt(name: String): Int? {
    val resources = Resources.getSystem()
    val id = resources.getIdentifier(name, "integer", "android")
    return if (id == 0) null else runCatching { resources.getInteger(id) }.getOrNull()
}

/**
 * The two float configs are `dimen` resources declared with `format="float"`, which is why this
 * reads them as a float [TypedValue] rather than with `getDimension` — `Resources.getFloat`, which
 * does the same, is Android 10. The configs themselves are Android 9 and later, so on 8 this answers
 * null, which is the same as a ROM that leaves them unset. A negative value is AOSP's
 * "not configured" sentinel — it ships as `-2` — and is reported here as absent, which it is.
 */
private fun frameworkFloat(name: String): Float? {
    val resources = Resources.getSystem()
    val id = resources.getIdentifier(name, "dimen", "android")
    if (id == 0) return null
    val value =
        runCatching { TypedValue().also { resources.getValue(id, it, true) } }
            .getOrNull()
            ?.takeIf { it.type == TypedValue.TYPE_FLOAT }
            ?.float ?: return null
    return value.takeIf { it >= 0f }
}
