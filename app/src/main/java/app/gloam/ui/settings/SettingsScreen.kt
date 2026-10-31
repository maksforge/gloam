package app.gloam.ui.settings

import android.app.StatusBarManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gloam.R
import app.gloam.data.ThemeMode
import app.gloam.shade.canRequestAddShadeTile
import app.gloam.shade.requestAddShadeTile
import app.gloam.theme.Spacing
import app.gloam.ui.appViewModelExtras
import app.gloam.ui.common.SectionHeader
import app.gloam.ui.common.SwitchRow
import app.gloam.ui.support.SupportRequest
import app.gloam.ui.support.hintRes
import app.gloam.ui.support.sendSupportMail
import app.gloam.ui.support.titleRes
import app.gloam.work.hasAutostartSettings
import app.gloam.work.openAutostartSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSupport: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModel.Factory, extras = appViewModelExtras()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) },
    ) { insets ->
        Column(modifier = Modifier.padding(insets).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_appearance))

            Row(modifier = Modifier.padding(horizontal = Spacing.base)) {
                for (mode in ThemeMode.entries) {
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                        modifier = Modifier.padding(end = Spacing.tight),
                    )
                }
            }

            // Its own section rather than a row under *Appearance*: this is not what the app
            // looks like, it is which of Gloam's two control surfaces the icon opens. The hint
            // names the routes it does **not** move, because the preference only moves one of
            // three — the notification and the tile reach the small controls either way.
            SectionHeader(stringResource(R.string.settings_controls))
            SwitchRow(
                title = stringResource(R.string.settings_launcher_compact),
                subtitle = stringResource(R.string.settings_launcher_compact_hint),
                checked = state.launcherCompact,
                onChange = viewModel::setLauncherCompact,
            )
            SwitchRow(
                title = stringResource(R.string.settings_controls_bubble),
                subtitle = stringResource(R.string.settings_controls_bubble_hint),
                checked = state.controlsBubble,
                onChange = viewModel::setControlsBubble,
            )

            QuickTileRow()

            // Advice rather than a control, because the platform leaves nothing to control: the
            // keyguard hides every `TYPE_APPLICATION_OVERLAY` window, which releases the shade's
            // backlight override along with it, so the lock screen comes up at the user's own
            // brightness however far Gloam was dimming (docs/night-reading-research.md §4). No
            // button to display settings — the brightness slider is one pull away in quick settings.
            SectionHeader(stringResource(R.string.settings_lock_screen))
            Text(
                text = stringResource(R.string.settings_lock_screen_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.base),
            )

            // Advice again, and for the same reason: flicker is the panel's way of dimming at low
            // brightness, and no API lets an app change it (docs/night-reading-research.md §2). The
            // toggle's label is passed in rather than repeated in the body, so renaming it renames it
            // here too.
            SectionHeader(stringResource(R.string.settings_flicker))
            Text(
                text = stringResource(R.string.settings_flicker_body, stringResource(R.string.dim_backlight_label)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.base),
            )

            SectionHeader(stringResource(R.string.settings_language))
            LanguageRow()
            LanguageReportRow()

            AutostartRow()

            RestoreDefaultsRow(onRestore = viewModel::restoreDefaults)

            SectionHeader(stringResource(R.string.settings_about))
            // Above the licences, because it is the row a tester needs and the other is the row a
            // licence obligation needs. Both are in *About* for the same reason: it is where someone
            // looks when they want to know something about the app rather than change it.
            SettingsRow(stringResource(R.string.settings_support), onClick = onOpenSupport)
            SettingsRow(stringResource(R.string.settings_licences), onClick = onOpenLicences)

            // The developer-only section. In a release build this composable is a no-op that
            // renders nothing — see `src/release/…/DebugSettings.kt`. It is a source-set seam
            // rather than an `if (BuildConfig.DEBUG)` because with `isMinifyEnabled = false` a
            // statically-false branch is still compiled into the AAB, and its strings are still
            // inside the translation gate. A hide is not a strip.
            DebugSettings()
        }
    }
}

