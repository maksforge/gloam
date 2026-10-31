package app.gloam.shade

import android.os.Build
import android.view.WindowManager

/**
 * `dumpsys window windows` in both of the shapes it takes between `minSdk` and `targetSdk`.
 *
 * Android 8 prints a window's type and flags as numbers (`ty=2038 fl=#1000318`) and its frame as
 * `mFrame=`; later releases print names (`ty=APPLICATION_OVERLAY fl=NOT_FOCUSABLE NOT_TOUCHABLE …`)
 * and `frame=` on the `Frames:` line. Measured on the API-26 emulator and the phone.
 */
internal object OverlayDump {
    private val OVERLAY_TYPE =
        Regex("""ty=(APPLICATION_OVERLAY|${WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY})\b""")
    private val HEX_FLAGS = Regex("""\bfl=#([0-9a-f]+)""")

    /**
     * The `[l,t][r,b]` of the window's frame. The leading space keeps the modern spelling off
     * `parent=` and `display=`, which sit on the same line and end in the same shape.
     */
    val FRAME = Regex("""(?: frame|mFrame)=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    /** One of [packageName]'s `TYPE_APPLICATION_OVERLAY` windows, rather than one of its activities'. */
    fun isOurOverlay(
        block: String,
        packageName: String,
    ): Boolean = packageName in block && OVERLAY_TYPE.containsMatchIn(block)

    /** Whether the window carries [flag], printed as [name] or inside a hex `fl=#…`. */
    fun hasFlag(
        block: String,
        name: String,
        flag: Int,
    ): Boolean =
        HEX_FLAGS.find(block)?.let { it.groupValues[1].toLong(16) and flag.toLong() != 0L }
            ?: (name in block)

    /** The shade's requested size as `mAttrs` prints it — see [fillDisplay] for why it differs. */
    fun shadeLayout(manager: WindowManager): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "fillxfill"
        } else {
            val side = manager.shadeSidePx()
            "(${side}x$side)"
        }
}
