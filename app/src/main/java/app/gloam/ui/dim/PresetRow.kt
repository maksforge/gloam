package app.gloam.ui.dim

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import app.gloam.data.StoredPreset
import app.gloam.data.matching
import app.gloam.data.named
import app.gloam.theme.Spacing

/**
 * Every preset as a chip — the built-ins and the user's own alike — and a **＋** chip after them that
 * saves the current values as a new preset, always there whatever is lit. Under them, **one line
 * saying what the lit one is for**, with *Edit* beside it whenever one is lit. A long press on any
 * chip opens the same rename/delete dialog without applying it, so deleting a preset doesn't change
 * the sliders on the way. Deleting asks first.
 *
 * Which chip is lit is derived from the values, never stored, so dragging any control off a preset's
 * numbers unlights its chip. Presets are unique in their values, so normally one chip lights; a list
 * that breaks the rule may light two, and then the hint and *Edit* follow the first.
 */
@Composable
fun PresetRow(
    dimLevel: Int,
    warmth: Int,
    warmthColor: Int,
    presets: List<StoredPreset>,
    onPreset: (StoredPreset) -> Unit,
    onSavePreset: (name: String) -> Unit,
    onRenamePreset: (id: String, to: String) -> Unit,
    onDeletePreset: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = Preset.entries.associateWith { stringResource(it.label) }
    val labelOf: (Preset) -> String = labels::getValue
    val lit = presets.filter { it.matches(dimLevel, warmth, warmthColor) }
    // Kotlin note: `rememberSaveable` is `useState` that also survives rotation and process death,
    // so a half-typed name is still in the dialog when the user comes back to it.
    var saving by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editLabel = stringResource(R.string.preset_edit)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.hair)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
            presets.forEach { preset ->
                FilterChip(
                    selected = preset in lit,
                    onClick = { onPreset(preset) },
                    label = { Text(preset.displayName(labelOf)) },
                    modifier = Modifier.onLongPress(preset.id, editLabel) { editingId = preset.id },
                )
            }
            AssistChip(
                onClick = { saving = true },
                label = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.preset_save)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(presetHint(lit.firstOrNull(), anyPresets = presets.isNotEmpty())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            lit.firstOrNull()?.let { preset ->
                TextButton(onClick = { editingId = preset.id }) { Text(editLabel) }
            }
        }
    }

    if (saving) {
        PresetNameDialog(
            title = stringResource(R.string.preset_save),
            initialName = "",
            taken = { name -> presets.named(name, labelOf) != null },
            takenMeansReplace = true,
            sameSettingsAs = presets.matching(dimLevel, warmth, warmthColor)?.displayName(labelOf),
            onConfirm = { name ->
                onSavePreset(name)
                saving = false
            },
            onDelete = null,
            onDismiss = { saving = false },
        )
    }
    // Kotlin note: `?.let` runs the block only when the lookup is non-null, with `preset` as the
    // non-null value — `const preset = …; if (preset) { … }`. A preset deleted while its dialog was
    // open simply closes it.
    presets.firstOrNull { it.id == editingId }?.let { preset ->
        PresetNameDialog(
            title = stringResource(R.string.preset_edit_title),
            initialName = preset.displayName(labelOf),
            taken = { name -> presets.named(name, labelOf, exceptId = preset.id) != null },
            takenMeansReplace = false,
            sameSettingsAs = null,
            onConfirm = { name ->
                onRenamePreset(preset.id, name)
                editingId = null
            },
            onDelete = {
                deletingId = preset.id
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }
    presets.firstOrNull { it.id == deletingId }?.let { preset ->
        DeletePresetDialog(
            name = preset.displayName(labelOf),
            builtIn = preset.builtIn != null,
            onConfirm = {
                onDeletePreset(preset.id)
                deletingId = null
            },
            onDismiss = { deletingId = null },
        )
    }
}

/** A built-in comes back only through *Restore to default*, which resets everything else too, so the body says so. */
@Composable
private fun DeletePresetDialog(
    name: String,
    builtIn: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_delete_confirm_title, name)) },
        text =
            if (builtIn) {
                {
                    Text(
                        stringResource(R.string.preset_delete_builtin_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                null
            },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.preset_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.preset_cancel)) } },
    )
}

/**
 * The line under the chips: a built-in's own hint while it still has its shipped values, whatever it
 * is called; the saved-preset hint for anything else lit; and with nothing lit, how to use the row —
 * or, once every preset is deleted, what the ＋ chip does.
 */
@StringRes
internal fun presetHint(
    lit: StoredPreset?,
    anyPresets: Boolean,
): Int =
    when {
        lit == null -> if (anyPresets) R.string.presets_hint else R.string.preset_save_body
        lit.isAsShipped -> lit.builtIn?.hint ?: R.string.preset_saved_hint
        else -> R.string.preset_saved_hint
    }

/**
 * One dialog for both saving and renaming, because both are "type a name".
 *
 * [taken] says whether another preset already has the typed name. Saving over it is allowed and is
 * how a preset's values get updated, so the confirm button says *Replace*; renaming onto it is not,
 * because two chips would answer to one name. [sameSettingsAs] names the preset that already carries
 * the current values, and while there is one nothing can be saved: two chips would do one thing.
 */
@Composable
private fun PresetNameDialog(
    title: String,
    initialName: String,
    taken: (String) -> Boolean,
    takenMeansReplace: Boolean,
    sameSettingsAs: String?,
    onConfirm: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val isTaken = name.isNotBlank() && taken(name)
    val canConfirm = sameSettingsAs == null && name.isNotBlank() && (takenMeansReplace || !isTaken)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                if (onDelete == null) {
                    Text(stringResource(R.string.preset_save_body), style = MaterialTheme.typography.bodyMedium)
                }
                if (sameSettingsAs != null) {
                    Text(
                        stringResource(R.string.preset_same_settings, sameSettingsAs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
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
