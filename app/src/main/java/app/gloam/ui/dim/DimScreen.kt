package app.gloam.ui.dim

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gloam.R
import app.gloam.shade.ShadeEnd
import app.gloam.shade.canDrawShade
import app.gloam.shade.escapeHatchLive
import app.gloam.shade.isDue
import app.gloam.shade.readBacklightTop
import app.gloam.shade.shadePermissionIntent
import app.gloam.shade.startShade
import app.gloam.shade.stopShade
import app.gloam.theme.Spacing
import app.gloam.tip.tipAskDue
import app.gloam.ui.appViewModelExtras
import app.gloam.ui.common.WarningBanner
import app.gloam.work.AppChannel
import app.gloam.work.NotificationPermissionOutcome
import app.gloam.work.isIgnoringBatteryOptimisations
import app.gloam.work.notificationRationaleAvailable
import app.gloam.work.notificationsAllowed
import app.gloam.work.openAppNotificationSettings
import app.gloam.work.openChannelNotificationSettings
import app.gloam.work.rememberNotificationPermissionAsk

/**
 * **Which button the escape-hatch warning carries.** Whether it is shown at all is
 * [escapeHatchLive]'s answer rather than this enum's, which is why there is no `None` case: "the
 * hatch is live" is one named predicate that Phase 2b's gate also reads, and a fourth entry here
 * would be a second copy of it waiting to drift.
 *
 * **Derived on every resume from what the system currently reports, not from how an ask ended.** An
 * outcome-driven banner is stale exactly when it matters: the user taps *Open notification settings*,
 * grants the permission there, comes back — and a remembered `PermanentlyDenied` still tells them
 * they have no Stop button. It would also miss the opposite case entirely, a permission revoked while
 * the app sat in the background with the shade up.
 *
 * Kotlin note: an enum rather than a handful of booleans threaded through the composable. The three
 * bad states each need a different button, and a discriminated set of cases is what stops an
 * impossible combination being drawn by accident — the same reason you would reach for a union type
 * in TypeScript instead of `{ denied?: boolean; muted?: boolean }`.
 */
private enum class ShadeWarning {
    /** Permission off, and the system dialog is still worth firing. */
    AskPermission,

    /** Permission off and the dialog spent — the app's own settings page is the only way back. */
    OpenAppSettings,

    /** Permission on, the channel muted — the fix is one screen further down than the page above. */
    OpenChannelSettings,
}

