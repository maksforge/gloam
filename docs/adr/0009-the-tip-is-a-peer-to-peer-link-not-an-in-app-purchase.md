# The tip is a peer-to-peer link, not an in-app purchase

## Context

`CLAUDE.md` and `README.md` both promise the same thing: *"an optional one-off tip that unlocks
nothing."* An earlier reading of Play's Payments policy — carried over from another project, which
investigated it and withdrew its own tip — concluded that Play most likely forbids this, and
`docs/PLAN.md` recorded the app as *"currently promising something it probably cannot ship."*

Re-read against the live policy text, that conclusion is wrong, and the carve-out is not the
tax-exempt one:

> "In cases where 100% of the tip or contribution from a user goes to the creator and the payment
> does not grant access to any digital content or services (including stickers, badges, special
> emojis etc.), then we regard this as a peer-to-peer payment and use of Google Play's billing
> system is **not** required."

That is Gloam's promise restated in Google's own words. The §4 anti-steering rule the earlier
investigation ran into — *"apps may not lead users to a payment method other than Google Play's
billing system"* — carries explicit exceptions for §3, §8 and §9, and the peer-to-peer carve-out
**is** §3.2.

Separately, and deliberately **not** relied on here: since October 2025, extended by the March 2026
settlement, Google no longer requires Play Billing or forbids external payment links at all. That
change applies only to *apps serving users in the United States*, and Gloam's listing serves Poland,
so the carve-out above is what actually does the work.

## Decision

**The promise stays, and the tip ships as an external peer-to-peer link** on the Support screen
(Phase 5). No billing dependency, no Console product, no server, no account — consistent with every
other claim the app makes about itself.

Three conditions are load-bearing rather than incidental, because they are exactly what §3.2 tests:

- **100% reaches the developer.** Not a project fund, not a split.
- **Nothing unlocks.** No badge, no theme, no thanks-screen that non-tippers do not get.
- **It is a payment to a person**, and the wording says so. "Tip", not "donate to the project".

## Alternatives

**A Play Billing consumable.** Unambiguously permitted, and the safe answer if the reading above is
ever contested. It loses on cost rather than on policy: a billing dependency, a Console product,
Google's cut, and an *"in-app purchases"* badge on the listing of an app whose entire pitch is that
there are none.

**Drop the tip and delete the promise.** The cheapest option, and it was the earlier project's
answer. It loses because the promise turns out to be shippable as written, and removing it on a
misreading would have quietly made the app worse for no reason.

## Consequences

- This rests on a **reading of policy text, not on an approval**. If a reviewer disagrees, the fix is
  to remove one link in an update — no code and no feature depends on it, which is the reason to
  prefer the link over anything more entangled.
- The listing copy must not describe the tip as a donation to a project or cause. That wording, not
  the mechanism, is what would move it out of §3.2.
- The framing sentence in `CLAUDE.md` — every feature free, no paid branch to keep alive — stands
  unamended, and now has a policy reading behind it rather than an assumption.

Amendment, 2026-08-30: **the reading above is correct on the text and was never checked against
enforcement.** That is the flaw in it. The policy language was re-verified live on this date and has
not moved — Google's own help page still says a tip where 100% reaches the creator and nothing is
granted "is a peer-to-peer payment and use of Google Play's billing system is not required", and the
anti-steering rule still reads "developers may not lead users to a payment method other than Google
Play's billing system **unless Section 3, 8, or 9** of Payments policy applies". Section 3 is where
peer-to-peer payments sit. On the text, nothing here was wrong.

What was missing is that apps have been rejected under this rule anyway:

- **StreetComplete, February 2022.** Rejected for displaying Patreon, Liberapay and GitHub Sponsors
  links in-app, citing the Payments policy. The links were removed. **And the rejection extended to a
  link to the project's own home page, because that page carried donation information** — which is
  the finding that matters most here, and the one no reading of the policy text would predict.
- **The owner's own recollection of the previous app**, which is what reopened this: a Buy Me a
  Coffee link there was reported as against policy. The original context section above recorded that
  app as having "investigated and withdrawn" its tip, which understated it.
- Other developers report the same, including a rejection over a link to a social profile that
  eventually led to a page carrying payment options.

**None of those is this decision's shape, and the difference is exactly the three conditions above.**
Patreon sells tiered memberships, so things unlock. Liberapay and GitHub Sponsors fund a *project*,
not a person. Buy Me a Coffee is a platform that takes a cut, so 100% does not reach the creator, and
it sells membership tiers too. A single personal payment link, worded as a tip, unlocking nothing, is
the one shape §3.2 actually describes. But that distinction has to survive a reviewer who is
skimming for "external payment link", and the reports above show the appeal path is poor.

