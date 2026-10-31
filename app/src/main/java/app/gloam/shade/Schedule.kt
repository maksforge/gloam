package app.gloam.shade

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * The nightly window: on at one time, off at another (CONTEXT.md: **schedule**).
 *
 * **One pair, not two independent switches**, and the type is what says so. A pair of times can be
 * asked *is it inside* and *when does it next open*; two switches can only be asked twice and
 * reconciled by whoever is asking. That difference is the whole reason the degenerate case below has
 * one answer rather than a convention every call site has to remember.
 *
 * **A fixed set of values, so it is a handful of settings and not a list** — `CLAUDE.md`'s
 * cardinality test, the same one that keeps [AutoOff] in DataStore. The kind and the lent location
 * (ADR-0013) add two more members to the set, not rows. Seven per-day windows would be
 * fourteen settings by that test and still no database; what keeps them out is size rather than
 * storage, and `docs/phase-4.md` says so where a future reader will look for it.
 *
 * Kotlin note: `java.time` is in the platform from API 26, which is `minSdk` (ADR-0008), so there is
 * no `ThreeTenABP`, no desugaring flag and no `Calendar` anywhere in this file. [LocalTime] is a wall-clock time with **no date and no zone**:
 * exactly the thing the user set, and deliberately not something you can subtract from an instant.
 */
data class Schedule(
    val enabled: Boolean,
    val onAt: LocalTime,
    val offAt: LocalTime,
    /**
     * Fixed times or sunset to sunrise (ADR-0013). The fixed pair is kept in both kinds: switching back
     * restores it, and it is the night the sun falls back to.
     */
    val kind: ScheduleKind = ScheduleKind.FixedTimes,
    /** The location the user lent, if any. Used only in the zone it was read in; see [locationIn]. */
    val lent: Location.Lent? = null,
)

enum class ScheduleKind { FixedTimes, SunsetToSunrise }

/**
 * Where sunset and sunrise are worked out for (CONTEXT.md: **location**), and where that came from,
 * since the schedule screen always says which.
 *
 * Kotlin note: a `sealed interface` is TypeScript's discriminated union with the compiler enforcing
 * it. `when (location) { is Lent -> …; is Estimated -> … }` must cover both cases to compile, and
 * inside each branch `location` is already narrowed to that case, as with `switch (x.kind)` in TS.
 */
sealed interface Location {
    val coordinates: Coordinates

    /**
     * The phone's approximate position, rounded to 0.1°, and the zone it was read in. The zone is what
     * makes travel safe: a location lent in Warsaw is ignored in New York, rather than giving Warsaw's
     * sunset on New York's clock (ADR-0013's consequences).
     */
    data class Lent(
        override val coordinates: Coordinates,
        val zoneId: String,
    ) : Location

    /** The principal location of the phone's time zone, from the generated tzdata table. */
    data class Estimated(
        override val coordinates: Coordinates,
    ) : Location
}

/**
 * The location a sunset-to-sunrise schedule uses in [zone], or `null` when there is none, which makes
 * every night the fixed pair (ADR-0013 §5): the lent location if it was read in this zone, otherwise
 * the zone's estimate.
 *
 * It answers for either kind, so the screen can show a sunset before the user switches.
 */
fun Schedule.locationIn(zone: ZoneId): Location? =
    lent?.takeIf { it.zoneId == zone.id } ?: estimatedLocation(zone)?.let { Location.Estimated(it) }

/**
 * Whether this schedule can ever open at all.
 *
 * **`onAt == offAt` is an empty window, never a twenty-four-hour one.** Both readings are available
 * and only one of them is safe: *on at 22:00, off at 22:00* read as a full day is a shade that never
 * comes down — the single failure this app exists to design out, reached by a user who tapped the
 * same time twice. Read as empty it is a schedule that does nothing, which is visible, harmless and
 * obviously wrong to the person who set it. The screen refuses to save it with a sentence saying
 * why; this is the floor under that refusal rather than a substitute for it.
 *
 * A disabled schedule answers identically, so **no caller needs to check the flag** before asking
 * any of the four questions below.
 *
 * This is the fixed kind's rule. A sunset-to-sunrise schedule with an empty fixed pair still has its
 * sun nights, and only the nights that fall back are empty ([nightOn]).
 */
