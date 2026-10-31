package app.gloam.shade

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What the user asked for. Read from DataStore and combined into one value.
 *
 * Kotlin note: a `data class` rather than three parameters on the service, because `copy()` is
 * object spread with the compiler checking the field names — and because the ramp below takes the
 * whole triple, so a caller cannot pass the dim level where the warmth was meant.
 *
 * @param dimLevel 0–100, the one value the product is about (CONTEXT.md: **dim level**).
 * @param warmth 0–100, how strongly the shade is tinted (CONTEXT.md: **warmth**). A separate
 *   control from the dim level, but not an independent one: [shadeValuesFor] scales it by the
 *   headroom the dim level leaves. Which tint, amber to deep red, is the warmth colour's, and it is
 *   not in this triple because it moves the hue and never the light — see [warmthTint].
 * @param lowerBacklight whether Gloam may take the **backlight** down before it draws the shade.
 */
data class DimSettings(
    val dimLevel: Int,
    val warmth: Int,
    val lowerBacklight: Boolean,
)

/**
 * What the shade's window and its children carry.
 *
 * @param backlight the window brightness override, or **null to release it** and hand the screen
 *   back to the user's own brightness. `Float?` rather than Android's `-1f`
 *   (`BRIGHTNESS_OVERRIDE_NONE`) sentinel: Java had no other way to say *absent*, Kotlin does, and
 *   `null` cannot be accidentally arithmetic'd into a ramp the way `-1f` can. It becomes the
 *   sentinel at the one place that talks to `LayoutParams`.
 * @param shadeAlpha the black child's alpha, `0f … MAX_SHADE_ALPHA`.
 * @param warmthAlpha the tint child's alpha, `0f … MAX_WARMTH_ALPHA`.
 */
data class ShadeValues(
    val backlight: Float?,
    val shadeAlpha: Float,
    val warmthAlpha: Float,
)

/**
 * The darkest the black child is allowed to be, and — through the composite bound below — the
 * darkest the whole shade is allowed to be.
 *
 * 1.0 is a black rectangle with every way out of it behind it: the notification shade, the app, the
 * Stop action. The cap is the difference between a very dark screen and a phone the user believes is
 * broken. A constant rather than a preference on purpose — it is a safety floor, not a taste.
 *
 * **It is an alpha, not a share of the light.** Under the platform's [WINDOW_ALPHA_CLAMP] a white page
 * keeps 0.24 of its stored value at the cap, which is ≈ 4.7% of its light ([shadeTransmission]).
 */
const val MAX_SHADE_ALPHA = 0.95f

/**
 * The alpha the platform lets the shade's *window* carry: `maximum_obscuring_opacity_for_touch`,
 * Android 12's cap on an overlay that lets touches pass through (ADR-0010's fourth amendment). The
 * window manager writes it into the window's own alpha, so the black child at `a` covers the page by
 * `WINDOW_ALPHA_CLAMP × a` — a number this app never sets and cannot raise without catching touches.
 *
 * **Android's default, read rather than chosen**: unset on the development phone and on the API-33
 * emulator, so both sit at the framework's 0.8 (`phase-3.md` R1, R13). It is readable at runtime
 * (`InputManager.getMaximumObscuringOpacityForTouch`) and deliberately not read, because only the
 * ramp's *shape* depends on it. On a device where somebody has moved the global, the slider's steps
 * are uneven, and the child still stops at [MAX_SHADE_ALPHA] exactly as it did before this constant
 * existed.
 *
 * **Below Android 12 there is no clamp**: the window keeps alpha 1 (measured on the API-26 emulator),
 * so the service hands the ramp `1f` there instead, and the cap leaves ≈ 0.4% of a white page's light
 * rather than ≈ 4.7%. Both invariants are stated over the children's alphas, so neither moves.
 */
const val WINDOW_ALPHA_CLAMP = 0.8f

