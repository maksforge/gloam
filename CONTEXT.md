# Vocabulary

The words this app uses, and the ones it does not.

Gloam has one unusually sharp naming problem for an app of its size: two completely different
mechanisms both look like "brightness" to a user, and only one of them is ours. Android's backlight
has a floor; Gloam's whole reason to exist is the range below it, which it reaches by drawing a dark
overlay rather than by turning the backlight down further. A word that blurs the two produces code
where nobody can tell which mechanism a value refers to, so the distinction is enforced here.

**The rule this file exists to enforce: one concept, one word, everywhere.** The entity, the DAO, the
route key, the string resource and the sentence in the UI all use it. When the domain word and the
user-facing word genuinely differ, both are written here with the difference stated.

## The domain

| Term | Means | Not |
| --- | --- | --- |
| Dim level | The single number the user moves, 0-100. The one value the product is about. | Not "brightness" - it runs the opposite way, and brightness is the system's word |
| Shade | The **untouchable** overlay window Gloam draws above every other app. The mechanism that reaches below the floor. | Not "filter", "mask" or "layer" - and not the panel |
| Panel | The **touchable** overlay window carrying the controls, sized to fit them and never to the display. A second window with the opposite safety rule. | Not "the overlay" - there are two |
| Compact controls | The same controls in an Activity, which is what puts them **below the shade**: a small, dialog-shaped host reached from the icon or the Quick Settings tile. On screen it is called *the controls* - and so is the panel, because a user meets them as one thing. | Not the panel, which is the same controls in an overlay window *above* the shade. Not "the smaller one" - the panel is small too, and the difference that decides whether either can be read is which side of the shade each sits on. Not "compact mode" - there is no mode |
| Bubble | The small circle the controls shrink to when they are put away over a running shade, and that a start from the Quick Settings tile leaves when Gloam is not in front: dragged anywhere, resting docked to a side edge as a half-round tab, tapped to bring the controls back, dropped on the ✕ to remove. A **touchable** window above the shade, bounded by its size (ADR-0016). | Not "chat head" or "floating button". Not the panel - it carries no controls |
| Floor | The dimmest the Android backlight goes on this device. What Gloam exists to get past. | Not "minimum brightness" in code - too easily read as our own minimum |
| Backlight | The system screen brightness. Android's value, which Gloam lowers to the floor as the first half of the dim level's ramp before the shade does the rest. | Never our own value |
| Backlight top | The backlight Gloam takes over from. Captured the moment the override is applied and held until it is released. **Not a device constant** - it is where the user left their own brightness, so it decides how much of the slider the backlight half is worth. | Not "maximum brightness" - it is the user's starting point, not the panel's ceiling |
| Warmth | How far the shade is tinted. It **adds** a tint, amber to deep red by the warmth colour, over an already-dark screen, shifting the average colour. A separate control from the dim level, but not an unbounded one: the two together decide whether anything underneath stays legible, so both are capped. | Not "night mode" - that name is already the app's own dark theme. Not "cutting blue" - an overlay composites source-over and can only add light, never subtract a channel from what is underneath |
| Warmth colour | Which tint warmth adds: a point on a fixed path from amber to deep red. It moves the **hue only**. Every point adds the same light, so it never changes how legible the screen stays, and the blue let through is warmth's alone. Set in the full app; the compact controls and the panel carry warmth without it | Not "colour temperature" - an overlay adds a colour on top, it does not change the temperature of the light. Not "filter colour", for the same reason "filter" is avoided below |
| Preset | One of a **fixed** set of named starting points - *Reading*, *Evening*, *In bed* - each a dim level, a warmth and a warmth colour. Tapping one writes those three values and nothing else: it never starts the shade, and a shade already up follows the new values. **Nothing about a preset is stored**: which one is lit is worked out from the current values, so moving any control off it unlights it. | Not "mode" - "night mode" is the theme and "compact mode" is ruled out above; a preset is not a state the app is *in*, only a way of setting values. Not "profile" |
| Saved preset | A preset the user names: the current dim level, warmth and warmth colour saved under their own word, shown as a chip after the three built-in ones. Saving under a name that exists replaces it; renamed or deleted by touching and holding its chip, or from *Edit* when lit. Main screen only - the compact controls have no presets yet. | Not "custom mode" or "profile". Stored, unlike which preset is lit - that is still worked out from the values |
| Running | The shade is on screen and the service is in the foreground. A live state. | Not "enabled", which reads as a settings toggle |
| Deadline | The one instant at which the shade next comes down, whoever set it. There is only ever one. | Not "auto-off" - that is one of the things that sets it. Not "timer" |
| Auto-off | The duration a hand-started shade lasts before it comes down on its own. One-shot - it never repeats - and choosing a duration re-sets the deadline from that moment, whether the shade is already up or not. | Not "timer" on its own - the schedule sets deadlines too |
| Pause | A short break in dimming that the user asks for: the shade is cleared and the backlight handed back for a length in whole minutes - 1, 5 or 15 on the full screen's pause chips, or any length from 1 to 120 typed under *Other…*, which only adds it as a chip of its own (a tap on that chip is what pauses) - remembered for the compact controls' single button - and then both come back on their own, with the **backlight top** captured afresh. The service, its notification and the stored intent stay as they are, so a paused shade is still **running**. It never outlasts the deadline: one that falls inside the pause takes the shade down and nothing comes back (ADR-0012's amendment). While it runs, the full screen counts down and the bubble and the compact pause button show a ring of the time left. *Resume now* or moving any control ends it early, and stopping ends both. Held in memory only, so a ROM kill ends it early and a backup never carries one. | Not "snooze" - nothing rings, and a snooze would be a writer of the deadline, which a pause is not. Not stopping: the intent, the deadline and the notification are untouched. Not the brightness slider being "paused" while the override is live - that is the system's slider; this is Gloam's dimming |
| Schedule | The nightly window. Either **fixed times**, on at one clock time and off at another, or **sunset to sunrise** at the schedule's location, which falls back to the fixed times on any night the sun gives no answer. One window a night, not two independent switches. | Not "alarm" - nothing rings, and Gloam claims no exact-alarm permission. Not "twilight" or "dusk" - sunset is the moment weather apps print, not the darker one after it |
| Location | Where sunset and sunrise are worked out for. The **estimated location** is the reference city of the phone's time zone and needs no permission. A **lent location** is the phone's approximate position, read only if the user allows it and kept on the phone. | Not "GPS" or "precise location" - Gloam never asks for the precise one. Not "position" in the UI |
| Escape hatch | A surface that stops the shade and can be reached without seeing Gloam's own UI, which the shade may be covering. Not every control that stops the shade is one. | Not the app's own *Stop dimming* button, which sits under the shade at high dim levels |
| Tip | An optional one-off payment to the developer through Google Play. It **unlocks nothing** - every feature is free either way. Asked about unprompted exactly once per install, on the home screen (ADR-0009). | Not "donation" or "support the project" - it is paid to a person. Not "purchase", "premium" or "pro" - nothing is bought |

## Words we deliberately avoid

- "Brightness" never refers to a Gloam value. It is Android's word for the backlight, the user
  already has a system slider called that, and reusing it makes every function signature ambiguous.
  Ours is the dim level, and it counts the other way: more dim level is less light.
- "Night mode" is the app's own light/dark theme and nothing else. `ThemeMode` and `NightMode.kt`
  already own it. The amber tint over other apps is warmth. These are separate settings that a user
  can set in opposite directions, so they cannot share a name.
- "Filter" is avoided entirely. Blue-light filter apps have made it mean warmth to some people and
  dimming to others, and Gloam does both as separate controls.
- "Off" is reserved for the shade not being drawn at all. A dim level of zero is still running - the
  service is alive, the shade is transparent - and the two states behave differently on a reboot.
  It follows that **auto-off cannot itself be switched "off"**: the choice that disables it is
  **Never**, because "auto-off: off" is a sentence meaning the shade stays on.
- "Overlay" on its own is avoided now that there are several of them. The shade passes every touch
  through; the panel catches them. A name that covers both hides the one property that keeps the
  user from being trapped, so say **shade** or **panel** and never "the overlay window".
- "Screen dimmer" is fine in the listing and the README, where it is what people search for. It is
  not a code word; nothing is named `Dimmer`.

## Naming conventions in code

Some names in this codebase are deliberately generic — `AppTheme`, `AppPreferences`, `AppContainer`,
`MainApplication`, `MainActivity`. They stay correct whatever the app is called, so
renaming the app is a package move rather than a repo-wide find-and-replace. Don't rename them to
match the product; name the *domain* types after the domain instead.
