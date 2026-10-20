---
layout: default
title: Privacy Policy
---

# Privacy Policy

_Last updated: 20 October 2026_

Gloam is an Android app that dims your screen below the darkest setting Android offers. This policy
describes what it does with information. It is short because the app does very little with any.

## The app collects nothing

Gloam has no account, no sign-in, no server, and **no network access of any kind**. That is not a
promise about intent: the app does not hold Android's `INTERNET` permission, so it is not capable of
sending anything anywhere, and a check runs against every release build to confirm that permission
has not appeared.

There is no analytics, no crash reporting, no advertising, and no third-party SDK that phones home.
The one Google library in the app, which handles tips through Google Play, carries usage logging of
its own; without the `INTERNET` permission it has no way to send it.

## What the app stores

Settings, in the app's private storage on your own device, readable only by the app:

- how dark and how warm you set the screen, the colour of that warmth, and whether Gloam may lower
  the backlight
- whether the shade should be on, and when it is due to come down
- how long a shade you start by hand stays up
- your theme choice, and whether the app icon opens the small controls or the whole app
- the nightly schedule: whether it is on, whether it follows two clock times or sunset and sunrise,
  the two times, and which night it last acted on
- how many times the shade has been started, and whether you have answered the one-time mention
  of the tip
- **only if you allow it**, your phone's approximate location — see the next section

Installs from before September 2026 may also hold one retired colour setting that nothing reads any
more.

Apart from that approximate location, none of it is about you: no identifier, no history, nothing
about what you read or which apps you use. None of it leaves your phone by any route the app
controls.

## Your approximate location, and only if you choose it

The schedule can follow the sun, coming on at sunset and going off at sunrise. To work out when
those are, Gloam needs to know roughly where you are, and it has two ways to know:

- **An estimate, which needs no permission.** Gloam takes the main city of your phone's time zone.
  This is what it uses unless you choose otherwise.
- **Your phone's approximate location, if you allow it.** When you choose *sunset to sunrise* on
  the schedule screen, Android asks whether Gloam may have your **approximate** location. If you say
  no, Gloam uses the estimate, and *Use this phone's location* on the same screen lets you change
  your mind later. Gloam never asks for your precise location, and never for location in the
  background.

If you allow it:

- Gloam reads the location **only while Gloam is open on your screen**, and only while the schedule
  follows the sun. It never reads it from the background, and never during the night's scheduled
  window, so a reading cannot move tonight's times once they have started.
- It keeps **one** location, rounded to about 11 km, together with your time zone. Each new reading
  replaces the last one. There is no history.
- It uses that location for one thing: working out sunset and sunrise.
- It keeps it in a separate file that is **excluded from Android's backup and from transfer to a new
  phone**, so it stays on this phone.

You can take the permission back at any time in Android's settings for Gloam. From then on nothing
new is read, and the schedule goes back to the estimate. The last location Gloam kept stays on the
phone until you uninstall Gloam or clear its storage in Android's settings.

## What the app can access, and why

- **Display over other apps.** This is the app itself. Gloam dims by drawing a dark layer above
  whatever you are using, which is the only way to go below the system's minimum brightness. The
  layer passes every touch straight through to the app underneath and reads nothing about it. The
  controls Gloam can open over other apps read nothing about them either.
- **Notifications.** While the shade is on, Android requires a permanent notification, and Gloam
  wants one anyway: it is how you reach the controls and turn the dimming off from anywhere,
  including from a screen that has become too dark to read. It contains no information about you.
- **Running while you use other apps.** Two related permissions let the shade stay on screen after
  you leave Gloam, which is the normal way to use it. They are also why the notification exists.
- **Knowing when the phone has started.** If the shade was on when your phone switched off, Gloam
  puts it back when the phone starts again, and it sets up the schedule again, because Android
  forgets an app's timers when it restarts.
- **Approximate location**, optional, as described above.
- **Google Play billing.** Lets Gloam ask the Google Play app for the price of a tip and open its
  payment sheet. It gives Gloam nothing about you.

Of these, only location is information about you, and the app has no way to send anything anywhere.

## Two things the app asks your phone for that are not permissions

Neither appears in Android's permission lists, because both are settings screens rather than
permissions. Gloam sends you to them and cannot change either one itself:

- **Battery optimisation**, asked only when you turn the schedule on. Unless you exempt Gloam,
  Android will not let it start the shade by itself at the time you picked. The schedule screen
  tells you when the exemption is missing.
- **Autostart**, on phones whose makers add that setting, such as Xiaomi. Without it, those phones
  may not let Gloam put the shade back after a restart, or start it on schedule. No app can check
  this setting, so Gloam can only point you to it.

Declining either one changes nothing else. The feature it is for may simply not work on your
phone.

## The three things that happen outside the app

None of these is done by Gloam, but together they are the honest answer to "where could my data
be":

1. **Android's automatic backup.** If it is enabled on your phone, Android backs up app settings to
   your own Google Drive, in a space that does not count against your storage quota and that Google
   states apps cannot read. For Gloam that means the settings listed above, **except the approximate
   location, which is never included.** You can turn backup off in your phone's settings.
2. **An email you choose to send.** *Help and feedback* can open your own mail app with a message to
   the developer already started. Below the space for your message, it adds the Gloam version, the
   Android version, and your phone's maker and model, because a problem report nearly always needs
   them. You can read and delete every line of it before sending, and nothing is sent unless you
   send it, from your mail app. If you do, the developer receives your message and your email
   address like any other email, uses them only to reply, and will delete them if you ask.
3. **Google Play.** Installing and updating an app is a transaction between your phone and Google,
   with its own privacy policy. The developer receives aggregate, anonymous statistics from Play —
   install counts, crash rates, country-level breakdowns — and no information about individuals.
   *Help and feedback* also has a row that opens Gloam's page on Google Play, in case you want to
   rate it. The row opens Play, or your browser if the phone has no Play, and tells it nothing
   about you. A rating or review you leave there is handled by Google under Google's policy, and
   reaches the developer only through Google.

   **A tip, if you leave one, is paid to Google Play.** Gloam asks the Play app on your phone for the
   prices and opens Play's own payment sheet; your payment details go to Google and never to Gloam.
   Tips unlock nothing, so Gloam keeps no record of one. The developer receives the payment and
   Google's order report, which identifies the order but not your payment details, and uses it only
   for accounting.

## Children

The app is not directed at children, and collects nothing from anyone.

## Deleting your data

Uninstalling Gloam, or clearing its storage in Android's settings, removes everything it stored on
the phone, the approximate location included. There is nowhere else to delete it from, because it
went nowhere else. The one exception is an email you chose to send, which you can ask the developer
to delete, including one sent to the earlier support address, gloam.dimmer@gmail.com.

## Changes

If this policy changes, the date at the top changes with it, and the full history of this file is
public in the app's repository.

## Contact

Questions about this policy, and requests to delete an email you sent, can be sent to
**gloam.support@maksforge.com**, the address also listed on the app's Google Play listing and on the
[app's website](index.md).