private val Schedule.isActive: Boolean get() = enabled && onAt != offAt

/**
 * Is the window open at [now]? Half-open: 22:00 is inside it, 07:00 is not.
 *
 * Three shapes, and the third is the one a nightly reading window actually has:
 *
 * | `onAt` vs `offAt` | Shape | Inside when |
 * | --- | --- | --- |
 * | `on < off` | Same day — 13:00 to 17:00 | `on <= t < off` |
 * | `on > off` | **Crosses midnight** — 22:00 to 07:00 | `t >= on` **or** `t < off` |
 * | `on == off` | Degenerate | **Never** — see [isActive] |
 *
 * **Half-open, `[on, off)`, and it agrees with [isDue] by construction.** `isDue` treats a deadline
 * that is exactly now as due, so a window whose end did not agree would fire the deadline a minute
 * after this function had already gone false.
 */
fun Schedule.contains(
    now: Long,
    zone: ZoneId,
): Boolean {
    if (kind == ScheduleKind.SunsetToSunrise) return nightAround(now, zone) != null
    if (!isActive) return false
    val t = now.localTimeIn(zone)
    return if (onAt < offAt) t >= onAt && t < offAt else t >= onAt || t < offAt
}

/**
 * The next instant **strictly after** [now] at which the window opens, or `null` if it never does.
 *
 * **Strictly after is not tidiness, it is what stops the alarm from looping.** The schedule's own
 * receiver re-arms from inside the broadcast the alarm just delivered, so a `nextOn` that could
 * answer *now* is an alarm that re-arms itself for the instant it is already at — a broadcast loop
 * on a user's phone, at 22:00, with a foreground-service start inside it.
 */
fun Schedule.nextOn(
    now: Long,
    zone: ZoneId,
): Long? =
    when {
        kind == ScheduleKind.SunsetToSunrise -> nextNight(now, zone)?.start
        !isActive -> null
        else -> nextOccurrence(onAt, now, zone)
    }

/**
 * The instant the window containing [now] closes, or `null` when [now] is outside it.
 *
 * This is the value the schedule writes into `off_at_millis`, so it is *the deadline* rather than a
 * fact about the window — which is why it answers null outside the window rather than answering
 * tomorrow's close. A shade started by hand at 13:00 under a 22:00-to-07:00 window is bounded by
 * auto-off and by nothing else, and a non-null answer here would silently make it bounded by a
 * window it is not in.
 */
fun Schedule.windowEnd(
    now: Long,
    zone: ZoneId,
): Long? {
    if (kind == ScheduleKind.SunsetToSunrise) return nightAround(now, zone)?.end
    if (!contains(now, zone)) return null
    val today = now.localDateIn(zone)
    // Crossing midnight and already past `onAt` means the close is tomorrow's; every other way of
    // being inside the window closes on the day we are already in.
    val day = if (onAt > offAt && now.localTimeIn(zone) >= onAt) today.plusDays(1) else today
    return day.atLocal(offAt, zone)
}

/**
 * The instant the window containing [now] opened, or `null` when [now] is outside it.
 *
 * **The identity of a night**, and that is what it is for. The schedule's reconcile has to tell
 * *this window has not been acted on* from *this window was acted on and the user pressed Stop*, and
 * comparing the marker it stored against the on-instant of the window it is looking at is what
 * separates them. The property that makes it usable is that it is **constant across every minute of
 * one window** — a version that drifted by a minute would let the reconcile raise the same night's
 * shade over and over — and the sweep asserts exactly that rather than trusting the derivation.
 *
 * The exact mirror of [windowEnd]: same helper, same null outside the window.
 */
