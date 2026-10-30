package app.gloam.ui.dim

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.gloam.R
import app.gloam.data.PRESET_NAME_MAX_LENGTH
import app.gloam.data.SavedPreset
import app.gloam.theme.Spacing

/**
 * The built-in presets, then the user's own, as chips — and **one line under them saying what the lit
 * one is for**, with the action that fits it: *Save as preset* when no chip is lit, *Edit* when one
 * of the user's own is. A long press on one of the user's own opens the same rename/delete dialog
 * without applying it, so deleting a preset doesn't start the shade on the way.
 *
 * Which chip is lit is derived from the values, never stored, so dragging any control off a preset's
 * numbers unlights its chip. A built-in wins a tie with a saved preset carrying the same numbers.
 */
@Composable
fun PresetRow(
    dimLevel: Int,
    warmth: Int,
    warmthColor: Int,
    savedPresets: List<SavedPreset>,
    onPreset: (Preset) -> Unit,
    onSavedPreset: (SavedPreset) -> Unit,
    onSavePreset: (name: String) -> Unit,
    onRenamePreset: (from: String, to: String) -> Unit,
    onDeletePreset: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val builtIn = Preset.matching(dimLevel, warmth, warmthColor)
    val saved = if (builtIn == null) savedPresets.firstOrNull { it.matches(dimLevel, warmth, warmthColor) } else null
    // Kotlin note: `rememberSaveable` is `useState` that also survives rotation and process death,
    // so a half-typed name is still in the dialog when the user comes back to it.
    var saving by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val editLabel = stringResource(R.string.preset_edit)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.hair)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
            Preset.entries.forEach { preset ->
                FilterChip(
                    selected = preset == builtIn,
                    onClick = { onPreset(preset) },
                    label = { Text(stringResource(preset.label)) },
                )
            }
            savedPresets.forEach { preset ->
                FilterChip(
                    selected = preset == saved,
                    onClick = { onSavedPreset(preset) },
                    label = { Text(preset.name) },
                    modifier = Modifier.onLongPress(preset.name, editLabel) { editing = preset.name },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text =
                    when {
                        builtIn != null -> stringResource(builtIn.hint)
                        saved != null -> stringResource(R.string.preset_saved_hint)
                        else -> stringResource(R.string.presets_hint)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            when {
                builtIn != null -> Unit
                saved != null ->
                    TextButton(
                        onClick = { editing = saved.name },
                    ) { Text(stringResource(R.string.preset_edit)) }
                else -> TextButton(onClick = { saving = true }) { Text(stringResource(R.string.preset_save)) }
            }
        }
    }

    if (saving) {
        PresetNameDialog(
            title = stringResource(R.string.preset_save),
            initialName = "",
            taken = { name -> savedPresets.any { it.isNamed(name) } },
            takenMeansReplace = true,
            onConfirm = { name ->
                onSavePreset(name)
                saving = false
            },
            onDelete = null,
            onDismiss = { saving = false },
        )
    }
    // Kotlin note: `?.let` runs the block only when `editing` is non-null, with `current` as the
    // non-null value — `if (editing != null) { const current = editing; … }`.
    editing?.let { current ->
        PresetNameDialog(
            title = stringResource(R.string.preset_edit_title),
            initialName = current,
            taken = { name -> savedPresets.any { it.isNamed(name) && !it.isNamed(current) } },
            takenMeansReplace = false,
            onConfirm = { name ->
                onRenamePreset(current, name)
                editing = null
            },
            onDelete = {
                onDeletePreset(current)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * One dialog for both saving and renaming, because both are "type a name".
 *
 * [taken] says whether another preset already has the typed name. Saving over it is allowed and is
 * how a preset's values get updated, so the confirm button says *Replace*; renaming onto it is not,
 * because two chips would answer to one name.
 */
@Composable
private fun PresetNameDialog(
    title: String,
    initialName: String,
    taken: (String) -> Boolean,
    takenMeansReplace: Boolean,
    onConfirm: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val isTaken = name.isNotBlank() && taken(name)
    val canConfirm = name.isNotBlank() && (takenMeansReplace || !isTaken)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                if (onDelete == null) {
                    Text(stringResource(R.string.preset_save_body), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PRESET_NAME_MAX_LENGTH) },
                    label = { Text(stringResource(R.string.preset_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    isError = isTaken && !takenMeansReplace,
                    supportingText =
                        when {
                            !isTaken -> null
                            takenMeansReplace -> {
                                { Text(stringResource(R.string.preset_replace_note)) }
                            }
                            else -> {
                                { Text(stringResource(R.string.preset_name_taken)) }
                            }
                        },
                    modifier = Modifier.widthIn(min = 200.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = canConfirm) {
                Text(
                    stringResource(
                        if (isTaken &&
                            takenMeansReplace
                        ) {
                            R.string.preset_replace
                        } else {
                            R.string.preset_save_confirm
                        },
                    ),
                )
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.preset_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.preset_cancel)) }
            }
        },
    )
}

/**
 * Runs [action] on a long press and keeps the press from reaching the chip's own click.
 *
 * `FilterChip` owns its `clickable` and has no long-click slot, so this listens on the
 * [PointerEventPass.Initial] pass — parent before child, the reverse of DOM bubbling — and consumes
 * the rest of the gesture once it fires, which the chip's click reads as a cancel. The semantics
 * action is what TalkBack's "double-tap and hold" calls.
 */
private fun Modifier.onLongPress(
    key: Any,
    label: String,
    action: () -> Unit,
): Modifier =
    semantics {
        onLongClick(label) {
            action()
            true
        }
    }.pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val ended =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    true
                }
            if (ended == null) {
                action()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
