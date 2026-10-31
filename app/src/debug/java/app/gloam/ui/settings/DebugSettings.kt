package app.gloam.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import app.gloam.ControlsActivity
import app.gloam.MainApplication
import app.gloam.shade.DebugMinBacklight
import app.gloam.shade.MIN_BACKLIGHT
import app.gloam.shade.armGateAlarm
import app.gloam.shade.readBacklight
import app.gloam.shade.showShadePanel
import app.gloam.shade.startShade
import app.gloam.theme.Spacing
import app.gloam.ui.common.SectionHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The developer-only section of Settings. **This file exists only in the debug source set** — see
 * the release half for why that is a source set and not a `BuildConfig.DEBUG` branch.
 *
 * ## The backlight sweep — Phase 1, checkpoint B
 *
 * Two questions the phone has to answer before the backlight half of the ramp can be written, and
 * neither can be answered from outside the app: **the window brightness override is per-window and
 * only its owner can set it**, so there is no `adb` command that applies one on our behalf.
 *
 * - *What does a given override float actually do to the panel?* The sweep walks a descending list
 *   of floats, holding each long enough to be read. `dumpsys display` keeps a hundred
 *   `BrightnessEvent` lines with `brt=` and `nits=` on each, so one dump after the run captures the
 *   whole curve — no polling, and nits without a light meter.
 * - *Is what the app computes from `Settings.System` a safe starting point?* The readout prints
 *   [app.gloam.shade.readBacklight]'s own answer beside the naive one an app would reach for, so
 *   both can be compared against what `dumpsys` says is really on the panel.
 *
 * **The sweep holds the screen on while it runs, and that is load-bearing rather than a courtesy.**
 * A screen approaching its inactivity timeout enters the DIM policy and is pinned to the panel's
 * floor regardless of any setting or override — which reads exactly like "the override did nothing"
 * and is how an earlier session mis-measured this phone by three orders of magnitude.
 *
 * ## The two-minute deadline — Phase 2, checkpoint B
 *
 * R7, R8a and R8b all need a deadline shorter than thirty minutes, and **nothing in the shipped app
 * could make one** when they were taken: the shortest auto-off was thirty minutes, and a debug-only
 * choice would have lived in `main/`, driven the chip row, and put its label through the translation
 * gate — the exact failure this source-set seam exists to prevent. A typed auto-off now goes down to
 * a minute, but only in whole minutes and only from the full screen. `adb` cannot do it either, because DataStore
 * sits in credential-encrypted storage and the live process holds its state in memory.
 *
 * So the same justification the sweep above carries applies here: **only the app can do this to
 * itself**, which is why the button is in the app and behind the seam rather than in a script.
 *
 * ## The gate alarm — Phase 4, checkpoint A
 *
 * The same justification a fifth time, and this one is the sharpest: **only the app can arm an alarm
 * as itself**. `adb shell cmd alarm` does not exist, `am broadcast` reaches the receiver as the
 * shell's uid rather than through `AlarmManager`, and a broadcast sent by hand to a process the ROM
 * has already started answers none of the three questions the gate asks — whether the alarm fires in
 * Doze, whether the foreground-service start is allowed from it, and whether HyperOS starts the
 * process for it at all.
 *
 * Two durations because the gate is taken twice. The short one is for the forced-Doze cells, which
 * are cheap and prove the code path; the long one is R4, the overnight run against natural Doze —
 * and `docs/phase-4.md` is explicit that `force-idle` is a simulation, so the verdict is taken on
 * the second.
 *
 * ## Opening the compact controls — Phase 3, checkpoint C
 *
 * `ControlsActivity` was `exported="false"` when this button was written, so `adb shell am start -n`
 * could not reach it at all — the shell runs as uid 2000 and the activity manager refuses with
 * `not exported from uid …`, which `docs/phase-3.md` §12 did not allow for when it wrote that
 * command down. **Shape iv exports it**, because it now carries the launcher's `<intent-filter>` and
 * that is what a launcher entry is; the button stays anyway. It reproduces the route with the flags
 * the real callers use, and a reading taken through a hand-built `am start` is a reading of the
 * command rather than of the app.
 *
 * R2, R3 and R5 are all taken through this button until checkpoint D builds the routes a
 * user will actually use.
 *
 * ## Summoning the panel — Phase 3, checkpoint F
 *
 * The panel's one door is the notification's content intent, and **that is checkpoint D**. Until it
 * lands there is no way for a user — or for `adb` — to put the panel on screen: it is a window
 * `ShadeService` owns, summoned by an action delivered to the service, and a service action is not
 * something the shell can send to an app that did not export it.
 *
 * So the same argument a fourth time, and R6 through R9 are all read through this button. It calls
 * the same `showShadePanel()` the notification will, so what is measured here is the route rather
 * than an approximation of it.
 *
 * ## The reading test — `docs/night-reading-research.md` §3
 *
 * Whether `DEFAULT_WARMTH_COLOR` should move is a question about how fast someone reads under the
 * shade, and **only the app can put the shade over a page it controls and time a tap on it**. So
 * the test is an Activity of its own behind this button. `ReadingTest.kt` has the protocol.
 *
 * None of this reaches a release binary, and none of its strings reach the translation gate — which
 * is why the text here is hardcoded English rather than a string resource.
 */
