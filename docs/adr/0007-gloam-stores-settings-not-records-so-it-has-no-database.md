# Gloam stores settings, not records, so it has no database

## Context

Gloam was generated from a template built around an app that keeps things for its owner: a table of
records, photos attached to them, an export you can carry to a new phone, and a carefully defended
story about migrating all of it across versions. ADR-0001, ADR-0002 and ADR-0005 are that story.

Gloam is a screen dimmer. Everything it needs to remember is a dim level and whether the shade
should be on — two values, both replaceable by moving a slider. There is no record whose loss would
cost the user anything they could not restore in three seconds.

Keeping the machinery anyway was the tempting option, on the theory that a feature might want a
table later. The reason it loses is an asymmetry in *when* the cost falls:

- Deleting now and adding a table later costs nothing. Nothing is shipped, so the database returns
  at version 1 with no installed base to migrate.
- Keeping an unused `items` table and removing it later is a **destructive migration against real
  installs** — exactly the failure ADR-0001 exists to prevent, arrived at by trying to honour it.

So the safe order is to delete, and to let a feature that genuinely needs storage pay for it then.

## Decision

Room, the `Item` domain, the media pipeline, the manual backup export and restore, the custom
`AppBackupAgent` and the reminder scheduling are removed. `AppPreferences` over DataStore is the
whole of Gloam's persistence.

Three consequences worth stating, because each one deletes a defence that existed for a reason:

- **No schema gate, and none is needed.** DataStore ignores a key it does not recognise and falls
  back to the default declared beside a key it cannot find, so there is no version at which a file
  becomes unreadable. `scripts/schema-gate.py` now reports "no database, nothing to gate" and passes
  rather than crashing on the absent file.
- **No custom backup agent.** That class existed only to checkpoint a database's write-ahead log,
  which static backup XML cannot express. With no database there is no WAL, and the platform's
  default Auto Backup handles a preferences file correctly on its own.
- **No exact alarms and no `SCHEDULE_EXACT_ALARM`.** There is nothing scheduled. This is a real gain
  rather than a tidy-up: a permission Play scrutinises is no longer declared at all.

## Alternatives rejected

**Keep Room with one small table so the migration machinery stays exercised.** Real appeal — the
gate, the migration tests and the schema export only stay trustworthy if they run. It loses because
the table would exist to serve the tests rather than the user, and because it pre-judges a feature
decision that has not been made yet.

**Keep the code but leave it unwired.** Dead code that looks load-bearing is worse than absent code.
A future reader cannot tell an unused defence from a live one, and the house rules would still be
describing a database that nothing opens.

## What this does not decide

Whether Gloam eventually wants saved presets, schedules, or per-app rules — all of which would need
a table. That is an open product question, and the feature discussion this POC exists to inform is
where it gets answered.

If the answer becomes yes, ADR-0001 is not superseded so much as *reactivated*: it is still the right
reasoning, `scripts/schema-gate.py` is still in the repository, and the database returns at version 1
with the full apparatus around it. That is the point of deleting now rather than hedging — the
reasoning survives in these documents, and it is only the code that goes.

## Amendment (2026-10-30): a list the user creates lives in a file, not a database

The first list arrived: presets the user saves under their own names. **It is stored as JSON under one
DataStore key (`saved_presets`), not in Room.** The bar for the database was set too high above. What
ADR-0001 defends — migrations, a schema gate, WAL-aware backup — is worth its weight for records that
are queried, related and numerous. A few named presets are none of those: they are read whole, written
whole, and replaceable in a minute.

- **The test moves from cardinality to shape.** A user-made list of small values that is read and
  written whole goes in DataStore as serialised JSON. Room comes back only for something that needs
  querying, relations or partial writes — and that still reactivates ADR-0001 in full.
- **The migration story is the decoder's tolerance.** `ignoreUnknownKeys`, defaults on any field
  added later, values coerced on read, and a file that will not parse reads as an empty list rather
  than a crash. Nothing is migrated in place.
- **It is backed up with the rest of the store**, which is what a preset is: a setting, welcome on
  the user's next phone.
- **Nothing online, ever.** No server, no account and no sync is still the product, not a phase.

Later the same day: **the built-ins joined the list.** All presets are one JSON list under `presets`,
each with a stable id (`reading`, `evening`, `in_bed`, or a generated one) and a name that is `null`
while a built-in keeps its translated label. An absent key reads as the three built-ins — the default
in the read, so *Restore to default* is removing the key. Names and the three values are each unique,
and the writes refuse a duplicate rather than trusting the screen. `saved_presets` never shipped and
is no longer read; *Restore to default* clears what a development install left there.
