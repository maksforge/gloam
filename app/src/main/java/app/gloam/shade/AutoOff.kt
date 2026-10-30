package app.gloam.shade

/**
 * How long a shade the user started by hand stays up (CONTEXT.md: **auto-off**), in whole minutes:
 * one of the [Presets] the chips offer, or a length typed under *Other…*, kept within
 * [MIN_MINUTES]..[MAX_MINUTES].
 *
 * Still **one setting**, not a list — `CLAUDE.md`'s cardinality test. A typed length replaces the
 * choice rather than adding a row, which is why this stays a single key in DataStore.
 *
 * **[Never] is the choice that disables it, and "off" is not available to mean that.** CONTEXT.md
 * reserves *off* for the shade not being drawn, so "auto-off: off" is a sentence meaning the shade
 * stays on — a settings row that can be read backwards is a settings row somebody reads backwards.
 *
 * Kotlin note: a `@JvmInline value class` is erased to its one field at run time — a bare `Int`,
 * like a TypeScript branded type — while the type checker still keeps it apart from other `Int`s.
 * The private constructor means every instance went through [ofMinutes], the shape `PauseLength`
 * has too.
 */
@JvmInline
value class AutoOff private constructor(
    val minutes: Int,
) {
    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 12 * 60

        /** Stored as `0`, which no typed length can reach: [MIN_MINUTES] is one. */
        val Never = AutoOff(0)

        val Presets: List<AutoOff> = listOf(Never) + listOf(30, 60, 120, 240).map(::AutoOff)

        /**
         * Two hours, and it ships **provisional**.
         *
         * The span the plan asks for is "an episode to a film", and this is the longest value that
         * is still obviously *not* "the next day" — which is the failure being designed out. It is
         * an argument about a taste made in a room with one person in it, so `PLAN.md` rule 5 hands
         * the question to the twelve testers rather than treating it as settled.
         *
         * **Defaulting *on* is a different question and is not a taste.** Until Phase 2b's Quick
         * Settings tile ships, auto-off is the only escape hatch that needs no gesture at all: the
         * notification's Stop action takes a long-press to reach on HyperOS (`phase-1.md`, R8) and
         * the app's own Stop button sits under the shade. A safety floor is not put to a vote.
         */
        val Default = AutoOff(120)

        /**
         * The stored minutes back into a choice. `0` is [Never]; anything else is a length, clamped
         * rather than refused, so a preference file from another build can never crash the read —
         * and a clamp errs short, towards light, which is ADR-0012's direction.
         */
        fun ofMinutes(minutes: Int): AutoOff =
            if (minutes == 0) Never else AutoOff(minutes.coerceIn(MIN_MINUTES, MAX_MINUTES))

        /** The chips to offer: the presets, and the remembered length after them when it is not one. */
        fun choicesWith(remembered: AutoOff): List<AutoOff> =
            if (remembered in Presets) Presets else Presets + remembered
    }
}

/**
 * The stored value meaning *no deadline*.
 *
 * DataStore has no null, and a sentinel that is also a valid instant would be **1970** — which is in
 * the past, which [isDue] would answer `true` to, which would take the shade down the instant it
 * went up. That is the whole reason this constant and [deadlineOrNull] exist rather than the raw
 * `Long` travelling around: the failure is silent and it is the app's one job that it not happen.
 */
const val NO_DEADLINE = 0L

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * The instant the shade should come down, or `null` for [AutoOff.Never].
 *
 * **Absolute rather than a countdown, and that is load-bearing rather than tidy.** HyperOS kills the
 * service and `START_STICKY` restarts it; a restarted service has no idea how long it was dead, so a
 * stored *duration* would silently restart the clock while a stored *instant* resumes the deadline
 * the user was actually promised.
 *
 * **This is one candidate now, not the answer.** It was `deadlineFor` until the schedule arrived
 * with a second promise about the same stored value, and the rename is deliberate rather than
 * cosmetic: [app.gloam.shade.deadlineFor] is the function that resolves *every* live deadline, and
 * leaving a two-argument overload beside the five-argument one is the single most likely way that
 * rule gets broken later. A new call site takes the shorter signature, compiles, passes every test,
 * and quietly ships a shade the schedule cannot bound. An overload here is not a convenience, it is
 * a trap with the same name as the safe thing — so this one is `internal` and differently named.
 */
internal fun autoOffDeadline(
    startedAt: Long,
    choice: AutoOff,
): Long? = if (choice == AutoOff.Never) null else startedAt + choice.minutes * MILLIS_PER_MINUTE

/**
 * The stored `Long` back into a deadline, with [NO_DEADLINE] read as *absent* rather than as 1970.
 *
 * The one place the sentinel is known, in the direction that matters. It is the mirror of
 * `ShadeValues.backlight` collapsing to `BRIGHTNESS_OVERRIDE_NONE`: Java's APIs and DataStore's keys
 * both had to spell *absent* as a number, Kotlin does not, and the conversion belongs at the single
 * boundary rather than in every caller.
 */
fun deadlineOrNull(stored: Long): Long? = stored.takeIf { it != NO_DEADLINE }

/**
 * Whether a stored deadline has already passed. A `null` deadline is never due.
 *
 * `>=` rather than `>`: the loop that fires this treats *remaining ≤ 0* as due, and a reader that
 * disagreed with it by one millisecond would leave a deadline that is exactly now unfired until the
 * next re-check.
 */
fun isDue(
    now: Long,
    offAtMillis: Long?,
): Boolean = offAtMillis != null && now >= offAtMillis
