# Definition of done — what is still open

The **live checklist**. Keep it short: when an item closes, tick it, write the *result* into whatever
long-form record you keep, and delete the detail from here. A session should be able to pick up the
work by reading this file alone.

Phases and sequence live in [`PLAN.md`](PLAN.md). This file is the worklist.

**Phase 1 is closed, and its record is [`phase-1.md`](phase-1.md)** — the entry gate, the ramp,
warmth and the readings, as five checkpoints. **A, B, C and D shipped on 2026-08-30, E and the
API-33 pass on 2026-08-31**: the dim level drives the backlight and then the shade, amber is tinted
over the top of it, `MIN_BACKLIGHT` was `0.01f` — **6.64 nits**, set by R2, and since ADR-0010's
fifth amendment the panel's floor, **2.0 nits** — and the whole ramp is a
pure function proven by a table sweep rather than by a screen. B could have vetoed the backlight half
and did not; **ADR-0010's third amendment** says why, and withdraws the reading that had made a veto
look likely — the panel was in its own inactivity dimming, not at the user's setting.

**Two of Phase 1's readings were taken for later phases and are theirs to read**, both written up in
`phase-1.md`'s readings block:

- **2b.** The notification's *Stop* action works, but HyperOS hides it in the long-press overlay
  rather than the collapsed row — and a plain tap lands on the app's own Stop button, which at
  maximum dim sits *under the shade* at 0.33 nits. Recorded rather than worked around, because it is
  vendor behaviour (stock Android expands the top notification and shows the action) and copy naming
  a gesture would be wrong on most devices. It is what turns **2b's Quick Settings tile from a nicety
  into the one-gesture escape hatch**.
- **3b.** The brightness belongs to the **topmost window that asks for one**, measured from both
  sides: system surfaces and a video player in swipe-to-dim both failed to move ours, while the
  keyguard and any runtime permission dialog release it by hiding our window rather than by
  out-ranking it. **Read out and closed by Phase 3's checkpoint A** (2026-09-02): the rule holds
  between two windows of Gloam's own as well, so the panel declines a brightness and the shade keeps
  the override. ADR-0010's fourth amendment.

The one item below that the phase still owns — starting the tester recruitment — stays here, because
it is what the door is waiting on.

**Phase 2's code is done, and its record is [`phase-2.md`](phase-2.md)** — seven checkpoints,
**A-G, all shipped between 2026-09-01 and 2026-09-02**: the shade comes down on its own after a
deadline it re-reads from the wall clock rather than from a timer, comes back after a restart, says
what Xiaomi's autostart costs if it is denied, and has a Help and feedback screen to be complained
to. The scaffolding the door was waiting on is resolved and the emulator matrix has a real test in
it. **What is left of the phase is not code**: the tester recruitment and the listing, both below,
and both calendar rather than effort.

**Phase 3's code is done, and its record is [`phase-3.md`](phase-3.md)** — seven checkpoints,
**A–G, shipped between 2026-09-02 and 2026-09-03**. The go/no-go went first and it went: a second
overlay window of ours sits *above* the shade and the shade keeps its backlight override, which is
what made 3b buildable rather than a bet. The controls came out of the screen that owned them and now
have three hosts — the full screen, a **compact activity** reached from the notification or from the
icon when the preference says so, and the **panel**, a touchable window above the shade and the only
control surface in Gloam that is legible at maximum dim (1.59 nits under it against 6.64 above,
measured both ways). The notification now says when the user's own brightness slider is paused.
**What is left of the phase is not code**: the two rule-5 questions below, which outlive it.

**Phase 4 is done, and its detail is [`phase-4.md`](phase-4.md)** — seven checkpoints, 0 and A–F.
**Every checkpoint is closed.** The gate answered on 2026-09-05 and its verdict is **(ii)**,
build as planned: the alarm fires in forced Doze with the battery exemption and autostart both
granted, the exemption licenses the *service start* rather than the alarm, and autostart is absolute.
So §7's Xiaomi half is load-bearing rather than a footnote, and the one finding no verdict
anticipated — an inexact alarm is delivered at the far end of a window 75% as wide as its futurity,
capped at an hour — is why §4 arms a chain of hops rather than `nextOn` itself. (**R4 narrows that**:
the far end is `force-idle`'s habit rather than the ROM's, and the chain is what makes it not matter.
Below.) The verdict and its numbers are in `phase-4.md` §1. **D landed on 2026-09-05** — the schedule screen and its route, the
dim screen's summary row, the pickers with the equal-times refusal and the short-window warning, the
battery banner and its widened hand-off, the compact hosts' read-only section, `launcherCompact`
defaulting `true`, the deadline on the notification's sub-text, and the copy in both locales.

**E is closed.** **R7 was taken 2026-09-06**: with a synthetic clock at 23:50 and a 23:55-to-00:05
window, the fire resolved its deadline onto **2026-09-07 00:05** in the device's own zone and the
shade came down 44 ms after it — the crossing, on a phone rather than in the sweep. It also found a
defect **D had introduced**: after *any* auto-off, the deadline going away re-posted the ongoing
notification 263 ms after the shade was already down, leaving "Screen dimmed" with a Stop action over
a screen nothing was dimming, removable only by a force-stop. Reproduced without the schedule
involved, fixed and re-read the same day (`b8fba4d`), which makes E a commit it was not expected to
be.

**And a second defect D had introduced, found the same way on 2026-09-07: the icon flashed the full
app.** Not the starting window `ba855ac` had already taken away — that fix was correct and
insufficient — but `MainActivity`'s own window, brought forward by the platform whenever Gloam's task
was alive, which is the ordinary state after anybody opens Settings and leaves. A forward cannot
outrun `moveTaskToFront`, so the launcher `<intent-filter>` moved to `ControlsActivity` instead:
shape **iv** in `phase-3.md` §2, with the reading in F3. The preference means what it always meant
and now reads from the other side of the forward; `MainActivity` is left with no launcher logic at
all. **One consequence to watch on update**: the launcher entry is a component, so a pinned
home-screen icon may need re-adding once.

**R4 was taken on the night of 2026-09-07 to 08, both halves in one night**, and it closes E. The
window ran 02:00 to 03:00 against the real receiver; the bare gate alarm was armed for 04:31, after
the window had closed. `deviceidle` has the device in deep `IDLE` from 01:01, with both fires landing
inside it rather than in a maintenance window — the natural Doze `force-idle` was standing in for.

- **The schedule works, and the hop chain is why.** Four hops, each re-armed at `gap / 1.75`, the
  last of them armed for the on-instant itself; the shade came up **4 m 4 s** into the hour. The hour
  a single long arm would have cost is a price the chain never pays.
- **The gate fired 2 m 50 s late and its service start was allowed** — not at the far end of its
  hour-wide window but 57 minutes short of it. **`force-idle` is pessimistic about delivery**, which
  is the opposite of the direction a simulation usually errs in, and is what the night bought.
- **The auto-off is the finding with a cost attached.** The shade came down **14 m 5 s** after the
  off-instant, because `DEADLINE_RECHECK_MS` is sixty seconds of *uptime* and this phone is awake
  5.7% of a screen-off night. The loop's own comment predicted the shape; fourteen minutes is the
  size, and it is what section 10's short-window warning is now written from.
- **The one question the night could not answer is still the bare half's.** The process was alive
  through both fires — this ROM's cleaners had killed the app repeatedly through the evening and then
  left it alone the moment the phone settled — so *whether it starts a **dead** process for a
  broadcast* needs a night where nothing launches the app after the last clean, and `am kill` still
  refuses to make one. Not a Phase 4 blocker: the schedule is proven on a ROM that keeps the process,
  and section 6's reconcile is what covers the case where it does not.

