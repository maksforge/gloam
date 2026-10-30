package app.gloam

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.gloam.data.AppPreferences
import app.gloam.data.ThemeMode
import app.gloam.shade.Schedule
import app.gloam.shade.ShadeStart
import app.gloam.shade.beginShadeAt
import app.gloam.shade.canDrawShade
import app.gloam.shade.startShade
import app.gloam.shade.tightenToWindow
import app.gloam.shade.windowStart
import app.gloam.theme.applyThemeMode
import app.gloam.work.armScheduleAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

/**
 * The schedule's own tag, shared with `shade/ScheduleReceiver.kt` and `work/ScheduleAlarm.kt` so
 * that `adb logcat -s GloamSchedule:*` shows the arm, the fire and the reconcile as one sequence.
 */
private const val TAG = "GloamSchedule"

/**
 * Holds the one [AppContainer] for the process.
 *
 * **There is no schema wipe guard here any more, and that is a deliberate consequence of having no
 * database.** The template's guard existed because an app that keeps a user's records cannot open a
 * file written by a schema it does not understand without risking them. Gloam keeps settings — a dim
 * level and a warmth — in DataStore, where a key the running build does not recognise is simply not
 * read and a key it expects but does not find falls back to the default declared beside it. There is
 * nothing to migrate and nothing to lose, so there is nothing to stand in front of.
 *
 * If a future feature adds a table, the guard comes back with it: `scripts/schema-gate.py`, the
 * house rules and ADR-0001 are all still in the repository for exactly that reason.
 *
 * Kotlin note: `by lazy` computes on first read and caches — the closest JS analogue is a memoised
 * getter.
 */
class MainApplication : Application() {
    /**
     * The scope for work that outlives any screen. `SupervisorJob` so one failed child does not
     * cancel its siblings — the closest JS analogue is `Promise.allSettled` semantics rather than
     * `Promise.all`.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The preference store. Read before the first window exists, because the theme depends on it.
     */
    lateinit var preferences: AppPreferences
        private set

    /**
     * The theme mode as it was on disk at process start, so the very first composition paints in the
     * right palette rather than flashing light and repainting.
     */
    var startupThemeMode: ThemeMode = ThemeMode.DARK
        private set

    /**
     * Whether a launcher tap opens the compact controls instead of this app's own screen
     * (`docs/phase-3.md` §3). `ControlsActivity.onCreate` reads it before it composes anything —
     * the icon lands *there* now (shape iv), so what the preference decides is whether that window
     * stays or bounces on to `MainActivity`.
     *
     * **Seeded by the blocking startup read and then kept current by a collector**, which is one
     * more moving part than [startupThemeMode] needs and the reason is that nothing corrects this
     * one afterwards. The theme mode is an initial value for a `Flow` the first composition
     * collects, so a stale seed is wrong for a frame; this value is read once, before there is a
     * composition, and then acted on irrevocably by finishing the Activity. A user who turns the
     * preference on and leaves the app has killed no process — so without the collector the next tap
     * on the icon would be decided by what was on disk when the process started, which is the wrong
     * answer until something happens to kill it.
     *
     * Kotlin note: `@Volatile` because the collector writes from a background dispatcher and
     * `ControlsActivity` reads on the main thread. It makes the write visible to other threads; it is
     * not a lock, and none is wanted here — the value is a single boolean and a reader that catches
     * the previous one is a reader that ran before the user's tap.
     */
    @Volatile
    var launcherCompact: Boolean = true
        private set

    val container: AppContainer by lazy { AppContainer(this, preferences, applicationScope) }