@Composable
fun DebugSettings() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { (context.applicationContext as MainApplication).preferences }

    // Bumping this re-reads the settings; the read is cheap and there is nothing to observe, so a
    // button beats a ContentObserver for a developer row.
    var readCount by remember { mutableIntStateOf(0) }
    val reading = remember(readCount) { readBacklight(context) }

    var applied by remember { mutableFloatStateOf(RELEASED) }
    var sweepRun by remember { mutableIntStateOf(0) }

    // Read from [SecondWindow] rather than owned here, because the window deliberately outlives this
    // composition: the whole point is to leave the app with it up. `remember` seeds the label from
    // the real state on re-entry; the buttons keep it in step after that.
    var secondWindowUp by remember { mutableStateOf(SecondWindow.isUp) }

    LaunchedEffect(reading) { Log.i(TAG, "reading: $reading") }

    // The write itself. Keyed on the value so each step of the sweep lands, while the release on
    // leaving the screen is a separate effect — keying the release on `applied` too would release
    // and immediately re-apply on every step, and the panel would ramp between the two.
    LaunchedEffect(applied) { activity?.applyOverride(applied) }
    DisposableEffect(Unit) { onDispose { activity?.applyOverride(RELEASED) } }

    LaunchedEffect(sweepRun) {
        if (sweepRun == 0) return@LaunchedEffect
        Log.i(TAG, "sweep start: ${SWEEP.size} steps, ${STEP_MILLIS}ms each")
        SWEEP.forEachIndexed { index, value ->
            applied = value
            Log.i(TAG, "sweep step ${index + 1}/${SWEEP.size} screenBrightness=$value")
            delay(STEP_MILLIS)
        }
        applied = RELEASED
        Log.i(TAG, "sweep done, override released")
    }

    // Phase 2b's comparison: where the ramp stops the backlight. The service collects the same flow,
    // so a tap re-paints a live shade without restarting it. `collectAsState` is the Compose end of a
    // `Flow` — it re-renders this row on each new value, like a `useSyncExternalStore` subscription.
    val lowest by DebugMinBacklight.value.collectAsState()
    val raised = lowest != MIN_BACKLIGHT

    SectionHeader("Developer")

    Column(modifier = Modifier.padding(horizontal = Spacing.base)) {
        Text(
            text =
                "lowest backlight: $lowest (${"%.2f".format(nitsOnDevPanel(lowest))} nits)" +
                    if (raised) " — the previous value, for comparison" else " — shipped, the floor",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(modifier = Modifier.padding(top = Spacing.tight, bottom = Spacing.base)) {
            Button(
                onClick = {
                    DebugMinBacklight.value.value = if (raised) MIN_BACKLIGHT else DebugMinBacklight.PREVIOUS
                    Log.i(TAG, "lowest backlight -> ${DebugMinBacklight.value.value}")
                },
            ) {
                Text(if (raised) "Back down to the floor (2.0 nits)" else "Up to the previous (6.6 nits)")
            }
        }

        // Also Phase 2b: the system dim, as a separate window over whatever is on screen. Measured
        // dead - it freezes touch for as long as it is up. Kept to retake the reading, not to use.
        val dimBehindUp by DimBehindWindow.up.collectAsState()
        Text(
            text =
                "dim-behind window: " +
                    if (dimBehindUp) "up, touch blocked, removes itself after 60 s" else "down (blocks all touch)",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Row(modifier = Modifier.padding(top = Spacing.tight)) {
            DIM_AMOUNTS.forEach { amount ->
                OutlinedButton(
                    onClick = { DimBehindWindow.add(context, amount) },
                    enabled = !dimBehindUp,
                    modifier = Modifier.padding(end = Spacing.tight),
                ) {
                    Text("Dim $amount")
                }
            }
        }
        Row(modifier = Modifier.padding(top = Spacing.tight, bottom = Spacing.base)) {
            OutlinedButton(onClick = { DimBehindWindow.remove() }, enabled = dimBehindUp) {
                Text("Remove dim")
            }
        }

        Text(
            text = reading.readout(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text =
                "override: " + if (applied < 0f) "released" else applied.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Spacing.tight),
        )

        Row(modifier = Modifier.padding(top = Spacing.tight)) {
            Button(
                onClick = { sweepRun++ },
                modifier = Modifier.padding(end = Spacing.tight),
            ) {
                Text("Run sweep")
            }
            OutlinedButton(
                onClick = { applied = FLOOR },
                modifier = Modifier.padding(end = Spacing.tight),
            ) {
                Text("Hold floor")
            }
            OutlinedButton(onClick = { applied = RELEASED }) { Text("Release") }
        }

        Row(modifier = Modifier.padding(top = Spacing.tight, bottom = Spacing.base)) {
            // R3 in one tap: hold the app's own computed backlightTop, so `dumpsys` can be asked
            // whether engaging the override at dim level zero changes the panel at all.
            Button(
                onClick = { reading.top?.let { applied = it } },
                enabled = reading.top != null,
                modifier = Modifier.padding(end = Spacing.tight),
            ) {
                Text("Hold top")
            }
            OutlinedButton(onClick = { readCount++ }) { Text("Re-read") }
        }

        Row(modifier = Modifier.padding(bottom = Spacing.base)) {
            // `beginShade` rather than a deadline setter, because there is no deadline setter: the
            // intent and the deadline are written as a pair so that neither can be stale beside the
            // other. Starting the service alongside it keeps the two agreeing when the button is
            // tapped with no shade up — the arrival is the thing being measured, and a deadline with
            // nothing running measures the resume reconcile instead.
            Button(
                onClick = {
                    scope.launch {
                        val deadline = System.currentTimeMillis() + ARM_MILLIS
                        preferences.beginShade(deadline)
                        context.startShade()
                        Log.i(TAG, "armed auto-off for $deadline (${ARM_MILLIS}ms out)")
                    }
                },
            ) {
                Text("Arm 2-minute deadline")
            }
            // R10's, and the length is the reading's rather than a taste: the deadline has to pass
            // while the phone is genuinely suspended, which needs the cable **out** - a tethered
            // phone never suspends, measured, 0.0s of 427s with the screen off. Ten minutes is long
            // enough to unplug and let the SoC settle, short enough to stand next to.
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val deadline = System.currentTimeMillis() + SLEEP_ARM_MILLIS
                        preferences.beginShade(deadline)
                        context.startShade()
                        Log.i(TAG, "armed auto-off for $deadline (${SLEEP_ARM_MILLIS}ms out)")
                    }
                },
                modifier = Modifier.padding(start = Spacing.tight),
            ) {
                Text("Arm 10-minute deadline")
            }
        }

        Row(modifier = Modifier.padding(bottom = Spacing.base)) {
            // Checkpoint A's two arms. Both go through the same `setAndAllowWhileIdle` the real
            // schedule would use; the only difference between them is how long the phone gets to
            // decide what it thinks of us.
            Button(
                onClick = { context.armGateAlarm(GATE_SOON_MILLIS) },
                modifier = Modifier.padding(end = Spacing.tight),
            ) {
                Text("Gate alarm 2 min")
            }
            OutlinedButton(onClick = { context.armGateAlarm(GATE_OVERNIGHT_MILLIS) }) {
                Text("Gate alarm 10 h")
            }
        }

        Row(modifier = Modifier.padding(bottom = Spacing.base)) {
            OutlinedButton(onClick = { context.armGateAlarm(GATE_EARLY_MILLIS) }) {
                Text("Gate alarm 5 h")
            }
        }

        Text(
            text = "second window: " + if (secondWindowUp) "up" else "down",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )

        Row(modifier = Modifier.padding(top = Spacing.tight, bottom = Spacing.base)) {
            Button(
                onClick = {
                    SecondWindow.add(context)
                    secondWindowUp = SecondWindow.isUp
                },
                enabled = !secondWindowUp,
                modifier = Modifier.padding(end = Spacing.tight),
            ) {
                Text("Add second window")
            }
            OutlinedButton(
                onClick = {
                    SecondWindow.remove(context)
                    secondWindowUp = SecondWindow.isUp
                },
                enabled = secondWindowUp,
            ) {
                Text("Remove second window")
            }
        }

        Row(modifier = Modifier.padding(bottom = Spacing.base)) {
            // `FLAG_ACTIVITY_NEW_TASK` so this reproduces the route checkpoint D will build rather
            // than a convenient one: the notification's `PendingIntent` starts the activity from
            // outside any task of ours, and `ControlsActivity` declares an empty `taskAffinity`, so
            // the window under measurement is the one that lands in its own task. Without the flag
            // it would open inside Settings' task instead, which is a different window ordering
            // from the one R2 is reading.
            Button(
                onClick = {
                    context.startActivity(
                        Intent(context, ControlsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ) {
                Text("Open compact controls")
            }
        }

        Row(modifier = Modifier.padding(bottom = Spacing.base)) {
            Button(onClick = { context.startActivity(Intent(context, ReadingTestActivity::class.java)) }) {
                Text("Reading test (warmth colour)")
            }
        }

        Row(modifier = Modifier.padding(bottom = Spacing.base)) {
            // Summon only. The panel refuses to appear without a shade under it
            // (`docs/phase-3.md` §6, rule 2), and that precondition is the thing being measured —
            // a button that quietly started one first would hide it. Start the shade from the dim
            // screen the way a user does, then tap this.
            Button(onClick = { context.showShadePanel() }) {
                Text("Summon panel")
            }
        }
    }
}

/**
 * R1's whole apparatus — Phase 3, checkpoint A.
 *
 * A second `TYPE_APPLICATION_OVERLAY` window of our own, put up over the live shade so that
 * `dumpsys` can be asked the two questions the panel is a bet on (`docs/phase-3.md` §5):
 *
 * - **Does it sit above the shade?** Both windows are the same type from the same uid, and ordering
 *   within a type is the window manager's business — the expectation is insertion order, which is an
 *   expectation rather than a documented guarantee. `dumpsys window windows` prints the stack in
 *   order, and the rectangle is a loud colour so a `screencap` says the same thing a second way: at
 *   dim 100 a magenta square *above* the shade stays magenta, and one below it is nearly black.
 * - **Who owns the backlight override?** This window sets no `screenBrightness` at all, so it leaves
 *   the field at `BRIGHTNESS_OVERRIDE_NONE` — *not asking*, rather than asking for nothing. By the
 *   rule Phase 1's R5 and R9 measured, the shade underneath should keep its `0.01`.
 *   `dumpsys display` is where that is read rather than assumed.
 *
 * **Bare on purpose.** No Compose, no lifecycle, no controls, nothing that could be blamed if the
 * answer is surprising. Every line the panel needs beyond this is a line written on the bet this
 * window is here to settle, which is why checkpoint A runs before any of them exist.
 *
 * **It carries `FLAG_NOT_TOUCHABLE`, which the real panel will not.** Touch is R7's question against
 * the real thing, not R1's, and a touchable rectangle dropped over an unknown screen is the trap
 * `docs/phase-3.md` §6 exists to bound — a debug button must not need its own way out. The flag
 * should not reach the answer either way: what a window asks for is a `LayoutParams` field, and
 * whether it can be touched is not one of the terms. R1 reads the result rather than trusting that.
 *
 * **A file-scoped object rather than composition state**, because the reading is taken with the app
 * in the background: `DisposableEffect` would take the window down at exactly the moment the phone
 * became worth looking at. The window dies with the process, which is the only cleanup a debug
 * surface owes.
 */
private object SecondWindow {
    private var view: View? = null

    val isUp: Boolean get() = view != null

    fun add(context: Context) {
        if (view != null) return
        val square = View(context).apply { setBackgroundColor(android.graphics.Color.MAGENTA) }
        val side = (SIDE_DP * context.resources.displayMetrics.density).toInt()
        val params =
            WindowManager
                .LayoutParams(
                    side,
                    side,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE,
                ).apply {
                    // Centred, so the square lands over neither the status bar's pull-down nor the
                    // gesture bar. The phone stays drivable with it up, which is what lets the
                    // reading be taken from other apps rather than from Gloam's own Settings screen.
                    gravity = Gravity.CENTER
                }
        runCatching { context.windowManager().addView(square, params) }
            .onSuccess {
                view = square
                Log.i(TAG, "second window added: ${side}px square, no screenBrightness set")
            }.onFailure { Log.w(TAG, "second window refused", it) }
    }

    fun remove(context: Context) {
        val square = view ?: return
        runCatching { context.windowManager().removeView(square) }
        view = null
        Log.i(TAG, "second window removed")
    }

    /**
     * The **application**'s window manager, not the Activity's. An Activity's carries that
     * activity's window token, and a window added with it is torn down with the activity — which is
     * the one thing this window must not do.
     */
    private fun Context.windowManager(): WindowManager = applicationContext.getSystemService(WindowManager::class.java)
}

/**
 * Phase 2b's one untested lever: **does the system's own dim escape the 0.8 overlay clamp?**
 *
 * The shade's darkness is capped by the platform, not by us. Since Android 12, WindowManager clamps
 * the `alpha` of any overlay window that carries `FLAG_NOT_TOUCHABLE` to the maximum obscuring
 * opacity (0.8), because a window that lets touches through must stay see-through enough that the
 * user can see what they are touching. The clamp reads `LayoutParams.alpha` and nothing else.
 *
 * `FLAG_DIM_BEHIND` asks for something else: WindowManager paints a black layer of its own, at
 * `dimAmount`, behind the window. The bet was that a layer with no input region escapes both the
 * alpha clamp and the input dispatcher's occlusion check. **Measured on HyperOS (API 36) on
 * 2026-09-13: it escapes the first and not the second, so the lever is dead.**
 *
 * - **(a) The dim is not clamped.** SurfaceFlinger lists `Dim Layer for - Display 0` at
 *   `a:0.950195`, and a screencap passes 5.4% of the undimmed pixel values. The only warning in
 *   logcat is this window's own alpha clamp, the same one the shade gets.
 * - **(b) It blocks every touch.** The dim layer belongs to the system (uid 1000), and the input
 *   dispatcher counts it as a *blocking* occluder rather than by its opacity: `Untrusted touch due
 *   to occlusion by /1000/Dim Layer…`, with no opacity in the line, and the touch is dropped at 0.5
 *   as surely as at 0.95. **The system Settings app runs as uid 1000 too**, and a window is never
 *   occluded by its own uid, so a swipe there scrolls and reads as a pass. Take R7 over any other
 *   app.
 * - **(c) Over the shade**, the dim lands between the shade and this window. A full-screen version
 *   of this window also blocked touches on its own account: the dispatcher adds up one uid's
 *   opacities, and two windows at 0.8 read as 0.96. That is why the window is one pixel, and why
 *   stacking a second shade cannot beat the clamp either.
 *
 * Kept rather than deleted, so the reading can be retaken on another ROM or API level, and so the
 * next person to reach for this flag finds the answer next to it.
 *
 * **It takes itself down after [DIM_BEHIND_MILLIS]**, because (b) is real: while it is up the phone
 * is frozen, with the button that would remove it underneath. The power button hides overlays behind
 * the keyguard, but they come back on unlock, and a debug button must not need its own way out.
 * [DIM_AMOUNTS] stops below 1.0 for the same reason.
 */
private object DimBehindWindow {
    private var view: View? = null

    /**
     * A flow rather than a getter like [SecondWindow.isUp], because this window also goes away on a
     * timer nobody tapped, and a label that only re-reads on its own button would say "up" over an
     * empty screen.
     */
    val up = MutableStateFlow(false)

    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { removeView() }

    // Kept for the expiry, which fires with no screen around to hand it a Context. The application's
    // rather than the one passed in, because an Activity held past its screen is a leak, and the
    // application context lives exactly as long as this window can.
    private var appContext: Context? = null

    fun add(
        context: Context,
        amount: Float,
    ) {
        if (view != null) return
        appContext = context.applicationContext
        // No background: the view draws nothing, so whatever reaches the eye is the system's dim.
        val empty = View(context)
        // **One pixel, and that is measured rather than tidy.** The dim layer belongs to the display,
        // not to this window (`Dim Layer for - Display 0` in SurfaceFlinger), so the window's size
        // does not bound it. But the window's own alpha still counts: the input dispatcher adds up the
        // opacity of every window of one uid over the touch point, and an empty full-screen window at
        // the clamped 0.8 over the shade's 0.8 reads as 0.96. Every touch was dropped. A pixel in the
        // corner is over no touch point anyone makes.
        val params =
            WindowManager
                .LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    app.gloam.shade.SHADE_WINDOW_FLAGS or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                    dimAmount = amount
                }
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java).addView(empty, params) }
            .onSuccess {
                view = empty
                up.value = true
                handler.postDelayed(expire, DIM_BEHIND_MILLIS)
                Log.i(TAG, "dim-behind window added: dimAmount=$amount, expires in ${DIM_BEHIND_MILLIS}ms")
            }.onFailure { Log.w(TAG, "dim-behind window refused", it) }
    }

    fun remove() {
        handler.removeCallbacks(expire)
        removeView()
    }

    private fun removeView() {
        val empty = view ?: return
        runCatching { windowManager()?.removeView(empty) }
        view = null
        up.value = false
        Log.i(TAG, "dim-behind window removed")
    }

    // The application's window manager, for the same reason as SecondWindow's: an Activity's would
    // take the window down with the activity, before the reading is taken from another app.
    private fun windowManager(): WindowManager? = appContext?.getSystemService(WindowManager::class.java)
}

