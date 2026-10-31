package app.gloam.shade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.gloam.MainApplication
import app.gloam.work.armScheduleAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * `adb logcat -s GloamBoot:*` is how the restore readings see which branch below was taken.
 *
 * **Not `BootReceiver`**, which is what this file was called first: the platform has a
 * `com.android.server.BootReceiver` of its own, and at every restart it writes a stack trace and
 * forty lines of `fs_stat` under exactly that tag. One line of ours arrived buried in it. The debug
 * build's backlight sweep already logs as `GloamSweep`; this is the same idea.
 */
private const val TAG = "GloamBoot"

/**
 * Puts the shade back after the phone restarts, and after Gloam itself is updated.
 *
 * ## Why this is allowed at all
 *
 * Starting a foreground service from the background is normally refused, and three broadcasts are
 * documented exemptions from that: `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` and
 * `MY_PACKAGE_REPLACED`. Two of the three are the ones registered here. Android 15 narrowed the
 * first with a **blocklist of foreground-service types** — `camera`, `dataSync`, `mediaPlayback`,
 * `mediaProjection`, `phoneCall`, and `microphone` from 14 — and `specialUse`, which is the shade's
 * type, is not on it. `RECEIVE_BOOT_COMPLETED` is a *normal* permission: no dialog, no runtime ask,
 * granted at install. All of that is platform, not choice.
 *
 * **`MY_PACKAGE_REPLACED` is not a nicety here.** An app update kills the service, and this build is
 * about to be updated repeatedly on twelve testers' phones for fourteen days. Without it, the first
 * thing each of them would learn is that Gloam turns itself off.
 *
 * **`LOCKED_BOOT_COMPLETED` is not an option, and the reason is storage rather than taste.** Gloam is
 * not direct-boot aware, so its DataStore lives in credential-encrypted storage and cannot be read
 * before the first unlock — and the only thing this receiver exists to do is read it.
 * `BOOT_COMPLETED` arrives *after* that unlock, which is also why every restart ends on one bright,
 * undimmed lock screen before the shade comes back: the keyguard is above `TYPE_APPLICATION_OVERLAY`
 * and, measured in Phase 1's R5, releases the brightness override outright.
 *
 * ## What it refuses to do, which is most of it
 *
 * Three of the four branches below are refusals:
 *
 * - **A deadline that passed while the phone was off means the shade was never coming back.** This
 *   is the one place auto-off and reboot restore meet: without the check, a phone switched off at
 *   23:00 with a two-hour deadline puts a shade back at 09:00 the next morning that the user had
 *   already asked to end. `endShadeAt()` clears both keys together, so the next launch
 *   reads a clean state rather than a stale one.
 * - **An intent this install did not write is not this phone's intent.** Auto Backup restores the
 *   preferences file onto a new device with `shade_running` in it, so the shade can be *on* on a
 *   phone the user has never dimmed. Measured in Phase 5's R4: the restore itself raises nothing,
 *   and neither does the first launch, but the next reboot or Play update reaches this receiver and
 *   used to put the shade up — at the old phone's dim level, and with no notification, because
 *   nothing had asked for the permission yet. The deadline check above does not catch it: with
 *   auto-off at *Never* there is no deadline. So the intent carries `shade_began_at`, and
 *   [startedThisInstall] compares it with `firstInstallTime`, which the platform never backs up.
 * - **No overlay permission means no window.** The service guards `addShadeWindow` on
 *   `canDrawShade()`, so starting anyway would not crash — it would post a *Screen dimmed*
 *   notification over an undimmed screen, and quiet and wrong is worse than not starting.
 * - **`shadeRunning` is left alone on that refusal.** It is the user's intent and they did not
 *   change it; an app that clears it for them is an app where a revoked permission silently becomes
 *   a lost setting. The next launch shows the explainer, which is where that conversation belongs.
 *
 * **The notification is deliberately not a fourth refusal.** A user who denied `POST_NOTIFICATIONS`
 * and started the shade anyway — which the dim screen allows on purpose — gets it restored here with
 * no Stop action. Refusing to dim somebody's screen because they refused a notification is the app
 * deciding it knows better, and a reboot does not change who decided. What it does change is that
 * nobody is watching, which is why the cost is written down: the hatches left are the power menu and
 * the app's own controls at 0.33 nits (Phase 1's R2), and auto-off still bounds the whole thing.
 *
 * ## Kotlin/Android notes
 *
 * A `BroadcastReceiver` is not an object with a lifetime. The system instantiates it, calls
 * [onReceive] **on the main thread**, and considers the process disposable the moment that method
 * returns — so a coroutine launched and left to run would be racing a process kill. [goAsync] is the
 * platform's answer: it hands back a token that keeps the receiver alive until `finish()` is called,
 * for roughly ten seconds. A DataStore read is milliseconds, so this is comfortable rather than
 * tight, and `finally` is what guarantees the token is always spent.
 *
 * There is no JS analogue for either half — no `await` in `onReceive`, because the platform is not
 * waiting on a promise, and no scope to inherit, which is why the coroutine below is launched into a
 * scope created on the spot. It is not leaked: nothing is collected, the launch completes, and the
 * scope is garbage once it does.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // The receiver is exported — the system is what delivers these — so it takes the action it
        // was handed rather than assuming the filter is the only way in.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // `applicationContext` rather than the `Context` handed in: a manifest receiver is given a
        // restricted one that refuses `bindService` and `registerReceiver`. Nothing here needs those
        // today, and reaching past it means nothing here ever quietly starts to.
        val app = context.applicationContext as? MainApplication ?: return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.restoreShade(action)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The decision, with the read that informs it — kept out of [BootReceiver.onReceive] so that what it
 * does is one `when` rather than a callback with a body.
 *
 * @param action logged rather than branched on: a restart and an app update take exactly the same
 *   four branches, and the only thing that differs is which one a reading is looking at.
 */
