package app.gloam.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0012's amendment, as a sweep: **a pause never carries the shade past the deadline.** Ending a
 * pause is not a start, so its end is the earlier of the two and only the pause running out brings
 * the shade back.
 */
class PauseTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val swept = PauseLength.Presets + PauseLength.ofMinutes(PauseLength.MAX_MINUTES)

    @Test
    fun `a pause ends no later than the deadline, and resumes only when the deadline is past it`() {
        for (length in swept) {
            val until = pausedUntilFrom(now, length)
            assertEquals(now + length.minutes * minute, until)
            for (offsetSeconds in -60..(length.minutes * 60 + 60)) {
                val offAt = now + offsetSeconds * 1_000L
                val end = pauseEnd(until, offAt)
                assertTrue("$length ends at ${end.atMillis}, after the deadline $offAt", end.atMillis <= offAt)
                assertTrue("$length outlasted itself", end.atMillis <= until)
                assertEquals("$length with the deadline at +${offsetSeconds}s", offAt > until, end.resumes)
            }
        }
    }

    @Test
    fun `with no deadline a pause always resumes when it runs out`() {
        for (length in swept) {
            val until = pausedUntilFrom(now, length)
            assertEquals(PauseEnd(atMillis = until, resumes = true), pauseEnd(until, null))
        }
    }

    @Test
    fun `a deadline exactly at the pause's end takes the shade down rather than raising it for an instant`() {
        val until = pausedUntilFrom(now, PauseLength.Default)
        assertFalse(pauseEnd(until, until).resumes)
    }

    @Test
    fun `any stored length is kept, clamped to the range`() {
        assertEquals(5, PauseLength.Default.minutes)
        assertEquals(7, PauseLength.ofMinutes(7).minutes)
        assertEquals(PauseLength.MIN_MINUTES, PauseLength.ofMinutes(0).minutes)
        assertEquals(PauseLength.MAX_MINUTES, PauseLength.ofMinutes(10_000).minutes)
        for (length in PauseLength.Presets) assertEquals(length, PauseLength.ofMinutes(length.minutes))
    }

    @Test
    fun `the ring is full at the start, empty at the end, and never outside either`() {
        val end = now + 5 * minute
        assertEquals(1f, fractionLeft(now, now, end), 0f)
        assertEquals(0.5f, fractionLeft(now + 150_000L, now, end), 1e-6f)
        assertEquals(0f, fractionLeft(end, now, end), 0f)
        assertEquals(0f, fractionLeft(end + minute, now, end), 0f)
        assertEquals(1f, fractionLeft(now - minute, now, end), 0f)
        assertEquals(0f, fractionLeft(now, now, now), 0f)
    }

    @Test
    fun `the ring drains a running pause to its end, and otherwise the deadline from when it was set`() {
        val pause = PauseSpan(from = now, until = now + 5 * minute)
        val set = now - 30 * minute
        val later = now + 60 * minute
        val inside = now + 2 * minute

        assertEquals(null, timeLeftSpan(pause = null, offAt = null, deadlineFrom = null))
        assertEquals(null, timeLeftSpan(pause = null, offAt = null, deadlineFrom = set))
        assertEquals(set to later, timeLeftSpan(pause = null, offAt = later, deadlineFrom = set))
        assertEquals(null, timeLeftSpan(pause = null, offAt = later, deadlineFrom = null))

        assertEquals(now to pause.until, timeLeftSpan(pause, offAt = null, deadlineFrom = null))
        assertEquals(now to pause.until, timeLeftSpan(pause, offAt = later, deadlineFrom = set))
        assertEquals(now to inside, timeLeftSpan(pause, offAt = inside, deadlineFrom = set))
        assertEquals(now to inside, timeLeftSpan(pause, offAt = inside, deadlineFrom = null))
    }
}