fun Schedule.windowStart(
    now: Long,
    zone: ZoneId,
): Long? {
    if (kind == ScheduleKind.SunsetToSunrise) return nightAround(now, zone)?.start
    if (!contains(now, zone)) return null
    val today = now.localDateIn(zone)
    // Crossing midnight and *before* `offAt` means we are in the small hours of a window that opened
    // yesterday. Every other way of being inside it opened on the day we are in.
    val day = if (onAt > offAt && now.localTimeIn(zone) < offAt) today.minusDays(1) else today
    return day.atLocal(onAt, zone)
}

/**
 * The first instant strictly after [now] whose local time is [time].
 *
 * Walks candidate days and takes the first strictly-future one. **Three days rather than two**,
 * because a daylight-saving gap can push a day's occurrence forward by the size of the gap, so
 * today's candidate can land after the naive reading of tomorrow's.
 */
private fun nextOccurrence(
    time: LocalTime,
    now: Long,
    zone: ZoneId,
): Long {
    var day = now.localDateIn(zone)
    repeat(DAYS_WALKED) {
        val candidate = day.atLocal(time, zone)
        if (candidate > now) return candidate
        day = day.plusDays(1)
    }
    // Unreachable: a local time recurs every day, so the third candidate is at least 24 hours out.
    // Answering the last candidate rather than throwing, because the caller of last resort is an
    // alarm being armed on somebody's phone at 22:00.
    return day.atLocal(time, zone)
}

private const val DAYS_WALKED = 3

// ------------------------------------------------------------------------------ sunset to sunrise

/**
 * One night of a sunset-to-sunrise schedule, as instants: open at [start], closed at [end].
 *
 * **Instants rather than wall-clock times, unlike the fixed kind above**, because a sunset is a moment
 * on the timeline and has no local time to resolve. The fixed nights it falls back to are resolved to
 * instants once, through [atLocal], and from then on are treated the same way. So the fixed kind's
 * autumn quirk, where the window is still open by the clock after its end has passed, cannot happen
 * here: a night is open exactly while `start <= now < end`.
 *
 * @param followsSun `false` for a night that fell back to the fixed pair, which the screen says.
 */
data class Night(
    val start: Long,
    val end: Long,
    val followsSun: Boolean,
)

/**
 * The night a sunset-to-sunrise schedule is in at [now], or the next one to open. This is for the
 * screen to show, and nothing computes a deadline from it.
 *
 * It answers whatever [Schedule.enabled] and [Schedule.kind] are, so the screen can show tonight's
 * sunset on a schedule that is still off or still on fixed times. `null` only when no night opens
 * within a year.
 */
fun Schedule.tonight(
    now: Long,
    zone: ZoneId,
): Night? {
    val asSun = copy(enabled = true, kind = ScheduleKind.SunsetToSunrise)
    return asSun.nightAround(now, zone) ?: asSun.nextNight(now, zone)
}

/** The night that contains [now], or `null` outside every night, and for a disabled schedule. */
private fun Schedule.nightAround(
    now: Long,
    zone: ZoneId,
): Night? {
    if (!enabled) return null
    val place = locationIn(zone)?.coordinates
    val today = now.localDateIn(zone)
    // [nightOn] keeps night D inside [start of D, start of D + 2), so a night containing now is the
    // one belonging to today or to yesterday, and to no other day.
    return listOf(today.minusDays(1), today)
        .firstNotNullOfOrNull { day -> nightOn(day, zone, place)?.takeIf { now >= it.start && now < it.end } }
}

/**
 * The first night to open strictly after [now], walking forward a day at a time.
 *
 * Night starts increase with their date ([nightOn]), so the first one after [now] is the answer, and
 * the walk begins at yesterday because yesterday's night can open after midnight, near the midnight sun.
 *
 * **Why [NIGHTS_WALKED] is a year.** With a normal fixed pair every date has a night, so the walk stops
 * within two days. Only an empty fixed pair at a latitude with polar night leaves dates without one, and
 * that can last for months. A year covers every season, and no night in a year means none will ever open.
 */