The numbers, and the two methodology findings that came with them, are in `phase-4.md`'s R4.

⚠️ **Run `python3 scripts/device-gate.py` before every device reading.** The autostart grant
lapses on its own, and a Doze run against an unknown one proves nothing in either direction — which
for an overnight reading costs a night rather than a minute.

⚠️ **Arm last, after the final install — but for the overlay permission, not the alarm.**
`adb install -r` revokes `SYSTEM_ALERT_WINDOW` on this ROM, so a schedule armed across an install
comes up with no window it is allowed to draw. It does **not** cancel the package's alarms, and this
file used to say it did: measured 2026-09-07, a gate alarm pending for 08:50:33 survived
`installDebug` at 23:28:29 with the same `origWhen` and nothing new in the removal history. Only the
gate alarm can settle it, because `MY_PACKAGE_REPLACED` re-arms the schedule's two seconds later.
What cancels an alarm is a force-stop (R5's row) and a `pm clear` — and this ROM force-stops apps of
its own accord. Also stop a shade the install restored: one already up at the on-instant is *adopted*
rather than started, which is R6 and not the reading a scheduled night is for.

⚠️ **An overnight capture cannot depend on the app's own log lines.** The main ring is 2 MiB, and
`GloamSchedule`'s and `ShadeService`'s lines from 02:04 and 03:14 were gone by 06:25 while framework
records from the same process, 44 ms apart, survived. `scripts/doze-capture.sh` pulls the events
buffer, `dumpsys alarm` and the preferences file for that reason, and R4's lateness figures were
rebuilt from those rather than read off the log. **And its closing guard counts *pending* alarms for
the package**, so the schedule re-arming itself for the next night reads as *"the run is not over"*
the morning after a run that finished perfectly — read *which* alarm is pending before believing it.

## The support address — open until the new route has carried a real report

`gloam.support@maksforge.com` replaces `gloam.dimmer@gmail.com` from 1.3.1. Why both stay alive for
good is in [`store-listing.md`](store-listing.md); these are the one-off steps.

- [ ] **The Console's contact email changes in Store settings the day this merges**, not at the
      upload: Pages republishes `privacy-policy.md` and `index.md` from `main` on merge, and the
      policy says the address is also the listing's. Then update the Store settings line in
      [`play-app-content.md`](play-app-content.md).
- [ ] **The IARC rating contact moves at the next resubmission** — the one the tip already owes
      below. Record the new contact under *Content rating* in `play-app-content.md`.
- [ ] **The route has carried a real report.** Only Gmail's send-as confirmation has come through
      Cloudflare. Send *Report a problem* from a non-Gmail account to the new address and confirm it
      lands in the inbox, not Spam, with the `#bug` and `#feature` filters still applying.
- [ ] **Turn forwarding on `gloam.dimmer@gmail.com` back on to the developer inbox.** It worked on
      2026-09-01 (`phase-2.md`, R9) and has since been switched off.

## The tip — open until a real purchase is consumed

ADR-0009's 2026-10-11 amendment. The code is in; these are the parts only Play and a phone can prove.

- [ ] **Internal testing build with billing uploaded.** Play Console refuses to create a one-time
      product until an artifact carrying `com.android.vending.BILLING` is on a track.
- [ ] **Products created and active**: `tip_small`, `tip_medium`, `tip_large`, `tip_xlarge` at
      5 / 10 / 20 / 50 PLN, each with multi-quantity on.
      An id that differs from `TIP_PRODUCT_IDS` is silently left out of the section.
- [ ] **One real purchase from a licence tester** on the internal-testing install: Play's sheet opens,
      the thank-you line appears, and buying the *same* tip again works — the second purchase is the
      proof that the first was consumed.
- [ ] **Content rating re-submitted** with *digital purchases* answered Yes (`play-app-content.md`),
      before 1.3.0 is promoted to production.
- [ ] **The debug build on the phone shows no tip section and no card.** Play has never heard of the
      `.debug` package, so an empty section is the correct reading, not a failure.
- [ ] **A line in the listing naming the tip**, once room is made in the German, Spanish, French and
      Brazilian Portuguese full descriptions (`store-listing.md` says why it is not there yet).

## The standing schema gate — parked, because there is no database

**Gloam has no database** (ADR-0007). It keeps a dim level and a shade-running flag in DataStore,
where an unrecognised key is ignored and a missing one falls back to the default declared beside it.
There is no version at which a stored file stops being readable, so nothing below applies today and
`scripts/schema-gate.py` reports *"no database, nothing to gate"* and passes.

**Everything below is kept word for word and comes back the day a feature adds a table.** That is
the point of parking it rather than deleting it: adding a table is then a deliberate act with this
checklist already attached, not a convenience someone reaches for on a Tuesday.

⚠️ **Its trigger has changed.** ADR-0001 was written against a "before 1.0 / from 1.0"
boundary, and this plan has no 1.0 — the app ships as 0.x deliberately (`PLAN.md`, rule 2), so that
boundary would never arrive. **Read every "from 1.0" below as "from the first build that reaches a
user's phone"**, which is the door at Phase 2. Recorded as a dated amendment on ADR-0001.

Whenever `APP_SCHEMA_VERSION` exists and changes, all five hold before the release goes out:

1. `MIGRATION_x_y` written **and registered in** `APP_MIGRATIONS` for every step. A migration Room
   never runs is not a migration, and nothing about the build complains.
2. The exported `app/schemas/*/N.json` committed — every later migration is transcribed from it,
   never from the entity classes.
3. `SchemaGateTest` extended, so the **launch gate** is proven to let the upgrade through. Every
   migration test opens the database directly and walks past that gate; this assertion is the only
   thing standing in front of it.
4. A migration test proving the **rows survive** — read values back, do not merely assert nothing
   threw. `runMigrationsAndValidate` compares *shapes* and is blind to a table emptied by a cascading
   `DROP`.
5. **An actual upgrade watched on a phone**: install the previous build, put real data in it, install
   the new one over the top, confirm the app opens and the data is there.
   `./gradlew assembleDebug -PreleaseShapedDebug` is how to do this without touching a Play install —
   it is minified *and* `BuildConfig.DEBUG == false`, so migrations are registered rather than the
   destructive fallback. ⚠️ **Both halves are load-bearing**: a plain debug build takes the
   fallback and proves the opposite of what you wanted.

`scripts/schema-gate.py` enforces 1–3 mechanically in CI. Items 4 and 5 are yours.

## Phase P — the pipeline. No code, do it now

Runs alongside whatever is being built. **All of it must land before the door** (Phase 2), because
`applicationId` is one of the four things the door freezes.

Developer identity verification is **already done** — the account exists and has a live app — so the
one step with an external queue is behind you.

- [x] **Create the upload keystore, outside the repo.** Done 2026-08-30, and proven rather than
      assumed: `bundleRelease` ran `signReleaseBundle`, and `keytool -printcert -jarfile` on the AAB
      returns the keystore's own SHA-256. Its path, alias, algorithm and fingerprint are recorded
      under *The upload key* in [`RELEASING.md`](RELEASING.md).
- [x] **Back the keystore up somewhere that is not this machine.** Done 2026-08-30, confirmed by
      the owner. The keystore *and* its password, which is in `local.properties` and nowhere else,
      are in a password manager. **Provenance, honestly:** this box rests on the owner's word rather
      than on a check this repo can run — nothing in the toolchain can see inside a password vault,
      which is why it is the one Phase P item that closed without machine evidence behind it.
      The shape that was used, and the reason for it: the key is 4312 bytes, so its base64 is 5752
      characters of **text** and goes in a secure note rather than a file attachment — no uploader to
      hang, and it survives a vault export, which attachments sometimes do not. Restore is
      `base64 -d`. The certificate SHA-256 is stored in the same note so a future restore proves
      itself: decode, `keytool -list`, and the fingerprint must be the one in `RELEASING.md`.
      ⚠️ **Re-verify this one occasionally**, because its failure mode is silent — some password
      managers clip long fields without warning, and a note holding 5,700 of 5,752 characters looks
      untouched right up until the day it is needed, which is also the day the app can never be
      updated again.
- [x] **Create the Play Console entry.** Done 2026-08-30. App content answered: no ads, no
      government/financial/health features, all functionality available without sign-in, content
      rating questionnaire all-No, target audience **18+**, category **Tools**. Internal testing
      track exists with the owner as its only tester.
- [x] **Bind the package name by uploading a build.** Done 2026-08-30. `io.github.srednimax.gloam`
      is now yours, and it was verified by round-trip rather than by reading the Console: the bundle
      went up to internal testing, came back down through Play onto the phone, and `dumpsys package`
      reads `versionCode=29 versionName=0.2.0 installerPackageName=com.android.vending` — the same
      artifact `aab-version.py` signed off, delivered by Play rather than sideloaded. The opt-in page
      named the package before the invite was accepted, which is where the string was checked.
      **Two things this also proved, neither of them a separate item any more:** the upload keystore
      is the one Play will expect for every future update, and the release build survives R8 and
      launches — it came up on its overlay-permission gate, not a crash.
      **And one it created:** the `applicationId` is frozen *and* married to that keystore. The
      backup item above stopped being hygiene the moment this box was ticked.
- [x] **Read back the app's access status.** Answered 2026-08-30, and the answer is the expensive
      one: **the 12-tester / 14-day closed test applies to this app.** It is not a one-time account
      unlock that the existing live app satisfied. So the plan's assumption held, the schedule stands
      as written, and **recruiting is the long pole** — see the item below.
- [x] **Answer the Console's *App content* questionnaire.** Complete 2026-08-30. Ads, government/
      financial/health, app access, content rating and target audience were answered when the entry
      was created (see above); **data safety was answered last: no data collected, no data stored,
      nothing shared.** It gates a *closed* release, not only production, which is why it sat in
      front of the door rather than in Phase 5.
      **That declaration is provable rather than promised**, and this is the reason to keep it that
      way: Gloam requests no `INTERNET` permission at all, so it has no route off the device, and
      `scripts/aab-permissions.py` asserts `INTERNET` and `AD_ID` *absent from the built artifact*
      rather than trusting the source. Any dependency that merges either one in turns a true data
      safety answer into a false one silently — the check is what catches that, so a red
      `aab-permissions.py` is a Play declaration problem before it is a build problem.
      **One nuance worth not re-litigating later:** Gloam uses platform Auto Backup, so a dim level
      can reach the user's own Google Drive. That is not developer collection — the platform moves
      it, the user controls it, and the developer never sees it — which is why the answer is still
      "no data collected". `docs/play-app-content.md`, where the previous app wrote these answers
      down as a set, still does not exist here; Phase 5 owes it.
- [x] **Set up the GitHub repository.** Done, verified 2026-08-30 by
      `python3 scripts/repo-setup.py --dry-run`, which reports the `main: require CI` ruleset, the
      rebase-only merge setting, Pages from `main/docs` and `RELEASE_PLEASE_TOKEN` all already in
      place. It is idempotent, so re-run it after any manual change in the GitHub UI rather than
      trusting this box.
- [x] **Set the five Play secrets and create the service account.** Complete 2026-08-30.
      `repo-setup.py --dry-run` reports all five set, plus `RELEASE_PLEASE_TOKEN`.
      The four upload secrets are **verified, not merely present**: the `upload.storePassword` in
      `local.properties` opens the keystore, `keytool -list` reports the single alias `upload`, and
      the certificate SHA-256 is the one `RELEASING.md` records. That check exists because GitHub
      never reads a secret back, so "set" is all the dry run can ever tell you.
      **`PLAY_SERVICE_ACCOUNT_JSON` is proven, and it took four attempts to prove it.** Nothing on
      this machine could ever have told you the JSON was valid or that Play had granted the right
      scope; **the first green run of `publish-play.yml` was the only possible proof**, and it
      arrived on 2026-08-31 - run `33412104006`, tag `v0.3.0`, **attempt 4**, job *Bundle, verify,
      upload to internal testing*, 2m53s, the AAB and its mapping on the internal track.
      **What the three red attempts were, and it was one cause rather than three:** neither of the
      failures this box braced for. The service account authenticated on the very first try and the
      bundle built, signed and verified; the Cloud project simply had
      `androidpublisher.googleapis.com` switched off - *"Google Play Android Developer API has not
      been used in project 118298064751 before or it is disabled"*. Enabling it in project
      `118298064751` was the whole fix, and the retries after it were its propagation delay.
      **The lesson is the trigger, not the API.** `workflow_dispatch` is what let four attempts cost
      four re-runs instead of four versions nobody wanted; reach for it before reaching for a tag.
      The two failures originally braced for are still the ones to expect next time: a 401 is usually
      Play-to-API propagation and is worth re-running first, and a 403 is a permission missing rather
      than a broken pipeline.
      **`UPLOAD_KEYSTORE_BASE64` is not a backup.** A GitHub secret is write-only; you cannot get the
      keystore out of it again. It is a copy CI can use, not one you could recover from. The backup
      item above is untouched by this and remains the sharpest thing in this file.
      Permissions granted, and the per-app convention behind them, are in `RELEASING.md` under
      *Creating the service account* — two boxes for the internal pipeline, and the two more that
      only the production workflow wants. **One of those two is granted**: *Manage store presence*,
      read off Phase 5's gate on 2026-09-17 rather than off the settings page. *Release to
      production* has not been read yet, because nothing has promoted to production.

- [x] **Decide the `applicationId` deliberately.** Done: **`io.github.srednimax.gloam`**. Reverse-DNS
      on a namespace verifiably yours; Play has never checked domain ownership and package
      registration is keyed to the **signing key**, not a domain. Short generic names like
      `app.gloam` are the ones another developer could claim first. `namespace = "app.gloam"` is
      internal and never reaches Play.

## Before the door — Phase 2 freezes these

Cheap now, expensive or impossible once a build sits on twelve strangers' phones.

- [x] **Resolve three pieces of scaffolding that describe features the app does not have.** Done —
      Phase 2 checkpoints A and E, and looking for the three found two more (`phase-2.md` §7).
      `onboardingDone` is deleted, `project.py` no longer reports a `DATABASE_FILE`, and
      `work/BatteryExemption.kt` was **split rather than deleted**: the half the Settings row calls
      is now `work/Autostart.kt`, and what stays behind the old name is unambiguously Phase 4's.
      The two it found — AppCompat's below-13 locale backport and WorkManager itself — are the ticks
      below and ADR-0003's second amendment.
- [x] **Ask for `POST_NOTIFICATIONS`.** Done — Phase 1 checkpoint A. `DimScreen` fires the ask from
      the start button, **before the first `startShade()` and never after**, so it is only ever raised
      with no shade on screen: the system refuses touches on non-system overlay windows while a
      permission dialog is up, and nobody should assume `FLAG_NOT_TOUCHABLE` exempts ours.
      `setRunning(true)` and `startShade()` moved into the outcome callback together, in all three
      outcomes — the shade starts even on a refusal, and the app says what was given up instead of
      withholding the feature.
      **No DataStore key was added**, which was the point: "never asked" and "asked twice and
      refused" are indistinguishable from `shouldShowRequestPermissionRationale` and want identical
      behaviour anyway, so the launcher answers it. The second denial is spent only by a deliberate
      tap on a control that says *Allow notifications*.
      The warning is a **live read** — `notificationsAllowed() && channelCanAppear(Shade)`, both
      halves, re-read on every resume beside `canDrawShade()` — rather than a remembered outcome,
      because the fix for it is a settings screen the app hands the user off to. It self-clears when
      they fix it and appears if they revoke mid-session.
- [x] **The 14-day closed-test window is closed and the production-access application is in.**
      The window opened about 2026-09-11 and **completed 2026-09-24** with the twelve still opted in —
      nobody left, which was the only way this box could have slipped. The application went in from the
      Console's Dashboard the same day. **Its nine answers are recorded verbatim in
      [`play-app-content.md`](play-app-content.md)** rather than summarised here, because the Console
      asks the same set again for the next app on this account and because a remembered answer is not
      the answer that was given.
      **And the application corrected this file's own record of the twelve.** A **public survey did go
      out** to the testers during the window and the Support screen's mail route carried replies, so
      `phase-5.md` §10's *"no message, no date, no owner"* is wrong as written and is amended there.
      What came back was **general and positive — no bug report, no feature request** — and the survey
      asked none of the four rule-5 questions, so all four still close *unanswered* and every shipped
      default stands. The boxes below are unchanged by it; only their reason is narrower.
- [x] **Production access is granted.** Submitted 2026-09-24, **granted 2026-09-25** — one day, the
      same as Binky's (`binky-app/docs/phase-9.md` §4), so this account's two datapoints agree. The
      production track now exists, which unblocks Phase 5 F and the 1.0.0 cut below. The answers the
      grant was made on stay in [`play-app-content.md`](play-app-content.md).
- [x] **The first production release is `1.0.0`, and the number is a deliberate act.** Decided by the
      owner 2026-09-24, amending `PLAN.md` rule 2, which had said this plan produces no 1.0 — it still
      produces none on its own. **release-please will not jump there**: with the major at 0 even a
      breaking `feat!:` bumps the minor, so 1.0.0 happens only if a commit body carries
      `Release-As: 1.0.0` ([`RELEASING.md`](RELEASING.md)). Three things have to be true on the day,
      and the order is the point:
      1. ✅ **Play has granted production access** — 2026-09-25, the box above.
      2. ✅ **Release notes for `1.0.0` in all nine locales**, in [`store-listing.md`](store-listing.md),
         written 2026-09-25 on an ordinary branch, ahead of `versionName` as `scripts/notes-gate.py`
         allows. Behind it is the direction that once shipped Binky's 1.9.0 carrying 1.8.0's text, and
         release-please's own branch is no place to fix that: it force-pushes over it.
      3. ✅ **The AAB and the listing go up together.** One run of `publish-play-production.yml` on
         2026-09-25 (run 36176678225, second attempt) promoted versionCode 226 `internal →
         production` with `update_listing: true`: nine listings, 36 screenshots and nine changelogs
         in the same edit. It was a **full rollout** (`rollout: 1.0`, `completed`), not the 10% staged
         default, so every production user gets it once Play's review passes. Before that, 226 was
         installed from the alpha track on the development phone and checked by hand the same evening.
         The first attempt failed on the production track targeting no countries, which is now in
         [`RELEASING.md`](RELEASING.md).
- [x] **Put auto-off's default to the testers** (`PLAN.md` rule 5). `Hours2` ships **provisional**:
      it is the longest value that is still obviously not "the next day", which is the failure
      auto-off is shaped to design out — but it is a taste argued in a room with one person in it.
      The twelve are the room. Recorded here rather than in `phase-2.md` so the question outlives
      the phase that raised it; the answer is a one-line change to `AutoOff.Default` or nothing.
      **Closed 2026-09-17 as *shipped, unasked* (`phase-5.md` §10).** The channel was never opened:
      no message, no date, no owner, so silence was never going to carry information. `Hours2` stands
      and stops being provisional — a value lived with by one person rather than twelve, which is the
      complaint, and not one an unsent message fixes. An answer can still arrive as ordinary mail and
      change a default in an ordinary release.
- [x] **Put the two edges of an episode to the testers** (`PLAN.md` rule 5). *Raised by Phase 4's
      section 3, and it is one question rather than two.* A shade the hand starts **inside** a window
      is bounded by the window's end, and a shade that **ends** inside one — Stop at 01:00, or a
      promised deadline expiring — spends that night, so opening the phone at 01:05 does not put the
      shade back. Both edges err toward light and both are defensible the other way; the difference
      only shows up in somebody who lives with it for a week, which is what the twelve are.
      [ADR-0012](adr/0012-one-deadline-monotone-except-at-a-start.md) is the rule, and the answer is
      a one-line change to `nightSpentBy` or nothing.
      **Closed 2026-09-17 as *shipped, unasked* (`phase-5.md` §10).** `nightSpentBy` is unchanged and
      ADR-0012's rule stands as written. Both edges err toward light, which is the direction this app
      errs in everywhere else.
- [x] **Ultra dark has a ceiling the platform holds, and 2b has to price it before it starts.**
      *Found by Phase 3's R13, 2026-09-03.* An app overlay that **passes touches** may not obscure
      more than `maximum_obscuring_opacity_for_touch` — unset on the phone and on the API-33
      emulator, so both sit at the framework default of **0.8**, which the window manager writes
      straight into the shade's window alpha. Moving the global to `0.5` brings the shade back at
      `alpha=0.5`, so it is the platform's number and not ours. **2b is defined as going past
      `MAX_SHADE_ALPHA`**, and past a point more alpha buys nothing: the composite is multiplied by
      0.8 whatever the children do, which is why R3 and R6 measured 24% transmission where the
      invariants compute 5%. The shade cannot drop the flag to escape it — a touch-catching
      full-screen overlay is the trap the app exists not to be — so 2b's honest options are the
      backlight (already at `MIN_BACKLIGHT` there), or accepting the ceiling and saying so in the
      copy. Read `phase-3.md`'s R13 and ADR-0010's fourth amendment before designing the feature.
      **Priced, 2026-09-13, on the dev phone.** The goal is **headroom**: dark enough that the level
      someone actually reads at sits well below 100, with the top of the range kept for the nights
      that need it. The backlight was not "already at `MIN_BACKLIGHT`" in the sense that mattered,
      because `MIN_BACKLIGHT` (0.01, 6.64 nits) sat well above the panel's floor (6.84e-4, 2.0 nits).
      Nits reaching the eye at dim 100, *reckoned in stored values. In light, 1.59 is ≈0.31 and 0.48
      is ≈0.09 (ADR-0010's seventh amendment)*:
      - **The old `0.01f`:** 1.59, measured.
      - **Backlight at the floor:** ~0.48, about 3× darker. **Taken the same day**: `MIN_BACKLIGHT`
        is now the floor ([ADR-0010](adr/0010-one-dim-level-drives-both-mechanisms-in-a-fixed-order.md)'s
        fifth amendment). The cost is R5: the notification and the panel dim with it, to 2.0 nits.
        **R2 is owed again by eye**: at dim 100, pull the notification shade down and tap *Stop*, in
        a dark room and a lit one. The debug build's Settings → Developer switches back up to the old
        0.01 for the comparison, and if R2 fails it is the way back.
      - **Shade cap raised to the 0.8 clamp:** ~0.40. About 17% more, and it breaks ADR-0010's
        invariants, so it is not worth it.
      - **Extra dim** (Reduce Bright Colors) on top: ~0.07, computed from framework-res rather than
        measured. It is the only lever past the ceiling, and it is the user's: an app can deep-link
        `android.settings.REDUCE_BRIGHT_COLORS_SETTINGS` but cannot set it.
      - **`FLAG_DIM_BEHIND` is dead.** Its dim is not clamped (`a:0.95` in SurfaceFlinger), but the
        layer belongs to uid 1000, and the input dispatcher treats it as a blocking occluder, so every
        touch to any other app is dropped, at any `dimAmount`. The system Settings app is uid 1000 and
        exempt, so it is the wrong place to test touches. Stacking a second full-screen shade window
        is dead too: one uid's opacities add up, and two windows at 0.8 read as 0.96. The readings are
        in `DimBehindWindow`'s KDoc in `src/debug/`.
      **Closed 2026-09-18 as *priced, and the feature cancelled*.**
      [ADR-0015](adr/0015-ultra-dark-is-cancelled-the-touch-obscuring-clamp-is-the-floor.md) is the
      decision and carries the whole table. The box asked 2b to price the ceiling before starting;
      the price came back at **17%** for breaking both of `shadeValuesFor`'s bounds, so 2b never
      starts that feature. `PLAN.md`'s Phase 2b is now **the second escape hatch** — the Quick
      Settings tile — and the Extra dim row on the list above is dead on this ROM as well as
      unbuilt: the toggle in HyperOS's own Settings does nothing (taps arrive, the secure value
      stays `0`), while `settings put` works, so the mechanism is fine and the ROM's Settings app
      is not. A *Darker still* deep link was built and deleted for that reason.
- [x] **R2 is owed again, by eye: is the notification's *Stop* still readable at 2.0 nits?**
      *Raised by the box above, 2026-09-13; kept open when it closed 2026-09-18.* Phase 2's R2 read
      the escape hatch against a 6.64-nit backlight. [ADR-0010](adr/0010-one-dim-level-drives-both-mechanisms-in-a-fixed-order.md)'s
      fifth amendment moved `MIN_BACKLIGHT` to the panel's floor for 3.3× more darkness, and the
      notification and the panel dim with it — that is R5, and it is the cost the change accepted.
      **The reading is a finger, not adb**: at dim 100, pull the notification shade down and tap
      *Stop*, once in a dark room and once in a lit one. If it fails, the way back is
      `MIN_BACKLIGHT = 0.01f`, and the debug build's Settings → Developer switches back up to the old
      value for the comparison. Nothing downstream is blocked on it; a failure costs the 3.3×.
      **Read 2026-09-18, and it passes.** At dim 100 — confirmed from the store rather than the UI,
      `dim_level = 0x64` — in a **lit** room, which is the hard case: the notification sits at the
      overridden backlight while the eye is light-adapted. *Stop* is legible and it works, end to
      end: `shade_running` went false, the notification went, and `ShadeService` went with it, and
      the deadline in `off_at_millis` cleared rather than being left behind. **`MIN_BACKLIGHT` stays
      at the panel's floor. The 3.3× stands and `0.01f` is not coming back.** The dark-room half was
      not taken and is not worth taking: a dark-adapted eye at 2.0 nits is the easy direction.
- [ ] **The notification's *Stop* is reachable on HyperOS but not discoverable, and the tile is the
      answer.** *Found 2026-09-18, taking R2.* The action exists — `dumpsys notification` reads
      `actions=1`, `[0] "Stop"`. It is **not drawn**: a full uiautomator dump of the rendered row
      holds `app_icon`, `title`, `time` and `text` and no `id/actions` node, no expand chevron, and a
      one-finger drag does not reveal it. **A two-finger pull does**, and that is how R2 above was
      taken. It is the ROM rather than our construction — the only other notification on the phone
      with an action (`com.google.android.gms`) draws no button either, and HyperOS substitutes its
      own *"Tap for more options."* hint.
      **What it costs.** `work/Channels.kt`'s KDoc says someone who dims to 95% and cannot find the
      app *"still has this notification and its Stop action"*. On this ROM that holds only for
      someone who knows the gesture. The route that does work without knowing anything is the
      notification **body** — it opens the panel, which carries its own stop and sits above the shade
      — so the hatch is two taps rather than one, and it runs through a window Gloam draws.
      **The decision this wants** is not new copy inside the notification, which the ROM would
      collapse the same way. It is `requestAddTileService()`: the QS tile is the only *one-tap* hatch
      on this device, which turns it from the second hatch into the discoverable one. That reframes
      `PLAN.md`'s Phase 2b bullet from *nice to have* to *the point*.
      **What is not known** is how far this generalises. It is one ROM, and stock Android draws
      action buttons in the collapsed row; the twelve testers are the sample that could say.
      **Half-answered 2026-09-18: `requestAddTileService()` is built and measured.** Settings grew a
      *Quick Settings tile* section under *Controls* — a permanent row that offers every time and
      remembers nothing, for the autostart row's load-bearing reason (the app cannot read back
      whether the tile is added, and a remembered key would be live state in a backed-up store).
      Both platform paths were read on the phone: already-added answers immediately and the row says
      so, and with the tile removed the system's *"wants to add the following tile"* dialog appears
      and accepting it answers `TILE_ADDED`. **What it does not do is place the tile where anyone
      will see it** — accepting appended it *twenty-third*, after `edit`, where it had been first.
      The platform chooses the position and the app cannot. So the box stays open on the question it
      was opened for: the tile is now easy to *add* and still not certain to be *found*, and whether
      the notification's action is invisible beyond HyperOS is still a question for the twelve.
