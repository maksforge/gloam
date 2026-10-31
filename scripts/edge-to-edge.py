#!/usr/bin/env python3
"""Drive the app through the edge-to-edge matrix and check it against the system insets.

Play Console raises edge-to-edge against every app targeting SDK 35+, and the notice is generic
advice rather than a detected defect. `MainActivity` already calls `enableEdgeToEdge()` and the
shell's `Scaffold` owns the insets, so the mechanism is in place; what is owed is *evidence*, and
evidence for every configuration across every screen the app has is not something anyone captures by
hand twice.

So this drives it. For each cell of the matrix it sets the rotation and the navigation mode, walks
the app to each scene, saves a screenshot, and — the part that makes the screenshots reviewable —
asks `uiautomator` where every text, icon and control actually landed, then intersects those
rectangles with the system-bar and display-cutout rectangles `dumpsys` reports for that same
configuration. A control inside the navigation bar's rectangle is the defect the checkpoint is
looking for, found by arithmetic instead of by squinting at three dozen PNGs.

The screenshots are still the deliverable: this narrows which ones a human has to open.

Usage:
    scripts/edge-to-edge.py --out /path/to/dir            # the whole matrix
    scripts/edge-to-edge.py --out DIR --config portrait-threebutton
    scripts/edge-to-edge.py --out DIR --scene dim,settings
    scripts/edge-to-edge.py --out DIR --locale pl         # the same walk, in Polish
    scripts/edge-to-edge.py --out DIR --assert-clean       # exit 1 on a defect, for CI
    scripts/edge-to-edge.py --out DIR --retry-unreached 2  # re-walk a scene the driver missed
    scripts/edge-to-edge.py --restore                     # hand the phone back to auto-rotate

The phone is left in whatever configuration the last cell used; `--restore` puts it back.

**It runs on an emulator as well as on the phone.** Navigation mode is set through whichever
mechanism the device family actually reads — see [set_nav_mode] — and the configurations a device
cannot be put into are dropped by name rather than captured under a label that would be a lie; see
[usable_configs], which is what keeps API 26-28 honest about having no gesture navigation at all.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))
import project  # noqa: E402  — the path insert above has to come first

# **Which of the two installs on the phone this driver drives.** Both are there at once — the debug
# one from `installDebug`, the shipped one from Play — and every `pm clear`, `appops set`,
# `pm grant` and `am start -n` below names one of them. Pointed at the wrong one *nothing fails*:
# the walk photographs a real app that answers every needle, just not the one the run was for.
#
# The debug install stays the default, because it is what the inset matrix has always measured and
# what a developer has to hand. [select_build] is how a run says otherwise.
PACKAGE = project.DEBUG_APPLICATION_ID
ACTIVITY = project.MAIN_ACTIVITY

# The compact controls' own Activity. It is a second launcher entry rather than a screen inside the
# app — empty `taskAffinity`, its own task — and `am start -n` reaches it because the launcher
# `<intent-filter>` makes it exported. That is the same window the ongoing notification opens
# ("The notification always opens the small controls", `settings_launcher_compact_hint`), which is
# what makes it the honest route: the debug section's *Open compact controls* button reaches the
# same place, but only on a build no user has.
CONTROLS_ACTIVITY = f"{PACKAGE}/{project.NAMESPACE}.ControlsActivity"

# **Whether [wipe] is allowed to run at all.** True for the debug install, which is scratch; false
# for the shipped one, which is somebody's actual Gloam — their dim level, warmth, schedule and
# theme, with no `run-as` on a release build to back any of it up first. See [select_build].
WIPEABLE = True


def select_build(build: str) -> None:
    """Point the driver at the debug install (`debug`) or at the shipped one (`release`).

    **`release` is the build a store screenshot has to come from**, and not for tidiness: the
    developer-only Settings section lives in `app/src/debug/`, so on the shipped build
    `DebugSettings()` is `= Unit` and the section is *absent* rather than hidden. A set shot off
    `debug` photographs the backlight sweep and *Summon panel* the moment any scene scrolls
    Settings.

    It also takes the wipe away, which is the dangerous half. `debug` is a scratch install nobody
    mourns; `…gloam` is the app on the phone's home screen, and [run_cell] reseeds — that is,
    `pm clear`s — once per cell. Nine locales would be nine wipes of live configuration. So
    [WIPEABLE] goes false here and [wipe] refuses, rather than leaving it to whoever remembers a
    flag at 2am.
    """
    global PACKAGE, ACTIVITY, CONTROLS_ACTIVITY, WIPEABLE
    if build not in ("debug", "release"):
        raise SystemExit(f"unknown build {build!r}: expected 'debug' or 'release'")
    PACKAGE = project.DEBUG_APPLICATION_ID if build == "debug" else project.APPLICATION_ID
    ACTIVITY = f"{PACKAGE}/{project.NAMESPACE}.MainActivity"
    CONTROLS_ACTIVITY = f"{PACKAGE}/{project.NAMESPACE}.ControlsActivity"
    WIPEABLE = build == "debug"

# The three inset types a screen can be wrongly drawn under. `displayCutout` is listed separately
# from `statusBars` on purpose: in portrait they coincide, and in landscape they do not. That was the
# reason landscape was in this matrix until the app went portrait-only; see [CONFIGS].
INSET_TYPES = ("statusBars", "navigationBars", "displayCutout")


# --------------------------------------------------------------------------------------------
# adb
# --------------------------------------------------------------------------------------------


def adb(*args: str, binary: bool = False) -> str | bytes:
    """Run an adb command. `binary` uses exec-out, which does not mangle \\n into \\r\\n."""
    result = subprocess.run(["adb", *args], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def shell(cmd: str) -> str:
    return adb("shell", cmd)


def shell_ok(cmd: str) -> bool:
    """Run a shell command, reporting whether it worked instead of raising.

    For the handful of calls that address a *device feature* rather than the app: a command missing
    on an older API level must not abort a two-hour matrix, and `adb` reports that case as a
    non-zero exit rather than as an exception worth unwinding for.
    """
    return subprocess.run(["adb", "shell", cmd], capture_output=True).returncode == 0


def settle(seconds: float = 0.6) -> None:
    time.sleep(seconds)


# --------------------------------------------------------------------------------------------
# The device configuration under test
# --------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Config:
    name: str
    rotation: int  # user_rotation: 0 portrait, 1 landscape (90°)
    nav: str  # "gesture" | "threebutton"


CONFIGS = [
    # Portrait + gesture is the cell 1.0's Play screenshots already evidence. It is captured
    # anyway, because the screens 4a-4e added have no prior evidence in any cell.
    Config("portrait-gesture", 0, "gesture"),
    Config("portrait-threebutton", 0, "threebutton"),
    # **No landscape cells since 2026-09-13, and that is a decision, not a gap.** `MainActivity` is
    # portrait-only (the manifest carries why), and every scene in this table starts from it, the
    # compact controls included. A landscape cell would pin `user_rotation` 1 over an Activity that
    # refuses to turn, and capture portrait under a landscape name, which is the defect
    # [repin_rotation] was written to catch. The engine keeps its landscape handling: if the lock is
    # ever lifted, these two lines come back with it.
    #   Config("landscape-gesture", 1, "gesture"),
    #   Config("landscape-threebutton", 1, "threebutton"),
]


_FAMILY: "str | None" = None


def device_family() -> str:
    """"miui" for HyperOS, "aosp" for everything else — an emulator, in practice.

    The seam exists for exactly one line of [apply_config], and it is detected rather than passed in
    because a run that has to be *told* what it is running on is a run somebody eventually tells
    wrong. What makes it worth detecting: the AOSP overlay path is a no-op **that reports success**
    on this phone, so a cell driven the wrong way still captures, still checks and still says
    "clean" — against an inset that never moved.
    """
    global _FAMILY
    if _FAMILY is None:
        _FAMILY = "miui" if shell("getprop ro.miui.ui.version.name").strip() else "aosp"
    return _FAMILY


_API: "int | None" = None


def api_level() -> int:
    global _API
    if _API is None:
        _API = int(shell("getprop ro.build.version.sdk").strip())
    return _API


# Gesture navigation is Android 10. Below it the platform has no gestural overlay to enable and no
# `force_fsg_nav_bar` to set, so a "gesture" cell on API 26-28 is a three-button cell wearing the
# wrong name — see [usable_configs].
GESTURE_MIN_API = 29

# `POST_NOTIFICATIONS` is Android 13. Below it the permission does not exist, `pm grant` fails with
# a non-zero exit, and there is nothing to grant anyway: notifications are on until an owner turns
# them off, so the banner the grant below exists to avoid cannot appear. The first nightly matrix
# run died here on API 26, one minute in.
POST_NOTIFICATIONS_MIN_API = 33

# Per-app languages are Android 13, and so is `cmd locale set-app-locales`. Below it there is no
# per-app locale to hand back, and `shell()` raising on the missing command would kill `--restore`
# before it put rotation and Do Not Disturb back.
APP_LOCALES_MIN_API = 33

# The AOSP navigation-mode overlays, all declaring `android:category="…systemui.navbar"`, which is
# what makes `enable-exclusive --category` a single call rather than an enable plus N disables.
NAVBAR_OVERLAYS = {
    "gesture": "com.android.internal.systemui.navbar.gestural",
    "threebutton": "com.android.internal.systemui.navbar.threebutton",
}

# `cmd overlay list`, read once. Not every image that reports API 29+ actually ships the overlays:
# the first nightly matrix run died one minute in on `enable-exclusive` returning 255 on the API 34
# `aosp_atd` emulator, while API 36 on the *same* target went straight through. An emulator image is
# free to omit a system overlay, and an API level is not a promise that it did not.
_OVERLAY_LIST: "str | None" = None


# `secure navigation_mode`: the platform's own record of which navigation the device is in, and the
# only reading left when the overlays are missing. 1 is the two-button mode of Android 9-10, which
# this matrix has no cell for.
NAV_MODE_SETTING = {"0": "threebutton", "2": "gesture"}


def current_nav_mode() -> "str | None":
    """The navigation mode the device is in, or None if the setting cannot be read or understood.

    None is not "three-button": an unreadable mode means a cell claiming to be in one would be the
    silently-wrong cell [usable_configs] exists to prevent, so it is skipped instead of assumed.
    """
    raw = shell("settings get secure navigation_mode").strip()
    return NAV_MODE_SETTING.get(raw)


def navbar_overlay_present(nav: str) -> bool:
    """Whether the overlay [set_nav_mode] would enable is installed at all.

    Presence, deliberately not *enabled*: `cmd overlay list` prints `[x] <package>` for an enabled
    overlay and `[ ] <package>` for an installed one that is off, and the gestural overlay is off on
    every device the moment before it is turned on. Asking for `[x]` would drop every cell.
    """
    global _OVERLAY_LIST
    if _OVERLAY_LIST is None:
        result = subprocess.run(["adb", "shell", "cmd overlay list"], capture_output=True)
        _OVERLAY_LIST = result.stdout.decode("utf-8", "replace") if result.returncode == 0 else ""
    return NAVBAR_OVERLAYS[nav] in _OVERLAY_LIST


def set_nav_mode(nav: str) -> None:
    """Put the device into `nav`, by whichever mechanism its family actually reads."""
    if device_family() == "miui":
        shell(f"settings put global force_fsg_nav_bar {1 if nav == 'gesture' else 0}")
        return
    if api_level() < GESTURE_MIN_API:
        # Three-button is the only navigation this platform has and it is already in place, so the
        # honest action is none. `enable-exclusive` is not reliably present this far back either,
        # and [usable_configs] has already dropped the gesture cells — so `nav` can only be
        # "threebutton" by the time control reaches here.
        return
    if not navbar_overlay_present(nav):
        # Same shape of no-op, reached a different way: an image with no navigation overlays cannot
        # be moved between modes at all, so [usable_configs] kept this cell only because the device
        # is already in `nav`. Calling `enable-exclusive` here is what took the API 34 leg down —
        # with 255 and `Unable to retrieve overlay information`, for the three-button cells that
        # image can otherwise run perfectly well.
        return
    shell(f"cmd overlay enable-exclusive --category {NAVBAR_OVERLAYS[nav]}")


@dataclass(frozen=True)
class Unreachable:
    """Why a cell cannot run, and whether that is a fact about the device or a failure to read it.

    The distinction decides an exit status. A leg whose every cell names a capability the device
    genuinely lacks has done everything there was to do, and going red for it would train everyone
    to ignore a red leg. A leg that could not *tell* has proven nothing, and green would be a lie.
    """

    reason: str
    readable: bool = True


def unreachable_reason(config: "Config") -> "Unreachable | None":
    """Why this device cannot be put into `config`, or None if it can.

    A reason rather than a boolean because it is printed and written into the report: "skipped" on
    its own, three weeks later in a CI artifact, is indistinguishable from "nobody asked for it".
    """
    # MIUI drives navigation through `force_fsg_nav_bar` and never touches the overlays, which are
    # all present and all disabled on that phone — so their absence would say nothing there.
    if device_family() == "miui":
        return None
    if config.nav == "gesture" and api_level() < GESTURE_MIN_API:
        return Unreachable(f"gesture navigation needs API {GESTURE_MIN_API} (this is {api_level()})")
    if api_level() < GESTURE_MIN_API:
        # Three-button is the only navigation the platform has, and it is already in place.
        return None
    if navbar_overlay_present(config.nav):
        return None
    # No overlay for this mode: the device cannot be moved into it. It may already be there, which
    # is the one case that still yields an honest cell — the mode is right, nothing had to set it.
    current = current_nav_mode()
    if current == config.nav:
        return None
    return Unreachable(
        f"this image ships no {config.nav} navigation overlay, and the device is in "
        f"{current or 'a mode that cannot be read'} — the mode cannot be set",
        readable=current is not None,
    )


def usable_configs(configs: "list[Config]") -> "tuple[list[Config], list[tuple[Config, Unreachable]]]":
    """Split the requested configs into the ones this device can be put into, and the rest.

    **A skipped cell and a silently wrong one are not the same result.** Below API 29 there is no
    gesture navigation on the platform, so pinning one leaves the three-button bar exactly where it
    was — and the cell would capture, check and report "clean" under a name describing a
    configuration the device was never in. That is the failure [_PINNED] exists to prevent, arriving
    by a different road, and it is worse in CI where nobody is watching the screen.

    **Nor is a skipped cell the same as a dead run.** An image that reports API 34 and has no
    gestural overlay used to reach [set_nav_mode] and abort the whole matrix on a 255, taking the
    three-button cells — which that image can perfectly well run — down with it. Unreachable is a
    property of a cell, not of the run.
    """
    kept: list[Config] = []
    dropped: list[tuple[Config, Unreachable]] = []
    for config in configs:
        reason = unreachable_reason(config)
        if reason is None:
            kept.append(config)
        else:
            dropped.append((config, reason))
    return kept, dropped


# The config the device is currently pinned to, so anything that kills the app can put the rotation
# back. `pm clear`, `am start -S` and `pm revoke` all hand the foreground to the portrait-locked
# launcher, and the window manager writes `user_rotation` back to 0 when it does — silently turning a
# landscape cell into a second portrait one that still captures, still checks, and still reports
# "clean". Read by [repin_rotation], which is what all three of them call.
_PINNED: "Config | None" = None


def apply_config(config: Config) -> None:
    """Pin the rotation and the navigation mode, then wait for the window to settle.

    HyperOS does not use the AOSP `com.android.internal.systemui.navbar.*` overlays — they are all
    present and all disabled — so the navigation mode is flipped through MIUI's own
    `force_fsg_nav_bar` global instead. Verified against the navigation bar's reported inset, which
    is the thing being tested and cannot be faked by the setting alone. Everything else — an
    emulator — has the overlays and no such global, and takes the other half of [set_nav_mode].
    """
    global _PINNED
    _PINNED = config
    shell("settings put system accelerometer_rotation 0")
    shell(f"settings put system user_rotation {config.rotation}")
    set_nav_mode(config.nav)
    # SystemUI rebuilds the navigation bar out of process; there is nothing to poll that is ready
    # before the new inset is published, so this is a wait rather than a check.
    settle(3.0)


def repin_rotation() -> None:
    """Put back the rotation the last force-stop cost us.

    **Anything that takes the foreground away from this app can unpin the rotation, and the platform
    does it silently.** `user_rotation` only turns a display whose top activity permits turning, so
    the moment the app dies the launcher is in front — and launchers are portrait-locked — the window
    manager settles on ROTATION_0 and writes `user_rotation` back to `0`. Nothing reports this: the
    cell keeps its name, the screenshots keep coming, and [read_insets] keeps telling the truth about
    a rotation that is no longer the one being tested. Measured on `gloam-api33` on 2026-09-03: a
    `force-stop` alone took `user_rotation` from 1 to 0 within three seconds, and every scene of both
    landscape cells came back 1080x2400 under a landscape name.

    So this is not a wipe's problem, which is how it was first written. Three callers force-stop —
    [wipe], [relaunch] and [deny_asks] — and each is a place a landscape cell can quietly become a
    second portrait one.

    **Read before write, because the write is not the cost — the settle is.** A portrait cell never
    loses anything and would otherwise pay 1.5s per scene to be told so; the round-trip that asks is
    an order of magnitude cheaper. `accelerometer_rotation` is read in the same trip rather than
    written blind: on a device that *has* a sensor, leaving it enabled hands the display back to the
    hand holding the phone, which is the same defect by a slower road.
    """
    if _PINNED is None:
        return
    live = shell(
        "settings get system user_rotation; settings get system accelerometer_rotation"
    ).split()
    if live == [str(_PINNED.rotation), "0"]:
        return
    shell("settings put system accelerometer_rotation 0")
    shell(f"settings put system user_rotation {_PINNED.rotation}")
    settle(1.5)


def restore_device() -> None:
    # Gesture is the phone's own default; below API 29 there is no such thing to go back to.
    set_nav_mode("gesture" if api_level() >= GESTURE_MIN_API else "threebutton")
    shell("settings put system accelerometer_rotation 1")
    set_dnd(False)
    set_demo_status_bar(False)


def set_dnd(on: bool) -> None:
    """Silence heads-up notifications for the run, and hand them back afterwards.

    **Setup and teardown, not a note in a document.** A heads-up notification posts over whatever
    screen the driver is on, exactly where its next tap lands. The tap opens the notification
    instead, and `AUTO_CANCEL` clears the banner on the way — so the evidence afterwards looks
    impossible: a scene that walked somewhere nobody asked it to, and no notification anywhere to
    explain it. Worth pinning *before* it costs you a run: from the artifacts alone it is close to
    undiagnosable.

    `set_dnd` and not a revoked `POST_NOTIFICATIONS`: the scenes photograph reminder copy, and an
    app that cannot post notifications draws a blocked-state banner instead — which would make the
    screenshots lie about a different thing. Zen suppresses the *presentation* and leaves the app's
    own state alone (verified: `zen_mode` reads 2 with it on and 0 with it off).

    It is **phone-wide**, which is why every caller's `off` belongs in a `finally`. A crashed run
    must not leave somebody's phone silent.
    """
    if not shell_ok(f"cmd notification set_dnd {'on' if on else 'off'}"):
        # Tolerated rather than fatal: on an emulator with no heads-up banner to suppress this is
        # cosmetic, and losing a whole matrix to a missing device command would not be.
        print("  -- note: `cmd notification set_dnd` unavailable, continuing without it")
    settle(0.5)


# What `sysui_demo_allowed` read before this run turned it on, so teardown puts back what was there
# rather than a guess. None means "not read yet", the string "null" is the setting being unset.
_DEMO_ALLOWED: "str | None" = None


def set_demo_status_bar(on: bool) -> None:
    """Empty the status bar of this phone's own state for the length of the run.

    **The status bar is in every screenshot, and by default it is a photograph of somebody's phone.**
    The first release smoke test caught the browser's tab counter reading *9* and scrolled it out of
    frame; the status bar is the same defect one row up and cannot be scrolled anywhere. On this
    phone it carried two notification icons, a network-speed readout in KB/s, bluetooth, Do Not
    Disturb, a muted bell and a battery percentage — none of which is Gloam, and all of which ships
    to a store listing in nine languages.

    SystemUI's demo mode is the platform's own answer: a broadcast that tells the status bar to draw
    a fixed set of icons instead of the real ones. It is gated behind a global that is off by
    default, so this reads that global before writing it and [restore_device] puts back what it
    found.

    **HyperOS honours some of it and silently ignores the rest, so what the shots actually get is
    measured rather than documented.** Against AOSP's command list on 2026-09-18:

      notifications visible false   honoured — the icons go, and so do bluetooth, DND and the bell
      battery level 100             honoured — the percentage is fixed
      battery plugged false         *ignored* — the charging bolt stays, and the cable has to be in
                                    for adb, so the bolt is the one thing that cannot be removed
      clock hhmm 1200               half honoured, and in the useful half: the requested time is
                                    ignored, but the clock stops being live and reads a fixed 8:16.
                                    Measured against a phone whose real time was 07:17. So the
                                    command is not sent — there is nothing to gain — and the set
                                    still gets one identical clock across all nine locales, which
                                    a live clock would not have given it
      network wifi/mobile show      *ignored*, and not in the direction asked: the signal and wifi
                                    icons disappear entirely rather than pinning to four bars

    The result is a clock, a battery and a bolt. That is fewer icons than asked for rather than more,
    which is the safe direction for a listing — a status bar with nothing personal in it. Do not
    "fix" the ignored rows by pinning them another way: the point is emptiness, not a fake phone.

    Like [set_dnd] this is **phone-wide**, so every caller's `off` belongs in a `finally`. A crashed
    run must not leave somebody's status bar frozen — and unlike Do Not Disturb, a demo status bar
    looks like a broken phone rather than a quiet one.
    """
    global _DEMO_ALLOWED
    if on:
        _DEMO_ALLOWED = shell("settings get global sysui_demo_allowed").strip()
        shell("settings put global sysui_demo_allowed 1")
        if not shell_ok("am broadcast -a com.android.systemui.demo -e command enter"):
            # Tolerated rather than fatal, on [set_dnd]'s reasoning: an emulator or a ROM without
            # demo mode should cost the shots their clean status bar, not the whole matrix.
            print("  -- note: SystemUI demo mode unavailable, status bar will show real state")
            return
        for command in (
            "-e command notifications -e visible false",
            "-e command battery -e level 100 -e plugged false",
        ):
            shell(f"am broadcast -a com.android.systemui.demo {command}")
        settle(1.0)
        return
    # Exit unconditionally, even if `enter` was never reached: a previous crashed run is exactly the
    # state this has to be able to clear, and the broadcast is harmless when nothing is in demo mode.
    shell("am broadcast -a com.android.systemui.demo -e command exit")
    if _DEMO_ALLOWED in (None, "null", ""):
        # It was unset, so delete rather than write a 0 — `put` would leave a row behind that the
        # phone never had. Behaviourally identical; this is just not leaving litter.
        shell("settings delete global sysui_demo_allowed")
    else:
        shell(f"settings put global sysui_demo_allowed {_DEMO_ALLOWED}")
    _DEMO_ALLOWED = None
    settle(0.5)


# The app language every wipe has to put back, since `pm clear` drops it. None is the device default.
_LOCALE: "str | None" = None


def set_locale(locale: str | None) -> None:
    """Pin the app's language, or hand it back to the device default.

    Per-app locales are system state keyed by package, which is why this is `cmd locale` and not the
    in-app Settings picker: the same mechanism the picker drives, reachable without eight taps.

    **It does not survive `pm clear`**, so the chosen locale is remembered here and re-applied by
    [wipe] — the one place this driver clears the app. Doing it there rather than at the top of each
    suite is what keeps the `empty` suite honest: its scenes wipe as their *first step*, so a locale
    applied only once per cell would shoot the setup wizard in English inside a Polish run.
    """
    if locale is not None:
        require_bcp47(locale)
    global _LOCALE
    _LOCALE = locale
    if locale is None:
        shell(f"cmd locale set-app-locales {PACKAGE} --locales")
    else:
        shell(f"cmd locale set-app-locales {PACKAGE} --locales {locale}")
    settle(1.5)


@dataclass(frozen=True)
class Rect:
    left: int
    top: int
    right: int
    bottom: int

    def intersects(self, other: "Rect") -> bool:
        return (
            self.left < other.right
            and other.left < self.right
            and self.top < other.bottom
            and other.top < self.bottom
        )

    def overlap(self, other: "Rect") -> "Rect | None":
        if not self.intersects(other):
            return None
        return Rect(
            max(self.left, other.left),
            max(self.top, other.top),
            min(self.right, other.right),
            min(self.bottom, other.bottom),
        )

    @property
    def area(self) -> int:
        return max(0, self.right - self.left) * max(0, self.bottom - self.top)

    def as_list(self) -> list[int]:
        return [self.left, self.top, self.right, self.bottom]


# **Two spellings, because the platform renamed these underneath the driver.** API 36 prints
# `InsetsSource id=ba5c0001 type=navigationBars …`; API 33 prints `InsetsSource
# type=ITYPE_NAVIGATION_BAR …` — no `id=`, and the framework's old internal names. A regex that
# knows only the modern spelling matches nothing at the `minSdk` floor and [read_insets] hands back
# an empty dict, at which point every scene in the cell is reported **clean because nothing was
# checked**. That is the failure this whole matrix exists to not have, so `id=` is optional and the
# old names are mapped below.
INSET_RE = re.compile(
    r"InsetsSource (?:id=\w+ )?type=(\w+) frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\] visible=(\w+)"
)

# The pre-34 names for the three types this driver cares about. The cutout has one source per edge
# down there and one type up here, which is why several map to the same name — [read_insets] already
# keeps the largest of any repeated type.
LEGACY_INSET_TYPES = {
    "ITYPE_STATUS_BAR": "statusBars",
    "ITYPE_NAVIGATION_BAR": "navigationBars",
    "ITYPE_EXTRA_NAVIGATION_BAR": "navigationBars",
    "ITYPE_TOP_DISPLAY_CUTOUT": "displayCutout",
    "ITYPE_BOTTOM_DISPLAY_CUTOUT": "displayCutout",
    "ITYPE_LEFT_DISPLAY_CUTOUT": "displayCutout",
    "ITYPE_RIGHT_DISPLAY_CUTOUT": "displayCutout",
}


def read_insets() -> dict[str, Rect]:
    """The system inset rectangles for the *current* rotation, straight from the window manager.

    Several providers report the same type (the status bar has one per top window); they agree, so
    the largest is taken. An invisible source contributes nothing — a hidden navigation bar is not
    an area anything can be wrongly drawn under.
    """
    found: dict[str, Rect] = {}
    for match in INSET_RE.finditer(shell("dumpsys window displays")):
        kind, left, top, right, bottom, visible = match.groups()
        kind = LEGACY_INSET_TYPES.get(kind, kind)
        if kind not in INSET_TYPES or visible != "true":
            continue
        rect = Rect(int(left), int(top), int(right), int(bottom))
        if kind not in found or rect.area > found[kind].area:
            found[kind] = rect
    return found or read_bar_windows()


# **When the inset sources say nothing this driver can use** — API 26 prints not one `InsetsSource`
# line, and API 29 prints them under names (`TYPE_TOP_BAR`, `TYPE_SIDE_BAR_1`) the map above does not
# know — the bars are still windows of their own, titled `StatusBar` and `NavigationBar` (with a
# display number, `NavigationBar0`, from API 30), with a frame and a surface that is or is not shown.
# That is the same rectangle and the same visibility, read one level down. No cutout: API 26 has no
# cutout API to report one.
BAR_WINDOWS = {"StatusBar": "statusBars", "NavigationBar": "navigationBars"}
BAR_WINDOW_RE = re.compile(r"Window #\d+ Window\{[^}]* u0 (StatusBar|NavigationBar)\d*\}:")
MFRAME_RE = re.compile(r"mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def read_bar_windows() -> dict[str, Rect]:
    found: dict[str, Rect] = {}
    for block in shell("dumpsys window windows").split("  Window #")[1:]:
        title = BAR_WINDOW_RE.match("Window #" + block)
        kind = BAR_WINDOWS.get(title.group(1)) if title else None
        frame = MFRAME_RE.search(block)
        if kind is None or frame is None or "Surface: shown=true" not in block:
            continue
        rect = Rect(*(int(v) for v in frame.groups()))
        if rect.area > 0:
            found[kind] = rect
    return found


# --------------------------------------------------------------------------------------------
# Reading the screen
# --------------------------------------------------------------------------------------------

NODE_RE = re.compile(r"<node ([^>]*?)/?>")
ATTR_RE = re.compile(r'([\w:-]+)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


@dataclass
class Node:
    bounds: Rect
    text: str
    desc: str
    cls: str
    package: str
    clickable: bool
    # Compose publishes `Modifier.selectable`'s state as the accessibility node's `isSelected`, and
    # the navigation bar is the only place this app uses it — so exactly one node in a top-level
    # dump carries it, and it is the current tab. Verified in a dump rather than assumed: the item
    # itself carries no text (the label is a child `TextView`), which is why [showing_home] compares
    # rectangles instead of reading it off the labelled node.
    selected: bool = False
    # Only read for *other* apps' chrome, where there is no string to match on. Gloam's own screens
    # are found by their translated labels, which is what [find] is for — a Compose node's
    # `resource-id` is usually empty anyway. [open_url] uses this to ask "is the browser's address
    # bar on screen", which has no answer in any language.
    resource_id: str = ""

    @property
    def label(self) -> str:
        return self.text or self.desc or self.cls.rsplit(".", 1)[-1]


def dump_ui() -> list[Node]:
    """Every visible node on screen, ours and the system's.

    `uiautomator dump` writes to the device and `exec-out cat` reads it back byte-for-byte; going
    through `adb shell cat` corrupts the XML on the way out.
    """
    for attempt in range(3):
        try:
            shell("uiautomator dump /sdcard/e2e-dump.xml")
            raw = adb("exec-out", "cat", "/sdcard/e2e-dump.xml", binary=True)
            xml = raw.decode("utf-8", "replace")
            if "<node" in xml:
                break
        except subprocess.CalledProcessError:
            pass
        settle(1.0)
    else:
        return []

    nodes: list[Node] = []
    for match in NODE_RE.finditer(xml):
        # **Unescape, because this reads XML with a regex and the labels are English prose.** The
        # dump writes `Backup &amp; restore`, so a needle spelled with a real ampersand —
        # `"Backup & restore"` — matches nothing at all, and a label like that tends to sit at the
        # head of many scenes. Found 2026-08-16 on the first English cell run since the needles were
        # lengthened on 2026-08-14; **the 146/146 Polish run could not see it**, because *"Opieka i
        # leki"* and *"Kopia zapasowa i przywracanie"* carry no ampersand. Third defect this phase that is
        # unreachable in one locale and fatal in the other, and the first one that English is the
        # broken half of.
        attrs = {name: html.unescape(value) for name, value in ATTR_RE.findall(match.group(1))}
        bounds_match = BOUNDS_RE.search(attrs.get("bounds", ""))
        if not bounds_match:
            continue
        left, top, right, bottom = (int(value) for value in bounds_match.groups())
        nodes.append(
            Node(
                bounds=Rect(left, top, right, bottom),
                text=attrs.get("text", ""),
                desc=attrs.get("content-desc", ""),
                cls=attrs.get("class", ""),
                package=attrs.get("package", ""),
                clickable=attrs.get("clickable") == "true",
                selected=attrs.get("selected") == "true",
                resource_id=attrs.get("resource-id", ""),
            )
        )
    return nodes


def screen_signature(nodes: list[Node]) -> str:
    """What the app's screen looks like, as a string, for "did anything happen?" comparisons."""
    return "|".join(f"{n.label}{n.bounds.as_list()}" for n in nodes if n.package == PACKAGE)


