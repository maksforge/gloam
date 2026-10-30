package app.gloam.shade

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gloam.R
import app.gloam.data.ThemeMode
import app.gloam.theme.AppTheme
import app.gloam.theme.Spacing
import app.gloam.ui.dim.PauseRing
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot
import kotlin.math.min

/**
 * The bubble's flags: the panel's without `FLAG_WATCH_OUTSIDE_TOUCH`, because a tap elsewhere is not
 * a reason for the bubble to go — it is what is left *after* a tap elsewhere. `FLAG_LAYOUT_NO_LIMITS`
 * is what lets it rest mostly past the display's edge, which is the point of tucking. ADR-0016.
 */
const val BUBBLE_WINDOW_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

/** The ✕ the bubble is dropped on. Untouchable like the shade: it is a place, not a control. */
const val DISMISS_TARGET_WINDOW_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

/**
 * The ✕ window's `LayoutParams.alpha`. **Untouchable is not enough on its own:** the input
 * dispatcher adds up one uid's untouchable windows over a point, and the ✕ (clamped to 0.8) over the
 * shade (0.8) reads as 0.96, past the 0.8 an app below accepts touches through. It skips windows at
 * alpha 0, so the ✕ is at 0 for as long as nobody is dragging — what draws it is the content.
 */
fun dismissTargetWindowAlpha(dragging: Boolean): Float = if (dragging) 1f else 0f

/** The bubble's square window. The circle is drawn [BUBBLE_SHADOW_DP] inside it, so its shadow fits. */
internal const val BUBBLE_SIZE_DP = 56
internal const val BUBBLE_SHADOW_DP = 5

/**
 * How much of the window stays on screen while the bubble rests tucked into an edge.
 *
 * **Sized against a window that is not ours.** Over an ordinary app, HyperOS lays a ~21dp strip
 * along each edge for its back gesture (`GestureStubLeft`/`Right`), and that strip sits *above*
 * every app overlay: a tap there never reaches the bubble. So the tucked tab has to reach well past
 * it — this leaves about 24dp of it touchable. The home screen has no strip, which is why a peek of
 * 39dp looked fine there and lost half its width over Chrome (ADR-0016's amendment).
 */
internal const val BUBBLE_PEEK_DP = 50

/** How far ahead a release's velocity is projected to choose the edge — a flick crosses the screen. */
internal const val BUBBLE_FLING_PROJECTION_S = 0.12f
internal const val BUBBLE_EXIT_MS = 160
internal const val DISMISS_TARGET_DP = 72
internal const val DISMISS_TARGET_BOTTOM_DP = 48
internal const val PROMPT_WIDTH_DP = 320

/** The bubble never takes more than this share of the display's shorter side. */
private const val BUBBLE_MAX_DISPLAY_FRACTION = 0.25f

/** The prompt is momentary and has two ways out besides its buttons, so it may be wider than the panel. */
private const val PROMPT_MAX_DISPLAY_FRACTION = 0.85f

/** Where the bubble may sit: the display minus the system bars and the cutout, in display pixels. */
data class BubbleArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class BubblePosition(
    val x: Int,
    val y: Int,
)

/**
 * The bubble's side in pixels, capped so that no density or display can make it more than a corner
 * of the screen. It is touchable and has no timeout, so its size is the whole bound.
 */
fun bubbleSidePx(
    sideDp: Int,
    density: Float,
    displayShortSidePx: Int,
): Int {
    val wanted = (sideDp * density.coerceAtLeast(0.1f)).toInt()
    val ceiling = (displayShortSidePx * BUBBLE_MAX_DISPLAY_FRACTION).toInt()
    return wanted.coerceAtMost(ceiling).coerceAtLeast(1)
}

