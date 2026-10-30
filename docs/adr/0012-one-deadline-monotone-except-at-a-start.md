# There is one deadline, and it may only move outward at a start

## Context

`off_at_millis` is a single key: *the one instant at which the shade next comes down, whoever set it*
(`CONTEXT.md`, written before this decision existed). Until Phase 4 it had one writer — auto-off, on
a shade the user started by hand — so nothing had to be resolved.

Phase 4 gives it a second source with its own opinion. A schedule is a pair of times, and a window
that is open is a promise that the shade comes down at its far end. That is a deadline the user set
just as deliberately as a chip tap, arriving from a receiver rather than from a screen, and
`PLAN.md` calls resolving the two *"the exact failure being designed out"*.

**Two failures are available here and only one of them is a safety property.**

- **A deadline silently lost.** The user is promised 23:00, something overwrites it with 07:00, and
  the shade is up all night. This is the failure, in the app's own words: *"I hate when other apps
  have no auto disable and the next day I cannot see anything on my phone."*
- **A deadline silently shortened.** The user expects 07:00 and gets 23:00. Annoying, visible,
  self-correcting — the Start button is right there — and it errs toward light.

The rule below errs toward the second every time, which is the direction ADR-0010's two bounds err,
the direction `BootReceiver`'s three refusals err, and the direction the notification permission went
in Phase 2.

**The word that turns out to be load-bearing is *silently*.** A schedule adopting a hand-started
shade at its on-instant replaces 23:00 with 07:00 — the exact sequence of writes named as the
failure — and it is correct, because the user asked for it when they enabled the schedule. What
separates the two is not the arithmetic, which is identical, but whether the person can watch it
happen.

## Decision

> **`off_at_millis` is the earliest of the deadlines live at the moment it is written. It is
> *resolved* at exactly two moments — the user's hand, and the schedule's on-instant — and every
> other writer may only bring it forward.**

Monotone except at a start. Four writers exist and the asymmetry between them *is* the invariant:

| Writer | When | May it move the deadline later? |
| --- | --- | --- |
| The hand — Start, or a chip on a running shade | `beginShadeAt(ByHand)` | **Yes.** An explicit act, and the shade is going up now |
| The schedule's on-instant | `beginShadeAt(BySchedule)` | **Yes**, and only here. The adoption row |
| The schedule edited under a live shade | `tightenToWindow`, from `MainApplication`'s collector | **No.** `min(stored, windowEnd)` |
| A window found already open | the reconcile, which resolves as a start | The marker stops it repeating |

Two candidates, and when each is live:

| Candidate | Live when | Value |
| --- | --- | --- |
| **Auto-off** | The episode is **hand-started** | `now + choice.minutes`, null for `Never` |
| **The window's end** | The shade is up **inside** an enabled window | `windowEnd(now, zone)`, null outside it |

```kotlin
fun deadlineFor(now: Long, zone: ZoneId, start: ShadeStart, autoOff: AutoOff, schedule: Schedule): Long?
```

Three things make the rule checkable rather than merely stated:

- **`ShadeStart` and `ShadeEnd` are enums, not booleans.** Why a shade goes up decides whether
  auto-off is a candidate; why it comes down decides whether the night is spent, which is what stops
  the reconcile from putting a stopped shade back at the next process start. `ShadeEnd.Reaped` — the
  boot receiver clearing a deadline that passed while the phone was off — is the only ending that
  spends nothing, because nobody decided anything.
- **Phase 2's two-argument `deadlineFor` is renamed rather than overloaded.** It survives as
  `autoOffDeadline(startedAt, choice)`, `internal` to the package, with its test rows intact. An
  overload here is not a convenience: a later call site takes the shorter signature, compiles, passes
  every test, and quietly ships a shade the schedule cannot bound.
- **The composition lives in `shade/Deadlines.kt`, not in `AppPreferences`.** Which deadline wins is
  not storage policy.

