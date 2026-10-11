package app.gloam

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gloam.shade.PANEL_SIDE_MARGIN_DP
import app.gloam.shade.ShadeEnd
import app.gloam.shade.canDrawShade
import app.gloam.shade.dismissPanelAndBubble
import app.gloam.shade.escapeHatchLive
import app.gloam.shade.panelOnScreen
import app.gloam.shade.showControlsBubble
import app.gloam.shade.startShade
import app.gloam.shade.stopShade
import app.gloam.theme.AppTheme
import app.gloam.ui.dim.CompactControls
import app.gloam.ui.dim.DimViewModel
import app.gloam.work.refreshLentLocation
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * **The compact controls: the same sliders in a floating window, for the shade that is already up.**
 *
 * This is the surface the notification and (from Phase 2b) the tile reach — the two doors that can
 * be opened *while the shade is on screen*, without going back to the launcher and without the full
 * app's first-run framing. `docs/phase-3.md` §2 is the argument for it; what follows is only the
 * part that is not obvious from reading the code.
 *
 * **And it is the launcher's door too** — shape iv, which is the manifest's `<intent-filter>` rather
 * than anything in this file. The icon used to land on [MainActivity], which read the preference and
 * forwarded here; that could not stop the full app flashing, because a launcher tap on a task that
 * already exists is a `moveTaskToFront` and the platform draws that task's window before any code of
 * ours runs. An intent that names this Activity resolves into a different task entirely — the empty
 * `taskAffinity` — so there is nothing of the full app to draw. What is left in code is the other
 * direction: [forwardIfFullAppWanted], for the user who has turned the preference off.
 *
 * ## It is a dialog because a theme said so, before any of this ran
 *
 * `Theme.App.Controls` is what makes this window float, and the platform reads it out of the
 * manifest when the window is created — earlier than `onCreate`, earlier than any code of ours.
 * There is no JS analogue: nothing in a browser decides your document's shape from a declaration it
 * read before your script existed. That is also why the compact host is a *second activity* rather
 * than a flag on [MainActivity]: one activity has one manifest theme, and the starting window is
 * drawn from it either way.
 *
 * `AppCompatActivity` for the same single reason [MainActivity] is one — `AppCompatDelegate` is what
 * applies the in-app language (ADR-0004) and moves the night mode (ADR-0006), and it throws on a
 * theme that does not descend from `Theme.AppCompat`.
 *
 * ## What this host deliberately does not have
 *
 * No overlay explainer, no notification warning banner, no permission hand-off, no top bar. Every
 * one of those is first-run framing, and a dialog summoned over somebody else's app in the dark is
 * the wrong place to meet Gloam for the first time. So when either precondition is missing this host
 * does not draw a degraded version of itself — it *becomes* the full app, which is the guard below.
 *
 * **Nor does it have the settings**, which is [CompactControls]' rule rather than this class's: the
 * backlight switch and everything after it belong to the full app, and the cog is how they are
 * reached. This host no longer reads `readBacklightTop` at all, because nothing it draws asks.
 *
 * **It is not an escape hatch** (`docs/phase-2.md` §2): reaching it still needs sight of Gloam's own
 * UI, under the shade. `EscapeHatch.kt`'s inventory does not gain a row for it.
 */
class ControlsActivity : AppCompatActivity() {
    /**
     * Whether this window closing should leave the bubble behind. True for Back and a tap outside,
     * which is the user turning back to what they were reading; false for every way out that says
     * something else — the ✕, the cog, a forward to the full app, the panel taking over (ADR-0016).
     */
    private var leavesBubble = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // **One edge bar at a time, the one opened last** (`panelOnScreen` has the why). Opening this
        // takes a panel down, and first rather than after the guards: the forward to the full app is
        // an opening too, and the panel's own cog already closes the panel on its way there.
        dismissPanelAndBubble()

        // Before anything is composed: a dialog with a broken start button on it is worse than no
        // dialog, and so is a dialog the user asked the icon not to open. `finish()` from `onCreate`
        // skips straight to `onDestroy` without composing.
        //
        // Kotlin note: `||` short-circuits exactly as it does in JS, so the guard's two live reads
        // are never taken on a tap that is leaving for the full app anyway.
        if (forwardIfFullAppWanted(intent) || forwardIfUnusable()) return

        val app = application as MainApplication

        // The other half: a panel appearing *after* this opened is the user asking for that one, so
        // this one goes. `drop(1)` skips the value current at subscription — a panel that is up right
        // now is one [dismissPanelAndBubble] above is already taking down, not a new one.
        //
        // Kotlin note: `lifecycleScope` is cancelled in `onDestroy`, so this wait needs no manual
        // unsubscribe — the `AbortController` is the Activity's own lifetime. Not `repeatOnLifecycle`:
        // a panel summoned while this sits stopped in the background should still close it, or it
        // is back on screen the moment the user returns.
        lifecycleScope.launch {
            panelOnScreen.drop(1).first { up -> up }
            leavesBubble = false
            finish()
        }

