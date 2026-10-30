package app.gloam.shade

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long a pause lasts (CONTEXT.md: **pause**). A fixed set of three, so it is one setting rather
 * than a list, and it is stored as minutes for [AutoOff]'s reason: a later build may offer more.
 */
enum class PauseLength(
    val minutes: Int,
) {
    Minute1(1),
    Minutes5(5),
    Minutes15(15),
    ;

    companion object {
        val Default = Minutes5

        fun ofMinutes(minutes: Int): PauseLength = entries.firstOrNull { it.minutes == minutes } ?: Default
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

/**
 * The instant the current pause ends, or `null` when the shade is not paused.
 *
 * In memory rather than in DataStore, so a ROM kill mid-pause brings the shade back early rather
 * than never, and a backup can never carry a pause to another phone. Exact for [shadeOnScreen]'s
 * reason: the service and every screen share one process.
 */
val shadePausedUntil: StateFlow<Long?>
    get() = paused.asStateFlow()

private val paused = MutableStateFlow<Long?>(null)

internal fun reportPausedUntil(until: Long?) {
    paused.value = until
}

internal enum class PauseRequest {
    Pause,
    Resume,
}

/**
 * Signals rather than start commands, for [dismissPanelAndBubble]'s reason: a command to a dead
 * service would create it and raise the shade, while an emission nobody collects is dropped.
 */
internal val pauseRequests =
    MutableSharedFlow<PauseRequest>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

fun pauseShade() {
    pauseRequests.tryEmit(PauseRequest.Pause)
}

fun resumeShade() {
    pauseRequests.tryEmit(PauseRequest.Resume)
}