- [x] **Read the shade's transmission in light, not in stored values.** Done 2026-09-13, from the
      phone alone, and the ramp now derives the shade alpha from light. The readings and what moved
      are in ADR-0010's seventh amendment.
- [x] **The readings the night-reading research owed** ([`night-reading-research.md`](night-reading-research.md)),
      both taken. **§2** (2026-09-14): the panel is PWM from somewhere between 126 and 64 nits
      downwards, so the floor flickers, and turning the toggle off helps only at a brightness that
      leaves the page at 3–6 nits. Settings now has a *Flicker* section that says so. **§3**
      (2026-09-16): three nights of the debug *Reading test*, 288 trials pooled, put every colour
      within noise of 50 — ratios against 50 of 0.95 (amber), 1.00 (25) and 1.04 (deep red), every
      95% interval crossing 1.0, against a resolution of about 11%. The pre-set rule wanted 25
      clearly faster and got 1.004, so `DEFAULT_WARMTH_COLOR` **stays at 50**, and ADR-0010's eighth
      amendment records the run — the colour bar is taste, not speed. Rows and per-night summaries
      are in `~/gloam-data/reading-test/`, outside the repo.
- [x] **Put the launcher preference's default to the testers** (`PLAN.md` rule 5). **The default
      inverted in Phase 4 D: the icon opens the compact controls, and the full app is the setting.**
      What stood behind the old default was an argument rather than a reading — that a first launcher
      tap landing on a dialog over another app is a bad first meeting with an app nobody has used yet
      — and R9 measured that it cannot happen: `ControlsActivity.forwardIfUnusable()` returns to
      `MainActivity` whenever `canDrawShade()` or `escapeHatchLive()` is false, and on a genuine first
      run both are. The twelve are still the room, because what is left of the question is about the
      *second week* of use rather than the first, and the answer is a one-line change to
      `AppPreferences.launcherCompact`'s default or nothing.
      ⚠️ **Their silence is not approval, and this is the one item where reading it
      that way would be wrong.** `escapeHatchLive()` is in the same guard, so a tester who declined
      notifications gets the full app from the icon for good, with nothing on screen saying why —
      they are not using the default at all. Only testers who granted notifications can answer this.
      **Closed 2026-09-17 as *unanswerable as posed* (`phase-5.md` §10).** The warning above outlived
      the question: `escapeHatchLive()` sits in the same guard, so a tester who declined notifications
      never met the default at all, and there was no way to know which testers those were except by
      asking. `launcherCompact` keeps its default. R9 had already removed the engineering objection;
      what is left is second-week taste.