/** Keep the whole bubble inside [area], so a drag can never leave it somewhere a finger cannot reach. */
fun clampBubble(
    wanted: BubblePosition,
    widthPx: Int,
    heightPx: Int,
    area: BubbleArea,
): BubblePosition =
    BubblePosition(
        x = wanted.x.coerceIn(area.left, (area.right - widthPx).coerceAtLeast(area.left)),
        y = wanted.y.coerceIn(area.top, (area.bottom - heightPx).coerceAtLeast(area.top)),
    )

enum class BubbleSide { Left, Right }

/** A resting bubble is a side and a height; its `x` follows from the side and the display. */
data class BubbleRest(
    val side: BubbleSide,
    val y: Int,
)

/**
 * Where a drag may take the bubble: the app's area, widened sideways so it can be pulled out of — or
 * pushed back into — a tuck without jumping. At least [peekPx] of it is on screen at every point.
 */
fun bubbleDragArea(
    area: BubbleArea,
    sizePx: Int,
    peekPx: Int,
): BubbleArea {
    val hidden = (sizePx - peekPx).coerceAtLeast(0)
    return area.copy(left = area.left - hidden, right = area.right + hidden)
}

/**
 * Where a released bubble comes to rest: tucked into the side its centre is nearer — after
 * [projected] has carried it on by its velocity — with only [peekPx] showing, at that height.
 */
fun tuckToEdge(
    projected: BubblePosition,
    sizePx: Int,
    area: BubbleArea,
    peekPx: Int,
): Pair<BubblePosition, BubbleSide> {
    val side = if (projected.x + sizePx / 2 < (area.left + area.right) / 2) BubbleSide.Left else BubbleSide.Right
    return tuckInto(BubbleRest(side, projected.y), sizePx, area, peekPx) to side
}

/**
 * Where a bubble resting at [rest] sits in [area]: the same side, its height clamped. The side is
 * kept rather than re-chosen because a rotation moves the midpoint — a right-docked bubble's `x` in
 * portrait is left of the middle in landscape.
 */
fun tuckInto(
    rest: BubbleRest,
    sizePx: Int,
    area: BubbleArea,
    peekPx: Int,
): BubblePosition {
    val x = if (rest.side == BubbleSide.Left) area.left - (sizePx - peekPx) else area.right - peekPx
    val y = clampBubble(BubblePosition(x, rest.y), sizePx, sizePx, area).y
    return BubblePosition(x, y)
}

fun overDismissTarget(
    bubbleCenterX: Float,
    bubbleCenterY: Float,
    targetCenterX: Float,
    targetCenterY: Float,
    radiusPx: Float,
): Boolean = hypot(bubbleCenterX - targetCenterX, bubbleCenterY - targetCenterY) <= radiusPx

fun promptWidthPx(
    displayWidthPx: Int,
    density: Float,
): Int {
    val wanted = (PROMPT_WIDTH_DP * density.coerceAtLeast(0.1f)).toInt()
    val ceiling = (displayWidthPx * PROMPT_MAX_DISPLAY_FRACTION).toInt()
    return min(wanted, ceiling).coerceAtLeast(1)
}

/**
 * Where the user last left the bubble, for the rest of this process. In memory rather than in
 * DataStore: a position is only meaningful on this display, and Auto Backup would carry a key to the
 * next phone (CLAUDE.md, *Storage*).
 */
internal var lastBubbleRest: BubbleRest? = null

/**
 * Ask the service for the bubble, from the compact controls closing by Back or a tap outside.
 * Dropped when no service is collecting, for [dismissPanelAndBubble]'s reason: no shade, no bubble.
 */
fun showControlsBubble() {
    bubbleRequests.tryEmit(Unit)
}

internal val bubbleRequests =
    MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

internal interface BubbleGestures {
    fun onPressChange(pressed: Boolean)

    fun onTap()

    fun onDragStart()

    fun onDrag(
        dxPx: Float,
        dyPx: Float,
    )

    /** [velocityX] and [velocityY] are the finger's at release, in display pixels per second. */
    fun onDragEnd(
        cancelled: Boolean,
        velocityX: Float,
        velocityY: Float,
    )
}