# What each English needle means in the locale under capture, filled in by [resolve_needles] before
# the first tap and empty for an English run — where every lookup is the identity.
_TRANSLATED: dict[str, str] = {}


def find(
    nodes: list[Node],
    needle: str,
    *,
    text_only: bool = False,
    exact: bool = False,
) -> Node | None:
    """The smallest node whose text or description contains `needle`, case-insensitively.

    Smallest, because Compose reports a merged semantics node for a whole row as well as the leaf
    inside it, and the leaf is the one whose centre is unambiguously on the thing named.

    `text_only` drops content descriptions from the match, which is how a **label** is told apart
    from an **icon that carries the same words**. A FAB whose `contentDescription` reuses the same
    string as the row it opens — which is the right thing to do, rather than spending a second string
    in every language — is indistinguishable from that row to a plain needle, and the FAB is usually
    the smaller node, so the driver taps the button that is already open. The row has text and no
    description; the FAB has a description and no text. Structure, not copy.

    `exact` goes further and demands the node's **text be the needle entire** — no descriptions, no
    substrings. It exists for the five tab labels, where "contains" is not a strong enough claim: a
    tab label can sit *inside* another string on the same screen, and then the smallest-node rule
    picks the wrong one. A tab label is routinely a *substring* of the FAB description on the same
    screen once both are translated — Czech `Pozorování` inside `Zapsat pozorování`, say — and the
    FAB is usually the smaller node, so the driver taps the FAB and photographs the sheet it opened
    instead of the tab it was asked for. English is often clean here only by accident of grammar,
    which is why this is a bug you meet on the first non-English run. See [TAB_NEEDLES].

    **Every needle is translated here**, which is the one place it can be: `tap`, `return_to_home`
    and `showing_home` all arrive through this function, so a locale run needs no second table and
    no scene rewritten. See [resolve_needles].
    """
    needle = _TRANSLATED.get(needle, needle).casefold()

    def matches_label(node: Node) -> bool:
        if exact:
            return node.text.casefold() == needle
        return needle in node.text.casefold() or (
            not text_only and needle in node.desc.casefold()
        )

    matches = [
        node
        for node in nodes
        if node.package == PACKAGE and matches_label(node) and node.bounds.area > 0
    ]
    return min(matches, key=lambda node: node.bounds.area) if matches else None


