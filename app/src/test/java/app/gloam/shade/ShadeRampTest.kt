package app.gloam.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.pow

/**
 * The ramp's safety values, proven by sweeping every input rather than by looking at a screen.
 *
 * **This is the test the constants exist for.** `MAX_SHADE_ALPHA`, `MIN_BACKLIGHT` and the two
 * warmth constants are each argued for in `ShadeRamp.kt`, but an argument in a comment does not
 * notice when somebody changes a number — and every one of these values fails *silently*: too dark
 * is a phone the user believes is broken, too bright is a screen they were deliberately keeping
 * dark. So the properties are asserted over the whole grid.
 *
 * A table sweep rather than a handful of examples because the inputs are 101 × 101 × 2 × a few tops
 * and the JVM does not care. `ShadeRamp.kt` has no Android imports, which is what makes that
 * possible: compile-time constants are safe in a JVM unit test, but a method call on `android.jar`
 * throws *"not mocked"*.
 */
class ShadeRampTest {
    /**
     * The three platforms `minSdk` 26 reaches, as the service hands them to the ramp: the window alpha
     * clamp from Android 12, a float override from 11, and an integer one below it.
     */
    private data class Platform(
        val name: String,
        val clamp: Float,
        val floor: Float,
    ) {
        /**
         * The tops worth sweeping: the user at their own maximum, exactly at the floor, **below** it
         * — a user already darker than Gloam would set them — and no usable reading at all.
         */
        val tops = listOf(1.0f, floor, floor / 2f, null)

        fun values(
            settings: DimSettings,
            top: Float?,
        ) = shadeValuesFor(settings, top, floor, clamp)

        override fun toString() = name
    }

    private val platforms =
        listOf(
            Platform("12+", WINDOW_ALPHA_CLAMP, MIN_BACKLIGHT),
            Platform("11", 1f, MIN_BACKLIGHT),
            Platform("8-10", 1f, MIN_INT_BACKLIGHT),
        )

    private val levels = 0..100
    private val warmths = listOf(0, 1, 25, 50, 75, 99, 100)

    /**
     * What the ramp will actually spend, which is not always what it was handed.
     *
     * The toggle being off, or a top below [MIN_BACKLIGHT], both collapse to *there is no backlight
     * to spend* — and the tests below have to agree with the ramp about which case they are in
     * without simply calling the ramp to find out.
     */
    private fun Platform.effectiveTop(
        top: Float?,
        lowerBacklight: Boolean,
    ): Float? = top?.takeIf { lowerBacklight && it >= floor && it <= 1f }

    /** The whole ramp's fall, end to end: `ratio × span`. */
    private fun Platform.fall(
        top: Float?,
        lowerBacklight: Boolean,
    ): Float {
        val effective = effectiveTop(top, lowerBacklight)
        val ratio = if (effective == null) 1f else effective / floor
        return ratio * (1f / shadeTransmission(MAX_SHADE_ALPHA, clamp))
    }

    /**
     * Total light reaching the eye at this dim level, as a fraction of what reaches it at dim 0.
     *
     * Released and *held at the top* are the same amount of light — that is the whole point of the
     * 5% trim in `readBacklight` — so a released override reads as the top here. The shade's share is
     * [shadeTransmission] rather than `1 - shadeAlpha`: an alpha is not a share of the light on this
     * platform, and `the shade model is anchored to the phone` below is what ties the model to a reading.
     */
    private fun Platform.light(
        values: ShadeValues,
        effectiveTop: Float?,
    ): Float {
        val top = effectiveTop ?: 1f
        return (values.backlight ?: top) / top * shadeTransmission(values.shadeAlpha, clamp)
    }