private suspend fun MainApplication.restoreShade(action: String) {
    // One read, both values. A second read could see a different transaction.
    val intent = preferences.shadeIntentNow()
    when {
        !intent.running -> Log.i(TAG, "$action: the shade was not running, nothing to put back")

        !startedThisInstall(intent.beganAtMillis, firstInstallMillis()) -> {
            // `Reaped` for the same reason the passed deadline uses it: nobody decided anything
            // here, so a window open right now has not been spent by this.
            preferences.endShadeAt(ShadeEnd.Reaped)
            Log.i(TAG, "$action: the running intent came from another install; cleared")
        }

        isDue(System.currentTimeMillis(), intent.offAtMillis) -> {
            // `Reaped`, which is the one ending that leaves the schedule's marker alone: nobody
            // decided anything here, so a window open right now has not been spent by this.
            preferences.endShadeAt(ShadeEnd.Reaped)
            Log.i(TAG, "$action: the deadline (${intent.offAtMillis}) passed while the phone was off")
        }

        !canDrawShade() ->
            Log.i(TAG, "$action: no overlay permission, leaving the stored intent alone")

        else -> {
            startShade()
            Log.i(TAG, "$action: shade restored, deadline=${intent.offAtMillis}")
        }
    }

    // An `AlarmManager` alarm does not survive a reboot or an app update, so the schedule's next hop
    // is re-armed here — and, like the schedule's own receiver, **on every branch above including
    // the refusals**: a phone that rebooted without the overlay permission still has a schedule, and
    // arming only on the success path would switch it off for good, silently.
    //
    // The night in progress is a different question and this is deliberately not where it is
    // answered. `armScheduleAlarm` arms `nextOn`, which is strictly future, so a reboot at 22:30
    // inside a window would leave tonight skipped. `MainApplication`'s collector reconciles that,
    // and it runs on this path too — the process that is running this receiver started to do it.
    armScheduleAlarm(preferences.schedule.first())
}

/**
 * Whether a stored *running* intent was written by **this** install of the app.
 *
 * `firstInstallTime` is the platform's own record of when this package first appeared on this
 * device. Auto Backup never carries it, and an app update never changes it, which is what makes it
 * the right side of this comparison: a restored intent was written on the old phone, necessarily
 * before this install existed, while every intent written here is later than it.
 *
 * `null` is refused. It means either a restore from a build older than `shade_began_at` or an
 * install of ours that predates the key, and the safe answer is the same for both: do not dim a
 * screen on the strength of a value nobody can date. The cost is one shade not coming back, once,
 * for whoever is updating with the shade up.
 */
internal fun startedThisInstall(
    beganAtMillis: Long?,
    firstInstallMillis: Long,
): Boolean = beganAtMillis != null && beganAtMillis >= firstInstallMillis

/**
 * When this package was first installed on this device, from the platform rather than from us.
 *
 * `PackageInfoFlags.of(0)` rather than the `Int` overload, which is deprecated from API 33 — the
 * minimum this app ships to (ADR-0008), so there is no older branch to keep.
 *
 * A failure here would mean the package manager cannot find the app that is asking, which is not a
 * state this code can be running in. It answers [Long.MAX_VALUE] anyway, because that is the value
 * that refuses every intent rather than honouring one it cannot date.
 */
private fun Context.firstInstallMillis(): Long =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).firstInstallTime
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).firstInstallTime
        }
    }.getOrDefault(Long.MAX_VALUE)