/**
 * The lowest window brightness override Gloam will ask for. **Never `0f`**, which is
 * `BRIGHTNESS_OVERRIDE_OFF` — the backlight *off*, over a live touchscreen.
 *
 * **The panel's floor, since 2026-09-13: the dimmest backlight Android will give an app.** This is
 * `mScreenBrightnessRangeMinimum` on the development panel, **2.0 nits** by R1's fit (`nits = 498.3 ×
 * override + 1.66`). ADR-0010's fifth amendment moved it here from `0.01f` (6.64 nits), which buys
 * about 3.3× less light at dim 100. The floor is not readable through a public API, so this is
 * this panel's number rather than each device's. An override below a panel's floor is clamped up to
 * it (R1), so on a panel whose floor sits higher the backlight half ends in a short flat stretch, and
 * on one whose floor sits lower Gloam stops above it. Neither is a safety question.
 *
 * **What it costs is the escape hatch, and the cost is taken knowingly.** [MAX_SHADE_ALPHA] never
 * dims the escape hatches, because all three sit above `TYPE_APPLICATION_OVERLAY`. The backlight
 * override dims everything including them, on a value the user can no longer change while it is
 * applied. R5 measured that the system's own surfaces do not carry a brightness of their own: the
 * notification shade, quick settings and the volume dialog all read back our override unchanged, so
 * this one number carries the whole escape-hatch argument. `0.01f` was R2's answer to the criterion
 * that the notification shade can be pulled down and **Stop** tapped in a dark room *and* a lit one,
 * with margin. At the floor those surfaces sit at 2.0 nits, and **R2 is owed again by eye**
 * (`DOD.md`). The debug build keeps a switch back up to `0.01f` until it is taken.
 *
 * The one surface that does lift it is the **keyguard**, which releases the override outright and
 * comes up at the user's own brightness. That is a free escape hatch this constant does not pay for,
 * and it is a platform behaviour rather than something Gloam arranges: a `TYPE_APPLICATION_OVERLAY`
 * window is hidden behind the lock screen, so it is not there to own the brightness. It returns on
 * its own after unlocking.
 */
const val MIN_BACKLIGHT = 6.83661E-4f

/**
 * The lowest override below Android 11, where the platform hands the override to power as an
 * integer, `(int) (value × 255)`. [MIN_BACKLIGHT] truncates to `0` there, which Android 9 and 10 read
 * as *no override* — the user's own brightness, above the step before it. One step up survives
 * the truncation, and the platform clamps it up to its own minimum (measured on API 26: `0` and `1`
 * both land on `config_screenBrightnessSettingMinimum`).
 */
const val MIN_INT_BACKLIGHT = 1f / 255f

/**
 * The most tint the warmth child may ever carry.
 *
 * It is half of the pair that keeps the composite legible — see [shadeValuesFor]'s two invariants —
 * and it is what caps the tint's own luminance at `0.10`, which is why the shade's colour cannot
 * come from `MaterialTheme`.
 */
const val MAX_WARMTH_ALPHA = 0.5f

/**
 * The shade alpha at which warmth starts to yield, so that it has reached zero by
 * [MAX_SHADE_ALPHA].
 *
 * The binding constraint is `(1 - WARMTH_EASE_FROM) × (1 - MAX_WARMTH_ALPHA) ≥ 1 - MAX_SHADE_ALPHA`,
 * which caps this at `0.90`. `0.88f` leaves margin — `0.06` against a `0.05` bound — and puts the
 * onset at dim level ≈ 96 with the backlight toggle on at full brightness and ≈ 86 with it off. (It was
 * ≈ 91 and ≈ 71 until ADR-0010's seventh amendment: the alpha now climbs later along the slider, so
 * warmth yields over fewer points.)
 *
 * **The ease is defined over the shade alpha rather than over the dim level**, because the invariant
 * it protects is stated over the shade alpha. Easing over the dim level instead would mean re-proving
 * the bound separately for each toggle state and each user's starting brightness.
 */
const val WARMTH_EASE_FROM = 0.88f

