# Translator brief

Hand this to whoever translates the app — including yourself with a machine translator, which is a
legitimate first pass and never a last one.

**Sections 1 and 2 are yours to write** for this app. Everything from section 3 on is craft that
transfers between apps unchanged, and is the reason this file is worth having rather than a one-line
"please translate `strings.xml`".

Translate from **English only**, never from another translation: every interpretation the first
translator made gets compounded, silently.

## 1. What the app is, in one paragraph

Gloam is a screen dimmer for people who read in the dark and find their phone's lowest brightness
setting still too bright. It takes the screen *below* that setting by drawing a dark layer — the
**shade** — over everything, and it can warm the colour at the same time. The person reading this
copy is in bed with the lights off, one hand on the phone, eyes adapted to the dark; they are not at
a desk and they are not shopping for features. So the copy is quiet and short, it never exclaims,
and it assumes the reader is mildly annoyed rather than delighted. Every feature is free, there is no
account and no premium tier, and nothing here is ever selling anything.

Two consequences for the wording. **The app is not a health product** — it makes a screen darker,
which is a comfort, and §2.2 is what keeps it that way. And **the screen it is describing may be
nearly black while being read**, which is why several strings are blunt to the point of terseness:
they get read at the very bottom of the brightness range, sometimes by somebody trying to undo what
they just did.

## 2. The rules that outrank fluency

Three of them. They are the promises this copy makes, and a translation that breaks one is wrong in a
way no reviewer of style would flag. **These are also the checklist a language is audited against
before it ships** ([ADR-0014](adr/0014-a-language-ships-on-an-audit-not-a-native-read-through.md)),
so they are written to be answerable string by string.

### 2.1 The escape hatch stays findable, and its wording is a safety claim

The shade covers every other app, including Gloam's own screen. The notification is how a person
stops it from anywhere, and when Gloam is not allowed to post one, **the app says so and says that
the current screen is the only way out.**

- *"the only way to stop dimming is this screen"* must stay **exclusive**. Not *"you can also stop it
  here"*, not *"one way to stop it is here"*. The whole sentence exists to tell somebody they must
  not leave this screen.
- The warning title names a button — *"No **Stop** button outside Gloam"* — and that word must be
  **the same word** as the notification's own action (`shade_notification_stop`). The user is being
  told to look for something and then has to recognise it. A test enforces this pair; it is in the
  brief because a test cannot tell you *why*.
- Nothing in this group gets softened for politeness. It is the one place in the app where being
  slightly rude is correct.

### 2.2 No health claims, ever

Gloam is declared to Google Play as a non-health app, and that declaration has to stay true of every
language. **Never introduce**: *eye strain*, *eyes*, *sleep*, *melatonin*, *blue light*, *protects*,
*healthier*, *rest your eyes*. Not as a benefit, not in a hint, not "helpfully".

The two places the temptation is strongest, because this is what apps of this shape usually say:

- **Warmth.** It is a colour, described as a colour. The English says the tint shifts towards amber or
  red — it never says why anyone might want that.
- **Flicker and the schedule.** The flicker copy says some people find flicker *tiring*
  (`tiring`, not *harmful*, not *damaging*); the lock-screen copy talks about the brightest thing in
  a dark room, which is **glare**, not sleep. Keep both at that level exactly.

And a vocabulary rule that comes from the same place: **never translate the app's warmth as a
"filter"**, in any language. An overlay adds light on top; it cannot subtract a colour from what is
underneath, so "blue-light filter" would be a false claim as well as a forbidden one.

### 2.3 Never state what the app cannot know

Two settings decide whether Gloam can start itself — the phone's autostart permission and its battery
optimisation — and **Gloam cannot read either one back.** So that copy names where to look and
promises no outcome:

- *"this is the setting to look at"* stays a suggestion. Never *"turn this on and the shade will come
  back"*.
- *"Gloam has no way to check whether it is"* is not hedging to be tidied away; it is the honest part
  of the sentence.
- Where the English says **this phone** it means the vendor's behaviour, and where it says **Android**
  it means the platform's. They are different claims and blaming the wrong one makes the sentence
  false on somebody's phone. Keep them apart.

## 3. Register

Decide it once and write it down, because it is the thing a translator most needs told and most
often guesses.

- **Who the app addresses, and how formally.** Many languages force a choice English does not: `du`
  or `Sie`, `ty` or `Pan/Pani`, `tu` or `vous`. Pick one and hold it across every string.
- **Whether the app speaks as "we" or is invisible.** "We couldn't save that" and "That couldn't be
  saved" are different products.
- **Sentence case or title case** in buttons and headings. English tolerates both; most languages do
  not use title case at all, so a translated title-cased heading reads as a mistake.

### Decided, for this app

**Sentence case everywhere**, including buttons. **Gloam speaks of itself in the third person** — "Gloam
cannot dim the lock screen" — never as "we"; there is one person behind it and a corporate *we* would
be a fiction. And the app addresses the reader **informally**, in every language that forces the
choice **except French**:

