package app.gloam.ui.dim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import app.gloam.R
import app.gloam.shade.AutoOff
import app.gloam.theme.Spacing

private const val MINUTES_PER_HOUR = 60
private const val MAX_HOURS = AutoOff.MAX_MINUTES / MINUTES_PER_HOUR

/**
 * An auto-off choice as a chip label — **one set, everywhere**, presets and typed lengths alike:
 * "Never", "30 min", "2 hours", "1 h 30 min". One function rather than a resource per preset, so a
 * length nobody listed still gets a label and a new preset cannot be left without one.
 *
 * Whole hours go through a `<plurals>` so they read exactly as the preset chips always have; a mixed
 * length uses each language's abbreviations, which carry no plural.
 */
@Composable
internal fun autoOffLabel(choice: AutoOff): String {
    if (choice == AutoOff.Never) return stringResource(R.string.compact_auto_off_never)
    val hours = choice.minutes / MINUTES_PER_HOUR
    val minutes = choice.minutes % MINUTES_PER_HOUR
    return when {
        hours == 0 -> stringResource(R.string.auto_off_length_minutes, minutes)
        minutes == 0 -> pluralStringResource(R.plurals.auto_off_length_hours, hours, hours)
        else -> stringResource(R.string.auto_off_length_hours_minutes, hours, minutes)
    }
}

/**
 * Any length from [AutoOff.MIN_MINUTES] to [AutoOff.MAX_MINUTES], typed as hours and minutes. The
 * fields take digits only and *Set* stays off until the total is in range, so the clamp in
 * [AutoOff.ofMinutes] is a backstop rather than a silent correction. An empty field counts as zero,
 * so "45 minutes" needs no 0 typed in front of it.
 *
 * It opens on the current choice — or the default, when that is *Never* — with the hours focused and
 * selected, so the first digit typed replaces them, as `PauseLengthDialog` does.
 */
@Composable
internal fun AutoOffLengthDialog(
    initial: AutoOff,
    onConfirm: (AutoOff) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = if (initial == AutoOff.Never) AutoOff.Default else initial
    // Kotlin note: TextFieldValue is the text plus its selection; rememberSaveable with its Saver
    // keeps both across a rotation, and LaunchedEffect(Unit) runs once per showing, like
    // useEffect(..., []).
    var hours by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = (start.minutes / MINUTES_PER_HOUR).toString()
        mutableStateOf(TextFieldValue(text, selection = TextRange(0, text.length)))
    }
    var minutes by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue((start.minutes % MINUTES_PER_HOUR).toString()))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val typedHours = hours.text.toIntOrNull() ?: 0
    val typedMinutes = minutes.text.toIntOrNull() ?: 0
    val hoursOk = typedHours in 0..MAX_HOURS
    val minutesOk = typedMinutes in 0 until MINUTES_PER_HOUR
    val total = typedHours * MINUTES_PER_HOUR + typedMinutes
    val chosen =
        AutoOff
            .ofMinutes(total)
            .takeIf { hoursOk && minutesOk && total in AutoOff.MIN_MINUTES..AutoOff.MAX_MINUTES }
    val confirm = { chosen?.let(onConfirm) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dim_auto_off_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { hours = digitsOnly(it) },
                        label = { Text(stringResource(R.string.dim_auto_off_hours_label)) },
                        isError = !hoursOk,
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = digitsOnly(it) },
                        label = { Text(stringResource(R.string.dim_auto_off_minutes_label)) },
                        isError = !minutesOk,
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text =
                        stringResource(
                            R.string.dim_auto_off_custom_range,
                            autoOffLabel(AutoOff.ofMinutes(AutoOff.MIN_MINUTES)),
                            autoOffLabel(AutoOff.ofMinutes(AutoOff.MAX_MINUTES)),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (chosen ==
                            null
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = chosen != null) {
                Text(stringResource(R.string.dim_auto_off_custom_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dim_auto_off_cancel)) }
        },
    )
}

private fun digitsOnly(typed: TextFieldValue): TextFieldValue {
    val digits = typed.text.filter(Char::isDigit).take(2)
    return if (digits == typed.text) typed else TextFieldValue(digits, TextRange(digits.length))
}
