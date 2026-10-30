package app.gloam.ui.dim

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gloam.R
import app.gloam.shade.PauseEnd
import app.gloam.shade.PauseLength
import app.gloam.shade.pauseEnd
import app.gloam.theme.Spacing
import kotlinx.coroutines.delay

/**
 * What every pause control draws from: the length a tap would pause for, and how the pause under
 * way ends, or `null` when there is none.
 */
data class PauseUi(
    val length: PauseLength = PauseLength.Default,
    val end: PauseEnd? = null,
)

fun pauseUi(
    length: PauseLength,
    pausedUntil: Long?,
    offAtMillis: Long?,
): PauseUi = PauseUi(length, pausedUntil?.let { pauseEnd(it, offAtMillis) })

/**
 * The full screen's pause: a button while the shade is up, and while paused a line saying how long
 * is left beside the way back. Nothing at all while the shade is down, because there is nothing to
 * pause.
 */
@Composable
fun PauseRow(
    running: Boolean,
    pause: PauseUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!running) return
    val end = pause.end
    if (end == null) {
        OutlinedButton(
            onClick = onPause,
            shape = RoundedCornerShape(percent = 50),
            modifier = modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(painter = painterResource(R.drawable.ic_pause), contentDescription = null)
            Text(
                text = stringResource(R.string.dim_pause, stringResource(pause.length.labelRes())),
                modifier = Modifier.padding(start = Spacing.tight),
            )
        }
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