# --------------------------------------------------------------------------------------------
# Driving
# --------------------------------------------------------------------------------------------


class StepFailed(Exception):
    pass


def wait_for_app(timeout: float = 12.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if PACKAGE in shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"):
            # Focus arrives before the first composition does, and a tap sent into the gap is
            # swallowed — the node is found in the dump and the tap lands on nothing. Long enough
            # to cover a cold start of the heaviest screen.
            settle(1.8)
            return
        settle(0.4)
    raise StepFailed("the app never took focus")


def relaunch() -> None:
    """A fresh start, so every scene walks from the same place.

    `force-stop` rather than plain `am start`: the back stack is saved state, so a warm start would
    reopen wherever the previous scene left off.

    **`force-stop` is not enough on its own, and the gap cost a whole cell.** It kills the process but
    leaves the task record, so Android may restore the saved-instance bundle on the next `am start` —
    the app comes back on whatever screen it was last on rather than at Home. One stray tap is then
    permanent: on 2026-08-12 a dose notification posted mid-run (`importance=4`, two actions, drawn
    exactly where the driver was about to tap), the tap landed on the banner instead of the app, and
    every scene afterwards relaunched into the record-dose screen and failed on a needle that was
    never wrong. `-S` force-stops, and `0x10008000` is `FLAG_ACTIVITY_CLEAR_TASK | NEW_TASK`, which
    drops the record the restore reads from. **A scene must not be able to inherit the last one's
    screen** — that is what makes 61 scenes independent rather than a sequence.

    **Everything above is what the intent *asks* for; [return_to_home] is what checks it.** Measured
    from a detail route and from a top-level tab that is not the root: both relaunches landed on the
    root with the tab bar up, so on that phone the flags do clear the restored Nav3 stack. That makes the step after this one a verification rather than a
    repair — which is worth one dump per scene, because the alternative is trusting an argument.
    """
    shell(f"am start -S -n {ACTIVITY} -f 0x10008000")
    wait_for_app()
    # `-S` is a force-stop, and a force-stop hands the foreground to a portrait-locked launcher on
    # the way past — which unpins the rotation for every scene in a landscape cell, not just the
    # wiping ones. See [repin_rotation]; without this line the landscape half of this matrix is a
    # second portrait half wearing landscape's name.
    repin_rotation()


# The two needles the isolation step is built on, both from the shell rather than from any screen.
# The bottom bar is drawn only while `onDetailScreen` is false (Navigation.kt), so finding a tab in
# it is exactly the question "is a top-level destination on screen?" — and [HOME_TAB] is the bar's
# first item, the one the back stack is rooted at. Naming them here rather than inline is what lets
# a locale run translate them with the rest of the table.
TAB_BAR = "Dim"
HOME_TAB = "Dim"

# The top-level destinations, named so [tap] can tell a tab from anything else it is asked to
# press. Two things follow from being on this list, and both are about a tab label being a *short*
# string that other strings can quote: the needle is matched exactly rather than by substring
# ([find]'s `exact`), and the tap is not believed until the tab it names reports itself selected
# ([showing_tab]). Kept as the English literals the scene table is written in — translation happens
# inside [find], one layer down.
#
# Exactness narrows the trap without closing it. Translation routinely makes a tab label collide
# with some other string on a screen — a two-word English label can become a one-word translation
# that is also a button somewhere — and where that happens, [showing_tab] turns it into a *failed
# scene* rather than a wrong screenshot. That is the division of labour: exactness is the fix, the
# assertion is what makes the next collision survivable.
TAB_NEEDLES = (HOME_TAB, "Settings")

# How many times [return_to_home] may press back before it gives up. Six covers the deepest route in
# the app (Settings → Licences → one licence, plus a dialog and an IME) with room to spare; it is a
# bound rather than a target, and hitting it is a failure and not a retry budget.
BACK_BOUND = 6


def showing_home(nodes: list[Node]) -> bool:
    """Is the *Home* tab the selected one, rather than merely some top-level tab?

    The distinction is the whole reason this exists. A restored back stack does not have to be a
    detail route — it can be another *tab*, which has the tab bar, passes every "are we at
    the shell?" check, and is still the wrong screen for `home`, `home-bottom` and every scene that
    taps *Edit* on a profile card.

    The selected navigation item carries no text of its own, so this is geometry: the one node in
    the dump with `selected="true"` is the current tab, and the *Home* label is inside it exactly
    when Home is that tab.
    """
    return showing_tab(nodes, HOME_TAB)


def showing_tab(nodes: list[Node], needle: str) -> bool:
    """Is the tab called `needle` the selected one?

    [showing_home]'s geometry, asked about any tab rather than only about the root — which is what
    turns a tab tap from "something on screen moved" into "the app is on that tab". Without it, a
    mis-tap that opens a sheet counts as movement, and the run reports every scene reached while
    some of them photographed the wrong screen entirely.

    Exact, because the whole point is to not be fooled by a label quoted inside another string —
    the same reason the tap itself is exact. See [TAB_NEEDLES].
    """
    label = find(nodes, needle, exact=True)
    if label is None:
        return False
    selected = next((node for node in nodes if node.package == PACKAGE and node.selected), None)
    return selected is not None and selected.bounds.intersects(label.bounds)


def return_to_home() -> None:
    """Put the app on the Home tab, or fail the scene saying so.

    **What it found, which is not what it was written to fix.** It was written on the reading that
    `am start -S -f 0x10008000` does not clear a restored Nav3 back stack. Checked directly — walk
    into a detail screen, relaunch; switch tab, relaunch — and **both came back on the root**. So the flags do their job on this phone, and the 2026-08-12 cell
    that relaunched into the record-dose screen over and over is better explained by the banner than
    by the stack: the missed 20:00 dose re-arms at process start (ADR-0025's self-heal), fires
    immediately because it is already past, and posts a fresh heads-up over *every* scene rather
    than poisoning one. [set_dnd] is the fix for that, and this is the check that says so — it
    prints when it has to correct anything, so a run that never prints is evidence about the
    relaunch and not merely the absence of a complaint.

    **`KEYCODE_BACK` alone is not the fix**, which is why this is bounded and checks first. Backing
    past Home exits to the launcher and makes every following scene worse, so the loop presses back
    only while the shell is *not* on screen, and stops the moment it is.

    Failing loudly is the other half. A driver that cannot find Home must not tap into whatever is
    open — that produces a screenshot of the wrong screen under the right name, which is worse than
    a skipped scene because it is evidence that looks like evidence.
    """
    for _ in range(BACK_BOUND + 1):
        nodes = dump_ui()
        if find(nodes, TAB_BAR) is not None:
            break
        back()
    else:
        visible = ", ".join(sorted({n.label for n in dump_ui() if n.package == PACKAGE and n.label})[:20])
        raise StepFailed(f"never reached a top-level tab in {BACK_BOUND} backs; on screen: {visible}")

    if not showing_home(nodes):
        # Only paid when it is actually owed. `tap` verifies by asking the screen whether anything
        # moved, and on the common path — already at Home — that is three taps and three dumps for
        # nothing, which is minutes across a four-cell matrix.
        print("     (restored off Home; corrected)")
        tap(HOME_TAB)


# How far [tap] will scroll looking for a needle, and how many dumps it takes before an unchanged
# screen is believed. The cap matches [swipe_to_end]'s for the same reason — the observation
# timeline holds a year of rows — and the floor is the composition grace the old fixed budget was
# really providing.
TAP_SCROLL_CAP = 16
MIN_TAP_TRIES = 4


def tap(needle: str, *, optional: bool = False, text_only: bool = False) -> None:
    # Retried rather than waited on a fixed delay: a screen that is still composing dumps as an
    # empty ComposeView, and how long that takes depends on the screen, not on the driver. An
    # *optional* tap is not retried — it is asking whether something is there, and four rounds of
    # waiting to be told "no" is most of the run time of the matrix.
    # **Scroll while the screen is still moving, rather than a fixed number of times.** A landscape
    # swipe covers 70%→32% of 1220px — about 464px, against roughly 1030px of a portrait screen — so
    # a budget of four tries reaches ~1856px sideways where it reaches ~4120px upright. The Care
    # screen is several thousand pixels long in landscape (it shows two and a half rows at a time),
    # which is how `visit-editor`, `weight-entry` and `home-crowded-all` came back unreachable on
    # 2026-08-16 for controls that are plainly reachable: `care-bottom` scrolls clean past them to
    # the banner at the end. **A fixed budget is a portrait-shaped constant**, the same shape of bug
    # as the rotation a wipe used to cost, and it fails only where the viewport is short.
    #
    # The signature check is what keeps this from being slower: a screen that cannot scroll stops
    # changing after one swipe and gives up sooner than the old budget did. [MIN_TAP_TRIES] is the
    # floor underneath it, because a screen still composing dumps as an empty `ComposeView` and two
    # identical empty dumps must not be read as "nowhere left to go".
    # A tab is matched exactly and then checked, rather than matched loosely and hoped for. Both
    # halves are [TAB_NEEDLES]'s reason for existing.
    is_tab = needle in TAB_NEEDLES
    attempts = 1 if optional else TAP_SCROLL_CAP
    node = None
    previous = ""
    for attempt in range(attempts):
        nodes = dump_ui()
        node = find(nodes, needle, text_only=text_only, exact=is_tab)
        if node is not None:
            break
        if optional:
            settle(1.0)
            continue
        current = screen_signature(nodes)
        if attempt >= MIN_TAP_TRIES - 1 and current == previous:
            break
        previous = current
        swipe_up(nodes)
    if node is None:
        if optional:
            return
        visible = ", ".join(sorted({n.label for n in nodes if n.package == PACKAGE and n.label})[:25])
        raise StepFailed(f"no node matching {needle!r}; on screen: {visible}")
    x = (node.bounds.left + node.bounds.right) // 2
    y = (node.bounds.top + node.bounds.bottom) // 2
    # **`input touchscreen tap`, never bare `input tap`** — the source has to be named. On this phone
    # `input tap` began exiting 0 while delivering nothing at all: not flaky, dropped every time, on a
    # screen provably on and focused, while `input keyevent` and `input swipe` kept working. Proved by
    # A/B on one screen at one coordinate — bare `tap` never moved the selection, `touchscreen tap`
    # moved it every time. `input` picks a default source when none is given, and that inference is
    # what HyperOS stopped honouring; naming the source sidesteps the guess (6c, 2026-08-06).
    #
    # The retry below stays regardless: exit status still proves nothing, so the screen is asked
    # instead — tap, look, tap again if nothing moved. Without it the first tap after a cold start
    # goes missing often enough to skip whole scenes, and a skipped scene reads like a clean one.
    before = screen_signature(nodes)
    for _ in range(3):
        shell(f"input touchscreen tap {x} {y}")
        settle(1.2)
        after = dump_ui()
        moved = screen_signature(after) != before
        if not is_tab:
            if moved:
                return
            continue
        # **"The screen moved" is not "the app is on that tab".** It was the whole test until 9g,
        # and a sheet opening over Home satisfies it perfectly. Ask the navigation bar instead.
        if showing_tab(after, needle):
            return
        if moved:
            visible = ", ".join(sorted({n.label for n in after if n.package == PACKAGE and n.label})[:25])
            raise StepFailed(
                f"tapped {needle!r}, the screen moved, and that tab is not the selected one — "
                f"the needle named something else; on screen: {visible}",
            )
    if is_tab:
        raise StepFailed(f"tapped {needle!r} three times and never landed on that tab")
    # Fell through: three taps and nothing moved. That is a finding about the scene, not about the
    # phone — a control that does nothing — so it is left to the caller's next step to fail on.


def back() -> None:
    shell("input keyevent KEYCODE_BACK")
    settle(0.8)


def screen_size() -> tuple[int, int]:
    """The display's size **as it is currently rotated**, which `wm size` does not report.

    `wm size` gives the *physical* size and stays portrait-first through every rotation, so the
    orientation has to come from somewhere else — and `user_rotation` is the wrong somewhere. That
    key records what the display was last **pinned** to, and it means nothing while
    `accelerometer_rotation` is 1: on 2026-08-20 it read `1` against a live 1220x2712 portrait
    screen, left over from an earlier run's pinning that `--restore` had handed back.

    [swipe_up] built a 2712-wide swipe out of it and sent every gesture to x=1356, off the right
    edge of a 1220px display. Nothing scrolled, so [tap] read the unchanged screen as "nowhere left
    to go" and the seed walk died on the wizard's *Continue* — before the matrix captured a single
    cell. **The same shape as the two rotation bugs already recorded here**: a value that describes
    what the driver asked for, trusted as a description of what the phone is doing.

    `dumpsys window displays` reports `cur=WxH` already rotated, which is the one reading that
    cannot disagree with the screen. It is also why this does not consult `mRotation`: a rotation
    still has to be turned into a width and a height, and `cur=` is both, measured.
    """
    match = re.search(r"cur=(\d+)x(\d+)", shell("dumpsys window displays"))
    if match:
        return int(match.group(1)), int(match.group(2))
    # Portrait-first from `wm size` is a poor guess, but the caller needs a midpoint rather than the
    # truth, and guessing beats dividing by nothing.
    match = re.search(r"(\d+)x(\d+)", shell("wm size").split(":")[-1])
    return (int(match.group(1)), int(match.group(2))) if match else (1220, 2712)


def content_box(nodes: list[Node]) -> "Rect | None":
    """The rectangle the app's own nodes occupy — **which is the popup when one is open**.

    A `DropdownMenu` is its own window, and a dump taken while one is open contains *only* that
    window's nodes: with a dropdown open, the whole dump sits inside the menu's own rectangle with
    nothing of the screen behind it. So the app's bounding box *is* the menu when one is open, and
    on an ordinary screen it is the whole display — measured, not assumed: two full-screen scenes
    box to exactly `(0, 0, 2712, 1220)` in landscape, which is why scrolling by this box leaves
    every full-screen scene swiping precisely where it always did.
    """
    ours = [node for node in nodes if node.package == PACKAGE]
    if not ours:
        return None
    return Rect(
        min(node.bounds.left for node in ours),
        min(node.bounds.top for node in ours),
        max(node.bounds.right for node in ours),
        max(node.bounds.bottom for node in ours),
    )


def swipe_up(nodes: "list[Node] | None" = None) -> None:
    """Scroll a list towards its end — where the row nearest the navigation bar is.

    **Swipes inside [content_box], not across the middle of the screen.** A `DropdownMenu` a couple
    of hundred dp wide, anchored under an app-bar control, scrolls internally when it is long. A
    swipe at the *screen's* own midpoint is x=1356 on a 2712px-wide landscape display and lands
    **outside** such a menu entirely: the gesture goes to the window behind it, the menu never
    moves, and [tap] reads the unchanged screen as "nowhere left to go".

    That failure mode is why this is worth knowing rather than a detail — it passes in both portrait
    configurations, where the midpoint happens to fall inside the menu, and fails in both landscape
    ones. **A swipe aimed at the screen is a claim that the screen is what scrolls.**

    `nodes` is the dump the caller already holds, so the common path costs no extra dump.
    """
    box = content_box(nodes if nodes is not None else dump_ui())
    if box is None:
        width, height = screen_size()
        box = Rect(0, 0, width, height)
    x = (box.left + box.right) // 2
    span = box.bottom - box.top
    # **Both ends of the swipe have to land inside the scrollable**, which is a smaller target than
    # it looks in landscape: a top-level tab there has a switcher above it and a navigation bar
    # below, leaving content between roughly 26% and 76% of a 1220px screen. Swiping from 75% —
    # comfortably inside a portrait screen — landed on the bottom bar's edge and scrolled nothing,
    # and a swipe that does nothing turns a scroll-to-end scene into a screenshot of the top of the
    # list wearing the name of the bottom.
    shell(f"input swipe {x} {box.top + int(span * 0.70)} {x} {box.top + int(span * 0.32)} 300")
    settle(1.0)


def swipe_to_end() -> None:
    """Scroll until the screen stops changing — the position the last row comes to rest in.

    This is the one that matters. Mid-scroll, a list *should* run under the navigation bar; that is
    what edge-to-edge looks like. The defect is a list whose last row still sits under the bar once
    it has nowhere left to go, and only the end of the scroll can tell the two apart.
    """
    # Each swipe now covers less of the screen, and the observation timeline holds a year of rows,
    # so the cap is generous: stopping early would report the middle of a list as its end.
    previous = ""
    # Dumped once up front and then reused: the signature dump each round is also the one the next
    # swipe aims by, so scrolling inside [content_box] costs a single extra dump for the whole loop.
    nodes = dump_ui()
    for attempt in range(16):
        swipe_up(nodes)
        nodes = dump_ui()
        current = screen_signature(nodes)
        if current == previous:
            return
        previous = current


def tap_field(index: str) -> None:
    """Tap the *n*-th editable field on screen, top to bottom. The IME scenes' way in.

    **A needle cannot name a field that has no text.** A hero input with no label and no
    placeholder — where the value is the whole control — has no string to aim at, and the nearest
    matching string is usually a help line *underneath* it, which is a plain `Text`. Tapping that
    focuses nothing, and the scene shoots a form with no keyboard from then on.

    This is the trap worth knowing about, because **a scene with no IME still produces a perfectly
    good screenshot** — nothing fails, and the check silently stops checking. Address a field by
    index (`tap_field`) rather than by a string that happens to sit near it.

    Structure rather than copy is the fix, and it is the right one twice over: an `EditText` is an
    `EditText` in every language, so this is the one needle a locale run cannot break.
    """
    wanted = int(index)
    fields = sorted(
        (node for node in dump_ui() if node.package == PACKAGE and node.cls.endswith("EditText")),
        key=lambda node: (node.bounds.top, node.bounds.left),
    )
    if wanted >= len(fields):
        raise StepFailed(f"asked for editable field {wanted}, found {len(fields)}")
    node = fields[wanted]
    shell(f"input touchscreen tap {(node.bounds.left + node.bounds.right) // 2} "
          f"{(node.bounds.top + node.bounds.bottom) // 2}")
    settle(1.2)


def type_text(value: str) -> None:
    shell(f"input text {value}")
    settle(1.2)


def wipe() -> None:
    """Back to a first run: no preferences and, more to the point here, no grants.

    Destructive, and deliberately only reachable from the `empty` suite — the `full` suite runs
    against an app that has been used, and a wipe in the middle of it would quietly capture the
    permission explainer under the name of whatever screen was meant to be behind it.

    **What `pm clear` costs is different in this app.** There is no database and no wizard: what it
    drops that shows on screen is the overlay appop and POST_NOTIFICATIONS, which is exactly the
    state the `empty` suite exists to photograph.
    """
    # **The shipped install is not ours to clear.** On `debug` this is free; on `…gloam` it destroys
    # a real dim level, warmth, schedule and theme, and a release build has no `run-as` to read them
    # back out first. Raising here rather than checking at every call site is deliberate: `wipe` has
    # three routes into it — the `empty` suite's own first step, [reset_to_seeded], and
    # [ensure_seed] — and a guard on two of them is a guard on none.
    if not WIPEABLE:
        raise StepFailed(
            f"refusing to `pm clear {PACKAGE}`: that is the shipped install, and this would wipe "
            "settings nothing here can restore. Run with --no-reseed, or point the driver at debug",
        )
    global _SEEDED
    _SEEDED = None
    shell(f"pm clear {PACKAGE}")
    settle(1.0)
    # Before the launch, not after: `pm clear` drops the per-app locale, and re-applying it to a
    # package that is not running costs nothing, where doing it afterwards restarts the activity.
    if _LOCALE is not None:
        shell(f"cmd locale set-app-locales {PACKAGE} --locales {_LOCALE}")
    shell(f"am start -n {ACTIVITY}")
    wait_for_app()
    # Only the rotation: the navigation mode is a global and survives a wipe, and re-writing it
    # would buy another 3s settle per wiping scene for nothing. Verified by the failure it exists to
    # stop — `mRotation=ROTATION_0` and 1220x2712 PNGs in a cell named `landscape-gesture`.
    repin_rotation()


def deny_asks() -> None:
    """Take both grants away and relaunch, so a first-run scene shows a first run.

    **A wipe is not enough, and the emulator is where that shows.** `pm clear` resets the overlay
    appop to its *default*, and the default is not the same answer everywhere: on the phone it means
    denied, on an AOSP emulator image the app can draw overlays straight after a wipe. So
    `dim-permission` photographed the ordinary dim screen on the API 33 leg — a scene whose name
    promised the explainer, delivering evidence that looks like evidence.

    Denying explicitly makes the scene mean the same thing on every device it runs on, which is the
    only way a matrix cell is comparable to another one.

    The relaunch at the end is not optional: `pm revoke` restarts the app's process, so without it
    every step after this one would be driving a screen that is no longer there.
    """
    shell(f"appops set {PACKAGE} SYSTEM_ALERT_WINDOW deny")
    if api_level() >= POST_NOTIFICATIONS_MIN_API:
        shell(f"pm revoke {PACKAGE} android.permission.POST_NOTIFICATIONS")
    shell(f"am start -S -n {ACTIVITY}")
    wait_for_app()
    repin_rotation()  # another force-stop, the same cost — see [repin_rotation]
    settle(0.8)


def reset_to_seeded() -> None:
    """Wipe, then put both grants back — which is the whole of Gloam's fixture.

    **This app has nothing to seed, and that is a property of what it stores rather than an
    omission.** Gloam keeps a dim level and a shade-running flag in DataStore and no records at all
    (ADR-0007), so there is no sample data, no debug seed receiver and no wizard to walk. The only
    thing that distinguishes a wiped install from an app in use is *permission state*, so that is
    the fixture: overlay allowed, notifications granted.

    Both matter to a screenshot rather than only to behaviour. Without the overlay grant every
    `full` scene photographs the permission explainer on the dim screen; without the notification
    grant the same screen carries the escape-hatch warning, which is a true state of the app and
    the wrong one to shoot under a name that means "in use". The `empty` suite is the deliberate
    exception and keeps both denials, because there they are the truth of a first run.
    """
    wipe()
    # The appop rather than a walk through the system settings screen. Granting the overlay is a
    # hand-off to another app's UI (`shadePermissionIntent`), and a scene driver that taps its way
    # through HyperOS's Settings is one ROM update away from photographing the wrong app.
    #
    # Never read this back with `appops get`: it reports last-use rather than the granted mode on
    # the phone in this loop. Nothing here needs to — the next screenshot shows whether the
    # explainer is gone, which is a better check than the one that lies.
    shell(f"appops set {PACKAGE} SYSTEM_ALERT_WINDOW allow")

    # Last, because `pm grant` can kill the process. Every caller relaunches before its next
    # screenshot, so the app picks both grants up cleanly.
    if api_level() >= POST_NOTIFICATIONS_MIN_API:
        shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
    settle(0.5)
    global _SEEDED
    _SEEDED = ""


# Which seed the phone is currently carrying: "" for the plain sample data, a variant name for one
# of [SeedVariantReceiver]'s, and None for "unknown" — which is what a wipe leaves and what the
# start of every cell asserts, so the first scene of a cell always reseeds exactly as it always did.
_SEEDED: "str | None" = None

SEED_RECEIVER = f"{PACKAGE}/{project.NAMESPACE}.debug.SeedReceiver"


def seed_variant(variant: str) -> None:
    """Add a variant on top of the sample data, through the debug build's own receiver.

    **The default seed is never changed, and that is the constraint rather than a nicety.** Sixty-one
    scenes, the before/after comparison and the Play listing screenshots all rest on it, so a third
    row or a repurposed series would move evidence that is already banked. A variant is additive
    and asked for by name.

    `-f 0x00000020` is `FLAG_INCLUDE_STOPPED_PACKAGES`: a package is in the stopped state after
    `pm clear` until something launches it, and a broadcast to a stopped package is dropped in
    silence — which would look exactly like a variant that seeded nothing.

    The receiver reports through the broadcast result, so this can fail on what actually happened
    rather than on a timeout: `result=0` and the data string it set, or a loud failure naming the
    exception. See [SeedVariantReceiver] for why it is a broadcast at all.
    """
    global _SEEDED
    output = shell(f"am broadcast -n {SEED_RECEIVER} -f 0x00000020 --es variant {variant}")
    if "result=0" not in output:
        raise StepFailed(f"seeding variant {variant!r} failed: {output.strip()[:200]}")
    _SEEDED = variant
    settle(1.0)


# **The hazard this app cannot reproduce, recorded rather than pretended.** A heads-up notification
# posting over the screen exactly where the driver is about to tap is what wrecks a driver run and
# is impossible to diagnose from the artifacts afterwards: the tap lands on the banner, `AUTO_CANCEL`
# clears it, and every scene after that relaunches somewhere unexpected and fails on a needle that
# was never wrong. [set_dnd] is the standing fix and stays.
#
# The template had a `--live-notice` flag that armed an overdue dose reminder to *prove* DND was
# doing something. It is gone, and it was never real here in two ways: the function it called was
# never ported, and `main` read an argument name that did not exist either — so every invocation of
# this script died on an `AttributeError` before its first tap. Gloam has exactly one notification,
# the shade's own ongoing one, and an ongoing notification does not heads-up. Wire a flag like that
# back the day this app can post something on demand.


def invalidate_seed() -> None:
    """Forget what the phone is carrying, so the next [ensure_seed] reseeds whatever it asks for."""
    global _SEEDED
    _SEEDED = None


def ensure_seed(variant: str) -> None:
    """Put the phone on the seed this scene asked for, reseeding only when it is not already there.

    Reseeding costs a wipe, a wizard and the sample data — the better part of a minute — so scenes
    are sorted by the seed they want and this is a no-op for every scene but the first of each
    group. Variant scenes therefore cost one reseed each per cell rather than one per scene.
    """
    if _SEEDED == variant:
        return
    print(f"  -- seeding{f' + {variant}' if variant else ''}")
    reset_to_seeded()
    if variant:
        seed_variant(variant)


# **The article the compact controls float over**, per language, keyed by the language subtag.
#
# A *real page in the reader's own language* rather than one of Gloam's own screens, because the
# compact controls' whole claim is that they work on top of whatever you are reading — a shot of
# them over Gloam's Settings proves the opposite of the thing being sold.
#
# **Filled in only where the title has been checked, and a missing entry is a hard failure rather
# than a guess.** A wrong title is not a broken run, it is a Wikipedia "there is no article with
# this exact name" page sitting in the middle of a store listing in a language nobody on this side
# reads. [open_url] raises on a language that is not here; fill it by opening the article and
# copying the title out of the URL, never by translating the English one.
#
# The eight below were taken from the English article's own interlanguage links — Wikidata's
# sitelinks for the same item, which is each wiki's real title for the same subject rather than a
# translation of "Night" — and every one was then confirmed to resolve to an article with a lead
# photograph. `pt-BR` is not a Wikipedia: [_language] cuts the region off, so it reads `pt`.
# How many extra scrolls [open_url] will spend trying to get the browser's own chrome off screen
# before it gives up and fails the scene. Three, because one has always been enough so far and a
# fourth would mean the page has stopped responding to drags rather than that it needs another.
BROWSER_CHROME_TRIES = 3

# Where the framing drag ends, as a fraction of screen height, starting from [SCROLL_FROM]. The
# default suits a long article with a lead photograph; the exceptions are languages whose article is
# short enough that the default runs off the end of it and lands on Wikipedia's footer — a "last
# edited by <username>" bar, a *Related pages* card, a navigation box. Measured 2026-09-18 by
# looking at all nine frames side by side, which is the only way any of this is visible.
SCROLL_FROM = 0.85
SCROLL_END_DEFAULT = 0.18
# An entry is one drag, or a tuple of them for an article whose furniture is taller than a single
# drag can carry off — each element is another drag from [SCROLL_FROM] down to that fraction, run in
# order with a pause between. The pause is load-bearing: it is what keeps two drags from becoming
# one fling, which is the thing the long comment in [open_url] measured.
WIKIPEDIA_SCROLL_END = {
    # *Notte* is a stub with a long table of contents: the default put the whole section list in
    # frame and the editor's name under it.
    "it": 0.45,
    # *Nuit* opens under a maintenance banner ("sources (mai 2010)") whose orange tail survived the
    # default drag by about sixty pixels. A second short drag clears it, and the distance is read off
    # the picture rather than derived: one long drag to 0.12 and this pair land within a few dozen
    # pixels of each other, so the arithmetic here is not worth trusting on its own.
    "fr": (0.18, 0.70),
    # *Noite* ends on its collapsed section list, and the default landed with the "last edited" bar
    # cut in half across the bottom edge — a clipped line of text photographs as a broken render
    # rather than as a page.
    "pt": 0.40,
    # *Ніч* has no lead photograph: an infobox sits straight after the intro and is taller than two
    # drags, so anything shorter than this either leaves it in frame or stops halfway down it.
    "uk": (0.45, 0.45, 0.45),
}

WIKIPEDIA_ARTICLE = {
    "en": "Night",
    "pl": "Noc",
    "cs": "Noc",
    "de": "Nacht",
    "es": "Noche",
    "fr": "Nuit",
    "it": "Notte",
    "pt": "Noite",
    "uk": "Ніч",
}


def _language() -> str:
    """The language subtag this run is in — the pinned locale, or the phone's own when none is."""
    if _LOCALE is not None:
        return _LOCALE.split("-")[0]
    for prop in ("persist.sys.locale", "ro.product.locale"):
        value = shell(f"getprop {prop}").strip()
        if value:
            return value.replace("_", "-").split("-")[0]
    raise StepFailed("no locale is pinned and the phone will not say what its own is")


def open_url(url: str) -> None:
    """Hand a URL to whatever the phone opens links with, and wait for it to be up.

    `{lang}` and `{article}` in [url] are filled from the run's locale, so one line in [SCENES]
    covers nine locales and each one gets its own Wikipedia rather than an English page behind a
    Ukrainian listing.

    **This leaves the app**, which no other step in this table does, so it is only ever correct in a
    scene whose subject is a window drawn *over* another app. [relaunch] is what puts things back,
    and every scene starts with one.

    Two things it cannot check and you can: that the phone has a default browser rather than a
    chooser (a chooser photographs as a chooser), and that the page actually resolved. Both are
    visible in the screenshot, which is why the English cell is reviewed before the other eight run.
    """
    language = _language()
    article = WIKIPEDIA_ARTICLE.get(language)
    if "{article}" in url and article is None:
        raise StepFailed(
            f"no checked Wikipedia article for {language!r} — add one to WIKIPEDIA_ARTICLE. "
            "Do not translate the English title; open the article and copy the title from the URL",
        )
    shell(f"am start -a android.intent.action.VIEW -d '{url.format(lang=language, article=article)}'")
    # A page load is a network round trip, and unlike everything else here there is no signal to
    # wait on that means "readable" rather than "attached". Long enough for a Wikipedia article
    # over a phone connection; the screenshot shows a half-drawn page if it was not.
    settle(4.0)

    # **Scrolled into the body, and both reasons are about what a listing frame must not contain.**
    # A browser parked at the top of an article shows its own chrome — on the first release smoke
    # test that was an address bar, a home button and a tab counter reading *8*, which is somebody's
    # personal browsing state in a public store listing. Chrome hides the toolbar once the page
    # scrolls. The same scroll carries off Wikipedia's fundraising and campaign banners, which are
    # dated the moment they are photographed ("Wiki Loves Monuments" in that run).
    #
    # Raw coordinates rather than [swipe_up], deliberately: that one reads a content box out of a
    # uiautomator dump, and the foreground here is an arbitrary browser rather than a Compose screen
    # this repo knows the shape of.
    #
    # **One slow drag, not two fast ones, and the difference is the whole point of this block.**
    # Two 300 ms swipes are a *fling*: Chrome derives momentum from the velocity it samples at
    # release, that sampling drifts with frame timing, and the page therefore lands somewhere
    # different every run. Measured 2026-09-17 on the phone, same article and same command, as mean
    # absolute pixel difference over the article area between two runs with the browser cold:
    #
    #   two 300 ms swipes (the fling)      42 / 255      — lands in a different paragraph each time
    #   two 1400 ms swipes                 54 / 255      — slower is not the fix; still a fling
    #   load, no scroll at all              0 / 255      — the page itself renders identically
    #   MOVE_HOME + one 2000 ms drag        5 / 255      — a ~2 px offset, which is nothing
    #
    # The third row is what localises the fault: the document is perfectly reproducible, so every
    # bit of the variance was being injected by the gesture. A drag slow enough to release at
    # roughly zero velocity leaves Chrome no momentum to compute and simply stops where it is put.
    #
    # **It has to be a touch gesture, and that is the platform rather than a preference.** Chrome
    # hides its toolbar for touch scrolling only — `KEYCODE_PAGE_DOWN` scrolls the document and
    # leaves the address bar and tab counter sitting in frame, which is the one thing this scroll
    # exists to prevent. Keyboard scrolling was measured as exactly reproducible and still unusable
    # for that reason. `MOVE_HOME` is safe because it only ever *precedes* the drag: it pins the
    # start to the top of the document — the browser is not force-stopped between scenes, so it may
    # restore a scroll position from the last one — and the drag that follows re-hides the toolbar.
    # Anything that ends by scrolling *up* brings the chrome straight back.
    #
    # The distance is tuned so the article's lead image sits whole under the window rather than
    # clipped: a listing frame wants a photograph behind the controls, not a wall of body text.
    # **It is bounded, not controlled** — each language is a different document, so the same offset
    # lands on different content, and that is what the per-locale review is for.
    #
    # **The one drag is not enough on its own, and believing it was cost a whole nine-locale set.**
    # Shot 2026-09-18: `en` and `pl` hid the toolbar and the other seven did not, so seven listing
    # frames carried an address bar and a tab counter reading somebody's real tab count. Nothing
    # failed — the cells were green, the manifest was clean, and the defect is only visible to an
    # eye on the picture. It does not reproduce from the gesture alone either: the same drag on the
    # same article hides the toolbar sometimes, which is why this is a *check* and not a longer drag.
    # A second, shorter drag cleared it on the first try in every case measured.
    #
    # So: drag, then ask the screen whether the address bar is still there, and drag again if it is.
    # The check is on `resource-id` because there is no text to match — the URL is a different
    # string in every locale, and "is the browser's own chrome on screen" is not a translatable
    # question. Failing loudly at the end is the point: a cell that cannot hide the toolbar must not
    # become a screenshot.
    width, height = screen_size()
    ends = WIKIPEDIA_SCROLL_END.get(language, SCROLL_END_DEFAULT)
    if not isinstance(ends, tuple):
        ends = (ends,)
    shell("input keyevent 122")  # MOVE_HOME — the top of the document, wherever the browser was
    settle(1.0)
    for end in ends:
        shell(
            f"input swipe {width // 2} {int(height * SCROLL_FROM)} "
            f"{width // 2} {int(height * end)} 2000"
        )
        # The toolbar slides away rather than vanishing, and a frame caught mid-slide has half of
        # it. The same wait is what makes a second drag a second *drag*: Chrome takes its momentum
        # from the velocity it samples at release, and a page that has already come to rest has
        # none to give it.
        settle(2.0)

    for attempt in range(BROWSER_CHROME_TRIES):
        if not any("url_bar" in node.resource_id for node in dump_ui()):
            return
        # **A tenth of the screen, and the smallness is the fix rather than a detail.** This nudge
        # was 30% of the height first, and on a short article three of them walked the page off the
        # end of the document and into the footer — so the retry that saved the frame from the
        # address bar was what ruined it. Chrome does not need distance to hide the toolbar, only
        # some downward motion: 10% over 800 ms clears it, measured on the shortest article in the
        # set. This exists to move the page, not to frame it.
        shell(f"input swipe {width // 2} {int(height * 0.60)} {width // 2} {int(height * 0.50)} 800")
        settle(2.0)
    raise StepFailed(
        f"the browser's address bar is still on screen after {BROWSER_CHROME_TRIES} scrolls — "
        "a listing frame cannot carry it, and it brings a tab counter with it"
    )


def start_controls() -> None:
    """Open the compact controls the way the notification does.

    `am start -n` on [CONTROLS_ACTIVITY] rather than a tap on the debug section's *Open compact
    controls* button, which is the route [SCENES] used to take and which does not exist on the
    build a store screenshot comes from. The Activity is exported — it carries the launcher
    `<intent-filter>` — so this is reachable on debug and release alike, and its empty
    `taskAffinity` is what lets it land over the browser instead of inside Gloam's own task.
    """
    shell(f"am start -n {CONTROLS_ACTIVITY}")
    wait_for_app()


STEP_RUNNERS = {
    "tap": lambda arg: tap(arg),
    "tap?": lambda arg: tap(arg, optional=True),
    # Tap a **label**, never an icon's description — see [find]. The one place it is needed so far
    # is the sheet behind the "+", whose row and whose FAB deliberately say the same words.
    "tap_text": lambda arg: tap(arg, text_only=True),
    "back": lambda arg: back(),
    "swipe_up": lambda arg: swipe_up(),
    "swipe_end": lambda arg: swipe_to_end(),
    "tap_field": tap_field,
    "type": type_text,
    "wait": lambda arg: settle(float(arg)),
    "wipe": lambda arg: wipe(),
    "deny": lambda arg: deny_asks(),
    "open_url": open_url,
    "start_controls": lambda arg: start_controls(),
}


@dataclass(frozen=True)
class Scene:
    """One screen worth capturing, and the taps that reach it from a cold start.

    `family` is how the review is organised: screens sharing chrome fail identically, so
    they are reviewed as a group against a representative, and a member that differs from its
    representative is itself the finding.
    """

    name: str
    family: str
    steps: list[tuple[str, str]] = field(default_factory=list)
    note: str = ""
    # "full" runs against a granted install; "empty" runs against a wiped and denied one, which is
    # the only way to see the permission explainer a first user actually meets.
    suite: str = "full"
    # **Inert in Gloam, and kept rather than deleted.** In the app this engine came from, the seeded
    # data raised a prompt on top of every launch that had to be captured before it was dismissed,
    # and this is the flag that said so. Gloam raises nothing over its own screens, so no scene sets
    # it and it is always False — which makes the engine's ordering, dismissal and retry branches
    # below dead code today. They are the cheapest thing to keep and the most expensive thing to
    # rediscover, so they stay until a scene needs them.
    keeps_watch_prompt: bool = False
    # **Inert in Gloam**, for the same reason as above and more completely: Gloam has no sample data
    # to seed, no debug receiver to seed it with, and one screen over DataStore rather than a list.
    # Every scene runs on the plain install, so this is always "" and the reseeding machinery it
    # drives never fires. It comes back with the first feature that stores rows a user created —
    # which the house rules say is a deliberate act with a migration story attached, not a Tuesday.
    seed: str = ""


# ---------------------------------------------------------------------------------------------
# THE SCENE TABLE IS YOURS. Everything above this line is the engine; everything below describes
# *this* app, and it is the only part you rewrite when you build a different one.
#
# A scene is a name, a shape, and a list of steps to reach it from a fresh launch. The shape names
# what the assertion expects to find under the system bars — see the `SHAPES` table for what each
# one means. Steps are `(verb, argument)` pairs run in order; `STEP_RUNNERS` lists the verbs.
#
# Three habits that come from a driver that got this wrong repeatedly:
#
#   1. **Name the thing you mean.** `find` is a case-insensitive substring match, so tapping `"Open"`
#      will happily match a permission banner's *Open* button and launch the system Settings app —
#      after which every later scene screenshots Settings and fails on a needle that was never
#      wrong. Tap a row by its own text.
#   2. **Every scene starts from a relaunch, so scenes are independent** — except for anything
#      stored in a preference, which survives it. A scene that needs some state says so in its own
#      steps and pays the taps, rather than depending on the scene before it.
#   3. **Prefer a route that survives landscape.** A button below the fold on a 1220px-tall viewport
#      is a route that works in three configurations out of four.
# ---------------------------------------------------------------------------------------------

# The order matters only in that each scene starts from a relaunch, so they are independent.
#
# **This table is Gloam's, and it is much smaller than the one it replaced.** The template's scenes
# walked `items`, `item-detail`, `Add an item`, `Backup and restore` and a schema-mismatch screen
# reached by patching a version byte into a database file. None of those exist here: the whole
# surface is one screen over DataStore, a Settings tab, and three detail screens behind it. Until
# this rewrite the nightly walked to screens that do not exist — a job that could not pass, which is
# not coverage but a red light everyone learns to ignore.
#
# **The `form` family is gone, and it is a loss rather than a tidy-up.** The three `item-editor`
# scenes were the most valuable cells in the old table: they are what catches a window being
# *panned* instead of resized, where the top bar and its Save button slide off the top of the screen
# and only a screenshot shows it. Gloam has no text field anywhere, so there is nothing to focus and
# no IME to raise — `imePadding` has nothing to prove here. The day this app grows a field, that
# family comes back, and this paragraph is the note saying what it was for.
SCENES = [
    # --- a wiped install: the two denials, which no granted run can show ------------------------
    # The screen a first user actually meets. `wipe` clears the preferences and `deny` puts both
    # asks back to refused — explicitly, because a wipe leaves the overlay appop at its *default*
    # and that default is "allowed" on an AOSP emulator image. Without the second step this scene
    # showed the ordinary dim screen on the API 33 leg, under a name that says otherwise.
    Scene("dim-permission", "tab", [("wipe", ""), ("deny", "")], suite="empty"),

    # --- the tabs ------------------------------------------------------------------------------
    Scene("dim", "tab", []),
    Scene("dim-bottom", "tab", [("swipe_end", "")]),
    Scene("settings", "tab", [("tap", "Settings")]),
    Scene("settings-bottom", "tab", [("tap", "Settings"), ("swipe_end", "")]),

    # --- detail screens: no bottom bar, so the navigation-bar inset is the app's own problem ----
    Scene("support", "detail", [("tap", "Settings"), ("tap", "Help and feedback")]),
    Scene("licences", "detail", [("tap", "Settings"), ("tap", "Open-source licences")]),
    Scene("licences-bottom", "detail", [("tap", "Settings"), ("tap", "Open-source licences"), ("swipe_end", "")]),
    # The one long scrolling body in the app, and the only needle in this table that is not a string
    # resource: the licence's own name comes from Licensee's output, is identical in every locale,
    # and [resolve_needles] passes it through untranslated for exactly that reason.
    Scene(
        "licence-text",
        "detail",
        [("tap", "Settings"), ("tap", "Open-source licences"), ("tap", "Apache License 2.0")],
    ),

    # --- the compact controls: a floating window, where the matrix's question is a different one --
    # A family of its own, because it shares chrome with nothing in the table: a dialog-shaped window
    # in its own task, no top bar, no bottom bar, and no system-bar inset of its own to get wrong.
    # What a screenshot has to show here is the other half — that a **gesture-navigation bar does not
    # clip it**. The window is bottom-weighted and sized to its content, which is exactly the shape
    # that gets clipped, and R5 already caught the Polish leg overflowing its height on the phone.
    #
    # **It used to be reached by tapping the debug section's *Open compact controls* button, and
    # that route is gone.** It was forced when this was written — `ControlsActivity` was
    # `exported="false"`, so `am start -n` could not reach it at all (`phase-3.md` R2) — and shape iv
    # removed the reason by giving the Activity the launcher `<intent-filter>`, which exports it.
    #
    # What retired the button is the store set. Developer surface lives in `app/src/debug/`, so on
    # the shipped build `DebugSettings()` is `= Unit` and the button is *absent*: a tap on it there
    # does not find a disabled control, it finds nothing, and the scene fails. [start_controls]
    # names the Activity instead, which is the route the ongoing notification itself takes and works
    # on both builds. A screenshot of a surface reached only from a debug menu was always answering
    # a question nobody asked.
    #
    # **What sits behind it is not decided here**, and that is the split between the two scripts
    # rather than an omission. This matrix asks whether a gesture bar clips the window, and the
    # answer does not depend on what is underneath; a store screenshot asks whether the controls
    # look usable on top of what you are reading, and there the background *is* the subject. So the
    # scene reaches a bare window and `screenshots.py --background-url` is what puts an article
    # under it — which also keeps this table runnable on a CI emulator that has no browser to hand
    # a `VIEW` intent to.
    #
    # Note what a capture can and cannot show either way: the shade's overlay *is* in a screencap
    # (R5, ×0.929 at dim 72), the backlight fall from 500 nits to 2 is not (`DOD.md` §"The
    # shade-down screenshot closes as a refusal"). This frame understates the product however it is
    # staged.
    #
    # **The panel is not in this harness and cannot be.** It is a `WindowManager` window with no
    # Activity under it, and this driver walks activities; its size bound is held by `PanelWidthTest`
    # on the JVM instead, which is the trade ADR-0011 makes explicit.
    Scene("compact-controls", "floating", [("start_controls", "")]),
]


# --------------------------------------------------------------------------------------------
# Needles in another language
# --------------------------------------------------------------------------------------------

RES_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

# The debug build's own resource overlay. It is not decoration: `9k` moved the debug section's
# thirteen strings out of `app/src/main/res` so they would stop being translated into nine languages,
# and two of their labels are needles this driver taps by name. The driver only ever drives the debug
# build, so its English table is main *plus* this — see [load_strings].
DEBUG_RES_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "debug" / "res"

# **The needles that belong to the driver rather than to a scene**, and the reason they are named
# here: [scene_needles] can only see what is in [SCENES], so a literal buried in a function is one a
# locale run does not translate — and the first Polish run failed on exactly that, in
# [reset_to_seeded], which is the step every cell starts with.
# A dialog that greets every launch and must be dismissed before a scene can be walked. The
# template has none; leave it as None until yours does, and see `keeps_watch_prompt`.
WATCH_CLOSE: "str | None" = None

def require_bcp47(tag: str) -> None:
    """Refuse the resource spelling of a locale, wherever it is handed in.

    `cmd locale` takes a BCP-47 tag and does not validate it: `pt-rBR` is accepted and comes back
    from `get-app-locales` as `[rbr]`, so the app runs in English while the needle table is
    Portuguese — a run that reports on a language it never displayed. That is worth a sentence
    rather than the `values-pt-rrBR` file-not-found it would otherwise become.
    """
    if "-r" in tag:
        raise SystemExit(
            f"--locale takes a BCP-47 tag, not a resource qualifier: {tag!r} is how "
            f"`values-{tag}/` is spelled. Pass {tag.replace('-r', '-', 1)!r}.",
        )


def resource_qualifier(tag: str) -> str:
    """The `values-` qualifier for a BCP-47 tag: `pl` → `pl`, `pt-BR` → `pt-rBR`.

    Two spellings of one locale, which `TranslationTest.qualifier` already converts between on the
    Kotlin side and this driver did not — `--locale pt-BR` died looking for `values-pt-BR`, and the
    obvious workaround was worse — see [require_bcp47], which is why this is never handed one.
    """
    require_bcp47(tag)
    language, _, region = tag.partition("-")
    return f"{language}-r{region}" if region else language


def load_strings(locale: str | None) -> dict[str, str]:
    """`name -> value` for `values/` or `values-<qualifier>/`, keyed by BCP-47 tag.

    The escapes matter and the markup does not: `uiautomator` reports what is *on screen*, so `\\'`
    is an apostrophe by the time a node carries it, while `&amp;` has already been resolved by the
    XML parser. `itertext` rather than `.text` so a value wrapped in inline markup still comes back
    whole.
    """
    # `en` is the base, not a qualified variant: the English strings live in `values/` and there is
    # no `values-en/` to find. It is still a shipped locale in `locales_config.xml`, so `--locale en`
    # is a thing to ask for — it pins the app to English rather than inheriting a Polish phone — and
    # without this line it died on a missing file before the first tap.
    def read(path: Path) -> dict[str, str]:
        found: dict[str, str] = {}
        for element in ElementTree.parse(path).getroot().findall("string"):
            name = element.get("name")
            if name is None:
                continue
            found[name] = "".join(element.itertext()).replace("\\'", "'").replace('\\"', '"')
        return found

    base = locale is None or locale == "en"
    if not base:
        return read(RES_DIR / f"values-{resource_qualifier(locale)}" / "strings.xml")
    # The debug overlay layered on top, because the debug build is the only one this driver runs and
    # `values/` alone no longer describes it: `9k` moved the debug section's strings to
    # `src/debug/res` and out of the translation gate. Without this the two labels `seed()` and the
    # `reminders-sheet` scenes tap would be unknown resources, and [resolve_needles] would fall to
    # its substring case and pick whichever *other* string happened to contain the words.
    return read(RES_DIR / "values" / "strings.xml") | read(DEBUG_RES_DIR / "values" / "strings.xml")


def scene_needles() -> set[str]:
    """Every string this driver will look for on screen, across all suites."""
    # `WATCH_CLOSE` is None in an app with no greeting dialog, and a None in this set is a
    # `TypeError` in the `sorted()` one caller down — a locale run that dies before its first tap.
    needles = {needle for needle in (TAB_BAR, HOME_TAB, WATCH_CLOSE) if needle}
    for scene in SCENES:
        needles.update(arg for kind, arg in scene.steps if kind in ("tap", "tap?", "tap_text"))
    return needles


def resolve_needles(locale: str) -> None:
    """Translate the whole needle table **once, before the first tap** (ADR-0013).

    `--locale` has switched the app for a while; what has never worked is everything after it,
    because the needles are English string literals and a needle like `tap("Settings")` matches nothing
    in Polish. ADR-0013 is what makes the fix small — every user-visible string is a resource in
    every locale, and `PolishTranslationTest` keeps them level — so a needle can resolve *through
    the resource name*.

    Three cases, in order:

    - **an exact match on an English value** → the locale's value for that resource;
    - **the unique resource whose value *contains* the needle** → the locale's *whole* value, which
      still matches because `find` is a substring match against the node's label. That is what
      carries deliberate fragments like `"What you noticed"`;
    - **no match at all** → the literal, unchanged. Sample data is identical in every locale, so
      a row's own name wants exactly this. It is also where a typo lands, which is
      why they are listed rather than passed over.

    **Resolved up front rather than at tap time**, because a run is ~40 s per scene per cell: the
    difference between failing early and failing late is the difference between a minute and an
    evening. An **ambiguous** needle — one whose candidates disagree about the translation — is
    fatal here for the same reason. Picking one would be a coin flip, and the fix is to lengthen the
    needle until it names one thing, which improves the English table too.
    """
    english = load_strings(None)
    translated = load_strings(locale)

    resolved: dict[str, str] = {}
    literals: list[str] = []
    ambiguous: list[str] = []
    by_substring = 0

    for needle in sorted(scene_needles()):
        folded = needle.casefold()
        names = [name for name, value in english.items() if value.casefold() == folded]
        exact = bool(names)
        if not names:
            names = [name for name, value in english.items() if folded in value.casefold()]
        # A resource the target locale does not carry is deliberately invariant — `app_name` is
        # `translatable="false"` on purpose (ADR-0013) — so its English text is already the right
        # needle and its absence is not a gap.
        candidates = {translated[name] for name in names if name in translated}
        if not candidates:
            literals.append(needle)
        elif len(candidates) > 1:
            ambiguous.append(f"    {needle!r} could be any of {sorted(candidates)}")
        else:
            resolved[needle] = candidates.pop()
            by_substring += 0 if exact else 1

    if ambiguous:
        raise SystemExit(
            f"{len(ambiguous)} needle(s) do not name one string in {locale!r}:\n"
            + "\n".join(ambiguous)
            + "\n  Lengthen the needle until it does — a coin flip here is a scene that shoots the "
            "wrong screen.",
        )

    _TRANSLATED.update(resolved)
    print(
        f"needles: {len(resolved)} translated to {locale} "
        f"({by_substring} by substring), {len(literals)} left literal: {', '.join(literals)}",
    )


# --------------------------------------------------------------------------------------------
# The check
# --------------------------------------------------------------------------------------------

# A node the app owns that carries a label or takes a tap. Backgrounds are not semantics nodes, so
# anything reported here is content, and content is what must not sit under a system bar.
def content_nodes(nodes: list[Node]) -> list[Node]:
    return [
        node
        for node in nodes
        if node.package == PACKAGE
        and node.bounds.area > 0
        and (node.text or node.desc or node.clickable)
    ]


def check(nodes: list[Node], insets: dict[str, Rect]) -> list[dict]:
    """Every app-owned label or control that lands inside a system inset, in two tiers.

    The tiers exist because the rectangles Compose publishes to accessibility are **touch** bounds,
    not drawn bounds: `Modifier.minimumInteractiveComponentSize` grows a small control's hit area to
    48dp, and the result is not clipped to the scroll viewport it lives in. So an unlabelled
    clickable node poking into the navigation bar is routinely an artifact of that expansion, while
    a node carrying *text* is something a person can actually read in the wrong place.

    - `drawn`: the node has a label, so there is something legible under a system bar.
    - `touch`: the node has none, so this is a hit area, and only its at-rest position tells you
      whether it is a defect — which is what the `-bottom` scenes are for.
    """
    findings = []
    for node in content_nodes(nodes):
        for kind, rect in insets.items():
            overlap = node.bounds.overlap(rect)
            if overlap is None or overlap.area == 0:
                continue
            # A modal scrim swallows a system bar whole — covering everything is what makes it
            # modal, and the dismiss target is *supposed* to reach under the bars. Content never
            # does: a row that runs under the navigation bar overlaps part of it, not all of it.
            # Matched on that shape rather than on the scrim's label, which is a translated
            # Material string ("Close sheet") and would stop matching in Polish.
            if overlap == rect:
                continue
            findings.append(
                {
                    "tier": "drawn" if (node.text or node.desc) else "touch",
                    "inset": kind,
                    "label": node.label[:60],
                    "class": node.cls.rsplit(".", 1)[-1],
                    "clickable": node.clickable,
                    "bounds": node.bounds.as_list(),
                    "overlap": overlap.as_list(),
                    "overlap_px": overlap.area,
                }
            )
    return findings


def reach_scene(scene: Scene) -> str | None:
    """Walk to one scene from a cold start. Returns the failure message, or None if it was reached.

    Split out of [run_scene] so `screenshots.py` can reuse the walk without the inset checking that
    follows it. The tap sequences in [SCENES] are this file's expensive asset — a second copy of
    them in another script is a copy that drifts, and the drift is silent because both scripts would
    still produce a screenshot of *something*.
    """
    relaunch()
    try:
        if not scene.keeps_watch_prompt:
            # The prompt is hosted above the shell and so composes a beat after it; asking before
            # that is asking too early, and an optional tap does not wait around to be told no.
            settle(1.2)
            if WATCH_CLOSE is not None:
                tap(WATCH_CLOSE, optional=True)
            # After the prompt, never before: the prompt sits over whatever route was restored, and
            # closing it first means [return_to_home] reads the screen underneath rather than a
            # dialog's window.
            #
            # **`full` only, and that is a rule about which suites can be lost rather than a
            # shortcut.** Every `empty` scene opens with its own `wipe`, which clears saved state
            # outright and lands on the wizard — where there is no tab bar and this would fail every
            # scene in the suite. It isolates itself; only `full` inherits.
            if scene.suite == "full":
                return_to_home()
        # `keeps_watch_prompt` scenes get neither step, deliberately. **Back can be a destructive
        # answer to a dialog** — where a dialog's dismiss handler is also its "no", a driver that
        # backed its way home would destroy the state the seed set up, for this cell and every cell
        # after it. Scenes that must not be backed out of run first in each cell, directly after the
        # `pm clear` inside [reset_to_seeded], so there is no saved state to restore anyway.
        for kind, arg in scene.steps:
            STEP_RUNNERS[kind](arg)
    except StepFailed as error:
        return str(error)
    return None


def run_scene(scene: Scene, config: Config, out_dir: Path, retries: int = 0) -> dict:
    # **A retry is only honest where the scene puts the phone back itself.** [reach_scene] opens a
    # `full` scene with [return_to_home] and an `empty` one with its own [wipe], so a second attempt
    # starts from a known screen however the first one died — that recovery is what makes retrying a
    # missed tap different from retrying a broken screen.
    #
    # `keeps_watch_prompt` scenes get no retries, and the rule is about correctness rather than
    # caution. They skip both of those steps deliberately (see [reach_scene]), so attempt two would
    # start wherever attempt one stopped; worse, a half-finished attempt may already have answered
    # the expiry prompt, which *deletes the row* — so the retry would shoot a stale screen and
    # record it clean. That is the exact failure retrying is supposed to avoid, arriving by the
    # other road. It costs one scene: `watch-expiry`.
    allowed = 1 + (0 if scene.keeps_watch_prompt else max(retries, 0))
    for attempt in range(1, allowed + 1):
        error = reach_scene(scene)
        if error is None:
            break
        if attempt < allowed:
            print(f"  {scene.name:28s} retrying ({attempt}/{allowed - 1})  {error[:70]}")
    if error is not None:
        result = {"scene": scene.name, "family": scene.family, "error": error}
        if attempt > 1:
            result["attempts"] = attempt
        return result

    settle(0.8)
    shot = out_dir / f"{scene.name}.png"
    shot.write_bytes(adb("exec-out", "screencap", "-p", binary=True))

    insets = read_insets()
    # Every scene this app draws has both bars, so a missing one is the driver or the device failing
    # to show it, and the scene would otherwise pass for having been checked against less than the
    # screen. CI's emulators did exactly that for weeks: no bars on ATD, no navigation bar on a
    # profile with hardware keys (`ci.yml`).
    missing = [kind for kind in ("statusBars", "navigationBars") if kind not in insets]
    if missing:
        error = f"no {' or '.join(missing)} inset could be read"
        return {"scene": scene.name, "family": scene.family, "error": error}
    findings = check(dump_ui(), insets)
    result = {
        "scene": scene.name,
        "family": scene.family,
        "note": scene.note,
        "screenshot": str(shot.relative_to(out_dir.parent)),
        "insets": {kind: rect.as_list() for kind, rect in insets.items()},
        "findings": findings,
    }
    # Only when it is news. A report where every scene carries `"attempts": 1` is a report where the
    # one scene that needed two is no easier to find than it was without the field.
    if attempt > 1:
        result["attempts"] = attempt
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="directory for screenshots and the report")
    parser.add_argument("--config", help="comma-separated config names, default all")
    parser.add_argument("--scene", help="comma-separated scene names, default all in the suite")
    parser.add_argument(
        "--suite",
        default="full",
        choices=["full", "empty"],
        help="'full' walks the app's screens; 'empty' WIPES it to reach the permission explainer",
    )
    parser.add_argument(
        "--locale",
        help=(
            "BCP-47 tag pinned as the app's language, e.g. pl. The scene needles are translated "
            "through the resource names before the first tap; default leaves the device alone"
        ),
    )
    parser.add_argument("--restore", action="store_true", help="undo the pinned rotation and nav mode")
    parser.add_argument(
        "--build",
        choices=("debug", "release"),
        default="debug",
        help=(
            "which install to drive. 'release' is the shipped applicationId — the build a store "
            "screenshot must come from, since the developer-only Settings section does not exist "
            "there. It also refuses every wipe, because that install is the user's own app"
        ),
    )
    parser.add_argument(
        "--retry-unreached",
        type=int,
        default=0,
        metavar="N",
        help=(
            "re-walk a scene up to N more times when the driver fails to reach it. For the nightly, "
            "where a missed tap on a slow emulator is the common red and re-running the whole cell "
            "costs half an hour to repair one scene. Default 0: on real hardware a scene that "
            "cannot be reached is a finding, and the run should say so the first time"
        ),
    )
    parser.add_argument(
        "--assert-clean",
        action="store_true",
        help=(
            "exit non-zero if any scene drew a control under a system inset, or if any scene was "
            "SKIPPED by a driver error. For CI, where nobody reads the report unless it is red"
        ),
    )
    args = parser.parse_args()
    select_build(args.build)

    if args.restore:
        if api_level() >= APP_LOCALES_MIN_API:
            set_locale(None)
        restore_device()
        print("rotation, navigation mode and Do Not Disturb handed back to the phone")
        return 0

    if not args.out:
        parser.error("--out is required unless --restore")

    configs = CONFIGS
    if args.config:
        wanted = set(args.config.split(","))
        configs = [config for config in CONFIGS if config.name in wanted]
    configs, unsupported = usable_configs(configs)
    for config, unreachable in unsupported:
        print(f"-- {config.name}: skipped, {unreachable.reason}")
    if not configs:
        # Every requested cell was skipped. With one job per configuration — which is how CI fans
        # this matrix out — that is the *expected* outcome for a leg like API 26's gesture cells,
        # and failing it would make a red leg mean nothing. But only when the device could actually
        # answer: an unreadable navigation mode is not a capability, it is a question that did not
        # come back, and a green there would be a lie about a cell nobody ran.
        if all(unreachable.readable for _, unreachable in unsupported):
            print(f"-- nothing to run: every requested configuration is one this device cannot enter")
            if args.out:
                args.out.mkdir(parents=True, exist_ok=True)
                (args.out / "report.json").write_text(
                    json.dumps(
                        {
                            "configs": [],
                            "device": {
                                "family": device_family(),
                                "api": api_level(),
                                "model": shell("getprop ro.product.model").strip(),
                                "skipped_configs": {c.name: u.reason for c, u in unsupported},
                            },
                        },
                        indent=2,
                    )
                )
            return 0
        parser.error(f"no requested configuration is reachable on API {api_level()}")
    scenes = [scene for scene in SCENES if scene.suite == args.suite]
    if args.scene:
        wanted = set(args.scene.split(","))
        scenes = [scene for scene in SCENES if scene.name in wanted]

    # Before anything touches the phone: a needle that names two strings, or none, is worth knowing
    # about now rather than 40 seconds into a cell (see [resolve_needles]).
    if args.locale:
        if api_level() < APP_LOCALES_MIN_API:
            parser.error(
                f"--locale needs per-app languages, API {APP_LOCALES_MIN_API}+; "
                f"this is API {api_level()}"
            )
        resolve_needles(args.locale)
        set_locale(args.locale)

    report = {
        "configs": [],
        # Downloaded from a CI artifact three weeks later, a report that does not say what it ran on
        # is a list of rectangles nobody can act on.
        "device": {
            "family": device_family(),
            "api": api_level(),
            "model": shell("getprop ro.product.model").strip(),
            "skipped_configs": {config.name: unreachable.reason for config, unreachable in unsupported},
        },
    }
    # Before the run rather than with the first cell's screenshots: the report is written in a
    # `finally`, so a run that dies on its first scene would otherwise lose its own error to a
    # missing directory.
    args.out.mkdir(parents=True, exist_ok=True)
    report_path = args.out / f"report-{args.suite}.json"
    set_dnd(True)
    try:
        run_matrix(report, configs, scenes, args.out, args.suite, report_path, args.retry_unreached)
    finally:
        # First in the block, because it is the one piece of cleanup that is about the *phone*
        # rather than about this run's output.
        set_dnd(False)
        # Write whatever was reached, always. A full matrix is four cells over about two hours on a
        # phone somebody also owns, and writing the report only at the end means an interruption at
        # 95% produces *nothing* — the screenshots survive on disk but the inset findings, which are
        # the point, do not. Twice on 2026-08-12 a run had to be stopped mid-cell and both times the
        # completed cells were lost. `run_matrix` also writes after each config, so the file is
        # complete for every cell that finished.
        write_report(report_path, report)
    print(f"\nreport: {report_path}")
    # **Green after a retry is not the same result as green**, and that difference is the whole risk
    # of retrying at all: a regression that only reproduces one run in three stops reddening
    # anything the moment a retry can paper over it. Every scene that needed a second attempt is
    # named here on every run, asserting or not, so "clean" never quietly absorbs "flaky" — the same
    # reason a SKIPPED scene fails the assert rather than counting as clean.
    flaky = [
        (cell["config"], scene["scene"], scene["attempts"])
        for cell in report["configs"]
        for scene in cell["scenes"]
        if scene.get("attempts", 1) > 1
    ]
    for config_name, scene_name, attempts in flaky:
        print(f"flaky: {config_name}/{scene_name} reached on attempt {attempts}")
    if args.assert_clean:
        drawn, broken = 0, 0
        for cell in report["configs"]:
            for scene in cell["scenes"]:
                if "error" in scene:
                    broken += 1
                else:
                    drawn += sum(1 for hit in scene["findings"] if hit["tier"] == "drawn")
        # `drawn` is the defect; `touch` is a target smaller than the guideline inside an inset,
        # which is a judgement call and deliberately not a build breaker. A SKIPPED scene fails too:
        # a driver that could not reach a screen has not shown it to be clean.
        if drawn or broken:
            print(f"FAILED: {drawn} control(s) drawn under a system inset, {broken} scene(s) unreached")
            return 1
        print("clean: nothing drawn under a system inset")
    return 0


