package app.gloam.work

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.gloam.data.AppPreferences
import app.gloam.shade.Coordinates
import app.gloam.shade.ScheduleKind
import app.gloam.shade.windowStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * How the location ask ended. The same three outcomes as the notification ask, for the same reason:
 * the third one needs a different button.
 */
enum class LocationAskOutcome {
    Granted,

    /** Refused, and askable again. */
    Denied,

    /** Android will not show the dialog again, so only the app's settings page can change the answer. */
    PermanentlyDenied,
}

/**
 * **The app's one ask for `ACCESS_COARSE_LOCATION`** (ADR-0013 §6), and never for fine or
 * background location.
 *
 * It is the notification ask's shape (`rememberNotificationPermissionAsk`), read the same way: the
 * request is fired and the result tells a refusal Android will ask about again from one it will not.
 * `shouldShowRequestPermissionRationale` is exact after a denial and ambiguous before one, so no
 * stored key is needed.
 *
 * Platform note: asking for coarse location alone is what makes Android 12 and later show only the
 * *Approximate* choice. Declaring fine location as well would put a *Precise* toggle in the same
 * dialog, which is exactly the thing ADR-0013 says Gloam never asks for.
 */
@Composable
fun rememberLocationAsk(onOutcome: (LocationAskOutcome) -> Unit): () -> Unit {
    val activity = LocalActivity.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onOutcome(
                when {
                    granted -> LocationAskOutcome.Granted
                    activity != null &&
                        ActivityCompat.shouldShowRequestPermissionRationale(
                            activity,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ) -> LocationAskOutcome.Denied
                    else -> LocationAskOutcome.PermanentlyDenied
                },
            )
        }
    return { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
}

/**
 * Whether the user has lent Gloam their approximate location. A live read, re-taken rather than
 * remembered, because the answer can change in the app's settings page and an *Only this time*
 * grant lapses on its own.
 */
fun Context.locationGranted(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The app's details page in Android's settings, where a location refusal can be undone. */
fun Context.openAppDetailsSettings() {
    try {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        // A skin without the page leaves nothing to open. The row stays where it is.
    }
}

/**
 * Read the phone's position and store it as the lent location, if the rules allow it (ADR-0013 §7).
 *
 * Called when Gloam comes to the front and right after a grant, and **never from a receiver**. Each
 * early return is one of the ADR's rules:
 *
 * - **No grant, no read.** An estimate is still a working schedule.
 * - **Only for a sunset-to-sunrise schedule.** A fixed-times schedule has no use for a position, and
 *   reading one anyway would be collecting it for nothing.
 * - **Never while a window is open, checked before and after the read.** Replacing the location moves
 *   that night's sunset, and with it the `windowStart` the reconcile compares its marker against. A
 *   shade the user stopped at 23:00 would then come back. The read can take seconds, and a window can
 *   open during them, which is why the check runs twice.
 * - **A failed read keeps what is stored.** Location switched off, or no position found in time.
 *
 * One consequence worth knowing: an *Only this time* grant given while a window is open is spent
 * without a read, and the schedule stays on the estimate. The screen keeps offering the ask.
 */
suspend fun Context.refreshLentLocation(preferences: AppPreferences) {
    if (!locationGranted()) return
    if (preferences.schedule.first().kind != ScheduleKind.SunsetToSunrise) return
    val zone = ZoneId.systemDefault()
    if (windowOpen(preferences, zone)) return

    val read = readApproximateLocation() ?: return
    if (windowOpen(preferences, zone)) return
    preferences.lendLocation(read, zone.id)
    Log.i(TAG, "location lent in ${zone.id}")
}

private suspend fun windowOpen(
    preferences: AppPreferences,
    zone: ZoneId,
): Boolean = preferences.schedule.first().windowStart(System.currentTimeMillis(), zone) != null

/**
 * One fresh position from `LocationManager`, or `null`. **No Play services**: the fused provider here
 * is the platform's own (API 31+), and the network provider is the fallback. GPS is never asked for.
 * With a coarse grant it would give a coarsened answer anyway, after a longer wait and more battery.
 *
 * Kotlin note: `suspendCancellableCoroutine` is `new Promise((resolve) => …)` around a callback API,
 * with one addition JS promises lack: if the coroutine is cancelled (the screen goes away), the
 * `invokeOnCancellation` block runs, and here it cancels the platform's request too. `resume` is
 * `resolve`, and calling it after cancellation is guarded because the platform may still call back.
 */
private suspend fun Context.readApproximateLocation(): Coordinates? {
    val manager = getSystemService(LocationManager::class.java) ?: return null
    if (!LocationManagerCompat.isLocationEnabled(manager)) return null
    // The fused provider is public from Android 12; below it, the network provider is the coarse one.
    val candidates =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER)
        }
    val provider =
        candidates.firstOrNull { LocationManagerCompat.hasProvider(manager, it) && manager.isProviderEnabled(it) }
            ?: return null

    return try {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            val executor = ContextCompat.getMainExecutor(this)
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
                if (continuation.isActive) {
                    continuation.resume(location?.let { Coordinates(it.latitude, it.longitude) })
                }
            }
        }
    } catch (e: SecurityException) {
        // The grant was revoked between the check above and the request.
        null
    }
}

private const val TAG = "GloamSchedule"