private fun Schedule.nextNight(
    now: Long,
    zone: ZoneId,
): Night? {
    if (!enabled) return null
    val place = locationIn(zone)?.coordinates
    var day = now.localDateIn(zone).minusDays(1)
    repeat(NIGHTS_WALKED) {
        val night = nightOn(day, zone, place)
        if (night != null && night.start > now) return night
        day = day.plusDays(1)
    }
    return null
}

private const val NIGHTS_WALKED = 370

/**
 * The night belonging to calendar date [day] in [zone]: open at that evening's sunset, closed at the next
 * sunrise, or the fixed pair for that date when the sun gives no answer (ADR-0013 §1 and §4).
 *
 * **Two clips make the nights a sequence that cannot overlap, whatever the inputs.**
 *
 * 1. Night D is kept inside [start of D, start of D + 2). For a real location, sunset on D is after
 *    D's noon and the next sunrise is before D + 1's noon, and a fixed pair lies inside those bounds
 *    anyway. The clip exists for inputs no geography produces, such as a location lent under a zone
 *    set by hand on the far side of the world.
 * 2. Night D opens no earlier than night D − 1 closes. With the first clip, only neighbouring nights can
 *    meet, and that happens only where a sun night hands over to a fallback night. For example, a
 *    polar sunrise at 08:00 followed by a fixed pair that opens at 06:00. The earlier night keeps its
 *    minutes.
 *
 * Neither clip reads its own output: night D − 1's *unclipped* end is enough, because D − 1 already
 * lies inside its own two days. So a night's start is a function of its date, its zone and its location
 * only. That is what keeps [windowStart] constant across a night, which the reconcile needs.
 *
 * `null` when the clipped night is empty.
 */
private fun Schedule.nightOn(
    day: LocalDate,
    zone: ZoneId,
    place: Coordinates?,
): Night? {
    val own = unclippedNightOn(day, zone, place) ?: return null
    val previousEnd = unclippedNightOn(day.minusDays(1), zone, place)?.end ?: Long.MIN_VALUE
    val start = maxOf(own.start, day.startIn(zone), minOf(previousEnd, day.plusDays(1).startIn(zone)))
    val end = minOf(own.end, day.plusDays(2).startIn(zone))
    return if (start < end) own.copy(start = start, end = end) else null
}

/** The sun's night for [day] if the sun gives one, otherwise the fixed pair's. */
private fun Schedule.unclippedNightOn(
    day: LocalDate,
    zone: ZoneId,
    place: Coordinates?,
): Night? = place?.let { sunNightOn(day, zone, it) } ?: fixedNightOn(day, zone)

/**
 * Sunset on [day] to sunrise on the next day, or `null` when either doesn't happen.
 *
 * `rise <= set` also counts as no answer. At the edge of the midnight sun, when the sun only just
 * touches the horizon at midnight, one day's sunset can land on or after the next day's sunrise. That
 * is a night of zero minutes or fewer, which is the case where the sun has no answer to give.
 */
private fun sunNightOn(
    day: LocalDate,
    zone: ZoneId,
    place: Coordinates,
): Night? {
    val sunsDay = sunsDayFor(day, place.longitude, zone)
    val set = sunset(sunsDay, place) ?: return null
    val rise = sunrise(sunsDay.plusDays(1), place) ?: return null
    return if (rise > set) Night(set, rise, followsSun = true) else null
}

/** The fixed pair on [day] as instants, or `null` for an empty pair. */
private fun Schedule.fixedNightOn(
    day: LocalDate,
    zone: ZoneId,
): Night? {
    if (onAt == offAt) return null
    val closes = if (onAt > offAt) day.plusDays(1) else day
    return Night(day.atLocal(onAt, zone), closes.atLocal(offAt, zone), followsSun = false)
}