- [x] **Put the brightness-slider question to the testers** (`PLAN.md` rule 5, the second of its two).
      Should the phone's own brightness slider mean *"give me more light"* while Gloam is dimming?
      Phase 3 ships the cheap half — the ongoing notification now says the slider is paused, on the one
      surface that can be read at maximum dim — and deliberately ships no `ContentObserver`.
      **The engineering objection is gone and the taste one is not.** `phase-3.md`'s R10 measured that
      the backlight override switches the framework's auto-brightness controller off entirely, so an
      observer listening while the shade is up cannot mistake an adaptive write for a user's drag, and
      the manual-mode-only limitation does not apply. What is left is that a *system* control would do
      something the system never promised, on a screen that does not name the app responsible. That is
      a taste argued in a room with one person in it, which is what the twelve are for. The answer is a
      small, already-measured piece of work or nothing.
      **Closed 2026-09-17 as *not built* (`phase-5.md` §10)**, and it loses least: nobody
      spontaneously reports the absence of a feature they were never told about. R10's measurement is
      what is kept, so the next person starts from *"this works, is it wanted"* rather than from
      scratch.
- [x] **Replace the placeholder mark.** Done 2026-09-05. The moon: a disc with a crescent bitten
      out of it, traced from `art/concept.png` into `art/mark.py` and both generators re-run.
      Provenance is recorded in `art/README.md` — an image model, the owner's own prompt — and the
      concept file is committed beside the mark so the claim is checkable rather than remembered.
      **`ART_SIZE` went 50 → 60dp with it**: the mark is a disc, so its reach from the centre *is*
      its radius, and 60dp is Android's keyline for a full-bleed circular icon. The script's own
      check reports 30.1dp against a 33dp safe radius, so no circular mask can bite it.