/**
 * The autostart hand-off: **a permanent row, not a prompt, and no key remembers it** (ADR-0003).
 *
 * On HyperOS, autostart is what decides whether the process is started for a broadcast at all — not
 * `BOOT_COMPLETED` specifically, any broadcast — so reboot restore is a feature the ROM allows or
 * does not. Three reasons this is a row that is always here rather than a one-off ask:
 *
 * - **The app cannot know whether it worked.** There is no appop, the setting has no public state,
 *   and the OEM screen's own checkbox reports `checked=false` for granted rows (`device-gate.py`
 *   infers it from where the app's name sits relative to a divider, over `adb`, which is a
 *   host-side capability the app will never have). A confirmation checkbox here would be the app
 *   repeating the user's guess back to them as its own assurance. A row that is always present
 *   makes no claim at all.
 * - **A once-only prompt needs a stored "offered" flag** — a written DataStore key, frozen the day
 *   a stranger's phone holds it, bought to answer a question this row does not ask. This phase
 *   deleted one such key; adding another in the same phase would be a poor trade.
 * - **The failure it explains is invisible and recurring**, and needs explaining on the day the
 *   shade does not come back, which is not the day the app was installed.
 *
 * Gated on [hasAutostartSettings], which is honest only because the manifest names
 * `com.miui.securitycenter` in `<queries>` — without that, package-visibility filtering answers
 * "no such activity" on a phone where it exists. So the row is absent on every phone with no such
 * screen, rather than being a button that does nothing.
 *
 * `remember` rather than a re-read on resume, unlike the dim screen's permission checks: whether the
 * ROM *has* an autostart screen is a property of the phone, not a switch the user can flip.
 */
@Composable
private fun AutostartRow() {
    val context = LocalContext.current
    if (!remember(context) { context.hasAutostartSettings() }) return

    SectionHeader(stringResource(R.string.settings_restart))
    Text(
        text = stringResource(R.string.settings_restart_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.base),
    )
    Button(
        // The Boolean it returns is dropped rather than shown: the row only exists because
        // `resolveActivity` already answered, so a false here means the screen was resolvable and
        // then refused us — nothing the user could act on, and a message about it would be about
        // Gloam rather than about their phone.
        onClick = { context.openAutostartSettings() },
        modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug),
    ) {
        Text(stringResource(R.string.settings_restart_open))
    }
}

/**
 * The Quick Settings tile hand-off: **a permanent row, like the autostart one, and for one of its
 * three reasons rather than all three.**
 *
 * The reason it shares is the load-bearing one: **the app cannot read back whether the tile is
 * added.** The platform offers `onTileAdded` / `onTileRemoved` and no getter, so the only way to
 * answer would be to remember — and a remembered `tile_added` key is live state in a store Auto
 * Backup carries whole to the next phone, where the tile is not added and the key says it is
 * (CLAUDE.md, and `shade_began_at` is what that cost once). So this row never claims the tile is
 * there or missing. It offers, every time, and says only what the platform just answered.
 *
 * **Placed under *Controls* rather than in a section of its own**, because that section's hint
 * already names the tile as one of the three routes to the small controls. It is a second escape
 * hatch as well, which would argue for putting it beside the notification warning on the dim
 * screen — but that warning is a *live* read that appears when something is wrong, and this is a
 * standing offer. Mixing the two would make the warning look conditional.
 *
 * **The result is Compose state and nothing else.** It is deliberately not remembered across
 * navigation: it describes one call, and a stale *"the tile is in Quick Settings"* after the user
 * removed it would be the app asserting something it cannot know. `TILE_NOT_ADDED` renders nothing
 * — the user just declined a dialog and does not need it repeated back — and so does any result
 * this `when` does not recognise, which keeps a future platform constant from printing the wrong
 * sentence.
 */
@Composable
private fun QuickTileRow() {
    val context = LocalContext.current
    var result by remember { mutableStateOf<Int?>(null) }

    SectionHeader(stringResource(R.string.settings_tile))
    Text(
        text = stringResource(R.string.settings_tile_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.base),
    )
    if (!canRequestAddShadeTile) {
        Text(
            text = stringResource(R.string.settings_tile_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug),
        )
        return
    }
    Button(
        onClick = { context.requestAddShadeTile { result = it } },
        modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug),
    ) {
        Text(stringResource(R.string.settings_tile_add))
    }
    result?.tileResultMessage()?.let { message ->
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug),
        )
    }
}

