# Versioning & releasing

Versions are automated from git — you never hand-edit them. This mirrors what
`standard-version` does in the JS world, adapted to Android's two version fields.

## The two Android version fields (they are not the same thing)

| Field | Type | Who sets it | What it is |
| --- | --- | --- | --- |
| `versionCode` | `Int` | **git commit count**, computed in `app/build.gradle.kts` | The build's true identity. Must strictly increase or the installer/Play Store refuses it. Not semver. |
| `versionName` | `String` | **release-please**, from Conventional Commits | The `1.4.2` semver users see. The one you think of as "the version". |

Both are derived; neither is edited by hand.

## Writing commits (this is the part that requires discipline)

Every commit subject must be a [Conventional Commit](https://www.conventionalcommits.org):

```
<type>[optional scope][!]: <description>
```

- `feat: …`  → next release bumps the **minor** (1.0.0 → 1.1.0)
- `fix: …`   → next release bumps the **patch** (1.0.0 → 1.0.1)
- `feat!: …` or a `BREAKING CHANGE:` footer → bumps the **major** (1.0.0 → 2.0.0)
- `docs / style / refactor / perf / test / build / ci / chore` → no version bump, but `chore`/`docs` etc. still show in history

A committed hook (`.githooks/commit-msg`) rejects messages that don't match, so a
bad message can't sneak in. **Activate it once after cloning:**

```bash
git config core.hooksPath .githooks
```

(It's plain shell — no husky/Node. Bypass in an emergency with `git commit --no-verify`.)

The same setting turns on `.githooks/pre-push`, which prints what the branch still
owes the translation gate before you push. It is **advisory and cannot fail a push**:
completeness is a merge boundary, not a work boundary, and a branch mid-translation is
ordinary. It exists only so you hear it in a second rather than
from CI four minutes later. Silent when there is nothing outstanding.

## How a release happens

1. You push normal Conventional Commits to `main`.
2. The **Release Please** GitHub Action keeps a PR open titled like
   `chore(main): release 1.1.0`. That PR bumps `versionName` and updates
   `CHANGELOG.md` — the changelog is generated from your commit subjects.
3. When you want to cut the release, **merge that PR**. release-please then tags
   the repo (`v1.1.0`) and creates a GitHub Release. Merge whenever you like;
   nothing ships until you do.

Config lives in `release-please-config.json` + `.release-please-manifest.json`
(the manifest holds the current version — release-please rewrites it).

### Merge pull requests with **rebase**, never a merge commit

The repo allows rebase merging only, and that setting is load-bearing for the
changelog rather than a matter of taste.

GitHub writes the PR title into a merge commit's *body*. `conventional-commits-parser`
strips the `Merge pull request #N from …` header and parses what follows as the
commit — so a PR titled `feat: …` whose branch also carries that `feat: …` commit
is counted **twice**, and the release notes list the feature twice. That is exactly
what 1.1.0's first draft did, for all five of its features; the four `fix:` entries
escaped only because those PR titles happened to be `chore:` or plain prose, which
is luck, not a rule.

Rebase replays the branch commits onto `main` with no merge commit in between, so
every conventional subject is counted exactly once and each commit keeps its own
changelog line. Squash would also de-duplicate, but it folds a PR's individual
subjects into a single entry taken from the PR title — and this repo writes a
meaningful subject per commit (PR #72 alone contributed three separate `fix:`
lines), so squashing would throw away detail the changelog exists to carry.

There is no release-please option for this. The merge strategy *is* the fix, which
is why it is enforced by the repo setting instead of by remembering.

## Release notes are a merge gate, not an afterthought

`scripts/notes-gate.py` runs in CI on every pull request, beside the schema and translation gates. It
asserts one thing: the newest `### x.y.z` under `## Release notes` in
[`store-listing.md`](store-listing.md) is the `versionName` about to ship.

It exists because `play-metadata.py` uploads **the newest notes it finds** and nothing else consults
them. Bump `versionName` without moving the notes and the previous release's text goes up attached to
the new build, with nothing to notice it. That is how **1.9.0 reached production describing 1.8.0** on
2026-08-26 — a note predating the timeline, multi-photo trays, kilogram entry and the light/dark
override. ⚠️ **Play does not allow release notes to be edited on a live release**, so the correction
could not be made where it was needed; it waited for the next upload.

The gate is invisible on ordinary branches — `versionName` is still the last released version and the
notes already match. It fails on **release-please's PR**, which is the one moment the two are allowed
to disagree and the last moment it is free to fix.

**Where the notes get written, and where they must not be.** The obvious place is release-please's
own PR branch, because that is where `versionName` already carries the new number. It is the wrong
place: the action **force-pushes that branch** every time anything lands on `main`, so a note
committed there survives only until the next merge and its loss is silent. Write them on an ordinary
branch instead, before the release PR merges. **The gate allows notes to run ahead of `versionName`
and fails only when they fall behind**, which is what makes that possible — and behind is the
direction that actually shipped 1.9.0 describing 1.8.0, so that half stays fatal.

Nothing can ship the wrong text in the window between the two. `publish-play.yml` uploads the bundle
with **no release notes at all**, and `publish-play-production.yml` — the only workflow that runs
`play-metadata.py` — is manual, held by the `production` environment's reviewer, and points at a
released tag where `versionName` and the newest heading agree again. The residual case worth naming
rather than pretending away is a `workflow_dispatch` of a publish against `main` mid-window; that is
already off-pattern, because `versionCode` is the commit count and a publish wants a tag.

**Worked example — 0.3.0.** Release PR #12 opened on 2026-08-30 and its CI went red at this gate and
at nothing else: *"versionName is 0.3.0, but the newest release notes are for 0.2.0"*. That was the
whole of what stopped 0.3.0 reaching Play — not the build, not the credentials, not Play. The notes
were written on an ordinary branch rather than on the release branch, which is the change that taught
the gate to accept notes running ahead; the release PR then has nothing left to fail on once it
regenerates over the new `main`.

**When nothing owner-visible changed, satisfying it is a rename, not a rewrite.** Move the heading to
the new version and say why the bodies stand unchanged; 1.8.0 is the worked example. The gate reads
the heading, so "these notes still hold" stays a decision someone made rather than the default.

```bash
python3 scripts/notes-gate.py            # the gate itself
python3 scripts/notes-gate.py --report   # what does this branch owe?
python3 scripts/notes-gate.py --pending  # will the release this branch feeds have notes?
```

It also checks what the notes must satisfy to be usable at all: a note for every locale the AAB
carries, and every one inside Play's 500 characters.

**`--pending` moves the failure from the release PR to the branch that caused it.** The gate can only
fail once release-please has bumped `versionName`, and by then the fix is a separate docs PR — which
is how 0.2.0, 0.3.0, 0.4.0, 0.7.0 and 0.9.0 all went. `--pending` predicts the number instead: the
manifest's version on `origin/main`, bumped by the conventional commits since the last release plus
this branch's own, read the way release-please reads them. Replayed against every release from 0.2.0
to 0.9.0 it proposed the version release-please actually cut. It exits **2** when that version has no
`### x.y.z` on the branch or on `main`, and 0 when there is no release pending — a docs-only branch
never owes notes.

**Claude Code runs it before every `git push` it makes** (`.claude/settings.json`, a `PreToolUse`
hook filtered to `Bash(git push*)`, so no other command starts it). Exit 2 blocks the push and hands
Claude the message, so the notes get written on the branch before it leaves; any other failure — the
script itself broken — only warns, because a broken check must not stop every push. It fetches
`origin/main` first, since a stale `main` would miss a `feat:` merged elsewhere. It does **not** run on
pushes from your own terminal; `.githooks/pre-push` stays advisory and about translations.
⚠ It checks that the heading **exists**, not that it describes everything. A second `feat:` branch
toward an already-noted 0.10.0 passes, so adding its line to the notes is still a judgement.

## Checking the artifact before it reaches Play

`bundleRelease`, never `assembleRelease` — Play wants an AAB and an AAB can't be
`adb install`ed, so the only build ever put on the phone is the one Play delivers.
Then read the version fields back **out of the artifact**, not out of the config
that was supposed to produce it:

```bash
./gradlew bundleRelease
python3 scripts/aab-version.py        # versionCode/versionName vs. git
python3 scripts/aab-permissions.py    # the <uses-permission> set, vs. an allowlist
python3 scripts/aab-locale.py         # every string of every shipped locale, vs. the resource table
python3 scripts/aab-reflection.py     # the classes only the manifest names, vs. what R8 left in the dex
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

All four scripts **exit non-zero** rather than printing and leaving you to read.
Each exists because the corresponding claim was once wrong in an artifact while
every source-side check was green: `versionCode` 1 on a signed bundle (3a), Polish
missing from the build that went up (fixed in 1.0.1), a permission set that had
quietly grown from two to six (found at 4h), and R8 shrinking away the no-arg
constructor of a class only the manifest names, which disabled the guided document
scanner without raising anything (10c). The pattern is the same every time — the
config said one thing, the artifact said another, and nothing compared them.

`aab-reflection.py` is the newest and the one whose failure is quietest, because
its subject never crashes: a class discovered by `Class.forName` and built with a
no-arg constructor just isn't there, and the framework that wanted it carries on
without it. It reads the classes to check **out of the manifest** — every
`<meta-data>` whose value marks one — rather than from a list that would go stale,
and looks each up in the dex. `aab-permissions.py` does the other half of the
same job: it asserts the only `android:screenOrientation` in the artifact is the one
this app decided on — `MainActivity`, portrait, since 2026-09-13 — so a dependency
bump cannot quietly lock another screen, and an edit cannot quietly unlock that one.
(A library AAR pinning its own delegate activity to portrait is a real Play policy
finding, and it is invisible in your own source.) It was a blanket ban until 0.7.0,
which is how 0.7.0 failed to publish: the app had grown a deliberate lock and the
gate was still asserting the app had none.

Don't reach for `aapt2 dump xmltree` here. An AAB stores its manifest as
**protobuf**, not the binary XML aapt2 reads, so it prints nothing and exits `0` —
it doesn't fail, it just declines to answer. That silence is how a release
produced a *signed* bundle carrying `versionCode` 1 and didn't find out until
later. `scripts/aab-version.py` decodes the protobuf and asserts the count matches
`git rev-list --count HEAD`.

## Reaching Play automatically (internal testing)

`.github/workflows/publish-play.yml` builds and uploads to the **internal testing** track
when a **GitHub Release is published** — that is, when you merge the release-please PR.
Nothing uploads on a bare push to `main`, so cutting a version is still a deliberate act;
this only removes the hand-build that used to follow it.

What the workflow does, in order: full checkout (`fetch-depth: 0`, because `versionCode`
is the commit count), materialise the upload key from secrets, `bundleRelease`, run all
four `aab-*.py` artifact checks, print the signing certificate, upload the AAB **and its
R8 mapping**, keep both as a build artifact for 90 days, delete the key.

The mapping matters from 1.9.0: R8 is on, so without it every Play crash report is
obfuscated frames.

**It has run green end to end once**, on 2026-08-31: run `33412104006`, tag `v0.3.0`,
attempt 4, 2m53s, AAB and mapping on the internal track. The three attempts before it were
one cause - `androidpublisher.googleapis.com` disabled in Cloud project `118298064751`,
then its propagation delay - and they were paid in `workflow_dispatch` re-runs rather than
in versions. Retry before you debug.

⚠️ **The upload step warns on every run**: `r0adkll/upload-google-play@v1.1.5` reports
`'track' is deprecated and will be removed in a future release. Please migrate to
'tracks'`. It uploads correctly today, and it is written down because of when the removal
would otherwise be discovered - a pinned action bump breaking a release is the worst
possible moment to read a deprecation notice for the first time.

### The upload key

Created 2026-08-30 and the only one there will ever be, short of a reset with Google:

| | |
| --- | --- |
| File | `~/.keystores/gloam-upload.jks` — **outside the repo**, and `*.jks` is gitignored as a second line of defence |
| Type / alias | PKCS12, alias `upload`. PKCS12 keeps **one** password, so `upload.storePassword` and `upload.keyPassword` are the same string |
| Key | RSA 4096, SHA384withRSA, valid until 2054-01-15 |
| SHA-256 | `EE:39:4F:B1:F1:AD:62:67:E9:88:AA:63:90:E2:8F:28:9B:19:22:CF:46:EC:72:86:1D:D8:01:A5:F7:78:22:1B` |

**That fingerprint is the one to compare** against what Play shows as the expected *upload*
certificate. It is not the app-signing certificate: Play re-signs with a key Google holds, which is
why losing this one is recoverable at all — and why a build signed with the wrong key is rejected at
upload rather than breaking installs.

Read it back off any artifact with `keytool -printcert -jarfile <aab>`. The four values live in
`local.properties`, which is gitignored and is the only place the password exists on this machine.

### The five secrets this workflow needs

Four for the upload key, one for Play. `RELEASE_PLEASE_TOKEN` is a sixth, needed
earlier and by a different workflow — see *Setting up a new repository*. Set them under *Settings → Secrets and variables →
Actions*:

| Secret | What it is |
| --- | --- |
| `UPLOAD_KEYSTORE_BASE64` | the keystore file itself, base64: `base64 -w0 /path/to/upload.jks` |
| `UPLOAD_STORE_PASSWORD` | same value as `upload.storePassword` in `local.properties` |
| `UPLOAD_KEY_ALIAS` | same value as `upload.keyAlias` |
| `UPLOAD_KEY_PASSWORD` | same value as `upload.keyPassword` |
| `PLAY_SERVICE_ACCOUNT_JSON` | the whole service-account JSON, pasted as-is |

⚠️ **The keystore is still never committed** — this changes where a copy *lives*, not the
rule. The one on disk stays outside the repo (ADR-0005); base64 in a GitHub secret is a
second copy, and losing control of it means resetting the upload key. That is recoverable
(Google holds the permanent app-signing key) but it is not free.

### Creating the service account — the part that is not in this repo

Once, by hand, and it is the only step CI cannot do for itself:

1. Google Cloud console → the project linked to your Play developer account → **enable the
   Google Play Android Developer API**.
2. **IAM & Admin → Service Accounts → Create**. No project roles are needed; its authority
   comes from Play, not from GCP.
3. On that account, **Keys → Add key → JSON**. The file downloads once — that is the
   `PLAY_SERVICE_ACCOUNT_JSON` value.
4. Play Console → **Users and permissions → Invite new user**, the service account's email.
   **Account permissions: none — every box empty.** That is the field that would quietly make this a
   credential for every app on the developer account rather than for this one.
   **App permissions, on this app only** (your `applicationId`), exactly two boxes:
   *View app information (read-only)* — the baseline the others build on — and
   *Release apps to testing tracks*, which is the one that does the work.
   Deliberately **not** *Release to production, exclude devices, and use Play App Signing*: production
   stays a human decision behind the environment gate. Also not *Manage testing tracks and edit tester
   lists* — that is editing who the testers are, and CI uploads builds rather than managing people.

**One service account per app, and scope it with a permission group.** Play Console → *Users and
permissions* → **Permission groups** → *Create permission group* lets you pick the two boxes once and
attach the group to an email afterwards, instead of re-ticking them per identity. Name it for the app
(`Gloam — CI release`), not for the role: a group carries its own app scoping, so one role-shaped group
reused across apps would have to list every app in it — which merges exactly the credentials that a
separate service account per app exists to keep apart. ⚠️ **Leave *Set access expiry date* unchecked.**
It is offered on the create screen and it is wrong for a machine identity: on the expiry date publishing
starts failing, wearing the same 401 that the propagation delay below tells you to shrug off.
Invite the email before creating the group — a user must exist to be selectable in *Users in this group*.

⚠️ **The production workflow uses this same secret, and wants two more boxes.** With testing-track
rights only, the first run of `publish-play-production.yml` **403s** — that is a permission missing
rather than the pipeline broken. It needs *Release to production, exclude devices, and use Play App
Signing*, and — **only when run with `update_listing: true`** — *Manage store presence*, because that
is the run that pushes descriptions, images and screenshots. **Release notes need neither.** They are
part of the track release rather than the listing, so they ride up on the release permission; that is
why the workflow renders metadata unconditionally and still skips the listing fields by default.
Granting these is the deliberate act that opens the production door, and leaving them ungranted is a
fourth brake on top of the three that workflow already carries.

**Where Gloam's account stands, read off a run rather than off this page.** *Manage store presence*
**is granted**: Phase 5's gate (`docs/phase-5.md`, R1, 2026-09-17) ran this workflow `internal → alpha`
with `update_listing: true` and `dry_run: true`, and it uploaded three screenshots and both locales'
changelogs before Play's validator stopped it on a *field* — a listing write that a missing permission
would have refused with a 403 first. The first run that **committed** a listing came two days later,
on 2026-09-19: `internal → alpha`, `update_listing: true`, `dry_run: false`, versionCode 220 (0.9.0)
— nine listings and 36 screenshots, and Play took the edit into review. 220 was already on alpha,
promoted by `publish-play-closed.yml` the night before, and re-promoting it was accepted without
complaint, so **a listing-only push is this workflow re-promoting what alpha already holds**.
⚠ **Until that run, the Console showed the old listing, and nothing had failed.** Every earlier
listing run was a dry run, which Play validates and discards, and the closed-testing workflow skips
the listing by construction. A green closed promotion therefore says nothing about the listing. And
the listing does not wait for production: it belongs to the app rather than to a track, so any
promotion through this workflow carries it.
*Release to production* has **not** been read either way: a promotion onto a testing track does not
need it. A dry run `internal → production` on 2026-09-18 failed with `Google Api Error: Invalid
request - Precondition check failed.` rather than a 403, read as the production track not being open
to the account yet (the closed-test window and the access application, `DOD.md`), so it says
nothing about the permission. The first production promotion after access is granted is the run
that finds out, and a 403 there is this paragraph rather than a broken pipeline. The setup steps
above still describe the two boxes a *fresh* account starts with, which is what they are for.

**Nor is any of it needed to reach closed testing.** `publish-play-closed.yml` promotes onto a
testing track and skips the listing unconditionally, so the two original boxes cover it - which
is why it works on a first run where the production workflow 403s.

**Nothing above is needed for the automatic internal pipeline.** `publish-play.yml` sends the AAB and
its mapping and nothing else — no metadata, no images, no notes — which is why two boxes cover it.

Propagation between Play and the API is not instant — a permission granted in the Console
can take a few minutes to be visible to the API, so a first run that 401s is worth simply
re-running before debugging it.

### Why internal, and what it does not prove

Internal processes in minutes with no Google review, which is what makes it a sane
automatic target. ⚠️ **It is not the track for an upgrade proof.** An internal-track
install demands an uninstall on the device where a closed-track one updates in place, so a
build that arrives this way cannot stand in for "an existing owner's install survived the
update". Promoting to closed testing is what `publish-play-closed.yml` below is for, and
production has its own workflow after it. Both are manual: neither happens without you
clicking *Run workflow*.

## Reaching closed testing (manual, promotes)

`.github/workflows/publish-play-closed.yml` - **Run workflow**, never automatic, for the same reason
production is not: no event means "the build on internal is ready for the twelve". You decide that, so
you trigger it. It promotes the build already sitting on internal to a **closed** track, and like the
production workflow it never runs `bundleRelease` and never materialises the upload key, because it
has nothing to sign.

| Input | Default | What it does |
| --- | --- | --- |
| `track` | `alpha` | destination closed track. Free text, because Console's *Create track* makes ids nobody can enumerate here |
| `version_code` | *(blank)* | which build to promote; blank asks Play what is on internal |
| `release_version` | *(blank)* | which release's notes go up; blank takes the newest in `docs/store-listing.md` |
| `dry_run` | `false` | validate against Play, commit nothing |

The source track is hardcoded to `internal`. That is the whole point of a second workflow: it is the
one promotion made over and over during a closed test, and a dialog field whose only correct answer is
`internal` is a field that can be got wrong.

**It cannot reach the public, by construction.** Play has exactly three tracks that are not closed
testing - `production`, `beta` (open testing) and `internal` - and every other track id is a closed
one, because Console's *Create track* only makes those. So the first step is a deny list of three,
which is a complete list, and a typo in the `track` box fails the run instead of publishing something.
That guard is also what pays for the missing approval gate: this job declares no `environment`,
because a required reviewer is what stands between a build and every *owner*, and there is nobody to
protect from a build reaching people who asked to test it.

⚠ **`dry_run` defaults to false here**, where production's defaults to true. A closed promotion is
reversible from the Console - halt the release - and cannot reach anyone who did not opt in, so making
every routine promotion a two-run ritual would only teach the habit of clicking through the first one.
Turn it on for the first run of this workflow, and any time the dialog holds a value you are unsure of.

**It needs no new Play permissions.** It stays inside the two boxes the service account already has -
*View app information* and *Release apps to testing tracks* - because it never touches the listing.
The three `--skip_upload_*` flags for metadata, images and screenshots are unconditional rather than an
input, and that is a permission boundary rather than a preference: descriptions, screenshots and
graphics belong to the *app* and not to a track, so pushing them from a test promotion would edit the
**public** listing and would want *Manage store presence*. Release notes are part of the track release,
so they still ride up - `--skip_upload_metadata` does not skip changelogs.

**Always `completed`, never a rollout fraction.** A closed test wants all twelve testers on the same
build. A fraction would quietly hold some of them back on the previous one, and since the 14-day
window is measured on testers being opted in, a tester who never receives the build is a tester
quietly not testing.

⚠ **`release_version` is the field that goes wrong in silence.** `play-metadata.py` renders the newest
`### x.y.z` under *Release notes* in `docs/store-listing.md`, which is the right answer only while the
checkout still points at the release being promoted. Promote an older build from a `main` that has
moved on and the testers are told about a version they do not have - exactly the failure
`scripts/notes-gate.py` exists to prevent, one ref away. Run the workflow from the tag, or name the
version in the dialog.

⚠ **The first release on a closed track waits on Play review.** A green run means the promotion was
accepted, not that any tester has the build. Later releases on a track Play has already reviewed
appear within minutes.

## Going to production (manual, gated)

`.github/workflows/publish-play-production.yml` — **Run workflow**, never automatic. It is the only
path that can reach every owner, so it carries two independent brakes, and a third you can opt into:

1. **You trigger it.** `workflow_dispatch` only; no push, tag or schedule reaches it.
2. **You approve it.** The job declares `environment: production`. ⚠️ **Add yourself as the sole
   required reviewer on that environment in repo settings** — without a reviewer configured the
   environment is just a label and gates nothing.
3. **Staged rollout, when it is worth having.** `rollout` defaults to `1.0`, everyone, since
   2026-10-11: with Gloam's user count a 10% stage reaches almost nobody, so it only delays the
   release without catching anything first. Pick `0.1` or lower once there are enough owners for a
   fraction to mean something, and widen from the Console once it looks clean.

And a fourth for the first run: **`dry_run` defaults to true**, which passes `--validate_only` —
Play validates the whole edit and discards it. Nothing publishes, nothing is sent for review. Untick
it when you mean it.

⚠ **A new app's production track targets no countries, and the API cannot fix that.** The first
committed production run (Gloam 1.0.0, 2026-09-25) uploaded all nine listings, 36 screenshots and the
changelogs, then failed at the commit with `Google Api Error: Invalid request - Release in track
targeting no countries`. Play discards a failed edit whole, so nothing went live. The fix is one-time
and Console-only: *Test and release → Production → Countries / regions → Add countries / regions*,
then re-run. A **dry run does not catch this.** The 2026-09-18 dry run stopped earlier, on the
production track not being open yet, so no validate-only run had ever reached this check. Tick the
countries before the first real run.

### It promotes; it does not build

The artifact that reaches production is the **exact one `publish-play.yml` already put on the
internal track** — same bytes, same signature, the copy you actually tested. This workflow never
runs `bundleRelease` and never materialises the upload key, because it has nothing to sign.

Rebuilding here was the original shape and it could not have worked. `versionCode` is the commit
count, so rebuilding a released tag reproduces a code Play has already accepted on internal, and
Play refuses a **new upload** of a versionCode it has seen:

```
[!] Google Api Error: Invalid request - Version code 423 has already been used.
```

Two tracks *sharing* a versionCode is not the problem — that is precisely what promotion produces,
and it is why promoting by hand in the Console never hit this. The refusal is about uploading the
same code twice. `--skip_upload_aab` / `--skip_upload_apk` are what turn the supply call into a
promotion: it moves the release already sitting on `from_track` instead of looking for a binary.

| Input | Default | What it does |
| --- | --- | --- |
| `track` | `production` | destination - also `beta` / `alpha`, though closed testing now has its own workflow above |
| `from_track` | `internal` | source track holding the build being promoted |
| `rollout` | `1.0` | fraction of users; `1.0` is everyone, `0.1` a tenth |
| `version_code` | *(blank)* | which build to promote; blank asks Play what is on `from_track` |
| `update_listing` | `false` | push descriptions + screenshots as well |
| `dry_run` | `true` | validate against Play, commit nothing |

**The versionCode comes from Play, not from git.** Every other workflow derives it from the commit
count, and doing that here is wrong in a way that looks right: the count describes the *checkout*,
while a promotion is about a build uploaded earlier. They agree only while `main` still points at the
release tag, and stop agreeing the moment anything else merges — which is a normal state to be in and
produces `Track 'internal' doesn't have any releases`, an error about the version filter that reads
like an error about the track. So the workflow asks the source track what it is holding. Any ref
works, and there is no dialog field to get right.

⚠️ A **halted** release is not promotable — Play hides the Promote button and the API will not
report it. Resume it in the Console first.

### One edit, one review

The bundle, the release notes and — when `update_listing` is on — the descriptions and screenshots
all go up in a **single** `supply` invocation, which is a single Play edit. Play reviews an *edit*,
not a field, so that is **one** review cycle. Uploading the build now and fixing the listing after is
two edits, two reviews and two waits.

Release notes always accompany the build; they are scoped to a version and mean nothing without it.
`update_listing` governs only the descriptions, screenshots and graphics.

### Why fastlane here and Python everywhere else

`scripts/play-metadata.py` renders `docs/store-listing.md` into the tree `supply` expects, at run
time, and it is gitignored — the markdown stays the only authored copy, so a listing change is still
one reviewable diff. What `supply` is bought for is one specific transaction: replacing a listing's
screenshots means **deleting the old set before uploading the new one**, and first-time code that
dies between those steps leaves the public listing with no screenshots at all. That is worth someone
else's mileage. Ruby is confined to this workflow; the internal-track one never loads the `Gemfile`.

⚠️ **Changelog files are named by `versionCode`, not semver** — `409.txt`, not `1.9.0.txt`. supply
keys notes to the build, and a name Play cannot match is ignored in silence rather than rejected.

⚠ **An incomplete locale stops a listing upload, and only a listing upload.** supply saves a
listing for every locale directory it is given, whichever `.txt` files are in it, and a directory
exists for every language with release notes. So a language whose descriptions are blank reaches Play
as an empty listing, and Play refuses the whole edit. `play-metadata.py --strict` refuses it first,
and the production workflow passes that flag exactly when `update_listing` is on. Every other run only
warns: supply skips listings there, and a release must not stop over copy it is not uploading.

## Setting up a new repository (once)

**None of this is inherited.** GitHub's template mechanism copies files and nothing else: no
rulesets, no branch protection, no secrets, no Pages setting, no merge-strategy preference. A repo
created from the template starts with `main` wide open and every item below undone — and the build
stays green throughout, so nothing tells you. The first symptom is a release PR that never appears,
which reads like a broken template rather than an unconfigured repository.

Most of it is one command:

```bash
python3 scripts/repo-setup.py --dry-run   # what it would change, and what is already right
python3 scripts/repo-setup.py             # do it
```

It creates the `main: require CI` ruleset with the right check names, sets rebase-only merging,
enables Pages from `docs/`, and reports which secrets are still missing. It is idempotent — run it
again after any manual change and it reports what is already correct rather than fighting you. It
verifies the required check names against `ci.yml` first, because a required check that CI never
publishes leaves every PR stuck at *"Expected — waiting for status to be reported"* with no way out
but deleting the ruleset.

**The one step it cannot do is the token.** GitHub has no API for minting a personal access token,
so do this by hand, first:

1. https://github.com/settings/personal-access-tokens/new — fine-grained, *Only select
   repositories* → this one, and exactly two permissions: **Contents: read and write**,
   **Pull requests: read and write**. (`Metadata: read-only` is added automatically; leave it.)
   The token's *name* is an account-unique label of your choosing and has nothing to do with the
   secret name — reuse across repos is impossible, so name it for the repo it serves.
2. `gh secret set RELEASE_PLEASE_TOKEN --repo <owner>/<repo>` and paste the value.

Without it `release-please.yml` fails in about seven seconds with `Input required and not supplied:
token`, and no release PR is ever opened.

What the script sets, and why each one matters, is in the sections above: **rebase-only merging**
(*Merge pull requests with rebase*), **the `main` ruleset** — no bypass actors, requiring both CI
contexts, and note how it interacts with the token, since a release PR opened by `GITHUB_TOKEN`
gets zero CI jobs and this rule then blocks its merge permanently — and **Pages**, because Play
demands a *hosted* privacy-policy URL and an app with no backend has no server of its own.

The **five Play secrets** (table above) are the exception: wanted at the first upload, not before.

### The template repository does not version itself

`release-please.yml` carries `if: github.event.repository.is_template != true`. The template ships
no app, so a release PR on it bumps a `versionName` nothing consumes and then fails the notes gate —
correctly, since that gate demands Play release notes for a version that will never reach Play.

**A repo generated from the template is not itself a template**, so the condition is false there and
release-please runs in full: release PR, `CHANGELOG.md`, the `versionName` bump, the tag, the GitHub
Release and the Play upload. Nothing is disabled downstream, and there is nothing for `bootstrap.py`
to rewrite.

## Gotchas

- **`versionCode` in CI debug builds is `1`.** GitHub's checkout is shallow, so the
  commit count can't be read. That's fine for debug. For a *release* build, do a full
  checkout (`fetch-depth: 0`) or build locally so the count is real.
- **The release workflow needs a `RELEASE_PLEASE_TOKEN` secret** — a fine-grained PAT
  with *contents: write* and *pull-requests: write* on this repo. The built-in
  `GITHUB_TOKEN` can't be used: GitHub won't run workflows on a PR that `GITHUB_TOKEN`
  opened, so the release PR's CI sits at `action_required` with zero jobs, and the
  `main: require CI` ruleset (no bypass actors) then blocks the merge forever. If the
  secret is missing or expired, the workflow fails and no release PR appears at all.
- **We start at `0.1.0`** on purpose — an app is pre-1.0 until you decide it is not.
  While the major is `0`, release-please stays in pre-release: `fix:` → `0.1.1`,
  `feat:` → `0.2.0`, and even a breaking `feat!:` bumps the *minor* (`0.2.0`), it
  does **not** auto-jump to `1.0.0`.
- **Cutting `1.0.0`** is a deliberate act: put a footer in a commit body —

  ```
  feat: the last thing 1.0 was waiting on

  Release-As: 1.0.0
  ```

  — and the next release PR targets `1.0.0`.
