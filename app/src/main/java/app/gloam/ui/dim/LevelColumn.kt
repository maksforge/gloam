package app.gloam.ui.dim

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gloam.R
import app.gloam.shade.warmthTint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **The control the redesign is about: a rectangle of light with a shade pulled down over it.**
 *
 * A slider says *how far along a range are you*. This says *how much of the light is covered*, which
 * is the same number and a completely different picture — and the picture is the point, because the
 * thing being set is not an abstract level, it is how much of the screen's own light survives. The
 * user drags the shade edge; down is darker.
 *
 * ## Why it is hand-drawn rather than a themed `Slider`
 *
 * M3's `Slider` is a track, a thumb and a value. Every part of what makes this legible in the dark —
 * that the covered part is *covering* something, that the edge is a hard line, that the bottom of
 * the column is a floor the shade cannot pass — is outside what a `Slider` can be styled into. What
 * it costs is the accessibility a stock control gives for free, so that is added back by hand below.
 *
 * @param trackHeight how much of [height] the shade may cover. The full-screen column hands its
 *   whole height; the compact bar keeps its foot band out of the range, which is the only reason
 *   this is a parameter rather than [height] itself.
 * @param content drawn inside the column, above the shade — the percentage, the pips, "FLOOR".
 *   A `BoxScope` so the caller can align its own pieces without this knowing what they are.
 */
@Composable
fun ShadeColumn(
    level: Int,
    width: Dp,
    height: Dp,
    corner: Dp,
    handleWidth: Dp,
    trackHeight: Dp,
    modifier: Modifier = Modifier,
    colors: ColumnColors = columnColors(),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(corner)
    val covered = trackHeight * (level.coerceIn(0, 100) / 100f)
    val edge = colors.edge

    Box(
        modifier =
            modifier
                .size(width, height)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(colors.plateTop, colors.plateBottom)))
                .border(1.dp, colors.plateOutline, shape),
    ) {
        // The shade itself. `fillMaxWidth` and an explicit height, top-aligned: it grows downward
        // from the top edge, which is what "pull it down" has to mean for the gesture to read.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(covered)
                    .background(Brush.verticalGradient(listOf(colors.coveredTop, colors.coveredBottom))),
        )

        // The shade edge, and the handle centred *on* it. Both are skipped at zero, where there is
        // no shade for an edge to belong to — an edge line floating at the top of an uncovered
        // column reads as a control that is already set to something.
        if (covered > 0.dp) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .offset(y = covered - COLUMN_EDGE_DP.dp)
                        .height(COLUMN_EDGE_DP.dp)
                        .background(edge),
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = covered - (COLUMN_HANDLE_HEIGHT_DP / 2).dp)
                        .size(handleWidth, COLUMN_HANDLE_HEIGHT_DP.dp)
                        // Compose has no blurred glow; an elevation shadow tinted with the edge
                        // colour is the closest thing the platform draws, and it is what gives the
                        // handle the lift the design asks for without a bitmap.
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(percent = 50),
                            ambientColor = edge,
                            spotColor = edge,
                        ).background(edge, RoundedCornerShape(percent = 50)),
            )
        }

        content()
    }
}

/**
 * Pointer-down anywhere sets the level, and the same press continues as a drag.
 *
 * **No touch slop, deliberately.** Compose's drag detectors wait for the finger to travel far enough
 * to be sure it is not a tap; here a tap *is* a value, so waiting would mean a press that lands and
 * does nothing until it moves. The zones that genuinely need to tell a tap from a drag get
 * [tapOrDragZone] instead.
 *
 * @param topOffsetPx where this element sits inside the column's own track, for a handler attached
 *   to something smaller than the track — the foot band is 258dp down its own bar, and a finger at
 *   its top edge means the level at 258dp, not zero.
 * @param requireUnconsumed for a column with tap zones *inside* it. Compose delivers a press to the
 *   child first and to this handler afterwards, consumed or not, so without this the bar would read
 *   a tap on its own Stop button as a level being set and slam the shade to 100 on the way through.
 *
 * Kotlin/Compose note: `pointerInput`'s block is a coroutine that lives as long as the keys hold. It
 * is closer to an event-handling *loop* than to an `addEventListener` callback: `awaitFirstDown()`
 * suspends until the next press, and `drag()` suspends until the finger leaves. Change a key and
 * Compose cancels the loop and starts a new one, which is why the track height is one — a stale
 * height would silently keep converting pixels with the old number.
 */
