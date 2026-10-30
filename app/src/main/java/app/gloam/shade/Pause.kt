package app.gloam.shade

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long a pause lasts (CONTEXT.md: **pause**), in whole minutes: one of the [Presets] the chips
 * offer, or a length the user typed under *Other…*, kept within [MIN_MINUTES]..[MAX_MINUTES].
 *
 * Kotlin note: a `@JvmInline value class` is erased to its one field at run time — a bare `Int`,
 * like a TypeScript branded type — while the type checker still keeps it apart from other `Int`s.
 * The private constructor means every instance went through [ofMinutes]'s clamp.
 */
@JvmInline
value class PauseLength private constructor(
    val minutes: Int,
) {
    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 120

        val Presets: List<PauseLength> = listOf(1, 5, 15).map(::PauseLength)
        val Default = PauseLength(5)

        fun ofMinutes(minutes: Int): PauseLength = PauseLength(minutes.coerceIn(MIN_MINUTES, MAX_MINUTES))
    }
}

private const val MILLIS_PER_MINUTE = 60_000L

internal fun pausedUntilFrom(
    now: Long,
    length: PauseLength,
): Long = now + length.minutes * MILLIS_PER_MINUTE

/**
 * How a pause ends: the shade coming back at [atMillis], or — when the deadline falls inside the
 * pause — the deadline taking it down at [atMillis] instead.
 */
data class PauseEnd(
    val atMillis: Long,
    val resumes: Boolean,
)

/**
 * A pause never outlasts the deadline, and ending one is not a start (ADR-0012's amendment): the
 * deadline is read here and never written, so a pause cannot carry the shade past it.
 */
fun pauseEnd(
    pausedUntil: Long,
    offAtMillis: Long?,
): PauseEnd =
    if (offAtMillis != null && offAtMillis <= pausedUntil) {
        PauseEnd(atMillis = offAtMillis, resumes = false)
    } else {
        PauseEnd(atMillis = pausedUntil, resumes = true)
    }

/** A pause under way: when it began and when it is due to end. */
data class PauseSpan(
    val from: Long,
    val until: Long,
)

/**
 * The span the time-left ring drains over, as `from to end`, or `null` for no ring: a pause while
 * one runs, drawn to [pauseEnd] so a deadline that cuts it short drains it to the deadline, and
 * otherwise the deadline, from [deadlineFrom]. A pause never outlasts the deadline, so the pause is
 * always the nearer end.
 *
 * Kotlin note: `a to b` builds a `Pair`, a two-element tuple — `[a, b]` — that the caller
 * destructures like an array.
 */
fun timeLeftSpan(
    pause: PauseSpan?,
    offAt: Long?,
    deadlineFrom: Long?,
): Pair<Long, Long>? =
    when {
        pause != null -> pause.from to pauseEnd(pause.until, offAt).atMillis
        offAt != null && deadlineFrom != null -> deadlineFrom to offAt
        else -> null
    }

/**
 * How much of a span is left, from 1 at [from] to 0 at [end] — what the time-left ring draws, for a
 * pause or for the deadline.
 *
 * For a pause, [end] is passed rather than read from the span, so a pause the deadline will cut
 * short drains towards the deadline instead of towards a resume that will not come.
 */
fun fractionLeft(
    now: Long,
    from: Long,
    end: Long,
): Float = if (end <= from) 0f else ((end - now).toFloat() / (end - from)).coerceIn(0f, 1f)

/**
 * The current pause, or `null` when the shade is not paused.
 *
 * In memory rather than in DataStore, so a ROM kill mid-pause brings the shade back early rather
 * than never, and a backup can never carry a pause to another phone. Exact for [shadeOnScreen]'s
 * reason: the service and every screen share one process.
 */
val shadePause: StateFlow<PauseSpan?>
    get() = paused.asStateFlow()

private val paused = MutableStateFlow<PauseSpan?>(null)

internal fun reportPause(span: PauseSpan?) {
    paused.value = span
}

/**
 * Kotlin note: a `sealed interface` is a discriminated union — `{ kind: 'pause', length } |
 * { kind: 'resume' }` — and a `when` over it must cover both.
 */
internal sealed interface PauseRequest {
    data class Pause(
        val length: PauseLength,
    ) : PauseRequest

    data object Resume : PauseRequest
}

/**
 * Signals rather than start commands, for [dismissPanelAndBubble]'s reason: a command to a dead
 * service would create it and raise the shade, while an emission nobody collects is dropped.
 */
internal val pauseRequests =
    MutableSharedFlow<PauseRequest>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

fun pauseShade(length: PauseLength) {
    pauseRequests.tryEmit(PauseRequest.Pause(length))
}

fun resumeShade() {
    pauseRequests.tryEmit(PauseRequest.Resume)
}
