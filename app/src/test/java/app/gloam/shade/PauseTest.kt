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

    @Test
    fun `a pause ends no later than the deadline, and resumes only when the deadline is past it`() {
        for (length in PauseLength.entries) {
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
        for (length in PauseLength.entries) {
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
    fun `an unrecognised stored length reads as the default`() {
        assertEquals(PauseLength.Minutes5, PauseLength.Default)
        assertEquals(PauseLength.Default, PauseLength.ofMinutes(7))
        for (length in PauseLength.entries) assertEquals(length, PauseLength.ofMinutes(length.minutes))
    }
}