/**
 * The one screen the app is about: two sliders and a switch for the shade.
 *
 * The screen owns starting and stopping the service rather than the `ViewModel` doing it — a
 * `ViewModel` has no `Context`, and handing it one is how it starts outliving its own scope.
 *
 * ## The entry gate
 *
 * Starting the shade for the first time asks for `POST_NOTIFICATIONS`, because the ongoing
 * notification's **Stop** action is the documented way out of a very dark screen and on Android 13+
 * that notification does not exist without the permission. The ask fires from the start button
 * rather than at first launch — the moment the feature that needs it is switched on — and **only
 * ever with no shade on screen**.
 *
 * That last rule is a platform constraint rather than a preference. The system hides or refuses
 * touches on non-system overlay windows while a permission dialog is up, precisely so that an app
 * cannot draw over the dialog and trick somebody into tapping *Allow*. Our shade is
 * `FLAG_NOT_TOUCHABLE`, which may or may not exempt it, and nobody should assume either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DimScreen(
    onOpenSchedule: () -> Unit,
    onOpenSupport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DimViewModel =
        viewModel(factory = DimViewModel.Factory, extras = appViewModelExtras()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    // Re-read on every resume rather than remembering the answer. `SYSTEM_ALERT_WINDOW` is a
    // special permission the user grants on a settings screen we send them to, and they can revoke
    // it there just as easily while the app is in the background — a cached `true` becomes a
    // service that starts and then cannot add its window.
    var canDraw by remember { mutableStateOf(context.canDrawShade()) }

    // The same property, three more times. Every one of these is a switch on a settings screen this
    // screen deliberately hands the user off to, so every one of them can change while the app is
    // backgrounded and none of them may be cached across that trip.
    //
    // `hatchLive` is the whole question — is there a way out of the shade that is not this screen? —
    // and the two below it only pick which button the answer carries. That is why the permission is
    // still read separately and the channel is not: the channel is the one thing left when the
    // permission is on and the hatch is dead anyway.
    var hatchLive by remember { mutableStateOf(context.escapeHatchLive()) }
    var notificationsOn by remember { mutableStateOf(context.notificationsAllowed()) }
    var rationaleLeft by remember { mutableStateOf(activity.notificationRationaleAvailable()) }

    // Whether this device hands Gloam a brightness it can trust. Answerable with no service and no
    // override applied — the resource resolves or it does not, the setting reads or it does not, the
    // decoded float is in range or it is not — and re-read on resume with the rest, because the user
    // can change their brightness mode on a settings screen and come back.
    var backlightAvailable by remember { mutableStateOf(readBacklightTop(context) != null) }

    // The fourth switch on a settings screen we hand people off to, and the only one of the plan's
    // four asks besides the two above that the app can read at all. It is here rather than only on
    // the schedule screen because the user who forgot they set a schedule is by definition not
    // opening the schedule screen — so the summary row below has to be able to say it.
    var exempt by remember { mutableStateOf(context.isIgnoringBatteryOptimisations()) }

    // **Session state, deliberately not a DataStore key.** The only thing a stored flag would buy is
    // telling "never asked" apart from "asked twice and refused", and those two want the same
    // behaviour anyway — fire the launcher and let it answer. It flips the moment the launcher comes
    // back with `PermanentlyDenied`, which is Android saying the dialog is gone for good.
    var dialogSpent by remember { mutableStateOf(false) }

    fun reread() {
        canDraw = context.canDrawShade()
        hatchLive = context.escapeHatchLive()
        notificationsOn = context.notificationsAllowed()
        rationaleLeft = activity.notificationRationaleAvailable()
        backlightAvailable = readBacklightTop(context) != null
        exempt = context.isIgnoringBatteryOptimisations()
    }

    /**
     * **The deadline nobody was alive to fire.**
     *
     * HyperOS kills the process and `START_STICKY` does not always bring it back: the window dies,
     * the stored intent still says running, and the deadline sits there while the clock walks past
     * it. Open Gloam the next morning and the button says *Stop* over a screen with no shade on it,
     * under a line reading "Turns off at 00:00" — the one way this app's two stored values can
     * disagree with each other.
     *
     * This is a comparison of two stored values against the wall clock, **not** a check of whether
     * a process is alive, which is the thing `CLAUDE.md` forbids. The stored intent still decides
     * what should be on screen; this only lets the user's own earlier instruction finish arriving.
     *
     * `stopShade()` as well as the write, because the two are not the same question: if a service
     * *is* alive and its own loop is still inside its recheck interval, clearing the deadline alone
     * would cancel that loop and leave the shade up over an intent that says it is down. On the
     * usual path — nothing alive — `stopService` is a no-op.
     */
    fun endShadeIfDue() {
        if (state.running && isDue(System.currentTimeMillis(), state.offAtMillis)) {
            viewModel.endShade(ShadeEnd.ByDeadline)
            context.stopShade()
        }
    }

    // Two triggers, because the check depends on two things that move independently. The state keys
    // catch a deadline that is already past when the first stored values arrive — a cold launch
    // after a force-stop, where `ON_RESUME` has come and gone before DataStore answered. The resume
    // observer catches the other half: the values did not change, the clock did, because the app sat
    // in the background for three hours.
    LaunchedEffect(state.running, state.offAtMillis) { endShadeIfDue() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    reread()
                    endShadeIfDue()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The system reports no result for this screen, so the contract is only how we learn the user
    // came back. The answer itself comes from re-reading, above and here.
    val requestOverlay =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            reread()
        }

    /**
     * The intent and the service, together, in one place.
     *
     * They are written together on purpose: if `beginShade()` landed on the button press and the
     * user backgrounded the app mid-dialog, DataStore would say *running* with no service alive, and
     * that flag is exactly what Phase 2's reboot restore reads back.
     */
    fun beginDimming() {
        viewModel.beginShade()
        context.startShade()
    }

    // Asynchronous, and that changes the call order rather than adding a callback to the end of it —
    // closer to `await` than to a fire-and-forget `.then()`, because everything after the ask depends
    // on its answer. **The shade starts in all three outcomes, refusal included.** Refusing to dim
    // somebody's screen because they refused a notification is the app deciding it knows better; the
    // honest move is to run and to say plainly what they have given up.
    val requestNotifications =
        rememberNotificationPermissionAsk { outcome ->
            dialogSpent = outcome == NotificationPermissionOutcome.PermanentlyDenied
            beginDimming()
            reread()
        }

    /**
     * **The one place the system dialog is raised, and it takes the shade down first.**
     *
     * §1's rule is that the ask is only ever made with no shade on screen, and it is a platform
     * constraint rather than a preference: the system hides or refuses touches on non-system overlay
     * windows while a permission dialog is up, so that an app cannot draw over the dialog and trick
     * somebody into tapping *Allow*. `FLAG_NOT_TOUCHABLE` may or may not exempt ours, and a user who
     * cannot tap the dialog is stuck at a very dark screen — R7 measures which it is, but the rule
     * holds either way, so it is enforced here rather than trusted to the answer.
     *
     * From the start button the shade is already down and `stopShade()` is a no-op. From the
     * warning's *Allow notifications* button it is not, which is the case this exists for: down
     * before the dialog, and back up in the outcome, because [beginDimming] runs in all three.
     *
     * **`shadeRunning` is deliberately not written here.** The stored intent is what the user asked
     * for and the live service is what is on screen — different questions — and clearing the intent
     * for the length of a dialog is what Phase 2's reboot restore would read back as *they turned it
     * off*.
     */
    fun askForNotifications() {
        context.stopShade()
        requestNotifications()
    }

    /**
     * Start, by whichever route the notification permission leaves open. Shared by the Start button
     * and the presets, so a preset tap can never start a shade the button would have asked first.
     */
    fun startOrAsk() {
        when {
            // Granted, or one denial already spent. Android permits two denials before the dialog
            // stops appearing, and the second is spent only by a deliberate tap on a control that
            // says *Allow notifications* — never by somebody who came here to dim their screen.
            notificationsOn || rationaleLeft -> beginDimming()
            else -> askForNotifications()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dim_title)) }) },
    ) { insets ->
        Column(
            modifier =
                Modifier
                    .padding(insets)
                    .verticalScroll(rememberScrollState()),
        ) {
            if (!canDraw) {
                PermissionExplainer(
                    onGrant = { requestOverlay.launch(context.shadePermissionIntent()) },
                )
                return@Column
            }

            // Only while the shade is actually up. A first-launch user who has started nothing is
            // not missing an escape hatch, because there is nothing to escape from yet.
            if (state.shadeUp && !hatchLive) {
                // Computed here rather than above, because it is only meaningful under this guard:
                // the last branch is "the channel is muted", and that is only what is left over once
                // the hatch is known dead and the permission known present.
                val warning =
                    when {
                        // Never asked and spent are indistinguishable from here, and both want the
                        // launcher.
                        !notificationsOn && (rationaleLeft || !dialogSpent) -> ShadeWarning.AskPermission
                        !notificationsOn -> ShadeWarning.OpenAppSettings
                        else -> ShadeWarning.OpenChannelSettings
                    }
                NotificationWarning(
                    warning = warning,
                    onAct = {
                        when (warning) {
                            ShadeWarning.AskPermission -> askForNotifications()
                            ShadeWarning.OpenAppSettings -> context.openAppNotificationSettings()
                            ShadeWarning.OpenChannelSettings ->
                                context.openChannelNotificationSettings(AppChannel.Shade)
                        }
                    },
                )
            }

            // **The second caller arrived, so the extraction happened** — `DimControls.kt` holds
            // these, and what stays on this screen is everything a dialog is the wrong place for:
            // the explainer above, the warning banner, the resume re-read, and the permission
            // branch this button's callback goes through.
            DimControls(
                dimLevel = state.dimLevel,
                warmth = state.warmth,
                warmthColor = state.warmthColor,
                // `shadeUp`, not the intent: after HyperOS kills the process the intent still says
                // running over a screen with nothing on it, and this button is the thing that has
                // to say so. Start from there re-asks for what was already stored, which is a fresh
                // deadline from now — the same as any other Start.
                running = state.shadeUp,
                onDimLevel = viewModel::setDimLevel,
                onWarmth = viewModel::setWarmth,
                onWarmthColor = viewModel::setWarmthColor,
                onToggleRunning = {
                    if (state.shadeUp) {
                        viewModel.endShade(ShadeEnd.ByHand)
                        context.stopShade()
                    } else {
                        startOrAsk()
                    }
                },
                // A preset is "turn on reading", not "move the sliders to reading's numbers", so it
                // starts a shade that is down. One that is already up just follows the new values,
                // because the service collects them — restarting it would re-set the deadline.
                onPreset = { preset ->
                    viewModel.applyPreset(preset)
                    if (!state.shadeUp) startOrAsk()
                },
                savedPresets = state.savedPresets,
                onSavedPreset = { preset ->
                    viewModel.applySavedPreset(preset)
                    if (!state.shadeUp) startOrAsk()
                },
                onSavePreset = viewModel::saveCurrentAsPreset,
                onRenamePreset = viewModel::renamePreset,
                onDeletePreset = viewModel::deletePreset,
            )

            // **Everything that is not the dim level, in one card.** The redesign's column takes
            // the height two sliders and a hint used to have, so the backlight switch moved in here
            // beside the auto-off chips, and the schedule keeps the summary row it had — stating the
            // window rather than the setting, so somebody who set a schedule three weeks ago and
            // forgot finds out on the screen they already open rather than two taps into Settings
            // (`docs/phase-4.md` §10).
            AutoOffCard(
                autoOff = state.autoOff,
                offAtMillis = state.offAtMillis,
                running = state.shadeUp,
                onAutoOff = viewModel::setAutoOff,
                lowerBacklight = state.lowerBacklight,
                backlightAvailable = backlightAvailable,
                onLowerBacklight = viewModel::setLowerBacklight,
                schedule = state.schedule,
                scheduleAtRisk = !exempt,
                onOpenSchedule = onOpenSchedule,
                modifier =
                    Modifier.padding(
                        start = Spacing.base,
                        end = Spacing.base,
                        top = Spacing.section,
                        bottom = Spacing.section,
                    ),
            )

            val installedAt =
                remember { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }
            val tipAsk = state.tipAsk
            if (tipAsk.available &&
                tipAskDue(tipAsk.asked, tipAsk.shadeStarts, installedAt, System.currentTimeMillis())
            ) {
                TipAskCard(
                    onTip = {
                        viewModel.answerTipAsk()
                        onOpenSupport()
                    },
                    onDecline = viewModel::answerTipAsk,
                )
            }
        }
    }
}