/**
 * The amber end of the warmth colour's path ([warmthTint]), ARGB — and **deliberately not a
 * `MaterialTheme` colour**, the one exception to the house rule that every colour comes from the
 * palette. Until ADR-0010's sixth amendment it was the only colour the warmth child could be.
 *
 * Three things separate it from every other colour in the app: the shade is not a surface but a
 * physical quantity chosen for its effect on light; it must not change when the user switches the
 * app's own light or dark theme; and it is bounded by [relativeLuminance] rather than by the
 * palette's contrast checks.
 *
 * **The bound is what picked it, and taste only chose between the survivors.** Source-over lays
 * veiling light *on top of* the content — `w x amber + (1 - w) x content` — so a bright amber passes
 * the composite's signal bound and still produces a screen nothing can be read through. `#FFB000`
 * has relative luminance `0.523`: at half alpha that is four times more veil than content. This one
 * is `0.073`, so `MAX_WARMTH_ALPHA x 0.073 = 0.036` against a bound of `1 - MAX_SHADE_ALPHA = 0.05`.
 *
 * The brand seed the palette is generated from — `B0763C`, dusk amber — is `0.224` and fails that by
 * more than twice. That is the number behind the exception rather than an assertion of it, and
 * `ShadeRampTest` holds both ends of it.
 */
const val SHADE_AMBER = 0xFF7A3B00.toInt()

/**
 * The deep-red end of the warmth colour's path, ARGB: pure red at **the same relative luminance as
 * [SHADE_AMBER]** (`0.0727` against `0.0727`), which is what picked `9E` out of its neighbours.
 *
 * Red at the far end because it is the light the eye's night vision is least sensitive to, which is
 * the one case where going past orange buys something for a reader in a dark room. Equal luminance
 * because of what [warmthTint] does with the pair.
 */
const val SHADE_RED = 0xFF9E0000.toInt()

/**
 * The colour the warmth child is painted with at a warmth colour of 0–100 (CONTEXT.md: **warmth
 * colour**): [SHADE_AMBER] at 0, [SHADE_RED] at 100.
 *
 * **The bar moves the hue and never the light the tint adds.** The two ends are blended in *linear*
 * light rather than in the stored bytes, and relative luminance is a weighted sum of linear channels,
 * so it is linear along the blend. Two ends of equal luminance make it constant across the whole bar —
 * `0.0719 … 0.0734` after rounding to bytes — and the veil invariant cannot depend on where the handle
 * sits. What the bar trades is how natural the screen looks against how much green light gets
 * through. Black text on a white page under full warmth and no dim keeps **6.0 : 1** at the amber end,
 * **5.8** in the middle and **5.3** at the red end, with 34%, 30% and 21% of the page's green light
 * left. The blue left is 21% everywhere, because neither end carries any blue: blue is set by the
 * warmth, not by its colour.
 *
 * A blend of the stored bytes would dip in the middle instead, because the transfer function is
 * convex. That is still inside the bound, but it would make the middle of the bar darker and duller than
 * either end, and nothing about the middle asks for that.
 *
 * The middle, `#8D2900`, is where [app.gloam.data.DEFAULT_WARMTH_COLOR] puts a first run: about the
 * hue of a 1000 K light.
 */
