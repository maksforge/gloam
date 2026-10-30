package app.gloam.ui.dim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The column control's arithmetic, swept rather than sampled.
 *
 * The redesign replaced two M3 sliders — components whose maths somebody else tests — with a
 * rectangle this app draws and reads finger positions off. Everything a `Slider` used to guarantee
 * is now a function in `ColumnGeometry.kt`, and the ways these can be wrong are all invisible in a
 * screenshot: an end the finger cannot reach, a percentage that lands half on the shade and half on
 * the plate, a bar taller than the display it is drawn on.
 *
 * Same shape as `ShadeRampTest` and `PanelWidthTest`: pure functions, every plausible input, on the
 * JVM. There is no Android and no Compose in this file, which is the point of there being none in
 * the file it tests.
 */
class ColumnGeometryTest {
    /** From a very short track (a landscape panel) to a very tall one (a tablet's full column). */
    private val plausibleTracks = listOf(120f, 258f, 326f, 400f, 774f, 978f, 1200f, 2400f)

    @Test
    fun `the top of the track is zero and the bottom is a hundred`() {
        for (track in plausibleTracks) {
            assertEquals("A finger at the top of a ${track}px track", 0, levelAt(0f, track))
            assertEquals("A finger at the bottom of a ${track}px track", 100, levelAt(track, track))
        }
    }

    /**
     * The whole range has to be reachable by a finger that is one pixel out, or the two ends — off
     * and fully dark — are the two values the control cannot set.
     */
    @Test
    fun `both ends are reachable from a pixel inside them`() {
        for (track in plausibleTracks) {
            assertEquals("One pixel below the top of a ${track}px track", 0, levelAt(0.4f, track))
            assertEquals("One pixel above the bottom of a ${track}px track", 100, levelAt(track - 0.4f, track))
        }
    }

    @Test
    fun `a finger outside the column is clamped rather than wrapped`() {
        for (track in plausibleTracks) {
            for (y in listOf(-10_000f, -1f, track + 1f, track * 3, 10_000f)) {
                val level = levelAt(y, track)
                assertTrue("levelAt($y, $track) = $level, outside 0..100", level in 0..100)
            }
        }
    }

    /** Down is darker, every step of the way. A single inversion would be a control that fights back. */
    @Test
    fun `the level never falls as the finger moves down`() {
        for (track in plausibleTracks) {
            var previous = 0
            var y = 0f
            while (y <= track) {
                val level = levelAt(y, track)
                assertTrue("levelAt fell from $previous to $level at y=$y on a ${track}px track", level >= previous)
                previous = level
                y += 0.5f
            }
        }
    }

    /** Warmth reads the same rectangle the other way up, and the two must agree about the middle. */
    @Test
    fun `warmth is the level inverted`() {
        for (track in plausibleTracks) {
            var y = 0f
            while (y <= track) {
                assertEquals(100 - levelAt(y, track), warmthAt(y, track))
                y += 1f
            }
            assertEquals("The top of a ${track}px warmth column is warmest", 100, warmthAt(0f, track))
            assertEquals("The bottom of a ${track}px warmth column is coolest", 0, warmthAt(track, track))
        }
    }

    /**
     * A track of no height happens for one frame — a window measured before its content — and the
     * answer has to be a number rather than an exception, because the exception would be thrown
     * inside the foreground service that owns the shade.
     */
    @Test
    fun `a degenerate track answers rather than dividing by zero`() {
        for (track in listOf(-1f, 0f)) {
            assertEquals(0, levelAt(50f, track))
            assertEquals(0f, coveredPx(50, track), 0f)
        }
        assertEquals(0, levelAt(Float.NaN, 326f))
        assertEquals(0, levelAt(Float.POSITIVE_INFINITY, 326f))
    }

    @Test
    fun `the shade covers none of the plate at zero and all of it at a hundred`() {
        for (track in plausibleTracks) {
            assertEquals(0f, coveredPx(0, track), 0.001f)
            assertEquals(track, coveredPx(100, track), 0.001f)
            // And a level out of range cannot make the shade taller than the track it is drawn in.
            assertEquals(track, coveredPx(150, track), 0.001f)
            assertEquals(0f, coveredPx(-20, track), 0.001f)
        }
    }

