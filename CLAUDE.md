# Gloam

A screen dimmer that takes the display below Android's minimum brightness, for people who read in
the dark and find the lowest system setting still too bright. Every feature is free — no ads, no
server, no account, no premium tier; the only payment is an optional one-off tip that unlocks
nothing. That constraint decides the arguments: there is no paid branch to keep alive, so a feature
either ships to everyone or it doesn't ship.

- **Vocabulary:** [`CONTEXT.md`](CONTEXT.md) — use these terms in code and UI.
- **Decisions and why:** [`docs/adr/`](docs/adr/) — read before changing anything they cover.
- **The arc, in phases:** [`docs/PLAN.md`](docs/PLAN.md) — sequence only; a phase being built gets
  its own `docs/phase-N.md`.
- **What's still open:** [`docs/DOD.md`](docs/DOD.md) — the live checklist. **Read this one first.**
- **Commits & releasing:** [`docs/RELEASING.md`](docs/RELEASING.md). Commit subjects **must** be
  [Conventional Commits](https://www.conventionalcommits.org) (`feat:`, `fix:`, `feat!:`, `docs:`, …)
  — a `commit-msg` hook rejects anything else, and release-please derives the version and
  `CHANGELOG.md` from them. `versionName` and `versionCode` are automated; never hand-edit either.
- **How to work here:** invoke the `lean-loop` skill at the start of every session and keep it
  active for the whole session. This repo is written before it is built — plans, ADRs and phase
  documents are long on purpose — so the waste is re-reading what is already in the transcript and
  testing code whose shape has not settled. Lean loop is the sequencing rule that stops both.

Don't restate ADR reasoning here; link to it. This file is loaded every session and must stay short.

## Working with the person who owns this repo

Fluent in JavaScript/TypeScript, new to Kotlin. Comment code where a Kotlin/Compose idiom has no
direct JS analogue — `Flow` vs promises/observables, `suspend` vs `async`, `remember` /
`derivedStateOf` vs `useMemo`, data class `copy()` vs object spread, sealed classes vs discriminated
unions, `by` delegation vs a getter. Explain the *why*, don't restate the line. Prefer explicit and
readable over clever.

The Android **platform** is the other half of the gap and has no JS analogue at all: the manifest as
a declaration the OS reads before any code runs, permission models that differ per API level, process
death, and vendor ROMs overriding documented behaviour. Say when a constraint comes from the platform
rather than from this codebase — that is the difference between "we chose this" and "Android will not
let us do otherwise".

## Stack

| Choice | Note |
| --- | --- |
| Kotlin, Jetpack Compose, Material 3 | Android only, `minSdk` 33 (ADR-0008) |
| **Navigation 3** (`androidx.navigation3`) | Replaces Navigation Compose 2.x — don't reintroduce the old one |
| **DataStore only — there is no database** | Gloam stores settings, not records. See *Storage* below |
| Manual DI via `AppContainer`, **not Hilt** | Constructor injection is clearer at this size and easy to migrate later |
| A foreground service owning one overlay window | `shade/ShadeService.kt` — the app's whole mechanism |
| On-device storage only | No backend, no account. Platform Auto Backup, no custom agent |

## House rules

- **Storage is DataStore, and there is no database.** Gloam remembers a dim level and whether the
  shade should be running — settings, not records. A new preference is a key, a `Flow` that reads it
  with its default, and a `suspend` setter; keep the default *in the read* rather than writing it on
  first launch, and there is nothing to migrate, ever.
  **The test is cardinality, not subject matter.** A *fixed set* is not a *list*, however many
  members it has: four schedule values are settings, and seven per-day windows would be fourteen
  settings. What needs a table is the user creating rows nobody knew about at build time.
  **Every key you add is backed up, whether or not you meant it to be.** Auto Backup carries the
  whole store, exclusions are per *file*, and the store is one file — so a key lands on the user's
  next phone with no code of ours involved in the write. Ask what it means there: a setting is
  welcome, live state is not, and live state is judged **at the read** ([ADR-0005](docs/adr/0005-backup-is-automatic-for-the-database-and-manual-for-media.md)'s
  amendments, and `shade_began_at` is what that cost once).
  **If a feature ever needs a list the user creates**, the whole Room apparatus comes back with it —
  `scripts/schema-gate.py`, ADR-0001 and this rule's previous wording are all still in git history
  for that. Adding a table is a deliberate act with a migration story attached, not a convenience.
- **The shade must never trap the user.** It is a window over every other app, so two things are
  load-bearing and neither is obvious: the window carries `FLAG_NOT_TOUCHABLE` and
  `FLAG_NOT_FOCUSABLE` so every touch passes through, and what reaches the eye through it is capped
  below fully opaque. Without the flags the phone appears frozen; without the cap the way out is
  behind the thing you need to get out of. The foreground notification is `ongoing` for the same
  reason — it is the escape hatch, not a courtesy.
  **There are two overlay windows now, and the second one carries the opposite rule.** The **panel**
  (Phase 3b) drops `FLAG_NOT_TOUCHABLE` on purpose — a control the user cannot touch is a picture of
  a control — so what stands in for the flag is the window's *size*, which is computed rather than
  constant and therefore has a test in front of it. Never reason about the panel from the shade's
  rule: [ADR-0011](docs/adr/0011-the-panel-is-touchable-and-what-keeps-it-from-trapping-the-user.md),
  and a `MATCH_PARENT` in its `LayoutParams` is a bug rather than a simplification. The **bubble**
  the controls shrink to is a third touchable window, bounded the same way, by its size and a clamp
  on every drag ([ADR-0016](docs/adr/0016-closing-the-controls-leaves-a-bubble-bounded-by-its-size.md)).
  **The cap belongs to the composite, not to a `View`.** From Phase 1 the shade is a `FrameLayout`
  with two children, black at the dim level and a tint at the warmth, and bounding each child alone
  does not bound the result: black at `MAX_SHADE_ALPHA` still leaves content visible, and a heavy
  amber wash over it is a screen nothing can be read through, with neither child past its own limit.
  **It takes two bounds, not one**: `(1 - shadeAlpha) * (1 - warmthAlpha) ≥ 1 - MAX_SHADE_ALPHA` for
  the signal that survives, and `MAX_WARMTH_ALPHA * relativeLuminance(warmthTint(c)) ≤ 1 -
  MAX_SHADE_ALPHA` for the light the tint *adds* at every warmth colour, which the first cannot see. `MAX_SHADE_ALPHA`,
  `MIN_BACKLIGHT`, `MAX_WARMTH_ALPHA` and `WARMTH_EASE_FROM` are constants rather than preferences;
  the second bound is also why the tint's two ends are constants in `shade/` rather than
  `MaterialTheme` colours; and `ShadeRampTest` sweeps every input against all of it (ADR-0010).
- **A branch merges with every shipped language complete.** Adding an English string does *not*
  redden your build — completeness is a merge gate (`scripts/translation-gate.py`), not a test, so
  copy is translated **once** rather than against draft wording and again after review. Everything
  else about a translation — format arguments, plural categories per CLDR, orphans,
  `translatable="false"` — is `TranslationTest` and holds continuously. Translate from **English
  only**, per [`docs/translator-brief.md`](docs/translator-brief.md).
- **The stored intent and the live service are different questions.** `shadeRunning` in DataStore is
  what the user asked for; whether the service is alive is what the ROM allowed. Xiaomi will kill the
  service without the user changing their mind, so read the preference to decide what *should* be on
  screen and never infer intent from a running process.
- **Say `dim level`, `shade`, `backlight`, `floor` and `warmth` exactly as [`CONTEXT.md`](CONTEXT.md)
  defines them.** Two different mechanisms both look like "brightness" and only one is ours; the
  vocabulary is the only thing keeping them apart in code.
- **Preferences are read as `Flow`**, screens collect them. Don't hand-roll refresh calls.
- One `ViewModel` per screen, UI state as a single immutable data class. A `ViewModel` never holds a
  `Context` — starting the service is the screen's job, because that is what has one.
- **Developer-only surface lives in `app/src/debug/`, never in `main/` behind `BuildConfig.DEBUG`.**
  With `isMinifyEnabled = false` a statically-false branch is still compiled into the release AAB —
  a hide, not a strip — and its strings are still inside the translation gate. The seam is
  `ui/settings/DebugSettings.kt`, one file per variant, with a no-op in `src/release/`.
- **Colours come from `MaterialTheme`, never literals.** The palette is generated from four seeds by
  `scripts/gen_scheme.py`; edit the seeds and regenerate, never a single role.
- **Spacing comes from `theme/Spacing.kt`.** Six steps on a 4dp grid; no screen invents a seventh.

## Commands

```bash
./gradlew assembleDebug          # build
./gradlew installDebug           # build + install on the connected phone
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests — the shade window's flags. Needs a device
./gradlew lint
./gradlew spotlessApply          # format (the CI gate is spotlessCheck)
./gradlew assembleDebug -PreleaseShapedDebug   # debug id, R8 on — find minification bugs early

python3 scripts/project.py                      # what the toolchain thinks this app is called
python3 scripts/translation-gate.py --report    # what translations does this branch still owe?
python3 scripts/translation-gate.py origin/main # the gate itself — would this merge?
python3 scripts/schema-gate.py origin/main
python3 scripts/notes-gate.py
adb devices                                     # confirm the phone is attached
```

## Layout

```
app/src/main/java/<namespace>/
  MainActivity.kt, ControlsActivity.kt, MainApplication.kt, AppContainer.kt, Navigation.kt,
  NavigationKeys.kt
               the app shell and Nav3 wiring stay at the package root, not under ui/ — they describe
               how the app hangs together rather than any one screen. ControlsActivity is the
               compact controls' host: a second host for the same shell, which is why it is here
  data/        AppPreferences.kt — the DataStore keys and their defaults. No database
  shade/       ShadeService.kt, the overlay window and the foreground service that owns it;
               PanelWindow.kt, the *second* window — its flags, its size bound and the Compose host
               it needs because there is no Activity under it;
               OverlayPermission.kt, the SYSTEM_ALERT_WINDOW read and the settings hand-off
  theme/       generated palette, type scale, spacing, the night-mode window half
  tip/         TipJar.kt, the Play Billing client for the tip (ADR-0009) — held by AppContainer
               so a purchase finishes after its screen is gone; TipAsk.kt, when the home screen
               mentions it, once per install
  ui/          Compose screens + ViewModels, one package per area — dim/ is the app's home screen,
               with settings/, support/ and the shared pieces in common/. dim/DimControls.kt is the
               controls themselves, hosted three times: the full screen, the compact activity and
               the panel
  work/        notification channels, the notification-permission ask, Xiaomi battery/autostart

app/src/debug/    the developer-only build: Settings' debug section — the backlight sweep, the
                  two-minute deadline, a second overlay window, summons for the compact
                  controls and the panel, and the timed reading test for the warmth colour.
                  None of it is reachable from outside the app
app/src/release/  only the no-op half of that seam, so main/ can call it unconditionally
scripts/          the Python toolchain. project.py is the one place the app's identity lives
art/             mark.py is the identity; both generators derive from it
```

## Versions

Pinned in `gradle/libs.versions.toml`: **AGP 9.0.1, Kotlin 2.3.20, Gradle 9.1.0, Compose BOM
2026.03.01**. `compileSdk`/`targetSdk` 36, `minSdk` 33, JDK 21 toolchain.

`compileSdk` stays at **36**: it is the combination AGP 9.0.1 validates against. Bump deliberately,
with a build to prove it.

## Environment notes

**No Android Studio — everything is CLI**, `./gradlew` against a physical Xiaomi phone over USB.
JDK 21, `ANDROID_HOME=~/Android/Sdk`, Python 3.14 for `scripts/`. No emulator is in the loop, so
anything device-shaped needs the phone plugged in — `adb devices` before assuming it is.

- **Vendor ROM behaviour.** The phone is Xiaomi/HyperOS, so everything below is what this device
  actually does rather than a hypothetical. Split-APK installs (`connectedAndroidTest`) prompt on
  the phone and a missed prompt fails the run with `INSTALL_FAILED_USER_RESTRICTED`; the workaround
  is to install both APKs plain and run the instrumentation directly:
  ```bash
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w <applicationId>.debug.test/androidx.test.runner.AndroidJUnitRunner
  ```
  **A brand-new `applicationId` is a different failure with the same error string**: HyperOS refuses
  the *first* install of a package outright, with no dialog to miss. Either enable USB installation
  in developer options, or `adb push` the APK to `/sdcard/Download/` and tap it in the file manager.
- **Xiaomi kills background work aggressively.** Scheduled notifications need battery-optimisation
  exemption and **autostart**, and autostart is the load-bearing one: without it the ROM does not
  start the process for a broadcast at all. It has no appop, it is a Settings toggle, and the grant
  **lapses on its own** — re-read it before any run that depends on it. `scripts/device-gate.py`
  reads and sets it.
- **Two device readings look identical to a pass when they are nothing at all.** A database pulled
  without its `-wal` sidecar is **stale** — the app's most recent writes sit in the WAL, so a deleted
  row still reads as present. Pull both and `PRAGMA wal_checkpoint(TRUNCATE)` on the host. And
  `cmd jobscheduler run -f -n androidx.work.systemjobscheduler <pkg> <id>` answers *"Could not find
  job N"* on stderr and **exits 0**: `am force-stop` cancels the app's jobs and WorkManager
  re-enqueues under a new id at the next launch, so read the id from `dumpsys jobscheduler` and
  confirm `WM-WorkerWrapper: Starting work for …` in logcat before believing any sweep result.