Two things have also changed since the decision, neither of which helps a tip. The Ninth Circuit
injunction (October 2025) stopped Google forbidding external links **for users in the US**, and the
external-links and alternative-billing programs that followed still charge a service fee, with
reporting obligations from 1 October 2026. For the EEA, which includes Poland, the policy now permits
leading users outside the app "subject to program requirements" — a sanctioned route that costs
enrollment and fees. Enrolling in a fee program to collect occasional tips is not a serious option,
so §3.2 remains the only route that costs nothing, and it remains a reading rather than an approval.

**So the decision is amended, not reversed. The promise stays and the mechanism stays; the risk moves
out of the first release.**

- **No payment link ships inside the app in v1.** The Support screen (Phase 5) carries no tip link.
- **The tip lives on the repository and the Pages site**, which the policy explicitly allows:
  "Outside of your app, you are free to communicate with your users about alternative purchase
  options."
- **Nothing the app links to may itself carry the tip.** This is StreetComplete's actual finding, and
  it constrains the Support screen's source-code link: point it at a page that does not carry a tip
  button, or accept that the link is the violation.
- **Revisit once the app is live**, with something to lose and a track record to appeal from, rather
  than at first submission where a rejection costs the closed-test window.

If it is ever put back in-app, the wording is the whole defence: a tip to a person, not a donation to
a project, and visibly unlocking nothing.

Amendment, 2026-09-17 (Phase 5, §6): **the second amendment's own third bullet reaches further than
it noticed, and where it reaches is the listing.** It removed the payment link from the app and put
the tip on the repository and the Pages site — then required that nothing the app links to carries
the tip, and named the Support screen's source-code link as the thing that constrained. That link
does not exist. What does exist is the **listing**: its Website field points at the Pages site, and
the last line of its full description points at the repository. Both are exactly the pages the second
amendment sent the tip to, and StreetComplete's rejection is the same shape — it reached a project
home page the app pointed at.

So the rule holds at one level out, and the decision is plainer than before: **no tip ships on any
surface this release controls.** Not in the app, and not on the two pages the listing links to. The
promise in `README.md` and `CLAUDE.md` — that there may one day be a one-off tip that unlocks nothing
— stays exactly as written, because a sentence with nothing to click is a statement about pricing
rather than donation information.

The two alternatives are kept rather than deleted, because this is reversible and someone will want
to re-take it with an audience:

- **Take the GitHub line out of the full description and keep the Website field tip-free**, so the
  tip can live on the repository alone. It costs the listing's *"read it, verify it"* line, which is
  part of how a source-available app earns trust, and it buys a tip nobody sends during a closed
  test. **This is the option to take later**, which is what the second amendment meant by *revisit
  once the app is live*.
- **Ship it on both pages and accept the exposure.** The three conditions genuinely hold — one
  person, 100%, nothing unlocks — and the policy text permits it. It loses on the same ground as
  before: the distinction has to survive a reviewer skimming for an external payment link, and the
  appeal path from a pre-launch rejection is poor.

**What the app's freeness never depended on:** none of this changes what ships. Every feature is
free either way, which is the whole reason the tip can wait.

Amendment, 2026-10-11: **the tip ships, and it ships as the first alternative above — a Play Billing
consumable — rather than as a link.** The app is in production, which was the second amendment's
trigger to revisit, and re-taking the question with an audience the owner chose the route with no
policy risk at all over the one with no cost. A link the listing never names reaches almost nobody,
and the reports above show what an external payment link risks on an app that now has something to
lose.

- **Four consumable one-time products**, `tip_small`, `tip_medium`, `tip_large` and `tip_xlarge`, at
  5, 10, 20 and 50 PLN with Play's own conversions everywhere else, each with Play's multi-quantity on
  — the nearest Play comes to an amount the user picks. Every purchase is consumed at once, which also
  satisfies Play's three-day acknowledgement; nothing is stored, because nothing is unlocked. The same
  four ids and prices are the owner's standard for every free app, Binky included.
- **Two surfaces.** A section on *Help and feedback*, present only where Play will sell a tip, and a
  card on the home screen shown **once per install** to somebody who has had Gloam a week and started
  the shade ten times. Either button on the card answers it for good, and the answer is backed up.
- **What it cost, as the alternative said it would.** A billing dependency; Google's 15% (the account
  is enrolled in the reduced tier); a *Contains in-app purchases* label on a listing whose pitch is
  that nothing is for sale, which the tip's own wording — it unlocks nothing — has to answer. And one
  cost the alternative did not foresee: the Play Billing Library depends on Google's `datatransport`,
  which merges `INTERNET`. The manifest removes it again and `aab-permissions.py` asserts it absent,
  so the privacy policy's *no network of any kind* still holds; billing is IPC to the Play app and
  never needed it.
- **The three conditions still describe the tip** — one person, nothing unlocked, worded as a tip —
  but they are no longer load-bearing for policy. They stay because they are the promise in
  `CLAUDE.md`, not because a reviewer reads them.
- **The listing still names no tip**, for a reason that is now about space rather than risk: four
  locales' full descriptions sit within five characters of Play's limit. The label Play adds says
  that purchases exist; a line saying what they are is a listing change of its own.