fun Modifier.levelDrag(
    trackHeightPx: Float,
    onLevel: (Int) -> Unit,
    topOffsetPx: Float = 0f,
    inverted: Boolean = false,
    requireUnconsumed: Boolean = false,
): Modifier =
    pointerInput(trackHeightPx, topOffsetPx, inverted, requireUnconsumed) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = requireUnconsumed)
            down.consume()
            onLevel(readLevel(down.position.y + topOffsetPx, trackHeightPx, inverted))
            drag(down.id) { change ->
                change.consume()
                onLevel(readLevel(change.position.y + topOffsetPx, trackHeightPx, inverted))
            }
        }
    }

/**
 * A tap target that is also part of the drag surface: **press and release is the tap, press and
 * slide is a level drag.**
 *
 * The compact bar has two of these — the foot band that runs and stops the shade, and the pip row
 * that opens the full screen — and without this rule each of them would be a strip of the control
 * that swallows drags starting inside it. A thumb that lands low on the bar can still set a level.
 *
 * [BAR_TAP_SLOP_DP] is smaller than the platform's own touch slop on purpose: the question is not
 * "did the user mean to scroll" but "did the finger move at all".
 *
 * @param onLongPress an optional second way in. The panel has no Back key and no title bar, so the
 *   long press on the foot band is a shortcut to the full screen for somebody who never noticed the
 *   pips — never the only route to anything.
 */
fun Modifier.tapOrDragZone(
    trackHeightPx: Float,
    topOffsetPx: Float,
    slopPx: Float,
    onTap: () -> Unit,
    onLevel: (Int) -> Unit,
    onLongPress: (() -> Unit)? = null,
): Modifier =
    pointerInput(trackHeightPx, topOffsetPx, slopPx, onLongPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val zone = SlopWatch(down.position.y, slopPx)

            val report: (PointerInputChange) -> Unit = { change ->
                if (zone.moved(change.position.y)) {
                    change.consume()
                    onLevel(readLevel(change.position.y + topOffsetPx, trackHeightPx, false))
                }
            }

            if (onLongPress == null) {
                drag(down.id, report)
            } else {
                // A null answer means the timeout won the race — the finger is still down. If it has
                // not travelled by then it is a long press; if it has, this is an ordinary drag that
                // happened to be slow, and it simply carries on.
                val settled =
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        drag(down.id, report)
                    }
                if (settled == null) {
                    if (!zone.dragging) {
                        onLongPress()
                        // Swallow the rest of the gesture, or the finger coming up would also read
                        // as a tap and fire the other action on the way out.
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    drag(down.id, report)
                }
            }

            if (!zone.dragging) onTap()
        }
    }

/**
 * The full screen's column (design 2a / 6a): the whole 326dp is the range, and the word at the
 * bottom names what the plate under the shade is.
 *
 * "FLOOR" sits on the part of the plate the shade never reaches, so it is a label on the mechanism
 * rather than a value — it is what the column is standing on.
 */