**The adoption row carries an obligation, and it is discharged rather than promised.** Because it is
the one place a deadline moves outward, the extension has to be visible at the moment it happens: the
ongoing notification's sub-text names the new instant. Measured twice rather than asserted: on the
API-33 emulator, a shade hand-started at 15:05:14 under a 30-minute auto-off, adopted at
15:13:13.611, the notification reading **"Do 15:45"** at that moment (`phase-4.md` R8); and on the
phone, where a 20:32 deadline became 21:00 at the on-instant and the sub-text went from **"Until
20:32"** to **"Until 21:00"** with it (R6). The phone is the half that matters, because the
notification is the one surface still legible under the shade.

## Alternatives

**Last writer wins.** What the code does by accident, and the reason this ADR exists at all: no
branch anywhere expresses it, so nobody reviewing a future writer would see it being chosen. It
loses on the named failure — the 22:00 on-instant overwrites a 23:00 hand-set deadline with 07:00 and
the night is gone.

**Auto-off applies to every episode, scheduled ones included.** Superficially the simplest rule —
one deadline source, no `ShadeStart` — and it loses to the two-hour default silently truncating every
scheduled night, which is the schedule appearing broken in exactly the way that produces support
mail. `CONTEXT.md` had already decided it: *auto-off* reads "the duration a **hand-started** shade
lasts".

**The schedule always wins while a window is open.** Loses in the other direction: a shade started by
hand at 23:00 with a thirty-minute auto-off would run to 07:00, because the window's end replaced a
promise the user made more recently and more specifically.

**Two writers, earliest wins.** This document's own first draft, and it did not survive contact with
the phase's section 6. An edit to the schedule under a live shade and a window found already open both have to touch the
key, and neither is a start — so "earliest wins" either forbids the legitimate adoption or licenses
an edit to push the deadline out. The asymmetry is what replaced it, and it is both stronger and more
testable: *outside two named moments, forward only.*

**A second key, one deadline per source, resolved at read time.** It removes the write-ordering
problem and adds a worse one: every reader of "when does the shade come down" must then know about
both keys and their precedence, so the invariant has no single place to be enforced or tested — and
`ShadeService`, the notification, `BootReceiver` and the screen are four readers that would each have
to get it right. One key with a rule in front of it is the version a sweep can assert.

## Consequences

- **A sweep, not an argument.** `DeadlineTest` asserts the rule across every auto-off choice, both
  start reasons, a day of minutes and a year of a DST zone, with the one unbounded configuration —
  `Never`, no schedule — named as the only one allowed to produce no deadline.
- **Scheduled-off adds no alarm and no new reader that decides.** The far end of the window is taken
  down by the deadline loop that was already there, which is only possible because the window's end
  *is* the deadline rather than a second thing to keep in step with it. A new reader deciding when
  the shade comes down would be the first sign this decision is being worked around rather than
  followed.
- **The next writer is the risk this ADR exists for.** A snooze, a per-day schedule or a "keep it on
  until I say" control all want to touch this key, and each one is a chance to reintroduce
  last-writer-wins by accident. The question to ask of any of them is the only question here: *is
  this a start?* If not, it may only bring the deadline forward.
- **Adoption is now a documented user-visible behaviour**, so a change to it is a change to a
  promise, not an implementation detail — and the notification's sub-text is part of the decision
  rather than decoration.
- **What is still open** is not the rule but a taste inside it: whether a hand-started episode ending
  *inside* a window should spend that night. `DOD.md` carries it as a rule-5 question for the twelve
  testers, phrased as one question about both edges of an episode rather than two.

Amendment, 2026-10-30. **A pause is not a writer, and ending one is not a start.** The snooze the
Consequences named arrived as a pause: the shade cleared and the backlight released for a few
minutes, then both restored. It answers *is this a start?* with *no* and so touches nothing here.
Pausing writes neither `shade_running` nor `off_at_millis`, and ending a pause, by the clock or by
*Resume now*, writes neither either: it re-applies the stored dim level to a service that never
stopped. The deadline loop keeps running through the pause, so a deadline that falls inside one
takes the shade down as it would have anyway, and nothing comes back. `pauseEnd` in
`shade/Pause.kt` is the one place that compares the two, read by the service, the notification and
the controls, and `PauseTest` sweeps every length against deadlines either side of the pause's end.
The pause itself lives in the service's memory and never in DataStore, so ADR-0005's backup question
does not arise: a ROM kill ends it early, which errs toward the shade, and a restore cannot carry one.