        // **The same edge the panel uses, for the same reason.** These two surfaces are one thing
        // to the user — the notification opens whichever the shade's state allows — so the bar has
        // to be in the same place in both, which means centred on the trailing edge rather than in
        // the bottom corner. A floating window's `x` is an offset from the edge its gravity names,
        // not an absolute coordinate; there is no `y` because the other axis is centred.
        // **Tap anywhere else to put it away.** A floating window is touch-modal unless it says
        // otherwise, so a tap outside the bar already comes *here* rather than to the app behind —
        // it was simply being swallowed. This turns it into `finish()`, which is what a dialog over
        // somebody else's screen should do and the only dismissal this host had besides Back.
        //
        // Set in code rather than as `windowCloseOnTouchOutside` in the theme: AppCompat's dialog
        // themes are built on `Theme.AppCompat` rather than on the platform's `Theme.Dialog`, so
        // whether the attribute is inherited at all depends on the support library's version. One
        // line here does not.
        setFinishOnTouchOutside(true)

        val density = resources.displayMetrics.density
        window.setGravity(Gravity.CENTER_VERTICAL or Gravity.END)
        window.attributes =
            window.attributes.apply {
                x = (PANEL_SIDE_MARGIN_DP * density).toInt()
            }

        setContent {
            val themeMode by
                app.preferences.themeMode.collectAsStateWithLifecycle(initialValue = app.startupThemeMode)

            AppTheme(themeMode = themeMode) {
                // **No `Surface` behind it.** The edge bar paints its own shape on a window
                // `Theme.App.Controls` already made transparent; a surface here would be a
                // rectangle of theme colour around a control designed to float.
                //
                // **`fillMaxSize` is load-bearing and the phone is what proved it (R8.)** A
                // floating window is measured `AT_MOST` the display, so filling *takes* all of it
                // and the window comes up 320x845dp with the bar aligned inside. Letting it wrap
                // the bar instead looks tidier, positions identically, and **breaks dragging**: the
                // press still registers and every move after it is lost, so the column can only be
                // pointed at, never pulled. Measured both ways with the same synthetic swipe — 26%
                // (the finger's release) filled, 39% (the finger's press) wrapped.
                //
                // So the window stays large and the empty part of it is made to mean something
                // instead. `detectTapGestures` here fires only on a press the bar did not take —
                // `levelDrag` consumes its own down, and `awaitFirstDown` under this detector wants
                // an unconsumed one — so a tap on the control sets a level and a tap anywhere else
                // closes the window. [setFinishOnTouchOutside] covers the strip beyond the window's
                // own 320dp; between them there is nowhere left to tap that does nothing.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { finish() } },
                ) {
                    ControlsBody(
                        onStart = ::startShade,
                        onStop = ::stopShade,
                        onOpenApp = ::openFullApp,
                        onClose = {
                            leavesBubble = false
                            finish()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /**
     * **A launcher tap on a task that already exists never calls `onCreate`** — the platform fact
     * F2 read off the phone when this Activity was the forward's *destination* rather than the
     * icon's target. It applies here now for the same reason: this window is `singleTop` and lives
     * in its own task, so a second tap on the icon while it is up is delivered here instead.
     *
     * Without this override the preference would be honoured on the tap that created the window and
     * ignored on every tap after it, for as long as the window survived — which is exactly the bug
     * F2 found, moved one component along.
     *
     * `setIntent` first, so a later reader — a resume, a configuration change — is not still being
     * told this Activity was entered from the launcher.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second tap on the icon is an opening as well, for `onCreate`'s rule.
        dismissPanelAndBubble()
        // `isFinishing` for [forwardIfUnusable]'s reason: this window may already be on its way out,
        // and starting the full app twice is two task animations for one tap.
        if (!isFinishing) forwardIfFullAppWanted(intent)
    }

    /**
     * **The launcher preference, read on the one entry it is about.**
     *
     * *Open the small controls from the icon* is on by default, so this is the minority path: the
     * user who wants the icon to open Gloam itself. The forward costs them one hop through a window
     * that never draws — `Theme.App.Controls` is floating and translucent, so the platform paints no
     * placeholder for it — and then [MainActivity] opens with its own starting window, which is the
     * placeholder that is finally telling the truth about what is opening.
     *
     * **The category test is not a detail.** `CATEGORY_LAUNCHER` rides on the icon's intent and on
     * nothing this app sends itself, so the notification and the tile keep opening these controls
     * whatever the preference says — which is what the shipped copy promises. Testing the category
     * rather than passing a "do not bounce me" extra also makes every later route in — a shortcut, a
     * deep link — correct by default rather than correct for as long as its author remembers.
     *
     * Deliberately **not** re-checked in [onResume], unlike the guard below: the preference can move
     * while this window sits in the background, and throwing somebody into the full app because they
     * changed a setting on a screen they have already left is not what the setting says it does.
     */
    private fun forwardIfFullAppWanted(intent: Intent): Boolean {
        val app = application as MainApplication
        if (app.launcherCompact || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return false
        openFullApp()
        return true
    }

    /**
     * The service decides whether there is a shade for the bubble to sit over; this only says the
     * controls were put away rather than dismissed. A Home press stops this window without finishing
     * it, so it leaves nothing.
     */
    override fun onDestroy() {
        if (isFinishing && leavesBubble) showControlsBubble()
        super.onDestroy()
    }

    /**
     * The second half of the guard, on the rule `DimScreen` already follows: both preconditions are
     * switches on settings screens, so both can move while this window is in the background.
     *
     * `isFinishing` because [forwardIfUnusable] may already have fired from `onCreate` — starting
     * the full app twice is a second task animation for one tap.
     */
    override fun onResume() {
        super.onResume()
        if (!isFinishing) forwardIfUnusable()
    }

    /**
     * Re-reads a lent location, `MainActivity.onStart`'s rule. It matters more here, because the
     * launcher icon lands on this window by default, so for most users this is what opening Gloam
     * means. `lifecycleScope` cancels a read still in flight when this dialog closes, which keeps
     * the read to while Gloam is in front (ADR-0013 §7).
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { refreshLentLocation((application as MainApplication).preferences) }
    }

    /**
     * **Two live reads, and neither may be cached.** `SYSTEM_ALERT_WINDOW` decides whether the start
     * button can do anything at all; `escapeHatchLive()` decides whether the shade this window
     * starts would have a way out that is not this window. The full app has copy for both cases —
     * the explainer and the warning banner — and this one has none, so being the full app instead is
     * the honest response rather than a fallback.
     *
     * `escapeHatchLive()` gets the second caller `docs/phase-2.md` §2 predicted, from 3a rather than
     * from 2b, with the predicate unchanged — which is the whole point of having written it as a
     * function in `shade/` instead of inline in the screen that first needed it.
     */
    private fun forwardIfUnusable(): Boolean {
        if (canDrawShade() && escapeHatchLive()) return false
        openFullApp()
        return true
    }

    /**
     * The way out to the full app, used by the guard and by the cog.
     *
     * **`FLAG_ACTIVITY_NEW_TASK` is load-bearing rather than boilerplate.** This activity declares an
     * empty `taskAffinity` and `excludeFromRecents`, so without the flag [MainActivity] would be
     * pushed onto *this* task — the full app, running inside a task the user cannot find in recents.
     * The flag sends it to the task its own affinity names, which is the app's, where it belongs and
     * where it can be come back to.
     *
     * **And the intent deliberately carries no categories**, which is what keeps
     * [forwardIfFullAppWanted] from reading this as a launcher tap if [MainActivity] ever grows a
     * route back. The ping-pong §2 argued about is gone with shape iv — only one of these two
     * activities forwards now, so there is no loop left to break — but the category is still what
     * says *how this Activity was entered*, and a start from inside the app is not the icon.
     */
    private fun openFullApp() {
        leavesBubble = false
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/**
 * The body of the floating window: the shared compact controls, wired to this host's `Context`.
 *
 * **The start button's wiring is the short version of `DimScreen`'s**, and the reason is the guard
 * above: the notification permission is a precondition of this window existing at all, so the ask
 * can never fire from here. What is left is the pair that must stay together — the stored intent and
 * the service — written the same way round as on the full screen.
 *
 * **`onClose` is passed, though the back gesture already closes an Activity.** This used to be the
 * one host without the close button, on the grounds that a button doing what Back does buys nothing.
 * It bought a visible seam instead: the panel has the button, the two hosts replace each other (one
 * edge bar at a time), and a group one button shorter centres its bar somewhere else — so the bar
 * jumped when one took over. Which host is showing is an implementation detail the user should not
 * be able to see, so both draw the same controls in the same place.
 *
 * No `extras` on the `viewModel()` call, unlike every screen inside `NavDisplay`: those resolve to a
 * bare per-entry `ViewModelStoreOwner` with no default extras, and this one resolves to the Activity,
 * which provides `APPLICATION_KEY` itself (`ui/AppViewModelExtras.kt`). Same `DimViewModel` as the
 * full screen, a different instance — it is scoped to whichever host is showing it, and both read
 * the same DataStore keys, so the two agree without talking to each other.
 */
@Composable
private fun ControlsBody(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DimViewModel = viewModel(factory = DimViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CompactControls(
        dimLevel = state.dimLevel,
        warmth = state.warmth,
        warmthColor = state.warmthColor,
        // The live half as well as the intent, as on the full screen — see `DimUiState.shadeUp`.
        running = state.shadeUp,
        autoOff = state.autoOff,
        onDimLevel = viewModel::setDimLevel,
        onWarmth = viewModel::setWarmth,
        onAutoOff = viewModel::setAutoOff,
        onToggleRunning = {
            if (state.shadeUp) {
                viewModel.endShade(ShadeEnd.ByHand)
                onStop()
            } else {
                viewModel.beginShade()
                onStart()
            }
        },
        onOpenApp = onOpenApp,
        onClose = onClose,
        modifier = modifier,
    )
}