def write_report(report_path: Path, report: dict) -> None:
    """Merge this invocation's cells into whatever the file already holds, keyed by scene name.

    **A partial re-shoot must not delete the run it is repairing.** The report was written per
    invocation, so re-running three scenes replaced a whole matrix with those three — which happened
    to the Polish run on 2026-08-15 and was rebuilt from the directory by hand, with a note that it
    was worth folding in here if it ever happened twice. It happened twice: the 2026-08-16 English
    matrix left seven landscape cells to redo after a driver fix, against 285 that stood.

    Replaced by name and never removed, so the merge cannot lose a cell. The cost of that choice is
    that a *renamed* scene leaves its old entry behind — the directory of screenshots is the truth,
    and a reader comparing the two will find the orphan rather than a silently shortened run.
    """
    merged: dict = {"configs": []}
    if report_path.exists():
        try:
            merged = json.loads(report_path.read_text())
        except json.JSONDecodeError:
            # A run killed mid-write leaves a truncated file, and refusing to start because of it
            # would be the wrong way round: this invocation's cells are the ones in hand.
            merged = {"configs": []}
    by_name = {config["config"]: config for config in merged.setdefault("configs", [])}
    for config in report["configs"]:
        existing = by_name.get(config["config"])
        if existing is None:
            merged["configs"].append(config)
            by_name[config["config"]] = config
            continue
        scenes = {scene["scene"]: scene for scene in existing.get("scenes", [])}
        for scene in config.get("scenes", []):
            scenes[scene["scene"]] = scene
        existing.update({key: value for key, value in config.items() if key != "scenes"})
        existing["scenes"] = list(scenes.values())
    report_path.write_text(json.dumps(merged, indent=2))