/**
 * The sun's day ([sunTimes]) whose local mean noon falls on calendar date [day] in [zone].
 *
 * It is the calendar date itself everywhere except where a zone is drawn far from its meridian. Samoa's
 * calendar 21 June is the sun's 20 June, so its evening's sunset is found by asking for the 20th. Mean
 * noons are exactly a day apart, so moving by one day is always enough.
 */
private fun sunsDayFor(
    day: LocalDate,
    longitude: Double,
    zone: ZoneId,
): LocalDate {
    val utcNoon = day.toEpochDay() * MILLIS_PER_DAY + MILLIS_PER_DAY / 2
    val meanNoon = utcNoon - (longitude * MILLIS_PER_DEGREE).roundToLong()
    val onTheClock = meanNoon.localDateIn(zone)
    return day.minusDays(onTheClock.toEpochDay() - day.toEpochDay())
}

private const val MILLIS_PER_DAY = 86_400_000L

/** The earth turns a degree every four minutes. */
private const val MILLIS_PER_DEGREE = 240_000.0

/** The first instant of this date in [zone], which is not always 00:00 on a daylight-saving day. */
private fun LocalDate.startIn(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * A local date and a wall-clock time resolved to an instant **in this zone, on this day**.
 *
 * The one place daylight saving is dealt with, and the behaviour on the two irregular days is
 * `java.time`'s rather than ours:
 *
 * - **Spring forward.** In `Europe/Warsaw` on the last Sunday in March, 02:00 to 02:59 do not exist.
 *   `atZone` resolves a gap **forwards by the size of the gap**, so a schedule set to come on at
 *   02:30 comes on at 03:30 that one night. Deterministic and documented; the alternatives —
 *   throwing, or skipping the night — are worse in both directions.
 * - **Autumn back.** On the last Sunday in October, 02:30 happens twice, and `atZone` resolves an
 *   overlap to the **earlier** offset. So the shade comes on at the first 02:30 rather than the
 *   second — and, in the mirror case, a window ending at 02:30 ends at the first one, which can put
 *   [windowEnd] in the past while the second 02:30 is still an hour away. That errs toward light,
 *   which is the direction every rule in this app errs in when the two failures are asymmetric.
 * - **The window's length changes by an hour on those two nights**, in both directions, and that is
 *   correct rather than a bug to compensate for: the user said *off at 07:00*, and 07:00 is when the
 *   clock says 07:00.
 *
 * Kotlin note: `ZoneId` is the function between a wall-clock time and a point on the timeline, and
 * it is **not injective in either direction** — one local time can be zero instants or two. That is
 * why everything here goes through `atZone` and takes what it resolves to, rather than doing
 * arithmetic on minutes. JS has one `Date` doing all three jobs and a `Temporal` proposal to fix
 * exactly this; Kotlin has had the fixed version since API 26.
 */
private fun LocalDate.atLocal(
    time: LocalTime,
    zone: ZoneId,
): Long = atTime(time).atZone(zone).toInstant().toEpochMilli()

private fun Long.localTimeIn(zone: ZoneId): LocalTime = Instant.ofEpochMilli(this).atZone(zone).toLocalTime()

private fun Long.localDateIn(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/**
 * Minutes since local midnight, as the wall-clock time the user set.
 *
 * Coerced rather than validated, the way `dim_level` and `warmth` are: a value outside `0..1439` can
 * only arrive from a build with a different range, and an `Int` cannot fail to parse — which is the
 * whole reason storage holds a number here rather than a formatted string.
 */
fun timeOf(minutes: Int): LocalTime = LocalTime.ofSecondOfDay(minutes.coerceIn(0, MINUTES_PER_DAY - 1) * 60L)

/** The inverse of [timeOf]: the wall-clock time as the number storage keeps. */
fun minutesOf(time: LocalTime): Int = time.hour * 60 + time.minute

private const val MINUTES_PER_DAY = 24 * 60
