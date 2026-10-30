package app.gloam.ui.dim

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gloam.R
import app.gloam.shade.PauseEnd
import app.gloam.shade.PauseLength
import app.gloam.shade.PauseSpan
import app.gloam.shade.fractionLeft
import app.gloam.shade.pauseEnd
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
 * controls' single button will use — lit the way the auto-off chips light their choice. *Other…*
 * only adds a length, as a chip of its own; pausing for it is that chip's tap, like any other.
 */
@Composable
fun PauseRow(
    running: Boolean,
    pause: PauseUi,
    onPause: (PauseLength) -> Unit,
    onPauseLength: (PauseLength) -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!running) return
    val end = pause.end
    if (end == null) {
        PauseChips(remembered = pause.length, onPause = onPause, onPauseLength = onPauseLength, modifier = modifier)
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
    onPauseLength: (PauseLength) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
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
        // The remembered length gets a chip of its own when it is not a preset, so a length the user
        // typed is one tap away the next time, the way the presets are.
        val lengths = if (remembered in PauseLength.Presets) PauseLength.Presets else PauseLength.Presets + remembered
        for (length in lengths) {
            // "Pause for 5 min" rather than the chip's bare "5 min", which says nothing on its own.
            val label = pauseLengthLabel(length)
            val description = stringResource(R.string.dim_pause, label)
            FilterChip(
                selected = length == remembered,
                onClick = { onPause(length) },
                // On the label rather than the chip, so it merges into the chip's own node.
                label = {
                    Text(
                        text = label,
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                },
            )
        }
        AssistChip(
            onClick = { choosing = true },
            label = { Text(stringResource(R.string.dim_pause_other)) },
        )
    }

    if (choosing) {
        PauseLengthDialog(
            initial = remembered,
            onConfirm = { length ->
                choosing = false
                onPauseLength(length)
            },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * Any whole number of minutes in [PauseLength.MIN_MINUTES]..[PauseLength.MAX_MINUTES], typed. The
 * field takes digits only, and the confirm button stays off until the number is in range, so the
 * clamp in [PauseLength.ofMinutes] is a backstop rather than a silent correction.
 *
 * It opens focused with the remembered number selected, so the first digit typed replaces it rather
 * than turning `5` into `52`.
 */
@Composable
private fun PauseLengthDialog(
    initial: PauseLength,
    onConfirm: (PauseLength) -> Unit,
    onDismiss: () -> Unit,
) {
    // Kotlin note: a TextFieldValue is the text plus its selection, like an input's value with
    // selectionStart/End held in state. rememberSaveable needs its Saver to put it in the Bundle
    // that survives rotation, and LaunchedEffect(Unit) is useEffect(..., []): once per showing.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = initial.minutes.toString()
        mutableStateOf(TextFieldValue(text, selection = TextRange(0, text.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val chosen =
        field.text
            .toIntOrNull()
            ?.takeIf { it in PauseLength.MIN_MINUTES..PauseLength.MAX_MINUTES }
            ?.let(PauseLength::ofMinutes)
    val confirm = { chosen?.let(onConfirm) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dim_pause_custom_title)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { typed ->
                    val digits = typed.text.filter(Char::isDigit).take(3)
                    field = if (digits == typed.text) typed else TextFieldValue(digits, TextRange(digits.length))
                },
                label = { Text(stringResource(R.string.dim_pause_minutes_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.dim_pause_minutes_range,
                            PauseLength.MIN_MINUTES,
                            PauseLength.MAX_MINUTES,
                        ),
                    )
                },
                isError = field.text.isNotEmpty() && chosen == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = chosen != null) {
                Text(stringResource(R.string.dim_pause_custom_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dim_pause_cancel)) }
        },
    )
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

@Composable
internal fun pauseLengthLabel(length: PauseLength): String =
    stringResource(R.string.pause_length_minutes, length.minutes)

/**
 * The share of a pause or a deadline still left, as a ring that empties clockwise from the top —
 * the bubble's and the compact button's way of saying "about this much to go" with no digits.
 *
 * A faint full circle under the arc, so a nearly empty ring still reads as a ring rather than as a
 * stray mark. The gap opens at the top and grows clockwise. The loop ticks about once per degree of
 * the whole span, never faster than a frame, so a long one redraws rarely and a one-minute pause
 * still moves smoothly.
 */
@Composable
internal fun TimeLeftRing(
    from: Long,
    end: Long,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
) {
    val left by produceState(fractionLeft(System.currentTimeMillis(), from, end), from, end) {
        val tick = ((end - from) / 360).coerceAtLeast(16L)
        while (value > 0f) {
            delay(tick)
            value = fractionLeft(System.currentTimeMillis(), from, end)
        }
    }
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
        drawArc(
            color,
            -90f + 360f * (1f - left),
            360f * left,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
    }
}
