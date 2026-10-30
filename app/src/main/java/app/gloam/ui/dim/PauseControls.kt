package app.gloam.ui.dim

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gloam.R
import app.gloam.shade.PauseEnd
import app.gloam.shade.PauseLength
import app.gloam.shade.PauseSpan
import app.gloam.shade.pauseEnd
import app.gloam.shade.pauseLeft
import app.gloam.theme.Spacing
import kotlinx.coroutines.delay

/**
 * What every pause control draws from: the length a tap would pause for, how the pause under way
 * ends, or `null` when there is none, and when it began, for the ring.
 */
data class PauseUi(
    val length: PauseLength = PauseLength.Default,
    val end: PauseEnd? = null,
    val from: Long? = null,
)

fun pauseUi(
    length: PauseLength,
    span: PauseSpan?,
    offAtMillis: Long?,
): PauseUi = PauseUi(length, span?.let { pauseEnd(it.until, offAtMillis) }, span?.from)

/**
 * The full screen's pause: a chip per length while the shade is up, and while paused a line saying
 * how long is left beside the way back. Nothing at all while the shade is down, because there is
 * nothing to pause.
 *
 * A tap both pauses and remembers the length, so the chip that is lit is the one the compact
 * controls' single button will use — lit the way the auto-off chips light their choice.
 */
@Composable
fun PauseRow(
    running: Boolean,
    pause: PauseUi,
    onPause: (PauseLength) -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!running) return
    val end = pause.end
    if (end == null) {
        PauseChips(remembered = pause.length, onPause = onPause, modifier = modifier)
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.snug),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(end.textRes(), rememberCountdown(end.atMillis)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).widthIn(min = 0.dp),
        )
        FilledTonalButton(onClick = onResume) {
            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
            Text(
                text = stringResource(R.string.dim_resume_now),
                modifier = Modifier.padding(start = Spacing.tight),
            )
        }
    }
}

@Composable
private fun PauseChips(
    remembered: PauseLength,
    onPause: (PauseLength) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Spacing.tight)) {
            Icon(
                painter = painterResource(R.drawable.ic_pause),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.dim_pause_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = Spacing.tight),
            )
        }
        for (length in PauseLength.entries) {
            // "Pause for 5 min" rather than the chip's bare "5 min", which says nothing on its own.
            val description = stringResource(R.string.dim_pause, stringResource(length.labelRes()))
            FilterChip(
                selected = length == remembered,
                onClick = { onPause(length) },
                // On the label rather than the chip, so it merges into the chip's own node.
                label = {
                    Text(
                        text = stringResource(length.labelRes()),
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                },
            )
        }
    }
}

private fun PauseEnd.textRes(): Int = if (resumes) R.string.dim_paused_resumes_in else R.string.dim_paused_ends_in

/**
 * The time left as `4:32`, ticking once a second while it is on screen.
 *
 * A countdown here where the rest of the app prints clock times (`rememberTimeText`): a pause is
 * minutes long, so "back at 23:05" would make the reader do the subtraction the line is for. It
 * ticks only while a pause is drawn, and `formatElapsedTime` is the platform's, so the digits follow
 * the locale with no plural to translate.
 *
 * Kotlin note: `produceState` is `useState` plus a `useEffect` that owns a coroutine — the loop is
 * cancelled when the composable leaves the screen or [untilMillis] changes.
 */
@Composable
internal fun rememberCountdown(untilMillis: Long): String {
    val seconds by produceState(secondsLeft(untilMillis), untilMillis) {
        while (value > 0) {
            delay(1_000L - System.currentTimeMillis() % 1_000L)
            value = secondsLeft(untilMillis)
        }
    }
    return DateUtils.formatElapsedTime(seconds)
}

private fun secondsLeft(untilMillis: Long): Long =
    ((untilMillis - System.currentTimeMillis() + 999L) / 1_000L).coerceAtLeast(0L)

internal fun PauseLength.labelRes(): Int =
    when (this) {
        PauseLength.Minute1 -> R.string.pause_length_1m
        PauseLength.Minutes5 -> R.string.pause_length_5m
        PauseLength.Minutes15 -> R.string.pause_length_15m
    }

/**
 * The share of a pause still left, as a ring that empties clockwise from the top — the bubble's and
 * the compact button's way of saying "paused, and about this much to go" with no digits to read.
 *
 * A faint full circle under the arc, so a nearly empty ring still reads as a ring rather than as a
 * stray mark. The arc is recomputed once a second and tweened across it, so a one-minute pause moves
 * smoothly rather than in six-degree steps.
 */
@Composable
internal fun PauseRing(
    from: Long,
    end: Long,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
) {
    val left by produceState(pauseLeft(System.currentTimeMillis(), from, end), from, end) {
        while (value > 0f) {
            delay(1_000L)
            value = pauseLeft(System.currentTimeMillis(), from, end)
        }
    }
    val shown by animateFloatAsState(left, tween(durationMillis = 1_000, easing = LinearEasing))
    Canvas(modifier) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val topLeft = Offset(inset, inset)
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(
            color.copy(alpha = 0.25f),
            0f,
            360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        drawArc(color, -90f, 360f * shown, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    }
}