@Composable
fun DimColumn(
    dimLevel: Int,
    onDimLevel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = columnColors()
    val trackPx = with(LocalDensity.current) { DIM_COLUMN_HEIGHT_DP.dp.toPx() }
    val label = stringResource(R.string.dim_level_label)

    ShadeColumn(
        level = dimLevel,
        width = DIM_COLUMN_WIDTH_DP.dp,
        height = DIM_COLUMN_HEIGHT_DP.dp,
        corner = DIM_COLUMN_RADIUS_DP.dp,
        handleWidth = DIM_HANDLE_WIDTH_DP.dp,
        trackHeight = DIM_COLUMN_HEIGHT_DP.dp,
        colors = colors,
        modifier =
            modifier
                .levelDrag(trackHeightPx = trackPx, onLevel = onDimLevel)
                .levelSemantics(label, dimLevel, onDimLevel),
    ) {
        Text(
            text = stringResource(R.string.dim_column_floor),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
            color = colors.plateInk,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = COLUMN_FOOTER_INSET_DP.dp),
        )
    }
}

/**
 * The compact surface's warmth column: same control, **inverted**, so warm is up.
 *
 * It is not a [ShadeColumn]: nothing here is covered, the whole column is the ramp itself, and the
 * handle rides it. Sharing the drag rule and not the painting is the honest amount of sharing —
 * a `covered = false` flag on the other one would make one composable answer to two pictures.
 */
@Composable
fun WarmthColumn(
    warmth: Int,
    warmthColor: Int,
    onWarmth: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = columnColors()
    val label = stringResource(R.string.dim_warmth_label)

    // `maxHeight` is what the parent offers in this same measure pass, so the drag maths and the
    // handle read the height the column is actually drawn at rather than an estimate of it.
    BoxWithConstraints(modifier = modifier) {
        val height = minOf(WARMTH_COLUMN_HEIGHT_DP.dp, maxHeight)
        val trackPx = with(LocalDensity.current) { height.toPx() }

        Box(
            modifier =
                Modifier
                    .size(WARMTH_COLUMN_WIDTH_DP.dp, height)
                    .clip(RoundedCornerShape(WARMTH_COLUMN_RADIUS_DP.dp))
                    .background(
                        // From the colour the shade actually paints down to nothing, so the top of the
                        // column is the tint the user chose rather than the app's accent.
                        Brush.verticalGradient(listOf(Color(warmthTint(warmthColor)), colors.warmthCool)),
                    ).levelDrag(trackHeightPx = trackPx, onLevel = onWarmth, inverted = true)
                    .levelSemantics(label, warmth, onWarmth),
        ) {
            val handleTop = height * ((100 - warmth.coerceIn(0, 100)) / 100f)
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = handleTop - (COLUMN_HANDLE_HEIGHT_DP / 2).dp)
                        .size(34.dp, 5.dp)
                        .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(percent = 50)),
            )
        }
    }
}

/**
 * What a hand-drawn control owes a screen reader, and what a `Slider` would have given for nothing.
 *
 * TalkBack needs three things to treat this as a value it can change: a name, the value with its
 * range, and an action that sets it — the last one is what makes the "swipe up to increase" gesture
 * work at all, because a blind user is not dragging a shade edge they cannot see.
 */
private fun Modifier.levelSemantics(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
): Modifier =
    semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..100f, steps = 99)
        setProgress { target ->
            onValue(target.roundToInt().coerceIn(0, 100))
            true
        }
    }

/** Which end of the track the finger is reading from. */
private fun readLevel(
    y: Float,
    trackHeightPx: Float,
    inverted: Boolean,
): Int = if (inverted) warmthAt(y, trackHeightPx) else levelAt(y, trackHeightPx)

/**
 * Has this press moved far enough to stop being a tap?
 *
 * A tiny class rather than two captured `var`s, because the flag and the threshold belong together
 * and the gesture loop reads the answer from more than one place. Once `dragging` is true it stays
 * true: a finger that came back to where it started still spent the gesture dragging.
 */
private class SlopWatch(
    private val startY: Float,
    private val slopPx: Float,
) {
    var dragging = false
        private set

    fun moved(y: Float): Boolean {
        if (!dragging && abs(y - startY) > slopPx) dragging = true
        return dragging
    }
}
