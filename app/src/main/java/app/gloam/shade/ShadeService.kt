package app.gloam.shade

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import app.gloam.MainActivity
import app.gloam.MainApplication
import app.gloam.R
import app.gloam.data.AppPreferences
import app.gloam.data.ThemeMode
import app.gloam.work.AppChannel
import app.gloam.work.ensureNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min

/** The notification's Stop action, delivered straight to the service so it needs no Activity. */
private const val ACTION_STOP = "app.gloam.shade.STOP"

/**
 * Summon the panel, delivered to the service for the same reason [ACTION_STOP] is — and for one
 * more: the panel is a window this service owns, so there is no Activity in the route at all. A tap
 * that would otherwise be a task switch to a screen under the shade becomes a card drawn above it.
 */
private const val ACTION_SHOW_PANEL = "app.gloam.shade.SHOW_PANEL"

/** A start that also leaves the bubble, for the Quick Settings tile (ADR-0016's amendment). */
private const val EXTRA_LEAVE_BUBBLE = "app.gloam.shade.LEAVE_BUBBLE"

private const val NOTIFICATION_ID = 1

/** Logcat's own name for this class, so `adb logcat -s ShadeService:*` finds the auto-off line. */
private const val TAG = "ShadeService"

/**
 * How long the deadline loop is ever allowed to sleep in one go.
 *
 * **A platform fact rather than a style.** `delay` on the main dispatcher is a `Handler.postDelayed`,
 * scheduled against `SystemClock.uptimeMillis` — a clock that **stops advancing in deep sleep**. One
 * two-hour `delay` on a phone that slept for ninety minutes of it fires ninety minutes late. Waking
 * to re-read the wall clock at most a minute at a time bounds the error to a minute *of CPU-awake
 * time*, which is the most any mechanism without an alarm can promise — and it costs nothing while
 * the phone is asleep, because `postDelayed` sets no alarm and holds no wakelock. The message is
 * simply overdue when the device next wakes for some other reason.
 *
 * **What it cannot promise is the wake itself**, and that is [screenOns] rather than this constant. A
 * deadline that passes at 07:00 on a sleeping phone is not evaluated late, it is not evaluated at all
 * — so the message is still owed up to a minute of *uptime* when the user presses the power button,
 * and for that minute the first thing they see is the shade. Shortening this constant fixes none of
 * it, because the process is not scheduled either way; it only costs a wakeup a minute on an awake
 * device.
 */
private const val DEADLINE_RECHECK_MS = 60_000L

/**
 * The flags the shade's window is added with, named rather than written inline so that a test can
 * hold them to account.
 *
 * **Two of these four are the property `CLAUDE.md` calls load-bearing.** `FLAG_NOT_TOUCHABLE` makes
 * every touch pass through to whatever is underneath and `FLAG_NOT_FOCUSABLE` keeps key events with
 * the app the user is actually in; a window over every other app that lost either one is a phone
 * that appears frozen, with the way out behind the thing freezing it. The other two are layout —
 * they put the window over the system bars instead of inside the content area — and nothing about
 * safety rests on them.
 *
 * **`const` is what makes the JVM half of the test possible.** A `const val` is inlined into its
 * callers at compile time, so `ShadeWindowFlagsTest` reads a number rather than calling into
 * `android.jar`, where an unmocked method throws. `ShadeWindowTest` covers the half a constant
 * cannot: that the window really arrives at the window manager carrying them.
 */
const val SHADE_WINDOW_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

/**
 * The foreground service that owns the shade — one window, added above everything else.
 *
 * ## Why a service at all
 *
 * The shade has to outlive the app's own screen: the entire point is to dim *other* apps, so the
 * moment the user leaves Gloam the process must still be alive and still own a window. An Activity
 * cannot do that. A **foreground** service is the only shape Android offers that survives the user
 * navigating away, and the price it charges is a permanently visible notification — which turns out
 * to be the feature, not the tax. Someone who dims to 95% and loses the app still has the Stop
 * action, and Android will not let that notification be swiped away.
 *
 * ## What the window actually is
 *
 * A `FrameLayout` with two children — black at the dim level, a tint at the warmth — in a window of
 * type `TYPE_APPLICATION_OVERLAY`, with the flags that make it **incapable of being interacted
 * with**: `FLAG_NOT_TOUCHABLE` means every touch passes through to whatever is underneath, and
 * `FLAG_NOT_FOCUSABLE` means it never takes keyboard focus. Without both, the shade would swallow
 * the user's taps and the phone would appear frozen — the single worst failure this app could ship,
 * because the way out of it is also underneath the shade.
 *
 * **Two children, still one window.** Every safety flag above, the window type, the cutout mode and
 * the foreground notification are attributes of the *window*, so a second layer costs nothing and
 * changes none of them — which is why the warmth layer lands here rather than in a second window
 * later. What it does change is where the safety cap lives: black at `MAX_SHADE_ALPHA` under the
 * tint at `MAX_WARMTH_ALPHA` leaves half of what the black child alone was ever allowed to leave, with
 * neither child past its own limit, so the bound belongs to the composite. [shadeValuesFor] is
 * where that is enforced, and it is proven on the JVM rather than by looking at a screen.
 *
 * `MATCH_PARENT`, the two layout flags, the cutout mode and `fitInsetsTypes = 0` are what carry it
 * over the status and navigation bars. Each covers a different edge — the flags alone left the
 * navigation bar's strip bright — and without all of them a system bar stays at full brightness,
 * which reads as a bug rather than as a design. `ShadeWindowTest` reads the frame back off the
 * window manager, because a missing one shows up only on a device.
 *
 * ## The backlight the window carries
 *
 * The same window also carries a **brightness override** — `LayoutParams.screenBrightness` — which
 * is why the backlight half needs no permission at all: it is an attribute of a window this app
 * already owns, not a write to `Settings.System` (ADR-0010 rejected `WRITE_SETTINGS` for exactly
 * that reason). Android applies it while the window is on screen and hands the panel back the
 * instant it is removed, **including when the ROM kills the process**, which is the property that
 * makes it safe to reach for: there is no state left behind to strand a user with.
 *
 * The price is stated in the UI rather than hidden: while the override is live the user's own
 * brightness slider moves and does nothing, and whatever they set lands the moment the shade comes
 * off. `dim_backlight_hint` says so, always visible, because the symptom recurs and a dismissed
 * dialog is not there when it does.
 *
 * ## The deadline it takes itself down on
 *
 * Auto-off is a job on this service's own scope and needs no permission at all, which is the whole
 * difference between taking something down and putting something up: taking it down only has to be
 * right the next time the user looks at the screen. [awaitDeadline] is the loop, and the deadline it
 * watches is an absolute instant in DataStore rather than a countdown in this object — so a ROM kill
 * and a `START_STICKY` restart resume the right deadline instead of silently restarting the clock.
 *
 * **Nothing here covers the kill that does *not* restart.** The window dies with the process, the
 * stored intent still says running, and no code of ours is alive to notice the deadline pass. That
 * case belongs to the screen's resume reconcile, not to this class.
 *
 * ## Kotlin/Android notes
 *
 * **The shade's window is the one exception to the next sentence.** Its views live on
 * [ShadeThread], and every touch of them goes through `shadeThread.run`.
 *
 * A `Service` is not a thread — every callback here runs on the main thread, which is why the
 * preference `Flow` is collected on a scope this class owns and cancels rather than blocking in
 * `onStartCommand`. `START_STICKY` asks Android to recreate the service if it is killed for memory;
 * on Xiaomi that promise is worth less than the stored intent, which is why what the user asked for
 * lives in DataStore rather than in this object.
 */
class ShadeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Read through the `Application` rather than held, because a `Service` outlives nothing and
     * owns nothing: `application` is valid from `onCreate` on, and a `get()` accessor means there is
     * no field to be stale.
     */
    private val preferences: AppPreferences
        get() = (application as MainApplication).preferences

    private var windowManager: WindowManager? = null

    /**
     * Where every touch of the shade's views and window happens. [ShadeThread] explains why: a
     * rotation drops the backlight override until the shade redraws, and on the main thread that
     * redraw waited behind our own screens for ~400 ms. The panel stays on the main thread, because
     * its Compose host is there.
     */
    private val shadeThread = ShadeThread()

    /**
     * The last values the preference collector produced, kept so a window added *after* it started
     * can still be painted.
     *
     * The collector is `distinctUntilChanged`, which is what stops an unrelated preference write
     * re-laying-out the window — and also means it will not re-emit for the benefit of a latecomer.
     * [addShadeWindow] no longer runs in `onCreate`, so it is now exactly such a latecomer: without
     * this it would add a window whose two children sit at the alpha 0 they are born with, and the
     * shade would be up and perfectly transparent.
     */
    private var lastSettings: DimSettings? = null

    /** The window's root: the `FrameLayout` that `WindowManager` holds, not either layer. */
    private var shadeView: View? = null

    /** The black child, carrying the **dim level**. */
    private var dimLayer: View? = null

    /** The tint child, drawn above the black one, carrying the **warmth** in the warmth colour. */
    private var warmthLayer: View? = null

    /**
     * The very `LayoutParams` the window was added with, kept rather than rebuilt.
     *
     * A window attribute is not a view property: `view.alpha = x` takes effect on its own, but
     * `params.screenBrightness = x` does nothing until [WindowManager.updateViewLayout] is handed
     * these params back. Building a fresh instance each time would mean re-stating every safety flag
     * correctly on every slider drag — one omission and the shade starts swallowing touches.
     */
    private var shadeParams: WindowManager.LayoutParams? = null

    /**
     * The backlight Gloam took over from, **captured once and held**.
     *
     * Not re-read while the override is live, and that is correctness rather than thrift: the stored
     * setting still *moves* underneath us, because the user's own slider writes to it blind, so a
     * re-read mid-override would pick up a value they set without being able to see it. Under
     * adaptive brightness it is worse — the framework keeps re-choosing as the room changes, so a
     * re-read would pick up a value chosen for a room rather than for the user.
     *
     * Null means either *no override is applied* or *this device did not give us a number we trust*.
     * Those two want the same behaviour — the shade-only ramp — so they are the same value here, and
     * re-reading on the next emission is right in both: nothing is live to be disturbed.
     */
    private var backlightTop: Float? = null

    /**
     * The lowest override the ramp may ask for. [MIN_BACKLIGHT] for good in a release build; the
     * debug build can switch it back up to the previous `0.01f` from Settings (`MinBacklight.kt`).
     */
    private var appliedMinBacklight = MIN_BACKLIGHT

    /**
     * The colour the warmth child is painted with: [warmthTint] of the stored warmth colour, kept
     * here so that [addShadeWindow] can paint a new child with it before any emission reaches it.
     * [SHADE_AMBER] only until the first read lands, and the child is transparent until then anyway.
     */
    private var appliedTint = SHADE_AMBER

    /**
     * What the notification on screen right now says: whether it carries the line about the paused
     * brightness slider, and which deadline it names.
     *
     * Remembered rather than recomputed at the post site, because it is the *transition* that is
     * worth a `notify()` and there is no flow operator that can see the first half: it depends on
     * [shadeParams] and on what [readBacklightTop] agreed to give us, neither of which is a
     * preference. `DimScreen`'s slider writes on every integer step, so re-posting per value would
     * be a binder call per slider pixel — and Android throttles sustained notification updates, so
     * the update that matters would be a good candidate for the one it drops.
     *
     * `null` before the first post, which is not the same as "nothing announced": it is what makes
     * the first `startForeground` the baseline rather than a change to be re-posted over.
     */
    private var announced: NotificationFacts? = null

    /**
     * The deadline [buildNotification] should name — the value, not the announcement.
     *
     * Kept beside the flow that owns it rather than read with `.first()` inside the builder, because
     * `buildNotification` is called from `onStartCommand` on the main thread and a suspending read
     * there is a start-up the foreground contract does not have time for.
     */
    private var currentDeadline: Long? = null

    /** The instant the current pause ends, or `null` when the shade is not paused. Mirrored into [shadePausedUntil]. */
    private var pausedUntil: Long? = null

    /** The wait for [pausedUntil], cancelled by anything that ends the pause first. */
    private var pauseJob: Job? = null

    /**
     * True from the moment this service has decided to stop, and it exists because of a platform
     * rule rather than a preference of ours.
     *
     * The foreground notification belongs to the *service* only while the service is foreground:
     * Android takes it down when the service goes. A `notify()` under the same id that lands after
     * that moment is an ordinary notification again — and an ordinary `ongoing` notification with
     * no service behind it is one nothing will ever remove, short of a force-stop. Measured on the
     * phone in checkpoint E: the auto-off wrote its own deadline away, the write came back through
     * [AppPreferences.shadeIntent] as a *changed* deadline, and [syncNotificationText] re-posted
     * **263 ms after the shade was already down**, leaving "Screen dimmed" over a screen that was
     * not, with a Stop action for a service that no longer existed.
     *
     * So the last write on the way down must not be announced. Reset in [onStartCommand], because
     * a start command arriving at a service that was stopping means it lives after all.
     */
    private var stopping = false

    /**
     * A screen coming on, as something [awaitDeadline] can wait on.
     *
     * **What it repairs, which is a defect and not a nicety.** The deadline loop re-reads the wall
     * clock, so it can never fire early or drift — but nothing in this process is *scheduled* while
     * the CPU is asleep, so a deadline that passes overnight is not evaluated late, it is not
     * evaluated. The user picks the phone up at 08:14, the screen turning on wakes the device, and
     * the pending `postDelayed` still owes up to sixty seconds of uptime: for that minute the first
     * thing they see is the shade, at the dim level, with the backlight override still on. That is
     * the app's own named fear reached through the feature built to prevent it.
     *
     * `replay = 0` with `DROP_OLDEST` over a buffer of one, for [panelTouches]' reason: this is a
     * wake signal rather than a queue, and `tryEmit` from the main thread must never suspend. An
     * emission landing while the loop is between waits is dropped and costs nothing — at that
     * instant the loop is performing the very wall-clock comparison the signal would have asked for.
     */
    private val screenOns =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * `ACTION_SCREEN_ON`, registered at runtime — because the platform will not accept it any other
     * way, and because that refusal is what makes this cheap.
     *
     * **A protected broadcast cannot be declared in a manifest at all.** So there is no manifest
     * entry, no permission, and **no further entry point for a vendor ROM to decline** — which is
     * the whole cost a second alarm for the same job would have carried. The receiver exists only
     * while this service does, which is exactly when there is a shade to take down, and it rides a
     * wake that has already happened rather than causing one, so it costs no battery.
     *
     * `RECEIVER_NOT_EXPORTED` because the only sender that matters is the system, and the system is
     * not subject to the export check for its own protected broadcasts.
     */
    private val screenOnReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                screenOns.tryEmit(Unit)
            }
        }

    /** The panel's root view while it is up, and the thing [removePanelWindow] takes down. */
    private var panelView: View? = null

    /**
     * The lifecycle and saved-state owners the panel's composition is hanging from.
     *
     * **One per summon, and that is not tidiness.** `DESTROYED` is terminal — `LifecycleRegistry`
     * throws when asked to move up out of it, and a `SavedStateRegistryController` restores once —
     * so a `show()` / `hide()` pair on one long-lived host works exactly once. The panel has three
     * ways to close and one to reopen, which makes the second summon the ordinary case.
     */
    private var panelHost: PanelHost? = null

    /** What the panel draws, updated while it is up by [trackPanel]. */
    private var panelState: MutableStateFlow<PanelState>? = null

    /** The state collectors and the idle timer, cancelled together when the panel comes down. */
    private var panelJob: Job? = null

    /**
     * The panel window's own `LayoutParams`, kept for the same reason [shadeParams] is: a re-measure
     * edits them in place and hands them back to `updateViewLayout`.
     */
    private var panelParams: WindowManager.LayoutParams? = null

    /**
     * Whether the panel currently has a disclosure open, which is half of what its width is computed
     * from. Reset by [removePanelWindow] rather than remembered across summons: every summon builds
     * a new `PanelHost` and starts with everything closed.
     */
    private var panelSectionOpen = false

    /**
     * Every touch the panel receives, whether or not a control consumed it.
     *
     * **Touches rather than values written**, which is the trigger the panel actually needs: its one
     * reason to exist is the dim level moving over real content, and judging that means setting a
     * value and then *looking*, which writes nothing. A user who drags once and studies the result
     * would lose the panel mid-judgement on a value-written timer. The recovery property survives
     * the wider trigger untouched — a panel drawn off-screen by a layout bug receives no touches
     * either, so it still dies on schedule.
     *
     * `DROP_OLDEST` over a buffer of one: this is a re-arm signal, so the only interesting question
     * is whether a touch happened recently, and `tryEmit` from the main thread must never suspend.
     */
    private val panelTouches =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** The bubble's host while it is up. Built per appearance, for [panelHost]'s reason. */
    private var bubbleHost: PanelHost? = null

    private var bubbleParams: WindowManager.LayoutParams? = null

    /** Where the bubble was when the current drag began; the drag is an offset from it. */
    private var dragOrigin: BubblePosition? = null

    /**
     * The ✕ window, added *before* the bubble and at window alpha 0 until a drag starts. Before,
     * because windows of one app stack in the order they were added, and the bubble must be dropped
     * *onto* the ✕ rather than slide under it. At alpha 0 because untouchable alone still costs the
     * app below its touches — [dismissTargetWindowAlpha].
     */
    private var targetHost: PanelHost? = null

    private var targetParams: WindowManager.LayoutParams? = null

    private val overTarget = MutableStateFlow(false)

    private val bubbleDragging = MutableStateFlow(false)

    private val bubblePressed = MutableStateFlow(false)

    /** True for the moment between a drop on the ✕ and the window going, while the bubble shrinks away. */
    private val bubbleLeaving = MutableStateFlow(false)

    /** Which edge the bubble is tucked into while it rests; `null` while it is held or flying. */
    private val bubbleTucked = MutableStateFlow<BubbleSide?>(null)

    /** The spring into an edge after a release; cancelled by the next press. */
    private var settleJob: Job? = null

    /** The wait while a thrown-away bubble shrinks; cancelled by anything else taking it down first. */
    private var exitJob: Job? = null

    /** The theme the bubble's three windows were drawn in, read once when the bubble appeared. */
    private var bubbleTheme = ThemeMode.DARK

    private var promptHost: PanelHost? = null

    private var promptParams: WindowManager.LayoutParams? = null

    /** The prompt's timeout, for the panel's idle timeout's reason: a window drawn where nobody can reach it still goes. */
    private var promptJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()

        windowManager = getSystemService(WindowManager::class.java)

        // **The shade is not raised here.** `onCreate` runs for every reason the service starts,
        // and one of those reasons is a summon (`ACTION_SHOW_PANEL`) arriving at a service that is
        // not running — from the debug button, or from a `PendingIntent` that outlived the process
        // it was built in. Raising the shade here meant the summon turned the shade *on* over a
        // user who had turned it off, and left `shadeIntent` saying "stopped" while the screen was
        // dimmed: a button reading *Start dimming* over an already-dark screen. Measured on the
        // phone, not reasoned about. `onStartCommand` decides now, because it is the half of
        // starting that knows *why*.

        // The one source of truth for how dark it is. Collected rather than passed in the Intent so
        // that dragging the slider updates the live window without restarting anything.
        //
        // Kotlin note: `combine` over three `Flow`s is `Promise.all` over streams that never finish
        // — it emits a fresh tuple whenever *any* input changes, so this gets one settled
        // `DimSettings` rather than three callbacks it would have to reconcile, racing each other
        // into `updateViewLayout`. `distinctUntilChanged` after it is what stops an unrelated
        // preference write re-applying the window layout.
        combine(
            preferences.dimLevel,
            preferences.warmth,
            preferences.lowerBacklight,
        ) { level, warmth, lower ->
            DimSettings(dimLevel = level, warmth = warmth, lowerBacklight = lower)
        }.distinctUntilChanged()
            .onEach { settings ->
                lastSettings = settings
                // Moving a control during a pause is asking to see what it does.
                if (pausedUntil != null) endPause() else applyShadeValues(settings)
            }.launchIn(scope)

        pauseRequests
            .onEach { request ->
                when (request) {
                    is PauseRequest.Pause -> pause(request.length)
                    PauseRequest.Resume -> endPause()
                }
            }.launchIn(scope)

        // The compact controls opening, asking for the panel to go: one edge bar at a time, the one
        // opened last. See [panelOnScreen].
        floatingDismissals
            .onEach {
                removePanelWindow()
                removeBubbleWindow()
            }.launchIn(scope)
        bubbleRequests.onEach { showBubble() }.launchIn(scope)
        preferences.controlsBubble.onEach { enabled -> if (!enabled) removeBubbleWindow() }.launchIn(scope)

        // Separate from the combine above so the release half, a one-value flow, costs nothing: it
        // emits once, before or after the first settings, and either order ends painted correctly.
        minBacklight
            .onEach { value ->
                appliedMinBacklight = value
                lastSettings?.let { applyShadeValues(it) }
            }.launchIn(scope)

        // The warmth child's colour, collected on its own rather than folded into the combine above.
        // A colour is not an alpha: a trip through [applyShadeValues] would re-apply the backlight and
        // re-post the notification for a change that touches neither. With no window up yet, storing
        // it is enough, because [addShadeWindow] paints the child from [appliedTint].
        preferences.warmthColor
            .map(::warmthTint)
            .distinctUntilChanged()
            .onEach { tint ->
                appliedTint = tint
                warmthLayer?.let { layer -> shadeThread.run { layer.setBackgroundColor(tint) } }
            }
            // Past the stored colour, so only a colour the user picks ends a pause.
            .drop(1)
            .onEach { if (pausedUntil != null) endPause() }
            .launchIn(scope)

        // Auto-off. `collectLatest` is `switchMap` rather than `forEach`: a new deadline cancels the
        // wait still running for the old one, which is what re-arms the loop when the user taps a
        // different chip while the shade is up, and when the boot receiver restores a deadline. The
        // loop cannot capture the deadline once, because it moves for reasons other than starting.
        scope.launch {
            preferences.shadeIntent
                .map { it.offAtMillis }
                .distinctUntilChanged()
                .collectLatest { offAt ->
                    // The notification names this, so it is posted from the same emission that
                    // re-arms the loop — one place where the deadline moves, one place that says so.
                    currentDeadline = offAt
                    syncNotificationText()
                    awaitDeadline(offAt)
                }
        }

        // What wakes that loop when the phone does. Registered here rather than only while a
        // deadline is live, because the registration is one binder call for the life of a service
        // that exists only while the shade is up, and a filter that came and went with the deadline
        // would be a second thing to keep in step with it.
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), RECEIVER_NOT_EXPORTED)
    }

    /**
     * Wait out one deadline and take the shade down, or return immediately if there is none.
     *
     * **`NonCancellable` is required here rather than defensive, and without it the failure is
     * intermittent.** [AppPreferences.endShade] writes the very value this block is collecting: the
     * write commits, the flow emits, `collectLatest` cancels the block that is still running — and
     * the cancellation lands *between* the write returning and [stopSelf]. The shade comes down,
     * the intent goes false, and the service stays alive holding an ongoing notification over a
     * screen it is no longer dimming. Putting `stopSelf()` first does not help either: `onDestroy`
     * cancels the scope, which cancels the write from the other side.
     *
     * Kotlin note: cancellation is only ever observed at a suspension point, so once the
     * `NonCancellable` block returns, the plain `stopSelf()` after it always runs. This is the one
     * place worth stepping outside structured concurrency, and JS has no analogue — a `Promise`
     * cannot be cancelled out from under you, so there is nothing to opt out of.
     */
    private suspend fun awaitDeadline(offAtMillis: Long?) {
        if (offAtMillis == null) return
        while (coroutineContext.isActive) {
            val remaining = offAtMillis - System.currentTimeMillis()
            if (remaining <= 0) {
                // The one line R7 reads the lateness off. A notification vanishing is visible on the
                // phone and is not timestamped, and logging is not developer *surface*, so it lives
                // here rather than behind the debug seam.
                Log.i(TAG, "auto-off fired ${-remaining}ms after the deadline")
                // Before the write, not before `stopSelf`: the write is what emits, and the
                // emission is what would re-post the notification into an orphan.
                stopping = true
                withContext(NonCancellable) { preferences.endShadeAt(ShadeEnd.ByDeadline) }
                stopSelf()
                return
            }
            // Sleep until the next re-check **or the screen coming on**, whichever arrives first.
            // The timeout is the awake case and is unchanged; [screenOns] is the sleeping one, and
            // it is the only half that can observe the deadline the instant the user can.
            withTimeoutOrNull(min(remaining, DEADLINE_RECHECK_MS)) { screenOns.first() }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopFromWithin()
            return START_NOT_STICKY
        }
        // A service that was on its way out and is started again is not on its way out any more —
        // `stopSelf` is a request Android drops the moment a new start command arrives.
        stopping = false
        // Unconditionally, and before the panel branch below: `startForegroundService` carries a
        // hard contract — call `startForeground` within a few seconds or Android kills the process
        // — and a summon delivered to a service the ROM had killed and restarted arrives here too.
        // Calling it again on an already-foreground service costs nothing.
        startForeground(NOTIFICATION_ID, buildNotification())
        // A summon raises the panel and nothing else; every other reason to be started — the app's
        // own Start, the boot receiver, and Android's `START_STICKY` restart with a null intent —
        // raises the shade. The `else` is the load-bearing half: it is what keeps a summon that
        // arrives at a dead service from putting the shade up as a side effect of creating one.
        if (intent?.action == ACTION_SHOW_PANEL) {
            showPanel()
        } else {
            addShadeWindow()
            if (intent?.getBooleanExtra(EXTRA_LEAVE_BUBBLE, false) == true) showBubble()
        }
        return START_STICKY
    }

    /**
     * The shade comes down, by the notification's Stop action or by the panel's own button.
     *
     * Records that the user turned it off *before* stopping. Without this the stored intent still
     * says "running", and the next launch — or a system restart of a `START_STICKY` service — would
     * helpfully put the shade back over a user who just dismissed it.
     */
    private fun stopFromWithin() {
        scope.launch {
            stopping = true
            preferences.endShadeAt(ShadeEnd.ByHand)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Before the scope, because the only thing the receiver does is feed a flow collected on it.
        unregisterReceiver(screenOnReceiver)
        scope.cancel()
        pauseJob = null
        setPausedUntil(null)
        // Before the shade, because the panel is a child of the shade's lifetime rather than a peer
        // of it: there is no state in which the panel is up and the shade is not. This is the third
        // of the panel's three ways out, and the only one the user does not ask for.
        removePromptWindow()
        removeBubbleWindow()
        removePanelWindow()
        removeShadeWindow()
        // After the removal, never before: the removal finishes on this thread. See [ShadeThread.quit].
        shadeThread.quit()
        // Beside [stopping] rather than instead of it: the flag stops a re-post being made, and
        // this removes one that was already made. Android removes the notification with the service
        // by itself in the ordinary case, so this costs one binder call to make the invariant
        // local — the notification does not outlive the service — rather than something to be
        // inferred from the framework's timing.
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun addShadeWindow() {
        if (shadeView != null) return
        // Guarded because the permission can be revoked while the service is alive, and `addView`
        // throws rather than returning a failure. A shade that fails to appear is a bug; a crash
        // over it is a worse one.
        if (!canDrawShade()) return

        // **The tint above black**, and the order is not arbitrary. It makes no difference to how much
        // of the content survives — `(1 - a) x (1 - w)` either way — but it decides the tint's own
        // strength: underneath, the tint would be attenuated by exactly the black layer that makes
        // it worth having, so warmth would fade out where a dark-adapted eye most notices it.
        //
        // Both start transparent. The window is added here, before the first preference emission
        // arrives, and a child at Android's default alpha of 1 would paint one opaque black frame
        // over whatever the user was looking at.
        val dim =
            View(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                alpha = 0f
            }
        val warmth =
            View(this).apply {
                setBackgroundColor(appliedTint)
                alpha = 0f
            }
        val view =
            FrameLayout(this).apply {
                addView(dim, fillParams())
                addView(warmth, fillParams())
            }
        val params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    SHADE_WINDOW_FLAGS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // **Measured on a device, not assumed.** With the flags alone the shade stopped
                    // below the status bar on HyperOS: everything dimmed except a bright strip
                    // across the top, which reads as a bug rather than as a design. The system's own
                    // overlays carry this attribute and ours did not.
                    //
                    // ALWAYS needs API 30 and `minSdk` is 33 (ADR-0008), so this is unconditional.
                    // It used to branch three ways; two of those branches could never run on a
                    // device this app ships to, and one of them had never executed anywhere.
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    // **The bottom strip, and the flags above do not reach it.** Since Android 11 a
                    // window is laid out clear of every system bar in `fitInsetsTypes`, and the
                    // default is all of them. `FLAG_LAYOUT_IN_SCREEN` takes only the *status* bar
                    // out of that set, so the navigation bar stayed in it — the dump read
                    // `fitTypes=NAVIGATION_BARS` and a frame ending at 2664 of a 2712 px display —
                    // and `FLAG_LAYOUT_NO_LIMITS` cannot help, because it lifts the clip rather than
                    // the inset the frame was measured inside. The strip was undimmed wherever the
                    // navigation bar is showing, and gone in an immersive reader that hides it,
                    // which is why it looked like a per-app bug. Zero asks for the whole display.
                    fitInsetsTypes = 0
                }

        // On [shadeThread], and that is what binds the window to it. The views were built on main and
        // that is safe: a view belongs to no thread until its window is added. From here on this
        // service touches them, and the params' brightness, only inside `shadeThread.run`.
        shadeThread
            .run { runCatching { windowManager?.addView(view, params) } }
            .onSuccess {
                shadeView = view
                dimLayer = dim
                warmthLayer = warmth
                shadeParams = params
                reportShadeOnScreen(true)
                // The children are born transparent and the collector that would darken them is
                // `distinctUntilChanged`, so on every path where it has already emitted, this is
                // the only thing that paints them. See [lastSettings].
                lastSettings?.let { applyShadeValues(it) }
            }
    }

    /**
     * Both children fill the window.
     *
     * Stated rather than left to `FrameLayout`'s default, and a fresh instance per child rather than
     * one shared: a `ViewGroup` keeps the reference it is handed, so two children sharing one
     * `LayoutParams` are two children one edit away from moving together.
     */
    private fun fillParams() =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

    private fun removeShadeWindow() {
        val view = shadeView ?: return
        shadeThread.run { runCatching { windowManager?.removeView(view) } }
        shadeView = null
        dimLayer = null
        warmthLayer = null
        shadeParams = null
        backlightTop = null
        reportShadeOnScreen(false)
    }

    /**
     * Summon the panel, or do nothing.
     *
     * **`shadeView == null` is `docs/phase-3.md` §6's rule 2, enforced rather than assumed**: the
     * panel cannot outlive the shade, and it may not precede it either — a window that cannot exist
     * without a shade can never be the surface that starts one. On the notification route that
     * precondition holds by construction, because the notification only exists while this service
     * does; the check is what keeps a stale `PendingIntent` or a future caller from finding out
     * otherwise.
     *
     * The read is `suspend`, so the window is added from a coroutine rather than from here. That is
     * the whole reason [panelStateNow] exists: a `Flow` collected inside the composition would
     * arrive after the first frame, and the first frame is the one with the user's dim level on it.
     */
    private fun showPanel() {
        if (panelView != null) return
        scope.launch {
            val state = panelStateNow()
            // **The stored intent decides, not the window field.** The guard used to read
            // `shadeView == null`, meaning "only over a live shade" — but on the path that matters
            // it could never fail, because the summon that created the service had just filled that
            // field in `onCreate`. What the user asked for is in DataStore; a summon arriving after
            // they stopped the shade has nothing to open over, and the service it started has no
            // reason to stay alive.
            if (!state.running) {
                Log.i(TAG, "summoned with the shade stopped; nothing to open over")
                stopping = true
                stopSelf()
                return@launch
            }
            // A no-op whenever the shade is already up, which is the ordinary case. It does the
            // work only when the ROM killed the service while the stored intent still said running
            // — the summon then restores the shade first and opens the panel over it, rather than
            // opening a panel with nothing underneath.
            addShadeWindow()
            removeBubbleWindow()
            addPanelWindow(state)
        }
    }

    /**
     * Everything the panel draws, read once, before there is a window to draw it in.
     *
     * Six `first()` calls rather than one combined read, and it is not six disk reads: DataStore
     * serves `store.data` from its in-memory cache once anything has collected it, and this service
     * has been collecting since `onCreate`. There is also nothing to tear here — unlike
     * [AppPreferences.shadeIntent], no two of these are written in one transaction, so a reader that
     * takes them apart cannot see a half-written anything.
     */
    private suspend fun panelStateNow(): PanelState {
        // Read once and taken apart, rather than `.first()` twice: the two halves of the intent are
        // written in one transaction, and two reads could straddle it and pair a live deadline with
        // a stopped shade. Everything else here is an independent key, so it does not have the
        // problem and does not need the ceremony.
        val intent = preferences.shadeIntent.first()
        return PanelState(
            dimLevel = preferences.dimLevel.first(),
            warmth = preferences.warmth.first(),
            warmthColor = preferences.warmthColor.first(),
            running = intent.running,
            autoOff = preferences.autoOff.first(),
            themeMode = preferences.themeMode.first(),
            offAtMillis = intent.offAtMillis,
            pauseLength = preferences.pauseLength.first(),
            pausedUntil = pausedUntil,
        )
    }

    /**
     * Add the panel's window above the shade, with the controls in it.
     *
     * ## The size is the safety bound
     *
     * A touchable window blocks every touch under it, so where the shade is safe because of a flag,
     * the panel is safe because of its `LayoutParams` — a weaker kind of guarantee, because a flag
     * is a constant and a size is a computation. [panelWidthPx] is that computation and
     * `PanelWidthTest` sweeps it. The height is `WRAP_CONTENT` on purpose: a bounded height clips a
     * control, and an unreachable close button is the trap this whole design is shaped to avoid.
     *
     * ## Why it is centred on the trailing edge rather than anchored to a corner
     *
     * The design anchors the group to the bottom corner, and on a real phone that reads as a ladder
     * down the side rather than a control in a corner. The bar is 84dp wide but 330dp tall, the
     * buttons under it are another 180, and 510dp of a 904dp screen held 24dp off the bottom starts
     * *above* the middle and ends on the navigation bar. `CENTER_VERTICAL` spends that height either
     * side of the middle instead of all of it downwards — the difference between a control that sits
     * on the edge and one that runs down it. The reach argument for the corner survives it: what a
     * thumb needs is the foot band and the buttons, and centring moves those *towards* the middle of
     * the screen rather than away from it.
     *
     * **Centred means the centre of the app's area, not of the display — and of exactly the area the
     * compact controls' dialog is centred in.** The two hosts draw the same bar and replace each other
     * (one edge bar at a time, [panelOnScreen]), so a bar that moves when one takes over from the other
     * shows the user a seam they have no reason to know about. The group centres between the bars
     * with no inset arithmetic here, because `fitInsetsTypes` does it: see the params below.
     *
     * **This paragraph used to say the frame already stopped short of both bars, and it did not.**
     * `FLAG_LAYOUT_IN_SCREEN` takes the status bar out of the default fit types, so the panel was
     * centred in `[0, 2664]` while the dialog, which fits every system bar, was centred in
     * `[130, 2664]`: 65 px apart on the phone, half the status bar's height. R6 read the bottom edge
     * back when this was an offset from the bottom, and the top was never looked at. The attempt
     * before R6 added the navigation inset by hand and floated the panel five times too high.
     *
     * ## No brightness of its own
     *
     * `screenBrightness` is left at `BRIGHTNESS_OVERRIDE_NONE`, which means *not asking* rather than
     * *asking for nothing* — and R1 measured that the shade underneath keeps its override when this
     * window declines. One dim level, one ramp, in a fixed order (ADR-0010): a second window deriving
     * a second brightness would be a second ramp wearing a window's clothes.
     */
    private fun addPanelWindow(initial: PanelState) {
        if (panelView != null) return
        val manager = windowManager ?: return
        // Guarded like `addShadeWindow`: the permission can be revoked while the service is alive,
        // and `addView` throws rather than returning a failure.
        if (!canDrawShade()) return

        val state = MutableStateFlow(initial)
        val host =
            PanelHost(
                context = this,
                onTouch = { panelTouches.tryEmit(Unit) },
                // A tap on the app behind the panel is the user going back to what they were doing.
                // The panel is a control summoned for a moment, so that is a dismissal — the same
                // answer the close button gives, reached without aiming at anything — except that
                // this one leaves the bubble behind, and the button does not (ADR-0016).
                onOutside = {
                    removePanelWindow()
                    showBubble()
                },
            )

        host.setContent {
            PanelContent(
                state = state,
                onDimLevel = { level -> scope.launch { preferences.setDimLevel(level) } },
                onWarmth = { warmth -> scope.launch { preferences.setWarmth(warmth) } },
                onAutoOff = ::setPanelAutoOff,
                onToggleRunning = ::togglePanelRunning,
                onPause = ::pause,
                onResume = ::endPause,
                onOpenApp = ::openFullAppFromPanel,
                onSectionOpen = ::onPanelSectionOpen,
                onClose = ::removePanelWindow,
            )
        }

        val density = resources.displayMetrics.density
        val params =
            WindowManager
                .LayoutParams(
                    currentPanelWidth(manager),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    PANEL_WINDOW_FLAGS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    // **One edge, centred on it.** A window that reaches into a single edge leaves
                    // the rest of the app touchable, which is the safety argument; why it is centred
                    // rather than cornered is in this function's doc. `END` rather than `RIGHT` so a
                    // right-to-left layout mirrors it with the rest of the UI. The known limitation,
                    // carried from the design: this assumes a right hand in a left-to-right locale,
                    // and a left-handed user has no preference to say so yet.
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    x = (PANEL_SIDE_MARGIN_DP * density).toInt()
                    // **Every system bar, stated rather than defaulted**, so the panel is centred in
                    // the area a floating dialog is — the compact controls' — and the two bars land on
                    // the same pixels. Setting this also marks the fit types as the app's own choice,
                    // which is what stops the platform's compatibility pass from dropping the status
                    // bar out of them for `FLAG_LAYOUT_IN_SCREEN`.
                    fitInsetsTypes = WindowInsets.Type.systemBars()
                    // No `y`. Under `CENTER_VERTICAL` it would be an offset *from the centre*, which
                    // is not a thing this layout wants — so the bottom margin that used to live here
                    // is gone, constant and all, rather than left at zero for a later reader to
                    // wonder about.
                }

        runCatching { manager.addView(host.view, params) }
            .onSuccess {
                panelView = host.view
                panelHost = host
                panelState = state
                panelParams = params
                // **After the first frame is on the surface, not when `addView` returns.** The
                // compact controls finish the moment this reads true, and a window that has been
                // added has not yet drawn anything: the phone showed one frame with *no* bar at all
                // between the two, which read as the panel jumping in. A frame-commit callback runs
                // once, after the frame it follows has reached the surface, so the compact controls
                // now go only when the panel is there to take their place.
                host.view.viewTreeObserver.registerFrameCommitCallback { reportPanelOnScreen(panelView != null) }
                // **`RESUMED` is not a formality.** Below `STARTED` the `Recomposer` stops applying
                // recompositions, and the window draws its first frame correctly and then never
                // changes again — a slider that will not move under a finger, with nothing in
                // logcat to say why.
                host.show()
                panelJob = scope.launch { trackPanel(state) }
            }.onFailure { host.destroy() }
    }

    /** The panel's width for the display as it is *now*, and for whatever the panel currently shows. */
    private fun currentPanelWidth(manager: WindowManager): Int =
        panelWidthPx(
            displayWidthPx = manager.currentWindowMetrics.bounds.width(),
            density = resources.displayMetrics.density,
            sectionOpen = panelSectionOpen,
        )

    /**
     * **The composition telling the window how much room it now needs.**
     *
     * The panel is touchable, so its width is what stands in for the shade's `FLAG_NOT_TOUCHABLE`
     * (ADR-0011) — and the edge bar's width is not constant: opening the warmth column or the timer
     * chips needs room to the left of the bar, and closing them gives it back. A window sized for
     * the *open* state at all times would block a strip of the app underneath that nothing is drawn
     * in, for the whole time the panel is up.
     *
     * It is a callback from the composition rather than a state the service infers, because the
     * disclosure belongs to `CompactControls` — the service has no way to know a chip was tapped.
     * Marked with the touch that caused it, too: re-arming the idle timeout here would be wrong, and
     * it does not need to be, because the same tap already went through `TouchReportingLayout`.
     */
    private fun onPanelSectionOpen(open: Boolean) {
        if (panelSectionOpen == open) return
        panelSectionOpen = open
        val view = panelView ?: return
        val params = panelParams ?: return
        val manager = windowManager ?: return
        val width = currentPanelWidth(manager)
        if (params.width == width) return
        params.width = width
        runCatching { manager.updateViewLayout(view, params) }
    }

    /**
     * Re-measure the panel when the display changes shape.
     *
     * **Android platform, not our choice:** a `WindowManager` window laid out from explicit pixels
     * keeps those pixels across a rotation. Nothing re-runs [panelWidthPx], so without this the
     * panel wears the width of whichever orientation it was summoned in. R8 read the bad case off
     * the phone: summoned in landscape the width cap gives 1200 px, and rotating to portrait leaves
     * 1200 px on a 1220 px display — a touchable window with 10 px of screen either side of it,
     * where the whole point of the inset is that touches still reach the app beside the panel.
     *
     * Only the width. The height is `WRAP_CONTENT`, so Compose re-measures it for free, and `y` is
     * a density-scaled constant that a rotation does not change. The prompt is the same case.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The bubble inherits ADR-0011's rotation hazard: a position kept from landscape can be off
        // a portrait display, and a touchable window nobody can see is the trap.
        retuckBubble()
        placeDismissTarget()
        val manager = windowManager ?: return
        resizeWindow(promptHost?.view, promptParams, currentPromptWidth(manager))
        resizeWindow(panelView, panelParams, currentPanelWidth(manager))
    }

    private fun resizeWindow(
        view: View?,
        params: WindowManager.LayoutParams?,
        width: Int,
    ) {
        if (view == null || params == null || params.width == width) return
        params.width = width
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun currentPromptWidth(manager: WindowManager): Int =
        promptWidthPx(manager.currentWindowMetrics.bounds.width(), resources.displayMetrics.density)

    /** Back into the side it rested on, at once: a rotation moves the display, not the user's choice. */
    private fun retuckBubble() {
        val params = bubbleParams ?: return
        val manager = windowManager ?: return
        val rest = lastBubbleRest ?: BubbleRest(bubbleTucked.value ?: BubbleSide.Right, params.y)
        settleJob?.cancel()
        moveBubble(tuckInto(rest, params.width, bubbleArea(manager), bubblePeekPx()))
        bubbleTucked.value = rest.side
    }

    /**
     * Take the panel down, from any of its three routes: the close control, the idle timeout, or
     * `onDestroy` when the shade goes.
     *
     * **It must not take the shade with it.** The panel closing is not the user saying they are done
     * reading; the two lifetimes point one way only.
     */
    private fun removePanelWindow() {
        panelJob?.cancel()
        panelJob = null
        val view = panelView ?: return
        runCatching { windowManager?.removeView(view) }
        panelSectionOpen = false
        panelHost?.destroy()
        panelView = null
        panelHost = null
        panelState = null
        panelParams = null
        reportPanelOnScreen(false)
    }

    /**
     * Keep [PanelState] current, and take the panel down when it has been left alone.
     *
     * One collector per preference rather than one `combine` over six, because each writes its own
     * field with `copy()` and none of them needs to know about the others — and `coroutineScope`
     * makes the whole set one cancellable unit, which is what [removePanelWindow] cancels.
     *
     * **The timeout is `collectLatest`, which is `switchMap` and not `forEach`**: a new touch cancels
     * the wait still running for the previous one, which is what re-arms it. `onStart` emits once so
     * the clock is running from the moment the panel appears rather than from the first touch — a
     * panel drawn where nobody can touch it is exactly the case the timeout exists for.
     *
     * Calling [removePanelWindow] from inside this block cancels the job the block is running in.
     * That is safe here and would not be in general: cancellation is only observed at a suspension
     * point, and there is none after the call, so the removal completes before the coroutine dies.
     * `ShadeService`'s deadline loop is the same hazard with the opposite answer — there the write
     * suspends, which is what `NonCancellable` is for in [awaitDeadline].
     */
    private suspend fun trackPanel(state: MutableStateFlow<PanelState>) =
        coroutineScope {
            preferences.dimLevel.onEach { v -> state.update { it.copy(dimLevel = v) } }.launchIn(this)
            preferences.warmth.onEach { v -> state.update { it.copy(warmth = v) } }.launchIn(this)
            preferences.warmthColor.onEach { v -> state.update { it.copy(warmthColor = v) } }.launchIn(this)
            preferences.autoOff.onEach { v -> state.update { it.copy(autoOff = v) } }.launchIn(this)
            preferences.pauseLength.onEach { v -> state.update { it.copy(pauseLength = v) } }.launchIn(this)
            shadePausedUntil.onEach { v -> state.update { it.copy(pausedUntil = v) } }.launchIn(this)
            // The deadline only for the pause's sake: a pause that the deadline will end says so.
            preferences
                .shadeIntent
                .onEach { v -> state.update { it.copy(running = v.running, offAtMillis = v.offAtMillis) } }
                .launchIn(this)
            preferences.themeMode.onEach { v -> state.update { it.copy(themeMode = v) } }.launchIn(this)

            launch {
                panelTouches.onStart { emit(Unit) }.collectLatest {
                    delay(PANEL_IDLE_TIMEOUT_MS)
                    Log.i(TAG, "panel idle for ${PANEL_IDLE_TIMEOUT_MS}ms, taking it down")
                    removePanelWindow()
                }
            }
        }

    /**
     * Leave the bubble on screen, or do nothing.
     *
     * Only over a running shade with neither the panel nor a bubble already up, and only while the
     * preference says so — all read here rather than at the callers, because a request can arrive
     * from the compact controls closing a moment after the shade was stopped from them.
     */
    private fun showBubble() {
        if (bubbleHost != null || panelView != null || shadeView == null) return
        scope.launch {
            if (!preferences.controlsBubble.first()) return@launch
            if (!preferences.shadeIntent.first().running) return@launch
            addBubbleWindow(preferences.themeMode.first())
        }
    }

    /** The app's area of the display: the bounds minus the system bars and the cutout. */
    private fun bubbleArea(manager: WindowManager): BubbleArea {
        val metrics = manager.currentWindowMetrics
        val insets =
            metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        val bounds = metrics.bounds
        return BubbleArea(
            left = insets.left,
            top = insets.top,
            right = bounds.width() - insets.right,
            bottom = bounds.height() - insets.bottom,
        )
    }

    private fun bubbleSizePx(manager: WindowManager): Int {
        val bounds = manager.currentWindowMetrics.bounds
        return bubbleSidePx(BUBBLE_SIZE_DP, resources.displayMetrics.density, min(bounds.width(), bounds.height()))
    }

    private fun bubblePeekPx() = (BUBBLE_PEEK_DP * resources.displayMetrics.density).toInt()

    /**
     * The bubble's three windows share a coordinate system: the whole display, origin top-left.
     * `fitInsetsTypes = 0` is what makes `x`/`y` display pixels, the same frame `MotionEvent.rawX`
     * reports in — see [BubbleTouchLayout].
     */
    private fun displayFrameParams(
        width: Int,
        height: Int,
        flags: Int,
    ) = WindowManager
        .LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun addBubbleWindow(theme: ThemeMode) {
        if (bubbleHost != null || panelView != null || shadeView == null) return
        val manager = windowManager ?: return
        if (!canDrawShade()) return
        bubbleTheme = theme
        bubbleLeaving.value = false
        bubblePressed.value = false

        addDismissTargetWindow(manager)

        val size = bubbleSizePx(manager)
        val area = bubbleArea(manager)
        // The trailing edge, centred — where the bar it stands in for was.
        val rest = lastBubbleRest ?: BubbleRest(BubbleSide.Right, (area.top + area.bottom - size) / 2)
        val position = tuckInto(rest, size, area, bubblePeekPx())
        bubbleTucked.value = rest.side

        val host = PanelHost(this, BubbleTouchLayout(this, bubbleGestures))
        host.view.contentDescription = getString(R.string.bubble_open)
        // Kotlin note: the trailing lambda is the action's handler — `{ _, _ -> … }` ignores the view
        // and arguments it is handed. A drag is the only gesture that removes the bubble, and an
        // accessibility service cannot perform one, so the removal needs an action of its own.
        ViewCompat.addAccessibilityAction(host.view, getString(R.string.bubble_remove)) { _, _ ->
            throwBubbleAway()
            true
        }
        host.setContent {
            BubbleContent(
                themeMode = theme,
                pressed = bubblePressed,
                leaving = bubbleLeaving,
                tucked = bubbleTucked,
            )
        }
        val params =
            displayFrameParams(size, size, BUBBLE_WINDOW_FLAGS).apply {
                x = position.x
                y = position.y
            }
        runCatching { manager.addView(host.view, params) }
            .onSuccess {
                bubbleHost = host
                bubbleParams = params
                host.show()
            }.onFailure {
                host.destroy()
                removeDismissTargetWindow()
            }
    }

    /** A shrinking bubble is already gone as far as the user is concerned, so it takes no more gestures. */
    private val bubbleGestures =
        object : BubbleGestures {
            override fun onPressChange(pressed: Boolean) {
                if (bubbleLeaving.value) return
                if (pressed) settleJob?.cancel()
                bubblePressed.value = pressed
            }

            override fun onTap() {
                if (bubbleLeaving.value) return
                removeBubbleWindow()
                showPanel()
            }

            override fun onDragStart() {
                if (bubbleLeaving.value) return
                val params = bubbleParams ?: return
                dragOrigin = BubblePosition(params.x, params.y)
                overTarget.value = false
                bubbleTucked.value = null
                setBubbleDragging(true)
            }

            override fun onDrag(
                dxPx: Float,
                dyPx: Float,
            ) {
                val origin = dragOrigin ?: return
                val params = bubbleParams ?: return
                val manager = windowManager ?: return
                val area = bubbleArea(manager)
                val wanted = BubblePosition(origin.x + dxPx.toInt(), origin.y + dyPx.toInt())
                var position =
                    clampBubble(wanted, params.width, params.height, bubbleDragArea(area, params.width, bubblePeekPx()))
                val target = targetParams
                val over = target != null && bubbleOverTarget(position, params.width, target)
                // Close enough counts as on it: the bubble jumps to the ✕'s centre and stays there
                // until the finger pulls it clear, so a drop is never a near miss.
                if (over && target != null) {
                    val centred =
                        BubblePosition(
                            target.x + (target.width - params.width) / 2,
                            target.y + (target.height - params.height) / 2,
                        )
                    position = clampBubble(centred, params.width, params.height, area)
                }
                overTarget.value = over
                moveBubble(position)
            }

            override fun onDragEnd(
                cancelled: Boolean,
                velocityX: Float,
                velocityY: Float,
            ) {
                if (dragOrigin == null) return
                dragOrigin = null
                setBubbleDragging(false)
                val dropped = !cancelled && overTarget.value
                overTarget.value = false
                if (dropped) {
                    throwBubbleAway()
                } else {
                    settleBubble(velocityX, velocityY, animate = true)
                }
            }
        }

    /** Shrink the bubble away, then take it down — from a drop on the ✕ or the accessibility action. */
    private fun throwBubbleAway() {
        if (bubbleHost == null || bubbleLeaving.value) return
        settleJob?.cancel()
        bubbleLeaving.value = true
        exitJob =
            scope.launch {
                delay(BUBBLE_EXIT_MS.toLong())
                // Cleared first, so [removeBubbleWindow] does not cancel the coroutine calling it.
                exitJob = null
                removeBubbleWindow()
                onBubbleThrownAway()
            }
    }

    private fun setBubbleDragging(dragging: Boolean) {
        bubbleDragging.value = dragging
        val params = targetParams ?: return
        val view = targetHost?.view ?: return
        params.alpha = dismissTargetWindowAlpha(dragging)
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun moveBubble(position: BubblePosition) {
        val params = bubbleParams ?: return
        val view = bubbleHost?.view ?: return
        if (params.x == position.x && params.y == position.y) return
        params.x = position.x
        params.y = position.y
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun bubbleOverTarget(
        bubble: BubblePosition,
        bubbleSize: Int,
        target: WindowManager.LayoutParams,
    ): Boolean =
        overDismissTarget(
            bubbleCenterX = bubble.x + bubbleSize / 2f,
            bubbleCenterY = bubble.y + bubbleSize / 2f,
            targetCenterX = target.x + target.width / 2f,
            targetCenterY = target.y + target.height / 2f,
            radiusPx = target.width.toFloat(),
        )

    /**
     * Tuck the bubble into an edge — sprung after a release, at once after a rotation.
     *
     * **A spring carrying the finger's velocity**, which is what makes a flick feel thrown rather
     * than placed: the release speed is both projected to choose the edge and handed to the spring
     * as its starting velocity, so the bubble leaves the finger at the speed it was moving.
     *
     * Compose's `Animatable`, though nothing here is composed: it only needs a frame clock, and
     * `AndroidUiDispatcher.Main` is the main thread with one attached — each frame then becomes an
     * `updateViewLayout`, because what moves is the *window*. Kotlin note: cancelling [settleJob] is
     * how a press stops the spring mid-flight, the way an `AbortController` would stop a JS tween.
     */
    private fun settleBubble(
        velocityX: Float,
        velocityY: Float,
        animate: Boolean,
    ) {
        val params = bubbleParams ?: return
        val manager = windowManager ?: return
        val area = bubbleArea(manager)
        val start = BubblePosition(params.x, params.y)
        val projected =
            BubblePosition(
                start.x + (velocityX * BUBBLE_FLING_PROJECTION_S).toInt(),
                start.y + (velocityY * BUBBLE_FLING_PROJECTION_S).toInt(),
            )
        val (end, side) = tuckToEdge(projected, params.width, area, bubblePeekPx())
        lastBubbleRest = BubbleRest(side, end.y)
        settleJob?.cancel()
        if (!animate) {
            moveBubble(end)
            bubbleTucked.value = side
            return
        }
        val bounds = bubbleDragArea(area, params.width, bubblePeekPx())
        settleJob =
            scope.launch(AndroidUiDispatcher.Main) {
                // The shape docks the moment the bubble touches the edge, not on the way there: a tab
                // drawn flat for an edge it has not reached yet shows a gap at the border. The
                // overshoot is clamped at the tuck, so the first crossing is exactly the arrival.
                val dockWithin = 2 * resources.displayMetrics.density
                val position = Animatable(Offset(start.x.toFloat(), start.y.toFloat()), Offset.VectorConverter)
                position.animateTo(
                    targetValue = Offset(end.x.toFloat(), end.y.toFloat()),
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                    initialVelocity = Offset(velocityX, velocityY),
                ) {
                    // Clamped every frame: the spring overshoots, and an overshoot must never carry
                    // the bubble further off screen than the tuck itself.
                    val onScreen =
                        clampBubble(
                            BubblePosition(value.x.toInt(), value.y.toInt()),
                            params.width,
                            params.height,
                            bounds,
                        )
                    moveBubble(onScreen)
                    // The clamped position, not the spring's: the spring's value is past the edge
                    // for the whole overshoot, while the bubble on screen is already at it.
                    if (bubbleTucked.value == null && abs(onScreen.x - end.x) < dockWithin) bubbleTucked.value = side
                }
                bubbleTucked.value = side
            }
    }

    private fun addDismissTargetWindow(manager: WindowManager) {
        if (targetHost != null) return
        val side = (DISMISS_TARGET_DP * resources.displayMetrics.density).toInt()
        val host = PanelHost(this, FrameLayout(this))
        bubbleDragging.value = false
        host.setContent { DismissTargetContent(bubbleTheme, bubbleDragging, overTarget) }
        val params =
            displayFrameParams(side, side, DISMISS_TARGET_WINDOW_FLAGS).apply {
                alpha = dismissTargetWindowAlpha(dragging = false)
            }
        runCatching { manager.addView(host.view, params) }
            .onSuccess {
                targetHost = host
                targetParams = params
                placeDismissTarget()
                host.show()
            }.onFailure { host.destroy() }
    }

    /** Bottom centre of the app's area; re-run on rotation, which moves both. */
    private fun placeDismissTarget() {
        val params = targetParams ?: return
        val view = targetHost?.view ?: return
        val manager = windowManager ?: return
        val area = bubbleArea(manager)
        val gap = (DISMISS_TARGET_BOTTOM_DP * resources.displayMetrics.density).toInt()
        params.x = (area.left + area.right - params.width) / 2
        params.y = area.bottom - gap - params.height
        runCatching { manager.updateViewLayout(view, params) }
    }

    private fun removeBubbleWindow() {
        settleJob?.cancel()
        settleJob = null
        exitJob?.cancel()
        exitJob = null
        bubbleLeaving.value = false
        bubbleTucked.value = null
        dragOrigin = null
        removeDismissTargetWindow()
        val host = bubbleHost ?: return
        runCatching { windowManager?.removeView(host.view) }
        host.destroy()
        bubbleHost = null
        bubbleParams = null
    }

    private fun removeDismissTargetWindow() {
        val host = targetHost ?: return
        runCatching { windowManager?.removeView(host.view) }
        host.destroy()
        targetHost = null
        targetParams = null
    }

    /** The first time only, ask whether the user wants the bubble at all. */
    private fun onBubbleThrownAway() {
        scope.launch {
            if (preferences.bubbleAsked.first()) return@launch
            preferences.setBubbleAsked()
            addPromptWindow()
        }
    }

    /**
     * The question, in a window of its own because it has to be read at maximum dim: an Activity
     * would be under the shade (ADR-0011). Shaped like the panel — not focusable, a tap outside
     * closes it, and it goes on its own after [PANEL_IDLE_TIMEOUT_MS].
     */
    private fun addPromptWindow() {
        if (promptHost != null) return
        val manager = windowManager ?: return
        if (!canDrawShade()) return
        val host = PanelHost(this, onTouch = {}, onOutside = ::removePromptWindow)
        host.setContent {
            BubblePromptContent(
                themeMode = bubbleTheme,
                onTurnOff = {
                    scope.launch { preferences.setControlsBubble(false) }
                    removePromptWindow()
                },
                onKeep = ::removePromptWindow,
            )
        }
        val density = resources.displayMetrics.density
        val params =
            WindowManager
                .LayoutParams(
                    currentPromptWidth(manager),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    PANEL_WINDOW_FLAGS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (DISMISS_TARGET_BOTTOM_DP * density).toInt()
                    fitInsetsTypes = WindowInsets.Type.systemBars()
                }
        runCatching { manager.addView(host.view, params) }
            .onSuccess {
                promptHost = host
                promptParams = params
                host.show()
                promptJob =
                    scope.launch {
                        delay(PANEL_IDLE_TIMEOUT_MS)
                        removePromptWindow()
                    }
            }.onFailure { host.destroy() }
    }

    private fun removePromptWindow() {
        promptJob?.cancel()
        promptJob = null
        val host = promptHost ?: return
        runCatching { windowManager?.removeView(host.view) }
        host.destroy()
        promptHost = null
        promptParams = null
    }

    /**
     * The panel's auto-off chips — **the mirror of `DimViewModel.setAutoOff`, and the same pair of
     * writes for the same reason.**
     *
     * Tapping a preset while the shade is up re-dates the deadline *from now* rather than from when
     * the shade started, which is what somebody looking at "2 hours" expects it to mean and what
     * makes the chips usable as "give me two more hours" without a second control for it.
     *
     * Written out here rather than shared with the `ViewModel`: a `ViewModel` is not reachable from
     * a service, and the alternative — pushing the policy down into `AppPreferences` — would put a
     * decision about deadlines inside the file `CLAUDE.md` reserves for keys, defaults and setters.
     * It is the same trade [togglePanelRunning] already makes below.
     */
    private fun setPanelAutoOff(choice: AutoOff) {
        scope.launch {
            preferences.setAutoOff(choice)
            // The same guard `DimViewModel.setAutoOff` carries, and for the same reason: the pair
            // that writes the deadline also writes `running = true`.
            if (preferences.shadeIntent.first().running) {
                preferences.beginShadeAt(ShadeStart.ByHand)
            }
        }
    }

    /**
     * The panel's cog: the full app, and the panel closed behind it.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is required rather than stylistic — a service has no task of its own
     * to start an Activity in, and the platform throws without it. The panel comes down because it
     * would otherwise sit above the very screen it just opened; the shade stays up, because leaving
     * it is not what the cog was tapped for.
     */
    private fun openFullAppFromPanel() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        removePanelWindow()
    }

    /**
     * The panel's start/stop button.
     *
     * **In practice this only ever stops**, because the panel cannot be up without the shade — but
     * the button says what [PanelState.running] tells it to say, and a button that lies is worse
     * than a branch that rarely runs. The stop half is deliberately the same call the notification's
     * Stop action makes, so the two cannot drift; the start half is `DimViewModel`'s pair, because
     * a deadline is never written without the flag beside it.
     */
    private fun togglePanelRunning() {
        if (panelState?.value?.running == false) {
            scope.launch { preferences.beginShadeAt(ShadeStart.ByHand) }
        } else {
            stopFromWithin()
        }
    }

    /**
     * Clear the shade and release the backlight for the chosen length, keeping the service, its
     * notification and the stored intent exactly as they are (CONTEXT.md: **pause**).
     *
     * Cleared by drawing dim level 0 rather than by removing the window: the panel and the bubble
     * both hang off the shade's window, and level 0 already means *running, transparent, the
     * backlight the user's own*. It also drops [backlightTop], so the resume captures afresh from
     * wherever the user left their own brightness during the pause.
     */
    private fun pause(length: PauseLength) {
        if (shadeView == null) return
        val until = pausedUntilFrom(System.currentTimeMillis(), length)
        pauseJob?.cancel()
        setPausedUntil(until)
        lastSettings?.let { applyShadeValues(it) }
        syncNotificationText()
        pauseJob = scope.launch { awaitPauseEnd(until) }
    }

    /**
     * The deadline loop's wall-clock wait, for its reason: `delay` runs on uptime, which stops in
     * deep sleep, so a single long wait would end the pause late on a sleeping phone.
     *
     * **Ending a pause is not a start** (ADR-0012's amendment). Nothing here writes the deadline, and
     * when the deadline falls inside the pause the shade is left clear for the deadline loop to take
     * down rather than raised for the moments in between.
     */
    private suspend fun awaitPauseEnd(until: Long) {
        while (true) {
            val remaining = until - System.currentTimeMillis()
            if (remaining <= 0) break
            withTimeoutOrNull(min(remaining, DEADLINE_RECHECK_MS)) { screenOns.first() }
        }
        if (!pauseEnd(until, currentDeadline).resumes) return
        // Cleared first, so [endPause] does not cancel the coroutine calling it.
        pauseJob = null
        endPause()
    }

    /** Bring the shade and the backlight back, from the pause running out or from a resume tap. */
    private fun endPause() {
        pauseJob?.cancel()
        pauseJob = null
        if (pausedUntil == null) return
        setPausedUntil(null)
        lastSettings?.let { applyShadeValues(it) }
        syncNotificationText()
    }

    private fun setPausedUntil(until: Long?) {
        pausedUntil = until
        reportPausedUntil(until)
    }

    /**
     * One dim level in, a backlight and an alpha out — the arithmetic itself is [shadeValuesFor],
     * which has no Android under it and is proven by `ShadeRampTest` rather than by looking at a
     * screen. What is left here is the part that genuinely needs a device: deciding *when* the
     * override is captured and when it is let go.
     */
    private fun applyShadeValues(requested: DimSettings) {
        val settings = if (pausedUntil != null) requested.copy(dimLevel = 0, warmth = 0) else requested
        // The override goes from released to applied exactly when the level crosses 0 or the toggle
        // comes on, and that is the moment to read: any later and we would be reading a brightness
        // the user could no longer see to change. A user who wants their own slider back drags Gloam
        // to 0, which releases the override — and makes the next capture a fresh one.
        if (settings.lowerBacklight && settings.dimLevel > 0) {
            if (backlightTop == null) backlightTop = readBacklightTop(this)
        } else {
            backlightTop = null
        }

        val values = shadeValuesFor(settings, backlightTop, appliedMinBacklight)
        // A view property, unlike the backlight below: `alpha` takes effect on its own, where
        // `params.screenBrightness` does nothing until the window layout is handed back. Set on the
        // shade's thread, which owns the views, and skipped outright when there is no window.
        val dim = dimLayer
        val warmth = warmthLayer
        if (dim != null && warmth != null) {
            shadeThread.run {
                dim.alpha = values.shadeAlpha
                warmth.alpha = values.warmthAlpha
            }
        }
        applyBacklight(values.backlight)
        // After the backlight, never before: [backlightOverrideLive] reads what was just written.
        syncNotificationText()
    }

    /**
     * Is the backlight override actually on the window, right now?
     *
     * Read off [shadeParams] rather than recomputed from the preferences, and that is the whole
     * point of the function. The level and the toggle say what we *want*; two further things can
     * still make it untrue, and neither is a preference. [readBacklightTop] can decline to give us
     * a number on a device that does not report one, and the shade window can be missing entirely —
     * a revoked overlay permission, or a summon that arrived at a dead service, leaves this service
     * alive with its notification up and nothing on screen to override anything. The params are
     * nulled with the window, so this reads false in every one of those states.
     *
     * That matters more than it looks: the line is being put on the notification *because* it is
     * the one surface a user can read at the backlight floor, and a false explanation there is worse than no
     * explanation in the app underneath.
     */
    private fun backlightOverrideLive(): Boolean {
        val brightness = shadeParams?.screenBrightness ?: return false
        return brightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    /**
     * Re-post the notification when what it says changes, and on nothing else.
     *
     * `notify` rather than a second `startForeground`: this notification is already the foreground
     * one, and posting under the same id replaces it in place rather than adding a second row.
     *
     * **There is a transition on the way down, and it was measured rather than reasoned about.**
     * This comment used to say there was none — that [removeShadeWindow] runs from `onDestroy`,
     * where the notification goes with the service. What it missed is that the deadline is a
     * *preference*: the write that ends the shade emits, the emission sets [currentDeadline] to
     * null, and this function is called from that emission while the service is already on its way
     * out. [stopping] is what closes it.
     */
    private fun syncNotificationText() {
        if (stopping) return
        val facts = NotificationFacts(backlightOverrideLive(), currentDeadline, pausedUntil)
        if (facts == announced) return
        announced = facts
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * A clock time for the notification, the platform's way.
     *
     * `DateFormat.getTimeFormat` follows the phone's own 12- or 24-hour setting and its locale, which
     * a hand-built pattern cannot — and it is the same call `rememberTimeText` makes in the app, so
     * the notification and the screen underneath it cannot format the same instant differently.
     */
    private fun formatTime(instant: Long): String =
        android.text.format.DateFormat
            .getTimeFormat(this)
            .format(java.util.Date(instant))

    /**
     * Push the window's brightness override, or release it.
     *
     * `null` becomes `BRIGHTNESS_OVERRIDE_NONE` here and nowhere else — the one place a nullable
     * Kotlin value collapses to Java's sentinel, the way a discriminated union collapses to a wire
     * format at the boundary.
     */
    private fun applyBacklight(value: Float?) {
        val view = shadeView ?: return
        val params = shadeParams ?: return
        val requested = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (params.screenBrightness == requested) return
        // The write happens on the shade's thread too, not only the hand-back. The window's root
        // view keeps a reference to these very params, so a write from main could land while that
        // thread reads them. Reading on main, as the line above and [backlightOverrideLive] do, is
        // safe: the shade thread never writes.
        shadeThread.run {
            params.screenBrightness = requested
            // Guarded for the same reason `addView` is: the window can be gone underneath us.
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    /**
     * What the notification on screen right now says, so a re-post only happens when it would
     * change.
     *
     * Both halves in one value rather than two fields, because they are compared together and a
     * second flag is a second thing to forget: `notify` is a binder call, and Android throttles a
     * notification updated too often.
     */
    private data class NotificationFacts(
        val backlightLive: Boolean,
        val offAtMillis: Long?,
        val pausedUntil: Long?,
    )

    private fun buildNotification(): Notification {
        // **The row's plain tap summons the panel, and launches no Activity at all.** Phase 1's R8
        // read what a tap does on HyperOS: the *Stop* action is behind a long-press and is not in
        // the collapsed row, so a plain tap follows this intent — which used to land on the full app
        // *under* the shade. The panel is above the shade instead, at the backlight's own level, so this
        // is the one route in this phase that is a legibility improvement rather than a reach one.
        //
        // `getService` rather than `getActivity`, which also settles the caching question: pending
        // intents are keyed by requesting identity, and two `Intent`s that are `filterEquals` — same
        // action, component, data, categories — are the same pending intent whatever their extras
        // say. This changes the kind as well as the component, so there is nothing stale to inherit
        // from a previous version of the app. A later change that touches only extras would need
        // `FLAG_UPDATE_CURRENT` beside `FLAG_IMMUTABLE`, or the old extras survive the update
        // silently.
        //
        // The panel's precondition — that there is a shade for it to belong to — holds by
        // construction here, because this notification exists only while the service does. It is
        // still checked in [showPanel]: a `PendingIntent` outlives the process that built it.
        val open =
            PendingIntent.getService(
                this,
                0,
                Intent(this, ShadeService::class.java).setAction(ACTION_SHOW_PANEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ShadeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val pause = pausedUntil?.let { pauseEnd(it, currentDeadline) }
        val builder =
            NotificationCompat
                .Builder(this, AppChannel.Shade.id)
                .setContentTitle(
                    getString(
                        if (pause ==
                            null
                        ) {
                            R.string.shade_notification_title
                        } else {
                            R.string.shade_notification_title_paused
                        },
                    ),
                ).setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(open)
                .addAction(0, getString(R.string.shade_notification_stop), stop)
                // Not dismissible: this notification is the way back out of a very dark screen.
                .setOngoing(true)
                .setSilent(true)
        // Phase 1 ships this explanation as `dim_backlight_hint`, in the app, which at maximum dim
        // sits under the shade at ~1.6 nits: the person who most needs it is the one who cannot read
        // it. Here it is on a surface drawn above the shade. Conditional rather than always present
        // because it would otherwise tell a user who turned the backlight toggle off that their
        // brightness slider is paused, which is false.
        if (pause != null) {
            val text = if (pause.resumes) R.string.shade_notification_resumes_at else R.string.dim_auto_off_at
            builder.setContentText(getString(text, formatTime(pause.atMillis)))
        } else if (backlightOverrideLive()) {
            builder.setContentText(getString(R.string.shade_notification_text_backlight))
        }
        // **The deadline goes in the subtext, and the surface matters more than the string does.**
        // The one moment a deadline moves *outward* is the schedule taking over an episode the hand
        // started (`docs/phase-4.md` §3) — and at 22:00 the user is in a reading app, not on the
        // screen that shows *"Turns off at 23:40"*. This notification is the surface that is on
        // their screen. `setSubText` rather than `setContentText` so it does not displace Phase 1's
        // backlight sentence, which would otherwise be lost on nearly every episode that has a
        // deadline; absent rather than empty when there is none.
        // Left off while a pause runs out on the deadline, because the content text already names it.
        if (pause == null || pause.resumes) {
            currentDeadline?.let { builder.setSubText(getString(R.string.shade_notification_until, formatTime(it))) }
        }
        return builder.build()
    }
}

/**
 * Start the shade.
 *
 * `startForegroundService` unconditionally: `minSdk` is 26, so the pre-Oreo `startService` path is
 * unreachable here. It carries a hard contract — the service **must** call `startForeground` within
 * a few seconds or Android kills the process with an ANR — which `onStartCommand` does on its first
 * statement for exactly that reason.
 */
fun Context.startShade(leaveBubble: Boolean = false) {
    startForegroundService(
        Intent(this, ShadeService::class.java).putExtra(EXTRA_LEAVE_BUBBLE, leaveBubble),
    )
}

/** Stop the shade and take the window down. */
fun Context.stopShade() {
    stopService(Intent(this, ShadeService::class.java))
}

/**
 * Summon the panel over a shade that is already up.
 *
 * `startForegroundService` rather than `startService` for the same contract [startShade] carries:
 * the target may not be running — a ROM kill between the caller reading the notification and tapping
 * it is exactly the case — and a background `startService` on a dead service throws. The service
 * calls `startForeground` on its first statement either way.
 */
fun Context.showShadePanel() {
    startForegroundService(Intent(this, ShadeService::class.java).setAction(ACTION_SHOW_PANEL))
}