    private fun sweep(body: Platform.(DimSettings, Float?, ShadeValues) -> Unit) {
        for (platform in platforms) {
            for (top in platform.tops) {
                for (lower in listOf(true, false)) {
                    for (level in levels) {
                        for (warmth in warmths) {
                            val settings = DimSettings(level, warmth, lower)
                            platform.body(settings, top, platform.values(settings, top))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the backlight is released or inside the range we are allowed to ask for`() {
        sweep { settings, top, values ->
            val backlight = values.backlight ?: return@sweep
            // `0.0f` is BRIGHTNESS_OVERRIDE_OFF — the backlight *off*, over a live touchscreen.
            assertNotEquals("$this $settings top=$top asked for BRIGHTNESS_OVERRIDE_OFF", 0f, backlight)
            assertTrue(
                "$this $settings top=$top emitted $backlight, below the floor $floor",
                backlight >= floor,
            )
            if (floor == MIN_INT_BACKLIGHT) {
                assertTrue("$this $settings top=$top truncates to 0", (backlight * 255).toInt() >= 1)
            }
        }
    }

    @Test
    fun `the ramp never brightens the screen`() {
        sweep { settings, top, values ->
            val backlight = values.backlight ?: return@sweep
            val ceiling = effectiveTop(top, settings.lowerBacklight)
            assertTrue("$this $settings top=$top emitted $backlight with no top to spend", ceiling != null)
            assertTrue(
                "$this $settings top=$top emitted $backlight, above the captured top $ceiling",
                backlight <= ceiling!! + EPSILON,
            )
        }
    }

    @Test
    fun `the shade alpha stays inside its cap`() {
        sweep { settings, top, values ->
            assertTrue(
                "$this $settings top=$top emitted shadeAlpha ${values.shadeAlpha}",
                values.shadeAlpha in 0f..MAX_SHADE_ALPHA,
            )
        }
    }

    @Test
    fun `the warmth alpha stays inside its cap`() {
        sweep { settings, top, values ->
            assertTrue(
                "$this $settings top=$top emitted warmthAlpha ${values.warmthAlpha}",
                values.warmthAlpha in 0f..MAX_WARMTH_ALPHA,
            )
        }
    }

    /**
     * **The signal invariant, stated over the composite rather than over either child.**
     *
     * Two layers each inside its own cap still multiply: black at `0.95` under the tint at `0.5` leaves
     * 2.5% of the content with neither child past its limit. The worst case over this grid is
     * *exactly* `1 - MAX_SHADE_ALPHA`, at dim 100, which is why the epsilon is load-bearing rather
     * than defensive.
     */
    @Test
    fun `the composite never takes more signal than the black layer alone was allowed to`() {
        sweep { settings, top, values ->
            val signal = (1f - values.shadeAlpha) * (1f - values.warmthAlpha)
            assertTrue(
                "$this $settings top=$top left $signal of the content, under ${1f - MAX_SHADE_ALPHA}",
                signal >= 1f - MAX_SHADE_ALPHA - EPSILON,
            )
        }
    }

    /**
     * True by construction in this formulation, which is exactly why it is worth asserting: a
     * fencepost in `t` would break it silently and look like nothing at all on a screen.
     */
    @Test
    fun `a higher dim level never emits more light`() {
        forEachRamp { platform, top, lower ->
            var previousBacklight: Float? = null
            var previousAlpha = -1f
            for (level in levels) {
                val values = platform.values(DimSettings(level, 0, lower), top)
                val backlight = values.backlight
                if (backlight != null && previousBacklight != null) {
                    assertTrue(
                        "$platform top=$top lower=$lower: backlight rose at $level",
                        backlight <= previousBacklight + EPSILON,
                    )
                }
                assertTrue(
                    "$platform top=$top lower=$lower: shadeAlpha fell at $level",
                    values.shadeAlpha >= previousAlpha - EPSILON,
                )
                previousBacklight = backlight
                previousAlpha = values.shadeAlpha
            }
        }
    }

    private fun forEachRamp(body: (Platform, Float?, Boolean) -> Unit) {
        for (platform in platforms) {
            for (top in platform.tops) {
                for (lower in listOf(true, false)) body(platform, top, lower)
            }
        }
    }

    /**
     * No jump where the backlight runs out and the shade takes over.
     *
     * The bound is derived rather than guessed. The shade alpha is `(1 - encode(s)) / clamp`, where
     * `s` is the light left for it to take and falls by `ln(ratio × span)` of itself per unit of `t`.
     * So the alpha climbs at `ln(ratio × span) × s × encode'(s) / clamp`, and `s × encode'(s)` is
     * largest at `s = 1` — exactly the handover — where the sRGB encode's slope is `1.055 / 2.4`. One
     * point of the slider can move the alpha by at most a hundredth of that. A fencepost at the seam
     * would move it by an order of magnitude more, which is what this catches — it does not catch a
     * *wrong* breakpoint, and is not meant to.
     */
    @Test
    fun `nothing jumps where the backlight runs out`() {
        for (platform in platforms) {
            for (top in listOf(1.0f, 0.5f, 0.1f, 0.02f, platform.floor)) {
                for (lower in listOf(true, false)) {
                    val fall = platform.fall(top, lower)
                    val alphaStep = ln(fall) * (1.055f / 2.4f) / platform.clamp / 100f * 1.05f
                    val backlightStep = fall.pow(1f / 100f) * 1.001f
                    for (level in 1..100) {
                        val before = platform.values(DimSettings(level - 1, 0, lower), top)
                        val after = platform.values(DimSettings(level, 0, lower), top)
                        assertTrue(
                            "$platform top=$top lower=$lower: shadeAlpha jumped " +
                                "${after.shadeAlpha - before.shadeAlpha} at $level",
                            after.shadeAlpha - before.shadeAlpha <= alphaStep,
                        )
                        val a = before.backlight
                        val b = after.backlight
                        if (a != null && b != null) {
                            assertTrue(
                                "$platform top=$top lower=$lower: backlight jumped from $a to $b at $level",
                                a / b <= backlightStep,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * **The assertion that pins the ramp's shape**, and the one the fixed-breakpoint design could
     * not make: total light falls by the same ratio per point on both sides of the handover.
     */
    @Test
    fun `the rate is constant end to end`() {
        forEachRamp { platform, top, lower ->
            val effective = platform.effectiveTop(top, lower)
            val fall = platform.fall(top, lower)
            for (level in levels) {
                val values = platform.values(DimSettings(level, 0, lower), top)
                val expected = fall.pow(-level / 100f)
                val actual = platform.light(values, effective)
                assertEquals(
                    "$platform top=$top lower=$lower level=$level: light was $actual, not $expected",
                    expected.toDouble(),
                    actual.toDouble(),
                    RATE_EPSILON.toDouble(),
                )
            }
        }
    }

    /**
     * A dim level of zero is still *running* (CONTEXT.md). This is what that means physically: the
     * shade is transparent, the override is released, and the user's own brightness slider works.
     */
    @Test
    fun `dim level zero releases the override and draws nothing`() {
        forEachRamp { platform, top, lower ->
            val values = platform.values(DimSettings(0, 0, lower), top)
            assertNull("$platform top=$top lower=$lower held an override at dim 0", values.backlight)
            assertEquals("$platform top=$top lower=$lower drew a shade at dim 0", 0f, values.shadeAlpha, EPSILON)
        }
    }

    /**
     * One branch, proven. The toggle-off case and the no-reading case are the same expression with
     * `ratio = 1` rather than an `if`, and this is what says so.
     */
    @Test
    fun `no reading and the toggle off produce identical output`() {
        for (platform in platforms) {
            for (level in levels) {
                for (warmth in warmths) {
                    val off = platform.values(DimSettings(level, warmth, lowerBacklight = false), 1.0f)
                    val unread = platform.values(DimSettings(level, warmth, lowerBacklight = true), null)
                    assertEquals("$platform level=$level warmth=$warmth", off, unread)
                }
            }
        }
    }

    /** A top the user is already below is not a top: there is nothing to spend and no override. */
    @Test
    fun `a top below our own floor is spent as if there were none`() {
        for (platform in platforms) {
            for (level in levels) {
                val below = platform.values(DimSettings(level, 0, lowerBacklight = true), platform.floor / 2f)
                val unread = platform.values(DimSettings(level, 0, lowerBacklight = true), null)
                assertEquals("$platform level=$level", unread, below)
            }
        }
    }

    /**
     * The constants' own arithmetic, asserted once rather than swept — these are the inequalities
     * that let a later edit to one number be caught by the build instead of by a user.
     */
    @Test
    fun `the constants leave the room the invariants need`() {
        assertTrue(
            "WARMTH_EASE_FROM=$WARMTH_EASE_FROM is too high for MAX_WARMTH_ALPHA=$MAX_WARMTH_ALPHA",
            (1f - WARMTH_EASE_FROM) * (1f - MAX_WARMTH_ALPHA) >= 1f - MAX_SHADE_ALPHA - EPSILON,
        )
        assertTrue("MIN_BACKLIGHT must never be BRIGHTNESS_OVERRIDE_OFF", MIN_BACKLIGHT > 0f)
        assertTrue("MAX_SHADE_ALPHA must leave the user something to read", MAX_SHADE_ALPHA < 1f)
    }

    /**
     * **The shade model is anchored to the phone**, so the rate test above is not the model agreeing
     * with itself.
     *
     * `phase-3.md`'s R3 and R6 read the shade at dim 100 off `screencap` as 0.2393 and 0.2411 of the
     * page's stored value: that is `1 - WINDOW_ALPHA_CLAMP × MAX_SHADE_ALPHA`, and it pins the clamp.
     * The light that value emits is its sRGB decode, ≈ 0.047 — which is why the pair of readings meant
     * a darker shade than the `0.24` they looked like, and what this says in numbers. And no alpha
     * leaves no light behind: a transparent shade passes all of it.
     */
    @Test
    fun `the shade model is anchored to the phone`() {
        val storedAtCap = 1f - WINDOW_ALPHA_CLAMP * MAX_SHADE_ALPHA
        assertEquals("R3 and R6 read 0.2393-0.2411 at the cap", 0.240f, storedAtCap, 0.002f)
        assertEquals("light at the cap", 0.047f, shadeTransmission(MAX_SHADE_ALPHA), 0.001f)
        assertEquals("a transparent shade", 1f, shadeTransmission(0f), EPSILON)
        assertEquals(
            "light at the cap with no clamp, below Android 12",
            0.0039f,
            shadeTransmission(MAX_SHADE_ALPHA, 1f),
            0.0001f,
        )
    }

    /**
     * **The veil invariant, which the signal bound cannot see.**
     *
     * Source-over is `w x tint + (1 - w) x content`: the tint does not only attenuate what is
     * underneath, it lays light *on top of* it, and how much depends on the tint's own luminance. A
     * bright amber passes every other assertion in this file and produces a screen nothing can be
     * read through — `#FFB000` at half alpha is four times more veil than content — so the second
     * bound says the tint may never add more light than the black child was allowed to leave.
     *
     * Swept over every warmth colour rather than asserted on one constant, because since the colour bar
     * the tint is an input: whatever the handle says is what gets painted.
     */
    @Test
    fun `no warmth colour adds more light than the black layer was allowed to leave`() {
        for (warmthColor in 0..100) {
            val luminance = relativeLuminance(warmthTint(warmthColor))
            val veil = MAX_WARMTH_ALPHA * luminance
            assertTrue(
                "warmth colour $warmthColor has luminance $luminance, so it veils $veil " +
                    "against a bound of ${1f - MAX_SHADE_ALPHA}",
                veil <= 1f - MAX_SHADE_ALPHA,
            )
        }
    }

    /** The bar's two ends are the two constants, so the constants are what the rest of this file proves. */
    @Test
    fun `the warmth colour runs from the amber to the red`() {
        assertEquals("0 is the amber", SHADE_AMBER, warmthTint(0))
        assertEquals("100 is the red", SHADE_RED, warmthTint(100))
        assertEquals("below the range clamps", SHADE_AMBER, warmthTint(-5))
        assertEquals("above the range clamps", SHADE_RED, warmthTint(150))
    }

    /**
     * **The colour bar moves the hue and not the veil**, which is the property the blend in linear light
     * was chosen for. Within 2% end to end, and the slack is only bytes being rounded. A blend of the
     * stored bytes would fail this in the middle, which is what it is here to catch.
     */
    @Test
    fun `every warmth colour carries the same light`() {
        val reference = relativeLuminance(SHADE_AMBER)
        assertEquals("the ends were picked to match", reference, relativeLuminance(SHADE_RED), reference * 0.02f)
        for (warmthColor in 0..100) {
            assertEquals(
                "warmth colour $warmthColor",
                reference.toDouble(),
                relativeLuminance(warmthTint(warmthColor)).toDouble(),
                reference * 0.02,
            )
        }
    }

    /** Redder all the way along: the green share never rises, and the tint never picks up blue. */
    @Test
    fun `the warmth colour only ever gets redder`() {
        var previous = Float.MAX_VALUE
        for (warmthColor in 0..100) {
            val tint = warmthTint(warmthColor)
            val red = tint shr 16 and 0xFF
            val green = tint shr 8 and 0xFF
            val share = green.toFloat() / red
            assertTrue("warmth colour $warmthColor turned greener", share <= previous)
            assertEquals("warmth colour $warmthColor carries blue", 0, tint and 0xFF)
            assertEquals("warmth colour $warmthColor is not opaque", 0xFF, tint ushr 24)
            previous = share
        }
    }

    /**
     * The luminance the bound above is measured with, checked against its two fixed points.
     *
     * Without this, a [relativeLuminance] that returned zero would pass the veil invariant for every
     * colour there is — including the ones it exists to reject.
     */
    @Test
    fun `relative luminance is anchored at black and white`() {
        assertEquals("black", 0.0, relativeLuminance(0xFF000000.toInt()).toDouble(), EPSILON.toDouble())
        assertEquals("white", 1.0, relativeLuminance(0xFFFFFFFF.toInt()).toDouble(), EPSILON.toDouble())
    }

    /**
     * **Why the shade's amber cannot come from `MaterialTheme`**, as a number rather than as three
     * sentences of reasoning.
     *
     * The palette's own seed is the colour somebody would reach for if they were tidying away the
     * house-rule exception in `ShadeRamp.kt`. It fails the bound above by more than twice, and this
     * is what says so before a user finds out by looking at an unreadable screen.
     */
    @Test
    fun `the brand seed could not be used as the shade amber`() {
        val duskAmber = 0xFFB0763C.toInt()
        assertTrue(
            "B0763C now passes the veil bound; the exception in ShadeRamp.kt needs re-reading",
            MAX_WARMTH_ALPHA * relativeLuminance(duskAmber) > 1f - MAX_SHADE_ALPHA,
        )
    }

    /** The worst composite over the grid is *equality*, so this pins the epsilon's necessity. */
    @Test
    fun `the tightest composite on the grid is exactly the bound`() {
        var worst = 1f
        sweep { _, _, values ->
            worst = minOf(worst, (1f - values.shadeAlpha) * (1f - values.warmthAlpha))
        }
        assertEquals(1f - MAX_SHADE_ALPHA, worst, EPSILON)
    }
}

/** Float arithmetic over a 101-point ramp; anything larger would hide a real fencepost. */
private const val EPSILON = 1e-5f

/**
 * Looser than [EPSILON] because it compares an accumulated `pow` against a reconstructed one — the
 * two reach the same number by different routes, and single-precision does not promise the last bit.
 */
private const val RATE_EPSILON = 1e-4f
