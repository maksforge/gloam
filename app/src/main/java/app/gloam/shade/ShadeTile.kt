package app.gloam.shade

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.gloam.ControlsActivity
import app.gloam.MainApplication
import app.gloam.R
import app.gloam.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * **The second escape hatch: a Quick Settings tile that takes the shade down.**
 *
 * `docs/phase-2.md` §2 defines an escape hatch as a surface that stops the shade and can be reached
 * without seeing Gloam's own UI, and lists exactly three that survive at maximum dim — the ongoing
 * notification's **Stop** action, this tile, and the power menu. All three sit above
 * `TYPE_APPLICATION_OVERLAY`, which is why the shade cannot cover them.
 *
 * **Why a second one when the notification already works.** The argument is independence, not
 * strength. The notification can be taken away by two different switches — `POST_NOTIFICATIONS`
 * revoked, or, far more quietly, the shade's *notification channel* lowered to `IMPORTANCE_NONE`,
 * which leaves the permission reading as granted while nothing appears on screen. [escapeHatchLive]
 * is the predicate over both. A tile is subject to neither: it needs no permission and no channel,
 * so the two hatches fail for disjoint reasons. That is the whole case for having both.
 *
 * **It is not a gate term.** `EscapeHatch.kt` was written expecting `escapeHatchLive() || tileAdded()`
 * to gate ultra dark. Phase 2b priced ultra-dark-by-alpha out — past [MAX_SHADE_ALPHA] there is 17%
 * of light to win and both of `shadeValuesFor`'s invariants to lose — so there is no new mode to
 * gate, and the disjunction is not built. That is the happier outcome, because **there is no live
 * read of "is my tile added"**. The platform offers `requestAddTileService` and the `onTileAdded` /
 * `onTileRemoved` callbacks, so the only way to answer it is to *remember*, and a remembered
 * `tile_added` key is live state in a store Auto Backup carries whole to the next phone — where the
 * tile is not added and the key says it is. `shade_began_at` is what that costs (CLAUDE.md).
 *
 * **One caller would still want the disjunction, and it keeps the conservative half.**
 * `ControlsActivity.forwardIfUnusable()` refuses the compact window when [escapeHatchLive] is false,
 * and with a tile added that refusal is now stricter than the danger warrants — the tile *is* a way
 * out. It stays as written: unreadable state cannot be an argument to a safety predicate, and the
 * failure it errs into is one extra hop to the full app.
 *
 * ## What it must never do
 *
 * **Stopping always works.** Every branch that can leave the shade up is a branch that can trap
 * somebody, so [onClick] has no precondition on the stop path — no permission read, no service
 * liveness check, no unlock. It writes the intent and calls [stopShade], and `stopService` on a
 * service that a ROM already killed is a no-op rather than a throw.
 *
 * ## Measured on the phone, 2026-09-18
 *
 * All three questions answered on HyperOS, by hand and from adb. The tile **is** rendered in
 * Control Center; a tap starts the shade (`shade_running` false→true, the service up, auto-off
 * armed) and a second tap stops it, clearing `off_at_millis` rather than leaving a deadline behind;
 * and it **highlights while on**, like the ROM's own tiles.
 *
 * Two things that only look like failures. `cmd statusbar click-tile` is refused with
 * `isBound: false`, but that is the command rather than the tile — `dumpsys activity services`
 * shows SystemUI bound (`hasBound=true received=true`), so [onStartListening] runs and [render]
 * lands. And a `uiautomator` dump reports `checked="false"` on this tile while the shade is up,
 * while the ROM's own tiles report `checked="true"` when on: HyperOS does not map a **custom**
 * tile's state onto the accessibility attribute, though it draws it correctly. Read this tile's
 * state by eye or by effect, never from a dump.
 *
 * **And the tile turned out to matter more than this file assumed.** Taking R2 the same day found
 * that HyperOS renders no notification action buttons at all — *Stop* needs a two-finger pull to
 * appear. So on this ROM the tile is not the *second* one-tap hatch. It is the only one, which is
 * the argument for `requestAddTileService()` that `PLAN.md` asks for. `DOD.md` carries both.
 *
 * Kotlin note: `TileService` is a `Service`, so it has a `Context` and may start the shade itself.
 * That is not the rule CLAUDE.md states for a `ViewModel` — the rule there is that a `ViewModel`
 * outlives no `Context`; a Service *is* one.
 */