- [x] **Write the listing, and shoot the feature graphic and screenshots.** *Moved here from Phase 5
      on 2026-08-30, closed 2026-09-18 when the last of its six parts landed.* Play will not let a **closed** test open without a complete store listing, and
      the closed test is what the 12-tester window runs on — so these three cannot be polish. The
      graphic comes off the item above (`make-feature-graphic.py` derives from `art/mark.py`), which
      is now a dependency of the door rather than a Phase 5 nicety. `docs/store-listing.md` — every
      heading in it is parsed by a script. Keep health claims out of the copy *and* the tags — "eye
      strain", "sleep", "blue light" — because App content was answered health-No.
      **Part-done 2026-08-30, and the remainder is named rather than implied:**
      - [x] English short (74/80) and full (2571/4000) descriptions.
      - [x] App name, both locales — `Gloam`, untranslated on purpose, and the file says why.
      - [x] Phone screenshots. **Superseded 2026-09-18** by the nine-locale set below: 36 shots,
            1526×2713, four scenes per language. The 2026-09-05 set was three English shots at
            1452×2582 and is gone rather than kept for comparison — it was light-labelled and shot
            dark, which is the defect that made `--theme light` a hard error.
      - [x] Icon (512²) and feature graphic (1024×500), regenerated 2026-09-05 off the real mark.
            **The graphic is no longer the template's**: the weight-chart motif it inherited from
            the other app is gone, the tagline is Gloam's own line, and its three colours are
            *read out of* `theme/Color.kt`'s dark scheme at render time rather than pasted — so a
            regenerated palette reaches the listing art by re-running one script.
      - [x] **Polish short and full descriptions.** Written 2026-09-17 in Phase 5 C, from the
            English B rewrote that morning: 76/80 and 3924/4000, so the Polish full description has
            76 characters of headroom and an English addition means a Polish cut. `--strict`
            passes on the document. The screenshots stay English (`phase-5.md` §4).
      - [x] Real screenshots off a real mark. Done 2026-09-05, redone 2026-09-18 off the **shipped
            0.8.0 install** rather than a local build — `--build release`, so the walk drives the
            app Play served this phone, with reseeding refused because that install is the user's
            own app and its dim level, warmth and schedule are real.
      - [x] A screenshot of the shade actually *down* — **closed as a refusal**, not as an omission.
            See §*The shade-down screenshot closes as a refusal* below; the short version is that the
            backlight override is a window attribute, so `screencap` reads the framebuffer before
            the panel scales it and the dim is simply not in the file. What *is* now shot is the
            next best true thing: the compact controls over a real article, which is the overlay
            doing its job on top of somebody else's app.