| Language | Form | Note |
| --- | --- | --- |
| Polish | *ty* | Verb forms chosen to avoid gendered past tenses — see 6.2 |
| Czech | *ty* | |
| German | *du* | Android's own German UI is *du*; a *Sie* app inside a *du* system reads like a letter from a bank |
| Spanish | *tú* | European vocabulary, nothing that reads oddly in America |
| French | **`vous`** | The deliberate exception: Android's French UI is *vous*, and *tu* reads as over-familiar rather than friendly |
| Italian | *tu* | Not *Lei* |
| Portuguese (BR) | *você* | Brazilian vocabulary — *tela*, *celular*, *aplicativo* |
| Ukrainian | *ти* | Translate from English, **not from Polish**: the two are close enough to pull false friends through and close enough that nobody would notice |

The name **Gloam** is never translated or transliterated, including into Cyrillic — it is the word on
the launcher icon and the user has to recognise it. It may inflect where a language needs it to
(Czech *Gloamu*, Ukrainian *Gloam* left undeclined), which is grammar rather than translation.

## 4. Do not translate

Three kinds of string, marked `translatable="false"` in `strings.xml` so lint and the gate leave
them alone. If you find yourself wanting to translate one, that is worth a conversation rather than
an edit.

- **The app's own name.**
- **Language names in the switcher.** They are **endonyms** — each language named *in its own
  language*, in every locale — so someone who has landed in a language they cannot read can find
  their way out by a name they recognise. `settings_language_english` reads "English" in every file.
- **Brand names, and anything with a legal identity.** A product name that is the same in every
  market stays the same; translating it invents a different product.

### Things that look untranslatable and are not

- **Unit symbols.** SI symbols look invariant and are not: spacing, the decimal separator, and
  sometimes the symbol itself change by locale. Keep them in resources.
- **Numbers inside sentences.** Grouping and decimal separators are locale-specific. Never build a
  number into a string by hand.
- **Dates and times.** Format them through the platform, never by concatenating a resource.
- **Sample and debug data.** Not resources at all — they live in `src/debug/` and never ship.

## 5. Vocabulary

Keep a table here of **every English word that appears more than once**, with the target word for
each context. This is the single highest-value section of the file.

The trap it exists for: **one English word is often several target words.** English *Normal* covers
six different nouns in six different genders; a translator working string-by-string will produce a
different form each time, all of them defensible, and the app will look sloppy in a way no test
catches. Likewise *Name*: a person's given name and an organisation's name are different words in
most languages.

| English | Resource ids | Why they differ | Example |
| --- | --- | --- | --- |
| | | | |

Words that genuinely *are* one word everywhere — *Settings*, *Close*, *Edit*, *Delete* — belong in
the same table with a note saying so, otherwise someone will vary them for variety.

### The table, for this app

Gloam's naming problem is unusually sharp for an app this size, and [`CONTEXT.md`](../CONTEXT.md) is
where it is settled: **two completely different mechanisms both look like "brightness" to a user, and
only one of them is ours.** Android's backlight has a floor; Gloam exists for the range below it,
which it reaches by drawing a dark layer instead. A translation that blurs the two produces an app
that appears to have two brightness sliders that disagree.

| English | Where | The rule | Watch for |
| --- | --- | --- | --- |
| **dim level** | `dim_level_label`, and the number beside it | Gloam's own value, 0–100, counting **upwards into darkness**. Needs a word built on *dim / darken / obscure*, never on *bright* | A translation that says "brightness 40%" inverts the meaning of the whole screen |
| **brightness** | `dim_backlight_*`, `settings_lock_screen_body`, `settings_flicker_body`, `shade_notification_text_backlight` | **Android's** value, the one with the system slider. Use the exact word the phone's own settings use in that language | Using this word for the dim level, or inventing a synonym for it |
| **shade** | Everywhere the dark layer is meant | One word per language, held across all ~20 strings that mention it. Not the literal window blind (`roleta`, `Rollo`, `persiana`, `store`, `tapparella`, `жалюзі`) and **never** *filter* (§2.2) | Varying it for variety: the same object is called the same thing in the permission body, the schedule hints and the notification |
| **warmth** | `dim_warmth_label` | The tint's strength. A word built on *warm* | *Colour temperature* — forbidden, and wrong: an overlay adds a colour, it does not change the light's temperature |
| **warmth colour** | `dim_warmth_color_label` | Which tint, amber → deep red. Hue only. Short — it sits in a narrow column | *Filter colour*, for the same reason |
| **floor** | `dim_column_floor` | The dimmest Android's own backlight goes. Rendered as a short all-caps word; *minimum* is the right sense in most languages | Anything reading as *Gloam's* minimum — it is the phone's |
| **the controls** | `controls_open_app`, `panel_close`, `settings_controls`, `settings_launcher_compact*` | One word for the small window of sliders, wherever it appears. The user meets two different windows as one thing, so they must share a name | Two different words in the Settings label and the Close button |
| **schedule** | `schedule_*`, `dim_schedule_*` | The nightly window. Not *alarm* (nothing rings) and not *timer* | *Timer*, which belongs to auto-off and not here |
| **preset** | `preset_saved_hint`, `preset_save`, `preset_replace_note`, `preset_name_taken`, `preset_edit_title` | A saved combination of dim level, warmth and colour — the built-in three and the user's own are the same thing. One word, held across all five | The language's **Settings** word (Spanish *ajuste*, Polish *ustawienie*): "Save as preset" then reads "save as a setting". Spanish *preajuste*, Polish *zestaw* |
| **Never** | `compact_auto_off_never` | The choice that disables auto-off. It is *Never*, not *Off* — "off" is reserved for the shade not being drawn at all | Translating it as *Off*, which would read as "the shade stays on" |
| **Stop** | `shade_notification_stop`, and quoted in `dim_notification_warning_title` | The notification's action. The same word in both places — §2.1 | |
| **location** | `schedule_location_*` | The approximate position sunset is worked out for. Gloam never asks for the precise one | *GPS*, and *precise location* |
| **Settings, Close, Back, Language** | throughout | Genuinely one word everywhere. Use the platform's own term and do not vary it | |