class ShadeTile : TileService() {
    /**
     * Alive only between [onStartListening] and [onStopListening].
     *
     * Kotlin note: a `CoroutineScope` here is the structured-concurrency answer to "unsubscribe on
     * teardown" — cancelling the scope cancels the collection, the way clearing an interval handle
     * would in JS, except it is impossible to forget one of several.
     */
    private var listening: CoroutineScope? = null

    private val preferences: AppPreferences
        get() = (application as MainApplication).preferences

    /**
     * The platform calls this when the tile becomes visible, and only between here and
     * [onStopListening] may [getQsTile] be touched at all — outside that window the reference is
     * stale and `updateTile` does nothing.
     *
     * **It reads the stored intent, never the running service** (CLAUDE.md). Xiaomi kills the
     * service without the user changing their mind, and a tile drawn from process liveness would go
     * dark on its own and invite a second tap that starts a second episode.
     */
    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        listening = scope
        preferences.shadeIntent
            .onEach { intent -> render(intent.running) }
            .launchIn(scope)
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    private fun render(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    /**
     * Toggle the shade.
     *
     * The two directions are deliberately asymmetric, because their failure modes are:
     *
     * - **Down** is unconditional. See the class KDoc — a stop that can decline is not a hatch.
     * - **Up** needs `SYSTEM_ALERT_WINDOW`, which the user can revoke at any time, so without it
     *   this hands off to [ControlsActivity] rather than failing silently. A tile that does nothing
     *   when tapped is indistinguishable from a broken one.
     *
     * `ShadeStart.ByHand`: this *is* a hand start — somebody pulled the shade down and tapped — so
     * auto-off applies exactly as it does to the button on the dim screen. `Deadlines.kt` explains
     * why a scheduled start is the case that must not take it.
     *
     * A start from here also leaves the bubble, unless Gloam itself is under Quick Settings
     * (ADR-0016's amendment).
     *
     * The scope is created here rather than reusing [listening], because a click can be the last
     * thing that happens before the platform stops listening, and cancelling mid-write would leave
     * the intent and the service disagreeing.
     */
    override fun onClick() {
        super.onClick()
        val app = application as MainApplication
        val gloamInFront =
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val running = preferences.shadeIntent.first().running
            if (running) {
                preferences.endShadeAt(ShadeEnd.ByHand)
                app.stopShade()
            } else if (app.canDrawShade()) {
                preferences.beginShadeAt(ShadeStart.ByHand)
                app.startShade(leaveBubble = !gloamInFront)
            } else {
                openTheApp()
            }
        }
    }

