# Gloam

Take the screen below the brightness Android is willing to give you. A dimming overlay for reading
in the dark, when the lowest system setting is still too bright.

**Every feature is free.** No ads, no server, no account, nothing behind a paywall. If the app
earns it there is a one-off tip — it unlocks nothing, it just says thanks.

## Status

Bootstrapped from [android-starter](https://github.com/maksforge/android-starter), and now a working
proof of concept: it dims the screen. A slider sets a dim level, a foreground service draws the shade
over every other app, and it survives you leaving Gloam — which is the whole point, since the apps
being dimmed are the ones you are reading.

A shade you start by hand **comes down on its own** after a while — thirty minutes to four hours, or
never, and two hours unless you say otherwise — and one that was up when the phone went down **comes
back after a restart**. Both exist because a dimmer you forget about is a dimmer that follows you
into the next morning.

**The controls come to where you are.** Tapping the ongoing notification raises a small panel over
whatever you are reading — the one surface in Gloam that stays readable at full dim, because it sits
*above* the shade instead of under it, so the slider moves the dim over the real content rather than
over Gloam's own screen. The launcher icon can be pointed at a compact version of the same controls,
in Settings.

**And it dims on a schedule** — a pair of clock times, including a window that runs across
midnight, or **sunset to sunrise**
([ADR-0013](docs/adr/0013-the-schedule-can-follow-the-sun-and-its-location-is-estimated-unless-lent.md)).
The sun needs a place, and Gloam estimates one from the phone's time zone unless the user lends it
the phone's approximate location, which is kept on the phone and out of backups.

Getting any schedule to fire costs more than it sounds like on a phone that would rather your app
were not running: the shade comes up from an *inexact* alarm, which Android delivers at the far end
of a window 75% as wide as its futurity, so the app arms a chain of hops toward the on-instant
instead of the instant itself
([ADR-0003](docs/adr/0003-two-scheduling-mechanisms.md)'s third amendment has the measurements). And
because one stored deadline now has four writers, there is a rule in front of it: it is *resolved*
when the hand starts a shade and when the schedule's on-instant arrives, and every other writer may
only bring it forward ([ADR-0012](docs/adr/0012-one-deadline-monotone-except-at-a-start.md)).

The template's data layer is gone ([ADR-0007](docs/adr/0007-gloam-stores-settings-not-records-so-it-has-no-database.md)):
Gloam stores settings, not records, so there is no database. The release pipeline and quality gates
are real. Gloam is on Play in closed testing, not yet in production, and what it stores is written
down in the [privacy policy](docs/privacy-policy.md) rather than here.

## Screenshots

Captured on a Xiaomi/HyperOS device by `scripts/screenshots.py`, off the build that is actually on
Play. Regenerate them rather than replacing them by hand — the walk is the asset, not the pixels.

**Dark only, and that is a property of the app rather than a choice about this page.** Gloam's theme
preference defaults to dark, so `cmd uimode night` stopped being a lever the moment that preference
existed: the light cell shot dark anyway, twice, without failing. The driver now refuses
`--theme light` outright rather than emitting a mislabelled picture.

| Shot | What it shows |
| --- | --- |
| [Compact controls](docs/screenshots/1_compact-controls-en.png) | The controls floating over a Wikipedia article: a vertical dim level track, a start arrow, and round buttons for warmth, a timer and closing. This is the app's actual claim — it works on top of whatever you are reading |
| [Dim](docs/screenshots/2_dim-en.png) | The home screen: the dim level slider, the warmth slider and Start dimming |
| [Settings](docs/screenshots/3_settings-en.png) | Appearance, controls, language and the after-a-restart section |
| [Settings, scrolled](docs/screenshots/4_settings-bottom-en.png) | The language grid, which is also where a release build proves the debug section was compiled out rather than hidden |

Links rather than embedded pictures, deliberately: this repo's `README.md` is guarded against image
embeds, and the same nine-language set is a click away in the listing anyway.

These are Gloam's own screens. **There is deliberately no screenshot of the shade actually down** —
the shade is an overlay owned by a foreground service, so it cannot be reached by tapping through the
app, and a photograph of a dimmed screen is a photograph of a dark rectangle. What the shade does is
the one thing the listing has to describe in words.

## Contributing

Gloam is source-available, not open source: read it, verify it, file issues. Pull requests are not
accepted — see [LICENSE](LICENSE).

## Build

```bash
./gradlew assembleDebug          # build
./gradlew installDebug           # build + install on the connected phone
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests — needs a device
./gradlew spotlessApply          # format (the CI gate is spotlessCheck)
python3 scripts/project.py       # what the toolchain thinks this app is called
```

Commit subjects are [Conventional Commits](https://www.conventionalcommits.org); release-please
derives the version and `CHANGELOG.md` from them. See [docs/RELEASING.md](docs/RELEASING.md).
