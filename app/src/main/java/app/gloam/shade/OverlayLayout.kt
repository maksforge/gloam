package app.gloam.shade

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.doOnPreDraw
import kotlin.math.max

/*
 * The overlay windows' geometry on every level `minSdk` 26 reaches (ADR-0008's amendment).
 *
 * **The branches are the platform's, not ours.** Android 11 replaced how a window learns where the
 * system bars are — `WindowMetrics` for reading them, `fitInsetsTypes` for laying out around them —
 * and below it only the older `Display` reads and layout flags exist. Each function here answers one
 * question in both vocabularies, so the windows themselves carry no `SDK_INT` checks.
 */

/** The whole display in its current rotation, nothing taken off for the system bars. */
fun WindowManager.displayBounds(): Rect =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        val size = Point().also { defaultDisplay.getRealSize(it) }
        Rect(0, 0, size.x, size.y)
    }

/**
 * The app's area of the display, in display pixels: the bounds minus the system bars and the cutout.
 *
 * Below Android 11 `Display.getSize` is the display minus the navigation bar only, so the status bar
 * comes off separately, and a navigation bar on the left is the one rotation where the area does not
 * start at `x = 0`. Approximate around a cutout on 9 and 10, which is the safe direction: the area is
 * always inside the display, and the bubble is clamped to it.
 */
fun WindowManager.appArea(): Rect {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = currentWindowMetrics
        val insets =
            metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        val bounds = metrics.bounds
        return Rect(insets.left, insets.top, bounds.width() - insets.right, bounds.height() - insets.bottom)
    }
    @Suppress("DEPRECATION")
    val display = defaultDisplay
    val real = Point().also { display.getRealSize(it) }
    val app = Point().also { display.getSize(it) }
    val left = if (display.rotation == Surface.ROTATION_270) real.x - app.x else 0
    return Rect(left, statusBarHeightPx(), left + app.x, app.y)
}

@SuppressLint("DiscouragedApi", "InternalInsetResource")
private fun statusBarHeightPx(): Int {
    val resources = Resources.getSystem()
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id == 0) 0 else resources.getDimensionPixelSize(id)
}

/**
 * Size the shade to cover the whole display, navigation bar included.
 *
 * **`MATCH_PARENT` is not enough below Android 11**: there it fills the parent frame, which the
 * platform keeps clear of the navigation bar for an overlay, and the bar's strip stayed bright —
 * measured on the API-26 emulator, a frame ending at 1794 of 1920 px. An explicit size is only
 * clipped to the display frame, which `FLAG_LAYOUT_NO_LIMITS` lifts, so it reaches the bottom edge.
 * A square on the longest edge covers either rotation without a resize; the shade is untouchable,
 * so the part past the display costs nothing.
 */
fun WindowManager.LayoutParams.fillDisplay(manager: WindowManager) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
    } else {
        val side = manager.shadeSidePx()
        width = side
        height = side
    }
    useDisplayFrame()
}

/** The pre-Android-11 shade's side, from [fillDisplay]. */
fun WindowManager.shadeSidePx(): Int = displayBounds().let { max(it.width(), it.height()) }

/**
 * Lay the window out in display pixels, system bars and cutout included — the shade, and the
 * bubble's frame. Needs `FLAG_LAYOUT_IN_SCREEN` and `FLAG_LAYOUT_NO_LIMITS` in the flags, which
 * below Android 11 are the whole of the mechanism.
 *
 * The cutout is reachable from Android 9 and only at its short edges until 11. On 8 the platform
 * has no cutout API, and a notch strip may stay undimmed there.
 */
fun WindowManager.LayoutParams.useDisplayFrame() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}

/**
 * Lay the window out clear of the status and navigation bars — the panel and the prompt, which are
 * controls and must not sit under a bar. Below Android 11 that is the default for a window *without*
 * `FLAG_LAYOUT_IN_SCREEN`, so the flag comes off there.
 */
fun WindowManager.LayoutParams.fitSystemBars() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        fitInsetsTypes = WindowInsets.Type.systemBars()
    } else {
        flags = flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
    }
}

/**
 * Run [action] once, after the view's next frame has been drawn. Android 10 can say when that frame
 * reached the surface; below it, a post from the pre-draw pass runs after the draw that follows it,
 * which can be a frame early.
 */
fun View.doOnFrameShown(action: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        viewTreeObserver.registerFrameCommitCallback { action() }
    } else {
        doOnPreDraw { post { action() } }
    }
}
