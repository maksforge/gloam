package app.gloam.ui.dim

import kotlin.math.roundToInt

/*
 * The column control's arithmetic, with no Compose and no Android under it.
 *
 * **Why a separate file at all.** The column is not a stock M3 component: it is a rectangle the user
 * drags, and every number that decides what their finger means — where the shade edge lands, which
 * ink the percentage takes, how far a press may slide before it stops being a tap — is a
 * calculation rather than a value someone set. `PanelWidthTest` already made the argument for the
 * panel's width (a flag is a constant, a size is a computation, and only one of those can be read
 * off the source), and this file is the same argument applied to the control the redesign puts in
 * front of everything else. `ColumnGeometryTest` sweeps it.
 *
 * Everything here is in **pixels or plain numbers**. The `*_DP` constants below are `Float`s rather
 * than `Dp`s for the same reason: a unit test that has to construct a `Density` to ask what 40dp
 * means is testing Compose, and the composables convert with `.dp` at the one place they draw.
 *
 * Kotlin note: these are top-level functions, not methods on some `Column` class. There is no state
 * to hang them off — each takes what it needs and returns a number, which is also what makes them
 * cheap to sweep across every input the phone could hand them.
 */

// ── The full screen's column (design 2a / 6a) ────────────────────────────────────────────────────

/** The column's own box. The whole 326dp is draggable; there is no dead zone on this one. */
const val DIM_COLUMN_WIDTH_DP = 124f
const val DIM_COLUMN_HEIGHT_DP = 326f
const val DIM_COLUMN_RADIUS_DP = 34f

/** The shade edge: the one line in the control that has to stay crisp at every level. */
const val COLUMN_EDGE_DP = 3f

/** The grip on the edge. Centred on it, not below it — the edge is what it moves. */
const val DIM_HANDLE_WIDTH_DP = 56f
const val COLUMN_HANDLE_HEIGHT_DP = 6f

/** "FLOOR", sitting on the part of the plate the shade can never reach. */
const val COLUMN_FOOTER_INSET_DP = 14f

// ── The compact edge bar (design 4a / 6b) ────────────────────────────────────────────────────────

const val BAR_WIDTH_DP = 84f
const val BAR_HEIGHT_DP = 330f
const val BAR_RADIUS_DP = 42f

/**
 * The bar's bottom band: run/stop, and **not part of the draggable range**.
 *
 * Opaque on purpose (the design's word). It is the only control on this surface whose contrast must
 * hold at every dim level, because it is the one that ends the dimming.
 */
const val BAR_FOOT_HEIGHT_DP = 72f

/** The top of the bar: three pips, and the only route from here to the full screen. */
const val BAR_PIP_ROW_DP = 40f

const val BAR_HANDLE_WIDTH_DP = 42f

/**
 * How far a press may slide before it stops being a tap and becomes a level drag.
 *
 * The foot band and the pip row are both tap targets *inside* a control whose whole job is dragging,
 * so each of them needs an answer to "was that a tap or the start of a drag?". Ten pixels is the
 * design's number and it is deliberately smaller than Compose's own touch slop: this is not "did the
 * user mean to scroll", it is "did the thumb move at all".
 */
const val BAR_TAP_SLOP_DP = 10f

/** Between the bar, the buttons under it, and a section opened beside it. */
const val BAR_GROUP_GAP_DP = 10f

const val BAR_ICON_BUTTON_DP = 52f

/**
 * The widest a disclosure may open beside the bar.
 *
 * It is a bound rather than a measurement, and the panel's window width is computed from it: a chip
 * reading *2 godziny* is wider than *2 hours*, and the window a translation grows is a window whose
 * touch-blocking area no test can see. Content wider than this wraps.
 */
const val BAR_SECTION_WIDTH_DP = 140f

/** The warmth column the compact surface opens to the left of the bar. Up is warmer. */
const val WARMTH_COLUMN_WIDTH_DP = 60f
const val WARMTH_COLUMN_HEIGHT_DP = 300f
const val WARMTH_COLUMN_RADIUS_DP = 30f

/**
 * Where the percentage sits relative to the shade edge, and the threshold at which it jumps.
 *
 * [BAR_VALUE_ABOVE_DP] lifts it clear of the edge when there is room above; [BAR_VALUE_BELOW_DP]
 * drops it under the pip row when there is not. The threshold is the pip row plus the number's own
 * height, which is why it is 78 rather than the 48 a bar without pips would use — at 48 the number
 * prints over the pips.
 */
const val BAR_VALUE_ABOVE_DP = 38f
const val BAR_VALUE_BELOW_DP = 44f
const val BAR_VALUE_FLIP_DP = 78f

// ── The arithmetic ───────────────────────────────────────────────────────────────────────────────