## 6. Traps

### 6.1 A substituted fragment has to fit its sentence

English composes freely because it barely inflects. `"in the last %1$s"` with `"30 days"` works;
the same substitution in an inflecting language needs the fragment in the right case, and the case
depends on the host sentence.

**Pre-inflect the fragment for its host, and say in a comment which host it belongs to.** If one
fragment is used by two hosts that need different cases, it needs to become two resources — that is a
code change, and it is cheaper to find now than after the translation.

### 6.2 The app knows nobody's gender

Not the user's, not the subject's, not any third party's. English hides this behind *they* and *you*.
Most other languages do not, and a translator forced to choose will pick one, producing copy that is
wrong for half the users and reads as an apology to the rest.

**Every string with a human doing something in it needs checking**, and the fix is usually to rewrite
the *English* into a form that does not need a gender — a noun phrase instead of a verb with a
subject. That is a change to the base language, made once, rather than a workaround in eight files.

### 6.3 Names the app cannot touch

Anything the user typed — a name, a label, a note — is substituted verbatim into a sentence. It
cannot be inflected, so the sentence has to be built so that it does not need to be. Watch for
possessives and for any preposition that would govern a case.

### 6.4 Plurals

**A count-dependent string is a `<plurals>`, never string concatenation, and never `if (n == 1)`.**

Which categories a language uses is **CLDR's** decision, not the translator's. English has `one` and
`other`; Polish has `one`, `few`, `many` and `other`; Arabic has six. Supplying only the English pair
in a language that needs four is a silently wrong app — `TranslationTest` fails the build on it,
which is the whole reason that test exists.

`%d` is not always the right placeholder either: some categories in some languages read better
without the number, and `quantity="one"` in Polish means *exactly* one, not "the singular".

### 6.5 Format arguments

- **Positional (`%1$s`), never bare (`%s`), whenever there is more than one.** Word order changes; a
  translator must be able to reorder the arguments, and bare placeholders make that impossible.
- **The same arguments must appear in every locale.** A missing one crashes at format time, on a
  screen you did not test in that language. `TranslationTest` checks this continuously.
- **A literal `%` must be escaped as `%%`.**

## 7. What happens to your draft

1. It goes into `res/values-<tag>/strings.xml`, or — while it is still a draft — into
   `translations/<tag>/` one directory up, which is staged and reported but **never shipped**.
2. `TranslationTest` checks it mechanically: format arguments, plural categories, orphans,
   untranslatable resources, array lengths. This runs on every build.
3. `scripts/translation-gate.py` checks it is *complete*, and that nothing has gone **stale** — the
   English changed and the translation did not. A stale translation is invisible to every other
   check: it parses, it carries its arguments, and it says something the app no longer means.
4. The language is offered to users only when its tag is added to `res/xml/locales_config.xml` and
   `AppLanguage`. Until then it exists and reaches nobody, which is the correct state for a draft.

## 8. What a language ships on

**An audit against §2 and a report row — not a native speaker's read-through.**
[ADR-0014](adr/0014-a-language-ships-on-an-audit-not-a-native-read-through.md) is the decision and
the reasoning; the short version is that seven reviewers were not available, that only the *fluency*
half of a read-through needed one, and that the half which outranks fluency is a bounded checklist
answerable from this brief and the string.

So, before a language goes into `locales_config.xml`:

- **Every string in §2's three groups is read against §2**, in that language. Escape-hatch copy keeps
  its *only* and its shared button word; nothing has acquired a health claim or the word *filter*;
  nothing promises an outcome the app cannot see.
- **The §5 table is checked for consistency**, because that is the failure that makes an app look
  sloppy in a way no test catches.
- **It is compiled, installed and looked at.** A staged draft has never met `aapt2`: clipped labels
  and lint's plural warnings are invisible until it does, and neither needs a native speaker to see.

After it ships, *"Something read wrong?"* under the language picker is the route back, and fluency
findings arrive as ordinary bug reports. **What a report must not do is talk the app back across §2**
— the reporter has the fluency, and this file has the reasoning.