/** The one time Gloam mentions the tip unasked. Either button answers it for good. */
@Composable
private fun TipAskCard(
    onTip: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.base, end = Spacing.base, bottom = Spacing.section),
    ) {
        Column(modifier = Modifier.padding(Spacing.base)) {
            Text(stringResource(R.string.dim_tip_ask_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.dim_tip_ask_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.tight),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                modifier = Modifier.padding(top = Spacing.tight),
            ) {
                TextButton(onClick = onTip) { Text(stringResource(R.string.support_tip)) }
                TextButton(onClick = onDecline) { Text(stringResource(R.string.dim_tip_ask_decline)) }
            }
        }
    }
}

/**
 * The shade is up and the notification that stops it cannot appear.
 *
 * Stated as what the user has lost rather than as which permission is missing — "no Stop button
 * outside Gloam" is the consequence; "notifications are denied" is trivia they would have to
 * translate themselves while looking at a very dark screen.
 */
@Composable
private fun NotificationWarning(
    warning: ShadeWarning,
    onAct: () -> Unit,
) {
    WarningBanner(
        title = stringResource(R.string.dim_notification_warning_title),
        body =
            stringResource(
                if (warning == ShadeWarning.OpenChannelSettings) {
                    R.string.dim_notification_warning_channel_body
                } else {
                    R.string.dim_notification_warning_body
                },
            ),
        actionLabel =
            stringResource(
                if (warning == ShadeWarning.AskPermission) {
                    R.string.dim_notification_warning_ask
                } else {
                    R.string.dim_notification_warning_settings
                },
            ),
        onAct = onAct,
    )
}

@Composable
private fun PermissionExplainer(onGrant: () -> Unit) {
    Column(modifier = Modifier.padding(Spacing.base)) {
        Text(
            text = stringResource(R.string.dim_permission_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.dim_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.tight),
        )
        Button(onClick = onGrant, modifier = Modifier.padding(top = Spacing.base)) {
            Text(stringResource(R.string.dim_permission_grant))
        }
    }
}