/**
 * The level a finger at [y] is asking for, on a track [trackHeightPx] tall. **Down is darker.**
 *
 * Rounded and clamped, so the two ends are reachable without precision: the top eighth of a pixel
 * is 0 and the bottom eighth is 100, rather than 0.4 and 99.6 which no finger can land on.
 *
 * A degenerate track answers 0 rather than dividing by zero. It can happen for one frame — a window
 * measured before its content — and a crash there would take the shade's own service down with it.
 */
fun levelAt(
    y: Float,
    trackHeightPx: Float,
): Int {
    if (trackHeightPx <= 0f || !y.isFinite()) return 0
    return ((y / trackHeightPx) * 100f).roundToInt().coerceIn(0, 100)
}

/**
 * The same track read the other way up, for warmth: **up is warmer.**
 *
 * A separate function rather than a `Boolean` flag on [levelAt], because the two are different
 * questions asked of the same rectangle and a call site reading `levelAt(y, h, inverted = true)`
 * says nothing about which end is which.
 */
fun warmthAt(
    y: Float,
    trackHeightPx: Float,
): Int = 100 - levelAt(y, trackHeightPx)

/** How much of the track the shade covers at [level]. The edge, the handle and the ink all follow it. */
fun coveredPx(
    level: Int,
    trackHeightPx: Float,
): Float {
    if (trackHeightPx <= 0f) return 0f
    return trackHeightPx * level.coerceIn(0, 100) / 100f
}

/**
 * Where the compact bar's percentage goes, and which ink it takes.
 *
 * **It is never half-covered**, which is the whole point: it sits either wholly above the shade edge
 * in light ink or wholly below it in dark ink on the bare plate, so it needs no scrim behind it and
 * stays legible at every level. [onShade] is what the caller colours it by.
 *
 * @param flipPx the covered height at which the number moves above the edge. Below it there is not
 *   enough shade to sit on.
 */
data class ValuePlacement(
    val topPx: Float,
    val onShade: Boolean,
)

fun valuePlacement(
    coveredPx: Float,
    flipPx: Float,
    abovePx: Float,
    belowPx: Float,
): ValuePlacement =
    if (coveredPx >= flipPx) {
        ValuePlacement(topPx = coveredPx - abovePx, onShade = true)
    } else {
        ValuePlacement(topPx = coveredPx + belowPx, onShade = false)
    }

/**
 * How tall to draw the bar on a surface with [availableHeightDp] to give it, under [buttons] round
 * buttons.
 *
 * **The design's 330dp is a maximum, not a measurement.** The compact controls are drawn over
 * whatever the user is in, on whatever display they have — a phone in landscape has barely 360dp of
 * height, and a bar that does not fit is a bar whose foot band, the one control that stops the
 * dimming, is off the bottom of the screen. So the height is computed from the room there is, and
 * the floor is the point below which the track is too short to set a level with; below *that* the
 * control would be a picture again.
 *
 * [buttons] is a parameter rather than a constant because [compactStack] drops buttons that do not
 * fit, and each one it drops is 62dp the bar gets back.
 */
fun barHeightDp(
    availableHeightDp: Float,
    buttons: Int,
): Float {
    val underIt = compactStackHeightDp(0f, buttons.coerceAtLeast(0))
    return (availableHeightDp - underIt).coerceIn(BAR_MIN_HEIGHT_DP, BAR_HEIGHT_DP)
}

/**
 * The shortest bar still worth touching: the foot band, plus a track long enough that one dp of
 * finger is not four levels.
 */
const val BAR_MIN_HEIGHT_DP = 200f

/**
 * The bar's height and how many of the [buttons] wanted under it are drawn, on [availableHeightDp].
 *
 * **The last button is the panel's close, and it is never the one that goes** (ADR-0011): once the
 * bar is at its floor the group stops shrinking, so on a short landscape display the buttons are
 * dropped from the one above the close upward until the group fits. Somewhere a button has to go,
 * and every one of them but the close is also on the full screen.
 */
data class CompactStack(
    val barHeightDp: Float,
    val buttons: Int,
)

fun compactStack(
    availableHeightDp: Float,
    buttons: Int,
): CompactStack {
    var shown = buttons.coerceAtLeast(0)
    while (shown > 1 && compactStackHeightDp(barHeightDp(availableHeightDp, shown), shown) > availableHeightDp) {
        shown--
    }
    return CompactStack(barHeightDp(availableHeightDp, shown), shown)
}

/** The bar and [buttons] rows under it, each row a gap and a button. */
fun compactStackHeightDp(
    barHeightDp: Float,
    buttons: Int,
): Float = barHeightDp + (BAR_GROUP_GAP_DP + BAR_ICON_BUTTON_DP) * buttons

/** The draggable part of a bar [barHeightDp] tall — everything above the foot band. */
fun barTrackHeightDp(barHeightDp: Float): Float = (barHeightDp - BAR_FOOT_HEIGHT_DP).coerceAtLeast(1f)