- [x] **The three emulator CI legs are vacuously green.** Closed — Phase 2 checkpoint F, both ways
      at once. The API 26 leg is cut (it could never have installed a `minSdk` 33 app), leaving
      33 + 36 on `aosp_atd`; and `app/src/androidTest` has its first test. `ShadeWindowTest` starts
      the real service and reads the real window out of `dumpsys window windows`, which is the only
      place the *effective* flags exist — a JVM test can only assert the constant we pass in.
      **Proven non-vacuous by mutation**: dropping `FLAG_NOT_TOUCHABLE` turns it red with the
      message it was written to print. Both legs green on PR #25, and green on the phone too
      (R10) once HyperOS let the instrumentation package install at all.
- [x] **Create the API-33 AVD** and run the end-of-phase pass on it (ADR-0008). Done 2026-08-31:
      `gloam-api33`, `system-images;android-33;google_apis;x86_64` on a Pixel 6 profile, created and
      booted headless entirely from the CLI — no Android Studio. R10 passed on it: the app launches,
      the shade window appears with every safety flag intact, and the permission flow works.
      **Two things it settled that had been guesses.** The emulator *does* honour the window
      brightness override, which `phase-1.md` §7 expected it not to — and it has no nits calibration
      at all (`mBacklight=null, mNits=null`, identity spline), so the float it applies means nothing
      photometric and **the phone stays the only place light is measured**. The readings block
      carries the numbers. Recreate with
      `emulator -avd gloam-api33 -no-window -gpu swiftshader_indirect`.
- [x] **Re-read ADR-0004 now `minSdk` is 33.** Done — Phase 2 checkpoint E, with the dated
      amendment on ADR-0004 rather than as a tidy-up. The `AppLocalesMetadataHolderService` entry is
      gone; AppCompat stays, because ADR-0006 needs `setDefaultNightMode` and the switcher is still
      `setApplicationLocales`. R12 read the phone afterwards: the framework holds `[pl]` across a
      force-stop, so nothing was propping the switcher up.
- [x] **Replace the placeholder domain.** Done: the `Item` domain and the database are gone
      entirely (ADR-0007), and `ui/dim/` is the app's one screen over `AppPreferences`.
- [x] **Choose the palette.** Done: dusk amber, warm taupe, twilight violet and warm grey, seeded in
      `scripts/gen_scheme.py`. All 22 contrast checks pass in both schemes. Re-read the stderr report
      if you touch a seed. The icon's ground is no longer a second copy of anything: it is a
      gradient generated from `mark.ground()`, and `ic_launcher_background.xml` is written by
      `make-launcher-icon.py` rather than checked by it.

## Before the polish half — Phase 5

The listing, the feature graphic and the screenshots **used to be here** and moved to *Before the
door* on 2026-08-30: a closed test will not open without them, so they are schedule, not polish.