def run_matrix(
    report: dict,
    configs: list[Config],
    scenes: list[Scene],
    out: Path,
    suite: str,
    report_path: Path | None = None,
    retries: int = 0,
) -> None:
    # `keeps_watch_prompt` scenes go first, exactly as they do in `screenshots.py`, and for the same
    # reason: the seed leaves one expired watch and every other scene opens by tapping `Close it`,
    # which *deletes the row* (WatchExpiry.kt — "close, dismiss and swipe-away are one action"). In
    # declared order `home` runs ~20 scenes ahead of `watch-expiry`, so the prompt is gone by then
    # and the shot is a plain Home screen under a dialog's name. Sorting is stable, so every other
    # scene keeps the order it is written in — which the inset findings are read against.
    #
    # Grouped by seed first, so a variant costs one reseed per cell instead of one per scene, and
    # every default-seed scene runs before any variant touches the install. "" sorts before every
    # variant name, which is what puts them in that order.
    scenes = sorted(scenes, key=lambda scene: (scene.seed, not scene.keeps_watch_prompt))
    for config in configs:
        # Seed *before* pinning the config, never after. Sorting fixes the watch prompt in the first
        # cell only — answering it is permanent, so cell 1 eats the one expired watch the seed
        # leaves and cells 2-4 would shoot a stale screen however they are ordered. But the seed
        # starts with a `pm clear`, and a wipe costs the rotation (see [wipe]), so seeding after
        # `apply_config` silently unpins every landscape cell. `full` only: `empty` wipes on purpose
        # to reach the wizard.
        if suite == "full":
            # Invalidated rather than compared: a cell must reseed even when the last one left the
            # right variant on the phone, because answering the watch-expiry prompt is permanent and
            # only a fresh seed brings it back. [ensure_seed] then does the work, and asking for the
            # first scene's seed rather than the plain one keeps a variant-only run to one reseed.
            invalidate_seed()
            ensure_seed(scenes[0].seed if scenes else "")
        apply_config(config)
        out_dir = out / config.name
        out_dir.mkdir(parents=True, exist_ok=True)
        # The app has to be in front before the insets mean anything: `user_rotation` only rotates
        # an app that permits it, and the launcher is portrait-locked, so reading them with the
        # launcher on screen reports portrait geometry for a landscape cell. Each scene re-reads
        # them for itself; this is only so the header is not a lie.
        relaunch()
        insets = read_insets()
        print(f"\n=== {config.name}  insets={ {k: v.as_list() for k, v in insets.items()} }")
        results = []
        for scene in scenes:
            # A no-op for every scene but the first of each seed group — see [ensure_seed]. A wipe
            # costs the rotation, which is why it may run here at all: [wipe] re-pins what
            # `apply_config` set, so a mid-cell reseed cannot silently turn a landscape cell into a
            # second portrait one.
            if suite == "full":
                ensure_seed(scene.seed)
            result = run_scene(scene, config, out_dir, retries)
            results.append(result)
            again = f"  (attempt {result['attempts']})" if result.get("attempts", 1) > 1 else ""
            if "error" in result:
                print(f"  {scene.name:28s} SKIPPED  {result['error'][:90]}{again}")
            else:
                hits = result["findings"]
                drawn = [hit for hit in hits if hit["tier"] == "drawn"]
                touch = [hit for hit in hits if hit["tier"] == "touch"]
                mark = "clean" if not hits else f"drawn={len(drawn)} touch={len(touch)} " + str(
                    sorted({hit["inset"] for hit in hits})
                )
                print(f"  {scene.name:28s} {mark}{again}")
        report["configs"].append(
            {
                "config": config.name,
                "insets": {kind: rect.as_list() for kind, rect in insets.items()},
                "scenes": results,
            }
        )
        # Land each cell as it finishes rather than banking four of them against a clean exit.
        if report_path is not None:
            write_report(report_path, report)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as exc:
        # `CalledProcessError.__str__` is the command and the exit status and nothing else, so the
        # device's own explanation — the part that says *why* — is captured and then thrown away.
        # That is how the first nightly matrix arrived: two red cells reporting `exit status 1` and
        # `exit status 255`, neither carrying the one line that would have named the cause.
        detail = (exc.stderr or b"").decode("utf-8", "replace").strip()
        if detail:
            print(f"\n-- the device said: {detail}", file=sys.stderr)
        raise