    /**
     * **Two spellings of the same call, and the split is the platform rather than a preference.**
     *
     * `startActivityAndCollapse(Intent)` is how this worked through API 33. API 34 deprecated it and
     * made it *throw* `UnsupportedOperationException` — not a warning, a crash — replacing it with a
     * `PendingIntent` overload that did not exist before. `minSdk` is below 34 and `targetSdk` is 36,
     * so both spellings are reachable and neither alone is enough.
     *
     * **`ControlsActivity`, not `MainActivity`, and the choice is `docs/phase-3.md` §3's rather than
     * this file's.** That section reserved the tile for 2b and left one integration behind so 2b
     * would not have to rediscover it: the tile's *open app* route goes to the compact host, because
     * a tile can be tapped with no shade running and the panel cannot answer that. It costs nothing
     * on the path this function is actually used from — `ControlsActivity.forwardIfUnusable()` sends
     * anyone without `canDrawShade()` straight on to `MainActivity`, through a window that never
     * draws — so today the two spellings land in the same place. It is written the documented way so
     * that a later long-press route inherits it rather than contradicting it.
     *
     * **Lint calls the `Intent` branch an error and it is wrong here, which is why the suppression is
     * on this function rather than in `build.gradle.kts`.** Its message — *"will throw … in apps
     * targeting UpsideDownCake and higher"* — reads `targetSdk` alone, and by that reading this app
     * throws everywhere. The throw is a `CompatChanges` gate in the **platform's** copy of
     * `TileService`, and Android 13 has no such gate to run: an API-33 device executes the old body
     * whatever the app targets, as does everything below it, and the `PendingIntent` overload it
     * would be told to use does not exist there to call. So the branch is on `SDK_INT` — the device — and the suppression is
     * scoped to the one call lint cannot see the guard around.
     *
     * ⚠ **Reasoned from the platform sources, not measured.** The dev phone is well past 33 and
     * `phase-5.md`'s R6 has the API-33 emulator blocked by a segfault, so nothing here has run the
     * `else` branch. It is the branch that does nothing worse than fail to open a settings screen.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openTheApp() {
        val intent =
            Intent(this, ControlsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Whether [requestAddShadeTile] exists on this device. The tile itself works from Android 7. */
val canRequestAddShadeTile: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * **Ask the system to put the tile in Quick Settings, rather than hoping the user finds the tile
 * editor.**
 *
 * Android 13 is the level this became possible at: below it there is no call, [canRequestAddShadeTile]
 * is false, and the row can only describe the tile editor in words.
 *
 * **What the platform does with this.** It shows *its own* dialog — Gloam draws nothing and cannot
 * style it — carrying the label and icon passed here, and answers on [onResult] exactly once. The
 * app must be in the foreground, which is why this hangs off a Settings row rather than off, say,
 * the service. If the tile is **already** added the platform answers immediately and shows no
 * dialog at all, and that case is the reason the caller renders a result instead of trusting the
 * dialog to be the feedback: a button whose entire effect is invisible reads as broken, which is
 * the same argument [onClick] makes about a tap that declines.
 *
 * **No key remembers that this was offered**, and the row that calls it is permanent for the same
 * reasons `SettingsScreen`'s autostart row is (ADR-0003, and CLAUDE.md on live state in a
 * backed-up store). There is still no live read of *"is my tile added"* — this call answers for the
 * moment it runs and nothing preserves the answer.
 *
 * **Measured on HyperOS, 2026-09-18, on both paths.** The call is implemented and does not throw.
 * With the tile already added it answers `TILE_ALREADY_ADDED` immediately and shows nothing, and
 * the row renders its line — which is the case that would otherwise look like a dead button. With
 * the tile removed the platform's dialog appears (*"Gloam debug wants to add the following tile to
 * Quick Settings"*, a preview of the tile, **Add tile** / **Do not add tile**), and accepting
 * answers `TILE_ADDED`.
 *
 * ⚠ **Accepting appends the tile at the end of the list, after `edit`.** Read out of
 * `settings get secure sysui_qs_tiles` before and after: a tile that sat first came back
 * twenty-third. So this call puts the hatch *somewhere*, not somewhere visible — on a phone with
 * twenty tiles the user may have to scroll to reach it. Nothing here can place it; the platform
 * chooses. It is still better than the tile editor, and it is a reason not to describe the tile as
 * *one tap* in copy without qualification.
 *
 * *(A correction while taking this: HyperOS **does** honour `sysui_qs_tiles`, contrary to what an
 * earlier session concluded from a Control Center screenshot. Removing the entry removes the tile
 * and restoring the string restores it, order included.)*
 *
 * Kotlin note: the two trailing lambdas are a Java `Executor` and a `java.util.function.Consumer`,
 * which Kotlin will SAM-convert. [ContextCompat.getMainExecutor] rather than an inline executor
 * because the result drives Compose state, and Compose state must be written on the main thread —
 * the platform makes no promise about which thread it answers on.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Context.requestAddShadeTile(onResult: (Int) -> Unit) {
    val statusBar = getSystemService(StatusBarManager::class.java)
    if (statusBar == null) {
        onResult(StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE)
        return
    }
    statusBar.requestAddTileService(
        ComponentName(this, ShadeTile::class.java),
        getString(R.string.app_name),
        Icon.createWithResource(this, R.drawable.ic_notification),
        ContextCompat.getMainExecutor(this),
        onResult,
    )
}