**Phase 5 is being built and its detail is [`phase-5.md`](phase-5.md)** — six checkpoints, A-F.
Read that file for the sequence; what follows is only what this file is for, the boxes.
**A to E are done, all on 2026-09-17, and F is what is left.** A, the gate, came back as verdict 2:
Play rejected the one-byte Polish short description, which made C's `fix:` a blocker rather than
hygiene — and it also showed that *Manage store presence* is granted, that the screenshots validate,
and that a promotion is accepted before production access exists. B rewrote the four documents. C
fixed the metadata script and wrote the Polish listing. D shipped the rate-on-Play row, which makes
this release **0.8.0**. E took the readings, and R4 found a fault the documents had asserted away —
a restored *dimming is on* raising the shade on a phone nobody had dimmed — so the phase carries one
`fix:` and `PLAN.md` rule 3's test count is five rather than four.
**F is documents, then the release, then the promotion**, and only the promotion waits on the
closed-test window and Play's production-access review (*Before the door*).
**The counts in `phase-5.md` were stale and F reconciled them**: the app stores **fifteen** keys in
one file, plus three in a second, backup-excluded one, and declares **six** permissions, seven on the
artifact with AndroidX's. Read them off `AppPreferences.kt` and the manifest, never off a plan.

- [x] **The privacy policy is true of 0.7.1.** Rewritten 2026-09-17 in B, against
      `AppPreferences.kt` and the 0.7.1 artifact rather than against the plan. It was worse than this
      box said: besides the deleted `onboardingDone` and the five-that-were-four, **it never mentioned
      location, and 0.7.0 had shipped `ACCESS_COARSE_LOCATION` to the closed track the day before**.
      It now has a section on the lent location, names all six permissions and both Settings
      hand-offs, and lists three routes off the phone, the support mail among them. `docs/index.md`
      and the listing's English full description were rewritten from the same list. The listing
      also stopped calling the app *open source*, which `README.md` says it is not.
      **D added the rate-on-Play row's line on 2026-09-17**, inside the policy's *Google Play* item
      rather than as a fourth route, because the row leads to the same place. The row opens the
      listing in Play and tells it nothing about the user.
- [x] **`scripts/play-metadata.py` no longer sends a blank locale to Play.** Fixed 2026-09-17 in C,
      before any Polish copy, after R1 showed Play refusing the one-byte files. A blank field is now
      omitted, and blank and missing are one rule: a **warning** on the notes-only path and a
      **refusal** under `--strict`, which `publish-play-production.yml` passes only when
      `update_listing` is on. Both paths were run against the real document, and against one with
      the Polish section deleted. **Omission alone would not have been enough**, which §4 did not
      know: supply 2.240.1 saves a listing for every locale *directory*, and `pl-PL/` always exists
      for its changelog, so `--strict` is the actual guard on the listing path.
- [x] **The tip's exposure moved but did not shrink.** ADR-0009's amendment put the tip on the
      repository and the Pages site; the listing's Website field and the last line of its full
      description point at exactly those two pages. **No tip ships in Phase 5 on any surface it
      controls**, and ADR-0009 gets a third amendment saying why the second did not reach far
      enough. §6. **Written 2026-09-17**, with both alternatives kept rather than deleted, because
      the choice is reversible and the time to re-take it is when there is an audience. `README.md`'s
      sentence about a one-off tip stays: nothing to click is a statement about pricing.
- [x] **`docs/play-app-content.md`** — written 2026-09-17 in B. Every answer, what it rests on and
      what would change it, and the Data safety answer re-reasoned for the location permission
      against Play's own definitions. **It corrects `phase-5.md` §§2 and 7**: Play's Data safety page
      has no exemption for support flows, and the mail is *not collected* because Gloam transmits
      nothing, which is the reason to give.
- [x] **Every App content answer is in the Console** — confirmed by the owner 2026-09-17, the
      foreground-service declaration and the advertising-ID question included. Three *values* are
      still only in the Console rather than in `play-app-content.md` — the IARC category, the
      advertising-ID date, the `specialUse` wording — and each is a ⚠ there to copy at the next
      re-declaration. That is record-keeping, not a blocker.
- [x] **A restore is a claim nobody has read off a device.** ADR-0005's amendment says platform Auto
      Backup covers the preferences file; `shade_running` and `off_at_millis` are live state rather
      than settings and travel with it. §9. **A `data_extraction_rules.xml` cannot fix that** -
      backup exclusions are file-granular and all thirteen keys share one DataStore blob, so the only
      exclusion Android can express is *every setting*. The rule is enforced at the read instead.
      **Read 2026-09-17 (R4), and the read does not hold yet.** The restore itself raised nothing,
      the lent location stayed behind, and the settings came back byte for byte. But the next app
      update put the restored shade up, because `BootReceiver`'s only refusal for a restored
      intent is the overlay grant. With auto-off at *Never* there is no deadline to refuse on, and
      the notification permission had been reset, so the shade came up with no Stop button in the
      drawer. **Fixed the same day**, which is what closes this box: the intent carries
      `shade_began_at`, and `BootReceiver` refuses one older than this install's `firstInstallTime`,
      which Auto Backup never carries. `RestoredIntentTest` holds the comparison, both branches were
      read back on the phone, and ADR-0005 has the second amendment.
- [x] **The shade-down screenshot closes as a refusal**, not as an open box: a capture cannot show
      the backlight half at all, so the one image whose job is to show the product understates it.
      §8 carries the argument, which needs no device; R5 is a capture taken for 2b's benefit while
      the phone is already attached, not the thing the refusal rests on. **Refused, and R5 was read
      2026-09-17**: the shade is in the capture (×0.929 at dim 72), and the fall from 500 nits to
      2 nits is not.

- [x] **Write the privacy policy** and confirm GitHub Pages is serving `docs/`. Done 2026-08-30:
      `docs/privacy-policy.md` is written, and since PR #6 merged
      <https://srednimax.github.io/gloam/privacy-policy.html> answers 200 — the URL the Console's
      *Set privacy policy* field wants. Play requires a *hosted* URL, and an offline app has no
      server of its own, so this one waits on a merge to `main` rather than on a commit.
      `docs/index.md` was the template's `<App name>` placeholder while Pages served it publicly;
      it was written in PR #7, so the site root is Gloam's own and the listing's Website field has
      somewhere real to point.

- [x] **Add yourself as the sole required reviewer on the `production` environment.** Done
      2026-08-30, and read back from the API rather than from the settings page: the environment
      exists, its only protection rule is `required_reviewers`, and the reviewer is `srednimax`.
      It had to be created — the repo carried only `github-pages`, and
      `publish-play-production.yml` declares `environment: production`, which GitHub would have
      created implicitly on first run **with no protection rules at all**. The workflow's promise
      that "the environment holds it until you approve" would have been false the one time it
      mattered.
      **`prevent_self_review` is `false`, and must stay that way.** You are both the only account
      that can dispatch that workflow and its only reviewer, so turning it on would deadlock the
      production path outright — nobody left who is allowed to approve. It reads as a security
      tightening and is, here, a lockout.

## Phase 6 — seven more languages

**Phase 6 is done and its record is [`phase-6.md`](phase-6.md)**, on 2026-09-17. Czech, German,
Spanish, French, Italian, Brazilian Portuguese and Ukrainian: the app in nine languages and the Play
listing in nine. The decision it makes is
[ADR-0014](adr/0014-a-language-ships-on-an-audit-not-a-native-read-through.md), which **retracts
ADR-0004's promise of a native read-through** rather than adding a rule — seven reviewers were not
available, only the fluency half of a read-through needed one, and the half that outranks fluency is a
checklist answerable from the brief. **It carries no `fix:` commit and that is deliberate**: the two defects below were found inside the
phase, in languages no release had ever offered, so neither is a bug anyone could have met. A
changelog line for *"Portuguese could not be selected"* would be addressed to nobody.

