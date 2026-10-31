package app.gloam.shade

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shade's window, read off a real window manager.
 *
 * **The first test in `androidTest/`, and it exists because the matrix that runs it had nothing to
 * run.** Three instrumented CI legs were passing by having zero tests — a green tick with nothing
 * behind it — and this is the one property worth putting there: a JVM test can assert what
 * [SHADE_WINDOW_FLAGS] *contains*, but only a device can say whether the window that reached the
 * window manager was built from it.
 *
 * **What it asserts**: the shade arrives as `TYPE_APPLICATION_OVERLAY` carrying `NOT_TOUCHABLE` and
 * `NOT_FOCUSABLE`. Lose either and the phone appears frozen with the escape hatch underneath the
 * thing freezing it, which is the worst outcome this app has.
 *
 * **The cost, stated honestly**: `dumpsys window windows` is version-shaped text and this is the
 * layer that will need attention when a platform changes its output. That is exactly why the flag
 * set is *also* asserted on the JVM, where nothing can drift.
 *
 * **Never read the appop back to check the grant.** `appops get` reports last-use rather than the
 * granted mode on the ROM in this loop — a trap already recorded once in `CLAUDE.md` — so the grant
 * is verified with [Settings.canDrawOverlays], which asks the question we actually mean.
 */
@RunWith(AndroidJUnit4::class)
class ShadeWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val packageName: String = context.packageName
    private val shadeLayout = OverlayDump.shadeLayout(context.getSystemService(WindowManager::class.java))

    /**
     * Whether this device already had the overlay grant before the test touched it.
     *
     * The tear-down uses it to leave the device as it was found. On CI that is a formality; on the
     * physical phone — where `CLAUDE.md`'s split-APK workaround runs this same instrumentation — it
     * is the difference between a test run and a test run that silently revokes the permission the
     * app cannot start without.
     */
    private var overlayWasGranted = false

    @Before
    fun grantWhatTheShadeNeeds() {
        overlayWasGranted = Settings.canDrawOverlays(context)
        if (!overlayWasGranted) {
            shell("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        }
        // Not for the window — a foreground service starts and adds its view without it — but the
        // service posts an ongoing notification, and a denied POST_NOTIFICATIONS leaves the run
        // logging a failed post on every leg. `pm grant` through the shell we have open already,
        // rather than a GrantPermissionRule, because that rule lives in `androidx.test:rules` and
        // this is the whole of what the dependency would buy.
        //
        // **This one is not put back, and that is deliberate.** `am instrument` runs the test *in
        // the target app's process*, and `pm revoke` restarts that process — the tear-down would
        // kill the run reading its own result. The appop above has no such effect, which is why
        // only it is restored.
        shell("pm grant $packageName android.permission.POST_NOTIFICATIONS")

        assertTrue(
            "The overlay grant did not take, so nothing below could be read. Verified with " +
                "canDrawOverlays() rather than by reading the appop back, which reports last-use.",
            Settings.canDrawOverlays(context),
        )
    }

    @After
    fun putTheDeviceBack() {
        context.stopShade()
        awaitNoOverlayWindows()
        if (!overlayWasGranted) {
            shell("appops set $packageName SYSTEM_ALERT_WINDOW default")
        }
    }

    /**
     * Block until no `TYPE_APPLICATION_OVERLAY` window of ours is left in the dump.
     *
     * `stopShade()` is not synchronous with the screen: it stops the service, and the window
     * manager removes the window some time afterwards. Returning from tear-down before that has
     * happened leaves the *next* test polling a dump that still contains this one's windows — which
     * is a test failing for the previous test's reasons, the hardest kind to read.
     *
     * Best-effort rather than asserted: a tear-down that fails the run for a window that outlived
     * its timeout would replace a rare flake with a rarer, more confusing one. The wait is the
     * point; the guarantee belongs to the assertions in the tests themselves.
     */
    private fun awaitNoOverlayWindows() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val ours =
                shell("dumpsys window windows")
                    .split("Window #")
                    .any { OverlayDump.isOurOverlay(it, packageName) }
            if (!ours) return
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun theShadeWindowCannotTrapTheUser() {
        context.startShade()

        val window =
            awaitShadeWindow()
                ?: throw AssertionError(
                    "No full-screen ($shadeLayout) APPLICATION_OVERLAY window for $packageName " +
                        "appeared within ${TIMEOUT_MS}ms of startShade(). The service never added " +
                        "its view, the shade stopped asking for MATCH_PARENT, or dumpsys no longer " +
                        "prints windows the way this test reads them.",
                )

        assertTrue(
            "The shade's window is missing NOT_TOUCHABLE — every touch on this screen would be " +
                "swallowed by a window the user cannot see. Window was:\n$window",
            OverlayDump.hasFlag(window, "NOT_TOUCHABLE", WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        )
        assertTrue(
            "The shade's window is missing NOT_FOCUSABLE — key events would go to the shade " +
                "instead of the app underneath it. Window was:\n$window",
            OverlayDump.hasFlag(window, "NOT_FOCUSABLE", WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        )
    }

    /**
     * **[shadeOnScreen] follows the window, both ways.** It is what the Start/Stop button reads to
     * avoid claiming a shade HyperOS killed, so a flag that stuck at `true` would bring that lie back
     * and one that stuck at `false` would offer *Start* over a dimmed screen. Readable from here at
     * all because `am instrument` runs this test inside the app's own process — the same memory the
     * service writes it in.
     */
    @Test
    fun theScreenIsToldWhetherTheShadeIsUp() {
        context.startShade()
        awaitShadeWindow() ?: throw AssertionError("No shade window appeared.")
        assertTrue("The window is up and shadeOnScreen does not say so.", awaitOnScreen(true))

        context.stopShade()
        awaitNoOverlayWindows()
        assertTrue("The window is gone and shadeOnScreen still says it is up.", awaitOnScreen(false))
    }

    /** Polled for the same reason [awaitShadeWindow] is: `dumpsys` and the service's main thread race. */
    private fun awaitOnScreen(expected: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (shadeOnScreen.value != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        return shadeOnScreen.value == expected
    }

    /**
     * **The whole display, navigation bar included.** `fillxfill` says what the shade *asked* for,
     * and the window manager is free to answer with less: until `fitInsetsTypes = 0` it laid the
     * shade out clear of the navigation bar, and the frame ended 48 px short of the display's bottom
     * edge on the phone — a bright strip under every app that shows the bar. The requested size never
     * changed, so only the frame it was given can catch that.
     *
     * Compared against [displayBounds], which is the display in its current rotation with no inset
     * taken off — the frame the shade should have.
     *
     * **Polled, and a single read did flake on the phone.** A window is in the dump from the moment it
     * is added, but its frame is assigned at its first relayout, which a cold process reaches later:
     * the first run after an install failed here and the rerun passed. So the test waits for a frame
     * that covers the display and fails only when none arrives within the timeout — which a
     * regression to the inset frame never does.
     */
    @Test
    fun theShadeCoversTheWholeDisplay() {
        context.startShade()
        val display = context.getSystemService(WindowManager::class.java).displayBounds()

        var window: String? = null
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            window = shadeWindowBlock()
            if (window != null && frameOf(window)?.contains(display) == true) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(
            "The shade's frame ${window?.let(::frameOf)} never covered the display $display within " +
                "${TIMEOUT_MS}ms — a strip of the screen is left undimmed. Window was:\n$window",
        )
    }

    /**
     * **The same, with the navigation bar on the left.** Below Android 11 that is the one rotation
     * where the platform moves an overlay's origin off the display's, and the strip under the bar
     * stayed bright while portrait passed. Settings is put in front because it follows the rotation
     * and this app's own screen does not; a device that will not turn skips rather than passes.
     */
    @Test
    fun theShadeCoversTheWholeDisplayInSeascape() {
        val autoRotate = shell("settings get system accelerometer_rotation").trim()
        val userRotation = shell("settings get system user_rotation").trim()
        try {
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 3")
            shell("am start -W -a android.settings.SETTINGS")
            val manager = context.getSystemService(WindowManager::class.java)
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (manager.displayBounds().let { it.width() < it.height() } &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(POLL_MS)
            }
            assumeTrue(
                "The display never turned to seascape.",
                manager.displayBounds().let { it.width() > it.height() },
            )
            theShadeCoversTheWholeDisplay()
        } finally {
            shell("input keyevent KEYCODE_HOME")
            restoreSetting("user_rotation", userRotation)
            restoreSetting("accelerometer_rotation", autoRotate)
        }
    }

    private fun restoreSetting(
        key: String,
        value: String,
    ) {
        shell(if (value == "null") "settings delete system $key" else "settings put system $key $value")
    }

    private fun frameOf(window: String): Rect? =
        OverlayDump.FRAME.find(window)?.destructured?.let { (left, top, right, bottom) ->
            Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        }

    /**
     * Poll `dumpsys` until the shade's window shows up, and hand back the block describing it.
     *
     * Polling rather than one sleep: adding the window is asynchronous — `startForegroundService`
     * hands off to the service's `onCreate` — and the wait it needs is a few hundred milliseconds on
     * a phone and considerably more on a cold emulator leg. A fixed sleep long enough for the
     * emulator would be paid on every run; a short one would be flaky on exactly the machine CI
     * uses.
     */
    private fun awaitShadeWindow(): String? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadeWindowBlock()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return shadeWindowBlock()
    }

    /**
     * The `dumpsys window windows` block describing the **shade**, or null.
     *
     * The output is a sequence of `Window #N Window{…}` blocks, so splitting on that header is the
     * whole of the parsing. Ours is identified by three things together — the package name,
     * `ty=APPLICATION_OVERLAY`, and the shade's own full-screen layout. The first two are because
     * the app's activities have windows in this dump too and asserting flags against one of those
     * would pass while proving nothing.
     *
     * **The third is not belt-and-braces, it is the whole correctness of this test now.** Since
     * Phase 3b the panel is *also* `TYPE_APPLICATION_OVERLAY` from this same uid, and it
     * deliberately does not carry `NOT_TOUCHABLE` — that is ADR-0011, not a defect. So "the first
     * overlay window we own", which is what this used to take, can be the panel, and the assertion
     * below then reports the panel's missing flag as the shade's. That failure has actually
     * happened on a CI leg: `PanelWindowTest` sorts first, its teardown does not wait for the
     * window manager to finish removing what it put up, and this test matched the outgoing panel.
     *
     * [shadeLayout] — `MATCH_PARENT` x `MATCH_PARENT`, or below Android 11 a square past the
     * display ([fillDisplay]) — is a size only the shade asks for — the same
     * discrimination `PanelWindowTest.OverlayWindow` already makes, and the reason a
     * `MATCH_PARENT` in the panel's `LayoutParams` is a bug rather than a simplification.
     */
    private fun shadeWindowBlock(): String? =
        shell("dumpsys window windows")
            .split("Window #")
            .firstOrNull { OverlayDump.isOurOverlay(it, packageName) && shadeLayout in it }

    /**
     * Run a shell command as the shell user and return everything it printed.
     *
     * Android note with no JS analogue: an instrumented test runs in its own process with the app's
     * permissions, which are not enough to grant an appop or read `dumpsys`. `UiAutomation` is the
     * hatch — commands issued through it run as uid 2000 (`shell`), the same uid `adb` gives you —
     * and it hands back a pipe rather than a string, which is why this reads a file descriptor.
     */
    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 250L
    }
}