/**
 * The bubble's root view, which takes every touch for itself and never hands one to Compose.
 *
 * **Screen coordinates, because the window moves under the finger.** A drag read in the view's own
 * coordinates feeds back on itself: each move shifts the window, which shifts where the next event
 * lands inside it. `rawX`/`rawY` are the display's, so the offset from the press is stable.
 */
@SuppressLint("ViewConstructor")
internal class BubbleTouchLayout(
    context: Context,
    private val gestures: BubbleGestures,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    init {
        isClickable = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackVelocity(ev)
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                gestures.onPressChange(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    gestures.onDragStart()
                }
                if (dragging) gestures.onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                gestures.onPressChange(false)
                val tracker = velocity
                tracker?.computeCurrentVelocity(1000)
                if (dragging) {
                    gestures.onDragEnd(cancelled = false, tracker?.xVelocity ?: 0f, tracker?.yVelocity ?: 0f)
                } else {
                    performClick()
                }
                releaseVelocity()
            }
            MotionEvent.ACTION_CANCEL -> {
                gestures.onPressChange(false)
                if (dragging) gestures.onDragEnd(cancelled = true, 0f, 0f)
                releaseVelocity()
            }
        }
        return true
    }

    /**
     * Fed a copy placed at the raw coordinates, for the reason the class doc gives: in the view's
     * own coordinates the window's movement would cancel out the finger's, and a fling would read
     * as standing still.
     */
    private fun trackVelocity(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            releaseVelocity()
            velocity = VelocityTracker.obtain()
        }
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        velocity?.addMovement(copy)
        copy.recycle()
    }

    private fun releaseVelocity() {
        velocity?.recycle()
        velocity = null
    }

    override fun performClick(): Boolean {
        super.performClick()
        gestures.onTap()
        return true
    }
}

/**
 * The app's mark on a shape that says where the bubble is: a circle while it is held or flying,
 * and a "D" docked to the edge while it rests — flat and square against the border, round on the
 * inside — so it reads as snapped rather than as a circle that slid off the screen.
 *
 * The morph is four animated numbers: the gap to each side of the window, and the corner radius on
 * each side. Tucked, the outer gap grows until the shape ends exactly at the display's edge (the
 * window runs on past it), and the outer corners go square. The mark is centred in whatever is left,
 * so it is always in the part that shows. While a pause runs, a ring around the mark empties as it
 * does — drawn to [pauseEnd], so a deadline that cuts the pause short drains it to the deadline, as
 * the compact button's ring does.
 *
 * Kotlin note: `animateDpAsState` is the Compose equivalent of a CSS transition on a value — the
 * composable states the target, and the framework tweens the number it reads on every frame.
 * `absolute…` because the edges are the display's, not the reading direction's.
 */