fun warmthTint(warmthColor: Int): Int {
    val t = warmthColor.coerceIn(0, 100) / 100f

    // Kotlin note: a function declared inside a function is a closure over `t`, the same as a nested
    // `function` in JS. It exists so each channel's blend is written once, not three times.
    fun channel(shift: Int): Int {
        val from = linearise((SHADE_AMBER shr shift and 0xFF) / 255f)
        val to = linearise((SHADE_RED shr shift and 0xFF) / 255f)
        return (encode(from + (to - from) * t) * 255f).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/**
 * WCAG relative luminance of an ARGB colour, alpha ignored: how much light the colour itself
 * carries, which is exactly the term the composite's signal bound cannot see.
 *
 * Arithmetic over three floats rather than `ColorUtils.calculateLuminance`, and that is the point —
 * a method call on `android.jar` throws *"not mocked"* in a JVM unit test, so reaching for the
 * platform here would move [SHADE_AMBER]'s bound onto a device. See this file's note on Android
 * imports below.
 */
fun relativeLuminance(color: Int): Float {
    val red = linearise((color shr 16 and 0xFF) / 255f)
    val green = linearise((color shr 8 and 0xFF) / 255f)
    val blue = linearise((color and 0xFF) / 255f)
    return 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

/** sRGB's transfer function, undone: the stored byte is gamma-encoded and light is not. */
private fun linearise(channel: Float): Float =
    if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

/** [linearise] run forwards: linear light back to the gamma-encoded value a byte stores. */
private fun encode(light: Float): Float =
    if (light <= 0.0030402f) light * 12.92f else 1.055f * light.pow(1f / 2.4f) - 0.055f

/**
 * The share of a white page's light that still reaches the eye through the black child at
 * [shadeAlpha]: the model this ramp is stated in, and the one [shadeValuesFor] runs backwards.
 *
 * Two steps, and **both belong to the platform rather than to this code**. The window is clamped to
 * [WINDOW_ALPHA_CLAMP], so the page keeps `1 - clamp × alpha` of each stored value. And the compositor
 * blends those stored, gamma-encoded values rather than light, so what the panel emits is that value
 * [linearise]d — roughly its 2.2th power. At the cap that is ≈ 0.047 of the light, where
 * `1 - MAX_SHADE_ALPHA` would read 0.05 and `1 - clamp × MAX_SHADE_ALPHA` 0.24.
 *
 * Stated for a white page, the brightest thing the shade covers. Near black the sRGB curve turns
 * straight, so dark content keeps a larger share — of very little light.
 */
fun shadeTransmission(
    shadeAlpha: Float,
    windowAlphaClamp: Float = WINDOW_ALPHA_CLAMP,
): Float = linearise(1f - windowAlphaClamp * shadeAlpha)

/**
 * One dim level, one ramp, in a fixed order (ADR-0010): spend the **backlight** first, then draw the
 * **shade** over what is left, then tint it.
 *
 * ## The shape, and why there is no breakpoint constant
 *
 * An earlier draft split the slider at a fixed point. The phone says that point is not a property of
 * the device at all — it is user state, and it moves from dim 43 to dim 65 across one phone's own
 * brightness range, with the worst case falling on exactly Gloam's user: someone already at their
 * system minimum, for whom a fixed breakpoint spends most of the slider going almost nowhere.
 *
 * So the ramp is stated once, over the quantity that matters, and the breakpoint falls out of it:
 *
 * ```
 * ratio = backlightTop / MIN_BACKLIGHT                // 1.0 when there is no backlight to spend
 * span  = 1 / shadeTransmission(MAX_SHADE_ALPHA)      // ≈ 21.3 — what the shade alone can take
 * light = (ratio * span) ^ -t                         // share of the top's light still reaching the eye
 * ```
 *
 * **Total light falls at a constant ratio per point from 0 to 100, and the backlight is spent
 * before the shade is touched.** Below the breakpoint `light × ratio ≥ 1`, so the shade alpha is 0
 * and there is no overlay over the user's content at all — the concrete thing ADR-0010 bought by
 * sequencing the two mechanisms rather than blending them. Above it the backlight is pinned at
 * [MIN_BACKLIGHT] and the shade supplies the remainder.
 *
 * Geometric rather than linear because perception of brightness is roughly logarithmic: equal slider
 * travel should buy an equal *ratio* of light, not an equal subtraction of it. Half-way through the
 * shade's stretch a ramp linear in alpha still passes 34% of the light and a geometric one 22%, which
 * is the difference between a slider whose useful range is its last few points and one whose whole
 * length does something.
 *
 * Four things this shape gets for free that a fixed breakpoint had to assert: there is no dead zone
 * for any user, the toggle-off case is the same expression with `ratio = 1` rather than a branch,
 * monotonicity and continuity are true by construction, and the perceived rate never kinks because
 * there is no seam to kink at.
 *
 * ## Geometric in the override float, not in nits — and what that costs
 *
 * The panel's response to the override is **affine, not proportional**: on the development device
 * `nits = 498.3 × override + 1.66`, so halving the override near the bottom of its range does not
 * halve the light. A ramp that is geometric in the float is therefore slightly *flatter* than
 * geometric in what the eye receives, and it flattens where this product lives.
 *
 * **Measured with the old `0.01f` floor, it was small enough to leave alone.** Over the whole slider
 * the shortfall in perceptual travel was under 4% at either end of the user's own brightness range.
 * The backlight stretch delivered 94% of the ratio it promised at maximum and 80% for a user already
 * at their system minimum. (This paragraph used to add that the shade half was exactly geometric
 * because alpha genuinely multiplies. It was not — see the next section.) The correction would cost a
 * constant whose value (`498.3` and `1.66`) is **this panel's and is not readable from an app**: a
 * device-specific number carried for every device.
 *
 * **Since [MIN_BACKLIGHT] moved to the floor, the distortion is no longer small.** It bites in the
 * float's bottom decade, where the 1.66-nit offset is most of the light, and the ramp now ends inside
 * that decade. Between `0.01` and the floor the float falls 14.6× and the light only 3.3×, so the last
 * stretch of the backlight half darkens the screen more slowly per point than the rest of the slider.
 * That is feel, not safety: monotonicity and continuity still hold. It is left alone until someone
 * notices, and the same panel-specific constant would still be the price of fixing it.
 *
 * ## The shade's alpha is derived from the light, not equal to the light it removes
 *
 * **Until ADR-0010's seventh amendment this ramp set `shadeAlpha = 1 - light`**, on the belief that
 * an alpha takes away that share of the light. On this platform it does not, for the two reasons in
 * [shadeTransmission]: the window alpha clamp, and blending in stored values rather than in light.
 * Neither is a choice this code made, and the ramp now runs that model backwards: the light the shade
 * must leave, then the stored value that emits it ([encode]), then the child alpha that becomes that
 * coverage once the window is clamped.
 *
 * **Read off the phone, without a light meter**, in three steps. `screencap` reads the shade at dim 100
 * as 0.2393 of the page's stored value (`phase-3.md` R3), where blending in light would have read
 * 0.53. `dumpsys SurfaceFlinger` lists the shade as `CLIENT` composition in 10 reads of 12 — the same
 * GPU renderer `screencap` uses — so those values are what the panel receives; the other two were
 * `DEVICE`, where the hardware composer is held to the same pixels (2026-09-13). And the display runs
 * in `ColorMode::SRGB`, which is the curve [linearise] undoes. The
 * last step is the display standard rather than a photon count; any exponent from 2.0 to 2.4 leaves
 * every conclusion here standing.
 *
 * **What the old expression cost.** With the toggle off, dim 60 passed 9% of the light where 16% was
 * meant, and the last forty points only halved it, so most of the slider's darkening happened in its
 * first half. Dim 100 does not move, because the cap does not: ≈ 4.7% of the light, ≈ 0.09 nits at
 * the floor.
 *
 * ## What it deliberately does not promise
 *
 * **The same dim level is not the same darkness under the two toggle settings, and it is not the
 * same darkness for two users sitting at different brightness.** That is what *"100 means as dark as
 * currently allowed"* already committed to, said out loud rather than hidden.
 *
 * ## The two invariants, which the tests hold it to
 *
 * ```
 * (1 - shadeAlpha) * (1 - warmthAlpha)  ≥  1 - MAX_SHADE_ALPHA
 * MAX_WARMTH_ALPHA * relativeLuminance(tint)  ≤  1 - MAX_SHADE_ALPHA   // every warmthTint(0..100)
 * ```
 *
 * The first says the composite may never take more signal than the black child alone was allowed to
 * take: two layers each inside its own cap still multiply, and black at `0.95` under the tint at `0.5`
 * leaves 2.5% of the content's stored values — half of what was ever allowed. Both are stated over
 * alphas, which is where the layers multiply, so deriving the alpha from light moved neither of them.
 * The second says the tint may never
 * *add* more light than the black child was allowed to leave, because source-over lays veiling light
 * on top of the content and the first invariant cannot see that term at all.
 *
 * ## Why this file has no Android imports
 *
 * It is the whole reason the safety values are proven by a JVM test rather than by looking at a
 * screen. Compile-time constants are safe in a JVM unit test; method calls on `android.jar` are not
 * — the stub throws *"not mocked"*. Keeping this file free of Android makes that a non-question.
 *
 * @param backlightTop the backlight Gloam is taking over from, as a fraction of the user's own
 *   maximum — captured at the moment the override is applied and held until it is released, never
 *   re-read while it is live. **Null means the backlight half does nothing this session**, which is
 *   also what a failed or untrustworthy read decays to; see [readBacklight].
 * @param minBacklight the lowest override the ramp may ask for. Always [MIN_BACKLIGHT] in a release
 *   build; a parameter only so the debug build can switch back up to the previous `0.01f` on a real
 *   page (`src/debug/.../shade/MinBacklight.kt`), and [MIN_INT_BACKLIGHT] below Android 11.
 * @param windowAlphaClamp [WINDOW_ALPHA_CLAMP] from Android 12, `1f` below it.
 */
fun shadeValuesFor(
    settings: DimSettings,
    backlightTop: Float?,
    minBacklight: Float = MIN_BACKLIGHT,
    windowAlphaClamp: Float = WINDOW_ALPHA_CLAMP,
): ShadeValues {
    val t = settings.dimLevel.coerceIn(0, 100) / 100f

    // A top below our own floor is a user already darker than we would set them, so there is nothing
    // to spend and applying the override could only brighten the screen. Releasing is the darker
    // answer as well as the safe one.
    val top =
        backlightTop
            ?.takeIf { settings.lowerBacklight && it >= minBacklight && it <= 1f }

    val ratio = if (top == null) 1f else top / minBacklight
    val span = 1f / shadeTransmission(MAX_SHADE_ALPHA, windowAlphaClamp)
    val light = (ratio * span).pow(-t)

    // Dim level 0 is the one special case, and it is one line: the override is *released* rather
    // than set to the top. CONTEXT.md already says a dim level of zero is still running; this is
    // what that means physically — the shade is transparent, the backlight is the user's own, and
    // their brightness slider works again. Everything else falls out of the expression above.
    val backlight = if (top == null || settings.dimLevel <= 0) null else maxOf(top * light, minBacklight)

    // The light left for the shade to take, then [shadeTransmission] run backwards. At dim 100 this
    // lands on `MAX_SHADE_ALPHA` by construction, give or take a float's last bit, which the clamp eats.
    val shadeLight = minOf(1f, light * ratio)
    val shadeAlpha = ((1f - encode(shadeLight)) / windowAlphaClamp).coerceIn(0f, MAX_SHADE_ALPHA)

    // Something has to give at the very top, and it cannot be the dim level — that is the one value
    // the product is about. So the applied warmth is the user's warmth scaled by the headroom the
    // dim level leaves. A hard clamp instead would put a visible hue cliff in the last two points,
    // which reads as a bug rather than as a limit.
    val headroom =
        ((MAX_SHADE_ALPHA - shadeAlpha) / (MAX_SHADE_ALPHA - WARMTH_EASE_FROM)).coerceIn(0f, 1f)
    val warmthAlpha = settings.warmth.coerceIn(0, 100) / 100f * MAX_WARMTH_ALPHA * headroom

    return ShadeValues(backlight = backlight, shadeAlpha = shadeAlpha, warmthAlpha = warmthAlpha)
}
