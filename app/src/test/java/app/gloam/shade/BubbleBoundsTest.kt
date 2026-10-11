package app.gloam.shade

import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble is touchable and has no timeout, so its size and where it may sit are the whole bound
 * (ADR-0016). Swept the way `PanelWidthTest` sweeps the panel.
 */
class BubbleBoundsTest {
    private val plausibleDisplays = 240..4096
    private val plausibleDensities = listOf(0.75f, 1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)

    @Test
    fun `the bubble is never more than a corner of the display`() {
        for (display in plausibleDisplays) {
            for (density in plausibleDensities) {
                val side = bubbleSidePx(BUBBLE_SIZE_DP, density, display)
                assertTrue("bubbleSidePx($BUBBLE_SIZE_DP, $density, $display) = $side", side in 1..display / 4)
            }
        }
    }

    @Test
    fun `the bubble is its designed size on an ordinary phone`() {
        assertEquals(168, bubbleSidePx(BUBBLE_SIZE_DP, 3f, 1220))
    }

    @Test
    fun `no drag can leave the bubble outside the app's area`() {
        val area = BubbleArea(left = 0, top = 130, right = 1220, bottom = 2664)
        val width = 168
        val height = 120
        for (x in -5000..5000 step 37) {
            for (y in -5000..5000 step 41) {
                val p = clampBubble(BubblePosition(x, y), width, height, area)
                assertTrue("x=${p.x} for wanted $x", p.x >= area.left && p.x + width <= area.right)
                assertTrue("y=${p.y} for wanted $y", p.y >= area.top && p.y + height <= area.bottom)
            }
        }
    }

    @Test
    fun `a position already inside the area is left alone`() {
        val area = BubbleArea(0, 100, 1000, 2000)
        assertEquals(BubblePosition(400, 900), clampBubble(BubblePosition(400, 900), 100, 80, area))
    }

    @Test
    fun `an area smaller than the bubble pins it to the top-left rather than throwing`() {
        val area = BubbleArea(10, 20, 50, 60)
        assertEquals(BubblePosition(10, 20), clampBubble(BubblePosition(500, 500), 100, 80, area))
    }

    private fun onScreenWidth(
        x: Int,
        size: Int,
        area: BubbleArea,
    ) = minOf(x + size, area.right) - maxOf(x, area.left)

    @Test
    fun `a released bubble always rests tucked into a side edge with the peek showing`() {
        val area = BubbleArea(left = 0, top = 130, right = 1220, bottom = 2664)
        val size = 168
        val peek = 96
        for (x in -3000..3000 step 29) {
            for (y in -3000..5000 step 97) {
                val (p, side) = tuckToEdge(BubblePosition(x, y), size, area, peek)
                assertEquals("on-screen width for released $x", peek, onScreenWidth(p.x, size, area))
                val touchesEdge = if (side == BubbleSide.Left) p.x < area.left else p.x + size > area.right
                assertTrue("side $side for x=${p.x}", touchesEdge)
                assertTrue("y=${p.y} for released $y", p.y >= area.top && p.y + size <= area.bottom)
            }
        }
    }

    @Test
    fun `the side it tucks into is the one the projected centre is nearer`() {
        val area = BubbleArea(0, 0, 1000, 2000)
        assertEquals(BubblePosition(-60, 500) to BubbleSide.Left, tuckToEdge(BubblePosition(300, 500), 100, area, 40))
        assertEquals(BubblePosition(960, 500) to BubbleSide.Right, tuckToEdge(BubblePosition(500, 500), 100, area, 40))
    }

    @Test
    fun `no drag leaves less than the peek on screen`() {
        val area = BubbleArea(left = 0, top = 130, right = 1220, bottom = 2664)
        val size = 168
        val peek = 96
        val dragArea = bubbleDragArea(area, size, peek)
        for (x in -5000..5000 step 31) {
            val p = clampBubble(BubblePosition(x, 1000), size, size, dragArea)
            assertTrue("on-screen width at x=${p.x}", onScreenWidth(p.x, size, area) >= peek)
        }
    }

    @Test
    fun `the drop zone is a circle around the target's centre`() {
        assertTrue(overDismissTarget(100f, 100f, 100f, 100f, 50f))
        assertTrue(overDismissTarget(130f, 140f, 100f, 100f, 50f))
        assertFalse(overDismissTarget(140f, 140f, 100f, 100f, 50f))
    }

    @Test
    fun `the prompt never spans the display`() {
        for (display in plausibleDisplays) {
            for (density in plausibleDensities) {
                val width = promptWidthPx(display, density)
                assertTrue("promptWidthPx($display, $density) = $width", width in 1 until display)
            }
        }
    }

    @Test
    fun `the bubble catches touches and never takes focus`() {
        assertEquals(0, BUBBLE_WINDOW_FLAGS and FLAG_NOT_TOUCHABLE)
        assertTrue(BUBBLE_WINDOW_FLAGS and FLAG_NOT_FOCUSABLE != 0)
        assertEquals(
            "a tap elsewhere must not take the bubble away — it is what is left after one",
            0,
            BUBBLE_WINDOW_FLAGS and FLAG_WATCH_OUTSIDE_TOUCH,
        )
    }

    @Test
    fun `the dismiss target passes every touch through`() {
        assertTrue(DISMISS_TARGET_WINDOW_FLAGS and FLAG_NOT_TOUCHABLE != 0)
        assertTrue(DISMISS_TARGET_WINDOW_FLAGS and FLAG_NOT_FOCUSABLE != 0)
    }
}