@Composable
internal fun BubbleContent(
    themeMode: ThemeMode,
    pressed: StateFlow<Boolean>,
    leaving: StateFlow<Boolean>,
    tucked: StateFlow<BubbleSide?>,
    pause: StateFlow<PauseSpan?>,
    deadline: StateFlow<Long?>,
) {
    val isPressed by pressed.collectAsStateWithLifecycle()
    val pauseSpan by pause.collectAsStateWithLifecycle()
    val offAt by deadline.collectAsStateWithLifecycle()
    val isLeaving by leaving.collectAsStateWithLifecycle()
    val tuckedSide by tucked.collectAsStateWithLifecycle()
    val resting = if (isPressed) null else tuckedSide

    val inset = BUBBLE_SHADOW_DP.dp
    val offScreen = (BUBBLE_SIZE_DP - BUBBLE_PEEK_DP).dp
    val round = ((BUBBLE_SIZE_DP - 2 * BUBBLE_SHADOW_DP) / 2).dp
    // Docking springs; undocking is instant. A finger pulling the tab away moves the window at once,
    // and a tab still flat for an edge it has left shows a gap at the border for every frame of it.
    val morph: AnimationSpec<Dp> =
        if (resting == null) {
            snap()
        } else {
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
        }
    val leftGap by animateDpAsState(if (resting == BubbleSide.Left) offScreen else inset, morph)
    val rightGap by animateDpAsState(if (resting == BubbleSide.Right) offScreen else inset, morph)
    val leftRadius by animateDpAsState(if (resting == BubbleSide.Left) 0.dp else round, morph)
    val rightRadius by animateDpAsState(if (resting == BubbleSide.Right) 0.dp else round, morph)
    val restAlpha by animateFloatAsState(if (resting == null) 1f else 0.85f, tween(200))

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    val pressScale by animateFloatAsState(if (isPressed) 0.88f else 1f, spring(stiffness = Spring.StiffnessMedium))
    val leaveScale by animateFloatAsState(if (isLeaving) 0f else 1f, tween(BUBBLE_EXIT_MS))

    // The morph's spring overshoots its targets, and both ends of it are wrong. Below zero, Compose
    // throws on a negative corner or padding — which, in the shade's own process, takes the shade
    // down. Past [offScreen], the flat edge bounces inside the display and leaves a gap at the border.
    val leftCorner = leftRadius.coerceIn(0.dp, round)
    val rightCorner = rightRadius.coerceIn(0.dp, round)
    val shape =
        AbsoluteRoundedCornerShape(
            topLeft = leftCorner,
            bottomLeft = leftCorner,
            topRight = rightCorner,
            bottomRight = rightCorner,
        )
    AppTheme(themeMode = themeMode) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .absolutePadding(
                        left = leftGap.coerceIn(0.dp, offScreen),
                        top = inset,
                        right = rightGap.coerceIn(0.dp, offScreen),
                        bottom = inset,
                    ).graphicsLayer {
                        val scale = appear.value * pressScale * leaveScale
                        scaleX = scale
                        scaleY = scale
                        alpha = leaveScale * restAlpha
                    }.shadow(6.dp, shape)
                    .background(colors.primaryContainer, shape),
            contentAlignment = Alignment.Center,
        ) {
            pauseSpan?.let {
                PauseRing(
                    from = it.from,
                    end = pauseEnd(it.until, offAt).atMillis,
                    color = colors.onPrimaryContainer,
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 2.5.dp,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Scales and fades in while a drag is on, and grows when the bubble is over it. */
@Composable
internal fun DismissTargetContent(
    themeMode: ThemeMode,
    shown: StateFlow<Boolean>,
    over: StateFlow<Boolean>,
) {
    val isShown by shown.collectAsStateWithLifecycle()
    val isOver by over.collectAsStateWithLifecycle()
    val scale by animateFloatAsState(
        targetValue =
            if (!isShown) {
                0.4f
            } else if (isOver) {
                1f
            } else {
                0.78f
            },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    val alpha by animateFloatAsState(if (isShown) 1f else 0f, tween(150))
    AppTheme(themeMode = themeMode) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }.background(if (isOver) colors.errorContainer else colors.surfaceContainerHigh, CircleShape)
                    .border(BorderStroke(1.dp, colors.outlineVariant), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = if (isOver) colors.onErrorContainer else colors.onSurface,
            )
        }
    }
}

@Composable
internal fun BubblePromptContent(
    themeMode: ThemeMode,
    onTurnOff: () -> Unit,
    onKeep: () -> Unit,
) {
    AppTheme(themeMode = themeMode) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.base)) {
                Text(
                    text = stringResource(R.string.bubble_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.bubble_prompt_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.tight),
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.snug),
                ) {
                    TextButton(onClick = onTurnOff) { Text(stringResource(R.string.bubble_prompt_turn_off)) }
                    TextButton(onClick = onKeep) { Text(stringResource(R.string.bubble_prompt_keep)) }
                }
            }
        }
    }
}