    /**
     * The percentage is never half-covered: it sits wholly on the shade or wholly on the plate, so
     * it needs no scrim and stays legible at every level. This is the assertion that the ink and the
     * position always agree — a placement that reported `onShade` while sitting on the plate would
     * be dark ink on a dark ground, which is a number nobody can read at two nits.
     */
    @Test
    fun `the percentage sits clear of the shade edge on whichever side it claims`() {
        val flip = 78f
        val above = 38f
        val below = 44f
        val height = 30f // roughly what a 26sp number occupies

        var covered = 0f
        while (covered <= 258f) {
            val placement = valuePlacement(covered, flip, above, below)
            if (placement.onShade) {
                assertTrue(
                    "At ${covered}px covered the number claims to be on the shade but its bottom " +
                        "edge (${placement.topPx + height}) is past the edge at $covered",
                    placement.topPx + height <= covered,
                )
                assertTrue("At ${covered}px covered the number is off the top of the bar", placement.topPx >= 0f)
            } else {
                assertTrue(
                    "At ${covered}px covered the number claims to be on the plate but its top " +
                        "edge (${placement.topPx}) is above the edge at $covered",
                    placement.topPx >= covered,
                )
            }
            covered += 0.5f
        }
    }

    /** Below the flip the number must clear the pip row, which owns the top of the bar. */
    @Test
    fun `the percentage never prints over the pips`() {
        var covered = 0f
        while (covered < BAR_VALUE_FLIP_DP) {
            val placement = valuePlacement(covered, BAR_VALUE_FLIP_DP, BAR_VALUE_ABOVE_DP, BAR_VALUE_BELOW_DP)
            assertFalse("The number went to shade ink below the flip", placement.onShade)
            assertTrue(
                "At ${covered}dp covered the number starts at ${placement.topPx}dp, inside the " +
                    "${BAR_PIP_ROW_DP}dp pip row",
                placement.topPx >= BAR_PIP_ROW_DP,
            )
            covered += 0.5f
        }
    }

    /**
     * The bar has to fit the surface it is drawn on, foot band and buttons included — a foot band
     * off the bottom of the screen is the one control that ends the dimming, unreachable.
     */
    @Test
    fun `the bar and its buttons fit the height they are given`() {
        for (wanted in 3..4) {
            var available = BAR_MIN_HEIGHT_DP + BAR_GROUP_GAP_DP + BAR_ICON_BUTTON_DP
            while (available <= 1400f) {
                val stack = compactStack(available, wanted)
                val bar = stack.barHeightDp
                assertTrue("A ${bar}dp bar on ${available}dp is taller than the design's maximum", bar <= BAR_HEIGHT_DP)
                assertTrue("A ${bar}dp bar is below the usable floor", bar >= BAR_MIN_HEIGHT_DP)
                assertTrue(
                    "The bar and ${stack.buttons} buttons need ${compactStackHeightDp(bar, stack.buttons)}dp " +
                        "of the ${available}dp on offer",
                    compactStackHeightDp(bar, stack.buttons) <= available + 0.001f,
                )
                assertTrue(
                    "The timer section may grow to ${sectionMaxHeightDp(bar)}dp beside a ${bar}dp bar",
                    sectionMaxHeightDp(bar) <= bar,
                )
                available += 1f
            }
        }
    }

    /**
     * The close row is the panel's first way out (ADR-0011), so it survives a landscape display that
     * has the bar at its floor: the buttons above it go first, and never all of it.
     */
    @Test
    fun `the close row fits even with the bar at its floor`() {
        var available = BAR_MIN_HEIGHT_DP + BAR_GROUP_GAP_DP + BAR_ICON_BUTTON_DP
        while (available <= 1400f) {
            val stack = compactStack(available, 4)
            assertTrue("No close row on ${available}dp", stack.buttons >= 1)
            assertTrue(
                "The close row ends at ${compactStackHeightDp(stack.barHeightDp, stack.buttons)}dp, " +
                    "past the ${available}dp on offer",
                compactStackHeightDp(stack.barHeightDp, stack.buttons) <= available + 0.001f,
            )
            if (available >= compactStackHeightDp(BAR_MIN_HEIGHT_DP, 4)) {
                assertEquals("A button was dropped on ${available}dp, which has room for all four", 4, stack.buttons)
            }
            available += 0.5f
        }
    }

    /** The draggable track is the bar minus its foot band, and never a negative number. */
    @Test
    fun `the track is what is left above the foot band`() {
        for (bar in listOf(0f, 50f, BAR_FOOT_HEIGHT_DP, 200f, 330f, 900f)) {
            val track = barTrackHeightDp(bar)
            assertTrue("A ${bar}dp bar gave a ${track}dp track", track >= 1f)
            if (bar > BAR_FOOT_HEIGHT_DP + 1f) {
                assertEquals(bar - BAR_FOOT_HEIGHT_DP, track, 0.001f)
            }
        }
    }
}