- [x] **Seven translations, complete and gated.** 99 translatable resources × 8 locales, no plurals
      and no string-arrays in this app, so the CLDR trap is absent entirely. `translation-gate.py`
      reads *complete, and nothing stale*; `TranslationTest` holds format arguments, orphans and
      untranslatable resources as it always did.
- [x] **The brief's placeholders are written.** `translator-brief.md` §§1, 2 and 5 were
      `<Write this>` blocks, and **an audit against a placeholder is theatre** — §2 is now the three
      rules that outrank fluency, §5 the vocabulary table that keeps nine files saying *shade* and
      *brightness* the way `CONTEXT.md` means them, §3 records the register per language, and §8 is
      what a language ships on. `translation-gate.py`'s docstring pointed at a §8 that did not exist;
      it does now, and says the opposite of what the docstring used to claim.
- [x] **The audit, in place of the read-through.** Forbidden-claim scan over all 99 × 8: **eighteen
      candidates, all eighteen morphological false positives** (`сон` inside *сонця*, `oczy` inside
      *Samoczynne*), so zero rule violations. Escape-hatch audit held in all nine — every locale keeps
      the exclusive *only*, and names the notification's button with the same word the action uses.
- [x] **That invariant is a test now.** `TranslationTest` asserts a string quoting another by name
      still contains it, for two pairs. **Confirmed by deliberately mispairing it**, because a test
      whose body silently skips is worse than none.
- [x] **The report row.** *"Something read wrong?"* under the picker, one tap to the support hand-off,
      `Gloam #language` subject of its own, and the resolved locale stamped into every report — read
      off `resources.configuration`, since `currentAppLanguage()` is `null` for "follow the phone".
- [x] **Six of nine languages were unreachable, and the device sweep is what found it.** `LanguageRow` was a plain `Row`: correct at
      two chips, and at ten the device sweep found only *System*, *English*, *Polski* and *Čeština*
      laid out with **the remaining six not laid out at all**. Translated, declared, gated, complete
      and unselectable. **No gate in this repo could see it — none of them knows how wide a chip is.**
      It is a `FlowRow` now, with the comment `DimControls` already carried for the auto-off chips.
- [x] **`pt-BR` would never have matched.** `currentAppLanguage()` compared whole tags and the platform
      reports plain `"pt"`, so the chip would never have looked selected. Matches language subtags
      now, with `AppLanguageTest` asserting the uniqueness that makes it sound. **The reference repo
      has the same bug unfixed.**
- [x] **Nine listings and nine release notes.** `play-metadata.py --strict` and `notes-gate.py` both
      named the seven missing locales before a word was written, and both pass now. Five of seven
      full-description drafts were over Play's 4000 and were cut from enumerations and restatements —
      never from the touch-through paragraph, the floor limits or the closing health line.
- [x] **Per-locale screenshots.** Shot 2026-09-18, one invocation per language: 36 files in
      `art/play-screenshots/`, four scenes across `en, pl, cs, de, es, fr, it, pt-BR, uk`. About 75
      seconds per cell, so the whole set is a quarter of an hour rather than the day it was assumed
      to cost — which is the reason this box stayed open for two weeks.
      **Three defects the runs found, all of them invisible in a single cell:**
      the background scroll was a *fling*, landing on a different paragraph every run (a slow drag
      releases at zero velocity and is reproducible to ~2 px); the status bar was a photograph of
      this phone's notifications and network speed, which SystemUI demo mode empties; and the
      compact-controls background needed a checked Wikipedia title per language, taken from the
      English article's interlanguage links rather than translated.
      **What is bounded and what is not:** each cell is reproducible, but the *same* framing across
      nine languages is not — each Wikipedia is a different document, so one offset lands on
      different content. Per-locale review is the answer, and no gate can replace it.
      **Four of the nine needed their own drag in the end** — `it`, `fr`, `pt` and `uk` — and three
      of those needed *more* travel than one drag from 0.85 can give, which is why an entry in
      `WIKIPEDIA_SCROLL_END` may be a tuple. Each number was read off the picture: the one attempt
      to compute a distance was wrong by a factor of two and a half.

## Standing checks that never close

- [ ] **Every release: run the artifact checks on the built AAB**, not on the source.
      `aab-permissions.py` is the one that finds what a *dependency* merged into your manifest —
      a permission you never declared, or a `uses-feature` that quietly filters the app off devices.
      **All four pass on the 2026-09-02 bundle** — `versionCode` 84, `versionName` 0.4.0 — and this
      is the run that proved WorkManager's removal on the artifact rather than in the diff.
      **Six permissions now, and nothing is merged**: `WAKE_LOCK` and `ACCESS_NETWORK_STATE` left
      with WorkManager, and `RECEIVE_BOOT_COMPLETED` stayed but is now declared in the source by
      `shade/BootReceiver.kt` instead of inherited. The one entry that is not ours is AndroidX's
      signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `aab-reflection.py` shows the same
      removal from the other side: three `androidx.startup` initializers, WorkManager's gone along
      with the `tools:node="remove"` surgery that used to hold it back.
      **The hazard is not gone with it, only this instance of it** — which is why the check is a
      standing one.
      **Run again on the Phase 4 bundle, 2026-09-05: still six, still all accounted for**, none of
      the five forbidden ones present, no `uses-feature`. That is worth a line because the phase that
      adds a schedule is the one where a reader assumes a permission came with it. None did: an
      inexact alarm needs none, and the battery-optimisation exemption is a hand-off to Settings
      rather than the system dialog, which is what `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` would have
      been for. **"No new permissions" is a claim to read off the artifact, not off the diff** — the
      diff cannot see what a dependency merged in.
      **And again on the 2026-09-07 bundle, after the manifest was rewritten**: still six, still
      all accounted for. The launcher `<intent-filter>` moving to `ControlsActivity` changed 112
      lines of `AndroidManifest.xml` and not one permission — the five the source declares are
      the same five `v0.5.0` declared, and the sixth on the artifact is AndroidX's as before. A
      rewrite of that file is exactly the moment this check earns its standing place.
      **0.8.0 owes this run, and R7 is where it happens** (`phase-5.md` §15). The source declares
      **six** since `ACCESS_COARSE_LOCATION` arrived in 0.7.0, seven on the artifact with AndroidX's,
      and this release adds a row to a screen and a preference key — so the number to expect is the
      same six. A release whose only feature opens a URL in another app is exactly the one where
      nobody would think to look.
      **Phase 6 adds nothing to that number either**: seven locale files, a `FlowRow` and two mail
      strings. Expect the same six, and read it off the artifact rather than off this sentence.
- [ ] **Every release: read the release notes gate's output** rather than trusting it passed.
      **This is not hypothetical here.** Release PR #12 (`chore(main): release 0.3.0`) opened on
      2026-08-30 and its CI went red at this gate and nowhere else — `versionName is 0.3.0, but the
      newest release notes are for 0.2.0` — which is the whole of what has kept 0.3.0 off Play. The
      0.3.0 notes are now written in `store-listing.md`, and the gate learned to accept notes that
      run **ahead** of `versionName` so they could be written on an ordinary branch rather than on
      release-please's own, which the action force-pushes over. `RELEASING.md` carries the reasoning.
      **The release PR still has to regenerate over the new `main` before it goes green** — that is
      the thing to check rather than assume.
- [ ] **Every dependency bump: re-run `licensee`.** The build fails on an unallowed licence, which is
      the point — but the fix is two things, the allowlist *and* the bundled text.
- [ ] **Test the release-shaped build on a device.** A missing R8 keep rule does not crash; it makes
      a feature silently stop working.