/**
 * `null` means *say nothing*, which is the right answer twice: for a declined dialog, and for any
 * result this build does not know about.
 *
 * The error branch is a range test rather than a list of the six `TILE_ADD_REQUEST_ERROR_*`
 * constants, because they are contiguous above the results and a seventh would otherwise fall
 * through to silence — the one case where the button really did nothing.
 */
private fun Int.tileResultMessage(): Int? =
    when {
        this == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
            this == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        -> R.string.settings_tile_added
        this >= StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE -> R.string.settings_tile_unavailable
        else -> null
    }

/**
 * *Restore to default*, behind a confirmation because nothing can bring the old values back. It
 * resets settings only; what it leaves alone is listed at `AppPreferences.restoreDefaults`.
 */
@Composable
private fun RestoreDefaultsRow(onRestore: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.settings_restore))
    Text(
        text = stringResource(R.string.settings_restore_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.base),
    )
    Button(
        onClick = { confirming = true },
        modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug),
    ) {
        Text(stringResource(R.string.settings_restore_button))
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_restore_confirm_title)) },
            text = { Text(stringResource(R.string.settings_restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onRestore()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.settings_restore_cancel)) }
            },
        )
    }
}

@Composable
private fun LanguageRow() {
    // Read on every composition rather than held in state: `setAppLanguage` recreates the Activity,
    // so this composable is rebuilt from scratch and the fresh read is always correct.
    val current = currentAppLanguage()
    // `FlowRow` rather than `Row`, and the same reasoning the auto-off chips carry: ten chips do not
    // fit one line. This one is not a judgement call — **it shipped broken and the phone said so.**
    // With two languages a `Row` was correct and looked correct; at nine, a device sweep found only
    // *System*, *English*, *Polski* and *Čeština* laid out and the remaining six simply absent, so
    // six of the nine languages were unreachable from the switcher that exists to reach them. No
    // test could see it: `AppLanguageTest` compares the enum to `locales_config.xml` and neither
    // knows how wide a chip is. Do not narrow this back to a `Row`.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        modifier = Modifier.padding(horizontal = Spacing.base),
    ) {
        FilterChip(
            selected = current == null,
            onClick = { setAppLanguage(null) },
            label = { Text(stringResource(R.string.settings_language_system)) },
        )
        for (language in AppLanguage.entries) {
            FilterChip(
                selected = current == language,
                onClick = { setAppLanguage(language) },
                label = { Text(stringResource(language.labelRes)) },
            )
        }
    }
}

/**
 * *Something read wrong?* — the channel that stands in for a native read-through (ADR-0014).
 *
 * It is directly under the picker on purpose. Seven of the nine languages ship without a native
 * speaker having read them, so the fluency half of that review happens in the field, one report at a
 * time — and this is the screen somebody is on at the moment they notice. A row on Help and feedback
 * as well would be a second name for the same thing.
 *
 * The mail carries the resolved locale in its own block (`SupportHandoff`), so nobody has to say
 * which language they are reading, and `Gloam #language` sorts the reports apart from bugs.
 */
@Composable
private fun LanguageReportRow() {
    val context = LocalContext.current
    // Same contract as the Support screen's: it says "the tap you just made went nowhere", which is
    // only true of that tap, so it is deliberately not `rememberSaveable` and a rotation clears it.
    var mailMissing by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { mailMissing = !context.sendSupportMail(SupportRequest.Language) }
                .padding(horizontal = Spacing.base, vertical = Spacing.snug),
    ) {
        Text(
            text = stringResource(SupportRequest.Language.titleRes()),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(SupportRequest.Language.hintRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Inline and in the error colour, the same way the Support screen reports it: there is no
        // snackbar host anywhere in this app, and the failure belongs to the row that was tapped.
        if (mailMissing) {
            Text(
                text = stringResource(R.string.support_no_mail_app),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.base, vertical = Spacing.base),
    )
}

private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