/** The computed number beside every ingredient that went into it, so a wrong one is visible. */
private fun app.gloam.shade.BacklightReading.readout(): String =
    buildString {
        appendLine("raw=$raw  adaptive=$adaptive  float=$settingFloat")
        appendLine("setting range=[$minSetting, $maxSetting]  (min unused: see Backlight.kt)")
        appendLine("float range=[$minFloat, $maxFloat]")
        appendLine("top=$top")
        append("($note)")
    }

/**
 * Apply — or release — this window's brightness override.
 *
 * `Window.getAttributes()` hands back the live `LayoutParams`, so the idiom is mutate-then-set:
 * assigning them back is what pushes the change to the window manager. `FLAG_KEEP_SCREEN_ON` rides
 * along with the override for the reason in [DebugSettings]'s notes.
 */
private fun Activity.applyOverride(value: Float) {
    window.attributes = window.attributes.apply { screenBrightness = value }
    if (value >= 0f) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** Compose hands out a `Context`, and the window belongs to the Activity behind it. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val TAG = "GloamSweep"

/** Phase 1's R1 fit for the development panel — affine, not a power law. Other panels differ. */
private fun nitsOnDevPanel(override: Float): Float = 498.3f * override + 1.66f

/** `BRIGHTNESS_OVERRIDE_NONE`: hand the window back to the user's own brightness. */
private const val RELEASED = -1f

/**
 * The panel floor read off this phone (`mScreenBrightnessRangeMinimum`), as a one-tap hold for
 * looking at the darkest state with both eyes rather than through `dumpsys`.
 */
private const val FLOOR = 6.83661E-4f

/**
 * Descending, and resolved finely near the bottom because that is where this product lives.
 *
 * **Never `0f`.** `screenBrightness = 0.0f` is `BRIGHTNESS_OVERRIDE_OFF` — the backlight *off*, over
 * a live touchscreen. The bottom of this list is below the panel's floor on purpose, to see where
 * the driver clamps, which is a different question from where the app should stop.
 */
private val SWEEP =
    listOf(
        1.0f,
        0.75f,
        0.5f,
        0.4999f,
        0.25f,
        0.125f,
        0.0625f,
        0.03f,
        0.015f,
        0.008f,
        0.004f,
        0.002f,
        0.001f,
        6.83661E-4f,
        0.0003f,
        0.0001f,
    )

private const val STEP_MILLIS = 2500L

/**
 * Two minutes: short enough to sit through with the phone in hand, and — deliberately — **longer
 * than one** `DEADLINE_RECHECK_MS`, so R7 measures the re-check loop waking up rather than a single
 * `delay` that happened to be shorter than the cap.
 */
private const val ARM_MILLIS = 120_000L

/**
 * Long enough to unplug, force Doze and step the state machine by hand before it fires; short enough
 * to sit through. The forced-Doze cells R1 to R3 are all taken on this one.
 */
private const val GATE_SOON_MILLIS = 120_000L

/**
 * R10's deadline: long enough to unplug the cable and let the phone reach real suspend before it
 * expires, short enough to wait out. [ARM_MILLIS] cannot serve - three of its two minutes are spent
 * getting the cable out, and a deadline that passes while the phone is still awake measures nothing.
 */
private const val SLEEP_ARM_MILLIS = 600_000L

/**
 * R4's arm: about ten hours, which is a phone put down for the night and left to reach Doze on its
 * own rather than being pushed into it. It is the reading the verdict is taken on, because
 * `force-idle` skips whatever the ROM does between 23:00 and 07:00 — which on this phone is the
 * entire question.
 */
private const val GATE_OVERNIGHT_MILLIS = 10L * 60 * 60 * 1000

/**
 * The same arm, for a night whose window sits early and whose morning starts early. Five hours put
 * down at about 23:00 fires between 04:00 and 05:00 — after a 02:00-to-03:00 window has closed and
 * the service it started has been reaped, and before the phone is picked up. The Doze it lands in is
 * shallower than [GATE_OVERNIGHT_MILLIS]'s, because the window itself woke the phone an hour or two
 * earlier; that is the trade a reading makes when it has to be read before six in the morning.
 */
private const val GATE_EARLY_MILLIS = 5L * 60 * 60 * 1000

/** Big enough to see and to land a `screencap` on, small enough to obscure nothing that matters. */
private const val SIDE_DP = 200

/** Long enough to take all three readings from another app, short enough to wait out if trapped. */
private const val DIM_BEHIND_MILLIS = 60_000L

/**
 * One tap each. 0.5 shows whether the dim appears at all, 0.9 sits past the shade's 0.8 clamp, and
 * 0.95 is as far as this goes. **Never 1.0**, which is a black screen, for the reason in
 * [DimBehindWindow]'s notes.
 */
private val DIM_AMOUNTS = listOf(0.5f, 0.9f, 0.95f)