    override fun onCreate() {
        super.onCreate()

        // The application's resources follow the per-app language on API 33+, which is all of ours.
        preferences = AppPreferences(preferencesStore, lentLocationStore, presetLabel = { getString(it.label) })

        // A blocking read, deliberately and exactly once. Everything else about DataStore is a Flow,
        // but this value has to be known before the first window is created — a Flow collected in a
        // composition arrives a frame later, which is a visible light flash for a user who chose
        // dark. One small file read at process start is the cheaper of the two costs.
        // Both reads in one `runBlocking`, and the second is not a second disk read: DataStore
        // serves `store.data` from its in-memory cache once anything has collected it, which the
        // first line just did.
        runBlocking {
            startupThemeMode = preferences.themeModeNow()
            launcherCompact = preferences.launcherCompactNow()
        }
        applyThemeMode(startupThemeMode)

        // And from here on the preference keeps itself current — see [launcherCompact]. A `Flow`
        // collected in a scope that never ends is the closest thing here to a subscription that is
        // never unsubscribed; it is deliberate, because the thing being kept alive is one boolean
        // for the life of the process.
        preferences.launcherCompact
            .onEach { launcherCompact = it }
            .launchIn(applicationScope)

        // **The schedule's one call site, and it covers three of the five ways an alarm is lost**
        // (`docs/phase-4.md` section 6). An `AlarmManager` alarm is not durable: a reboot and an app
        // update are `BootReceiver`'s, firing is the receiver's own re-arm, and the two left — the
        // user editing the schedule, and a force-stop — are both here. The second costs no code at
        // all, because a collector's first emission arrives on every process start, and a force-stop
        // is followed by a process start or by nothing that matters.
        //
        // **In `MainApplication` rather than in a ViewModel, and that is not the house rule being
        // bent.** `CLAUDE.md` says a `ViewModel` never holds a `Context` and that starting the
        // service is therefore the screen's job — which would make arming the screen's job too.
        // It works and it is wrong: the screen that wrote the preference can be gone before the
        // write lands, and an alarm whose existence depends on somebody looking at a screen is not
        // an alarm. The thing whose lifetime matches an alarm's is the process.
        //
        // Kotlin note: `distinctUntilChanged` on a data class compares by `equals`, so the three
        // schedule keys re-emit only when the window itself changed — without it, every unrelated
        // DataStore write (a dim level, a marker) would re-arm and reconcile. The nearest JS
        // analogue is a selector that memoises on deep equality rather than on reference.
        preferences.schedule
            .distinctUntilChanged()
            .onEach { schedule ->
                armScheduleAlarm(schedule) // the alarm
                preferences.tightenToWindow(schedule) // the deadline, forward only
                reconcileWindow(schedule) // the night, if one is open and unspent
            }.launchIn(applicationScope)

        // On the app coming to the foreground, not on process start: an alarm or a boot broadcast
        // starts the process too, and binding Play for those would wake it for nobody. The scope is
        // the application's so a consume already under way survives the user leaving again.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch { container.tipJar.settlePending() }
                }
            },
        )
    }
}

/**
 * Raise the shade if a window is open, has not been acted on, and nothing is up.
 *
 * **This is what stops a lost alarm from costing the whole night**, and it is why checkpoint A's
 * verdict could only ever have vetoed one file rather than the feature: every recovery site arms
 * `nextOn`, which is *strictly future*, so recovering the alarm after a force-stop at 21:50 arms it
 * for tomorrow and tonight is skipped in silence while the screen still says the schedule is on.
 * Losing the alarm and losing the night are different failures, and on HyperOS the second is the
 * ordinary one — *Clear all* in recents kills Gloam whenever another app is in front, a running
 * shade included, and without autostart nothing restarts it. Swiping the card alone does not.
 *
 * **The marker is the whole of why this is safe.** The naive version — *window open, shade down,
 * raise it* — breaks the Stop button: the user stops at 23:00 inside a 22:00-to-07:00 window and the
 * next process start puts the shade straight back. Comparing against the on-instant already acted on
 * tells *never opened this window* from *opened, and ended by the person*, so an episode that ends
 * inside a window spends that night and Stop stays Stop until morning. `ShadeEnd` is the other half
 * of that: `Reaped` is the one ending that leaves the marker alone.
 *
 * **The overlay refusal is [ScheduleReceiver]'s third refusal, here for the same reason** and not in
 * section 6's sketch of this function: starting the service without the permission posts a *Screen
 * dimmed* notification over an undimmed screen. It leaves the marker alone too, so a night refused
 * for a missing permission is still reconcilable the moment the user grants it.
 */
private suspend fun MainApplication.reconcileWindow(schedule: Schedule) {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()

    // Null is *outside the window*, and inside it this is the night's identity — constant across
    // every minute of one window, which is what makes the comparison below stable.
    val windowStart = schedule.windowStart(now, zone) ?: return

    if (preferences.scheduleHonouredAt.first() == windowStart) return
    if (preferences.shadeIntentNow().running) return
    if (!canDrawShade()) {
        Log.i(TAG, "window open since $windowStart, but no overlay permission")
        return
    }

    preferences.beginShadeAt(ShadeStart.BySchedule, now, zone)
    preferences.setScheduleHonouredAt(windowStart)
    startShade()
    Log.i(TAG, "reconciled: window open since $windowStart and unspent, shade up")
}
