# `minSdk` is 33, because the branches below it cost more than the devices they reach

## Context

`minSdk` 26 was inherited from the template, not chosen for Gloam. It looked free. It is not, and the
reason is the test rig rather than the code.

There is **one** device in the loop — a Redmi `amethyst` on HyperOS, Android 16, **API 36** — and no
emulator on the machine at all. That device sits at the *top* of the supported range, so every
platform difference the app depends on lives on the unvalidated side of it:

- `ShadeService` branches three ways on `layoutInDisplayCutoutMode` — `ALWAYS` at 30+, `SHORT_EDGES`
  at 28+, nothing below. **The bottom branch has never executed anywhere.**
- `POST_NOTIFICATIONS` is a runtime ask on 33+ and install-granted below it. The shade's ongoing
  notification is the documented way out of a very dark screen, so the app's central safety property
  has two behaviours and the phone can only show one of them.
- `TYPE_APPLICATION_OVERLAY` on API 26–27 predates three rounds of overlay tightening.

Walking up the ladder, what each step actually deletes *from this app*:

| `minSdk` | What it removes | Devices reachable |
| --- | --- | --- |
| 26 | — | ~99% |
| 30 | The three-way cutout branch collapses to one line; `<queries>` behaves uniformly | ~85–90% |
| **33** | `POST_NOTIFICATIONS` becomes one code path; ADR-0004's below-13 locale backport becomes dead weight | ~70% |
| 34 | Foreground-service types uniform — already declared, so nothing | ~55% |
| 35, 36 | **Nothing this app uses** | ~24% |

Android 16 was at ~24% of devices in August 2026 (AppBrain; StatCounter agreed within a point).

## Decision

**`minSdk` 33.** The phone at API 36 stays the primary target and the only place nits are measured;
**one API-33 AVD** covers the rest of the range, as a "does it launch, does the window appear, does
the permission flow work" pass at the end of each phase rather than as a test suite.

## Alternatives

**Hold at 26.** Reaches ~99% of devices and keeps every branch. It loses because those branches are
not merely untested — they are *untestable* in this setup, and shipping a safety property whose
alternate path has never run once is worse than not reaching the phones it was for.

**`minSdk` 36.** Considered because it would collapse the matrix to the one device that exists. It
loses badly: it costs roughly three phones in four and buys **nothing over 33** that this app uses.
Paying 46 points of reach for zero simplification is the worst trade on the table.

**`minSdk` 30.** The honest runner-up — ~85–90% reach, and it does kill the cutout branch. It loses
because the branch that matters is the notification permission, and 30 leaves that one in place.

## Consequences

- Roughly 30% of Android devices cannot install Gloam. That is the price, stated plainly.
- **The direction is asymmetric and this is why the decision is made now:** raising `minSdk` after
  launch strands existing installs on the last build that fitted them; lowering it later is free. It
  is one of the few decisions here that is genuinely cheaper to make before users than with them.
- The `layoutInDisplayCutoutMode` branches below `R` are dead and are removed with this change.
- **ADR-0004's below-13 locale backport is now dead weight** — the disabled
  `AppLocalesMetadataHolderService` manifest entry exists only for devices this app no longer ships
  to. It is *not* removed here: that is ADR-0004's territory and deserves its own reading. Noted in
  `DOD.md` so it cannot be forgotten.
- AppCompat itself stays regardless: ADR-0006 needs `setDefaultNightMode` to move the *configuration*
  so the window background and the system bars agree.

## Amendment, 2026-10-31: `minSdk` is 26 again

**The owner's call, on reach**: roughly 30% of phones could not install a free app whose whole
mechanism is one overlay window. Lowering is the cheap direction this ADR named — nothing installed
is stranded — so the question was only whether the branches below 33 could be made testable, and
they now can be:

- **The CI floor leg is API 26** (`default` image; `aosp_atd` starts at 30), so the instrumented
  window tests run on the oldest platform the app ships to on every pull request, and an API-26 AVD
  boots locally where the API-33 one segfaults.
- **The branches are gathered rather than scattered.** Every window-geometry difference below
  Android 11 lives in `shade/OverlayLayout.kt`, one function per question; the windows carry no
  `SDK_INT` checks of their own. Lint's `NewApi` at `minSdk` 26 found everything else, and each is a
  one-line fallback beside its call.

What older phones get, by level — **the platform's limits rather than choices of ours**:

| Below | What is different |
| --- | --- |
| 13 | No *Add the tile* button — `requestAddTileService` does not exist; the row says to use the tile editor. The language choice is kept by AppCompat (ADR-0004), and reaches activities only, so the notification and tile follow the system language. `POST_NOTIFICATIONS` is install-granted |
| 12 | No window alpha clamp: the shade's cap leaves ≈ 0.4% of a white page's light rather than ≈ 4.7%, and the ramp is shaped for that (measured: window alpha 1.0 on API 26). The fused provider is asked for by name, since its constant is not public |
| 11 | The shade is an explicit square on the display's longest edge rather than `MATCH_PARENT`, which the platform keeps clear of the navigation bar for an overlay (measured: 1794 of 1920 px). With the bar on the left that also moves the overlays' origin past it, so the shade starts a bar's thickness further left and the bubble's area starts at 0 (measured: the shade from 126, a tucked bubble off the display). The panel and prompt drop `FLAG_LAYOUT_IN_SCREEN` to sit clear of the bars. Display size and the bubble's area come from `Display` reads. The backlight override is an integer out of 255, so the floor is one step of it rather than the panel's (`MIN_INT_BACKLIGHT`); `MIN_BACKLIGHT` would truncate to 0, which 9 and 10 read as no override. Backup reads `@xml/backup_rules`, the twin of the extraction rules |
| 10 | The panel reports itself on screen from a post after pre-draw, which can be one frame early |
| 9 | No cutout API: a notch strip may stay undimmed. The backlight floor configs do not exist, so the floor is read as unset. The fused provider reports itself disabled to apps, so location comes from the network provider. `canDrawOverlays` reads stale for about a second after the grant, so the ask re-reads while it is on screen. The backlight override is clamped up to the setting's minimum (measured: 10 of 255) |

The table in *Context* is kept as the record of the argument it was; the cost it priced was
untestability, and that is what changed.
