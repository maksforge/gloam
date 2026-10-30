package app.gloam.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-off deadline, proven on the JVM because nothing on a device can tell you it is right.
 *
 * `phase-2.md` §10 calls these derivations rather than readings: a phone can show you that the shade
 * came down, and it cannot show you that it came down at the instant the user was promised. The
 * wrong answer here is a shade that never lifts, or one that vanishes the moment it goes up, and
 * both are the failure this app exists to prevent rather than a bug somebody would report.
 *
 * `AutoOff.kt` has no Android imports, which is what makes a plain JVM test possible — the same
 * property `ShadeRamp.kt` has, and for the same reason.
 */
class AutoOffTest {
    /**
     * A real-shaped instant rather than `0`, and that is the point of choosing one.
     *
     * `0` is [NO_DEADLINE], so a test that starts its clock there cannot see the sentinel trap at
     * all: every arithmetic result would sit near the epoch and *absent* would be indistinguishable
     * from *a deadline in 1970*. This is 2026-09-01T20:00:00Z.
     */
    private val now = 1_756_756_800_000L

    private val choices = AutoOff.Presets + listOf(1, 45, 90, 185, 720).map(AutoOff::ofMinutes)

    @Test
    fun `every choice but Never lands its own number of minutes ahead`() {
        for (choice in choices) {
            val deadline = autoOffDeadline(now, choice)
            if (choice == AutoOff.Never) {
                assertNull("Never has no deadline", deadline)
            } else {
                assertEquals(
                    "$choice is ${choice.minutes} minutes after the start",
                    now + choice.minutes * 60_000L,
                    deadline,
                )
            }
        }
    }

    /** The one case with no deadline is the one case the enum names, not a duration of zero. */
    @Test
    fun `Never is the only choice without a deadline`() {
        assertEquals(listOf(AutoOff.Never), choices.filter { autoOffDeadline(now, it) == null })
    }

    /** The presets span half an hour to four hours; a typed length can reach twelve, and no further. */
    @Test
    fun `the presets end at four hours, a typed length at twelve, and the default is a preset`() {
        assertEquals(240, AutoOff.Presets.maxOf { it.minutes })
        assertEquals(720, choices.maxOf { it.minutes })
        assertEquals(AutoOff.ofMinutes(120), AutoOff.Default)
        assertTrue(AutoOff.Default in AutoOff.Presets)
    }

    @Test
    fun `a deadline is due at the instant and after it, not before`() {
        val deadline = autoOffDeadline(now, AutoOff.ofMinutes(120))
        assertFalse("a millisecond early is not due", isDue(deadline!! - 1, deadline))
        assertTrue("exactly the deadline is due", isDue(deadline, deadline))
        assertTrue("a millisecond late is due", isDue(deadline + 1, deadline))
    }

    /**
     * The case the resume reconcile exists for: nothing was alive to fire it, hours went by, and the
     * screen has to read *that already happened* rather than *there is no deadline*.
     */
    @Test
    fun `a deadline long past is still due`() {
        assertTrue(isDue(now, now - 9 * 60 * 60_000L))
    }

    @Test
    fun `no deadline is never due, however late it gets`() {
        assertFalse(isDue(now, null))
        assertFalse(isDue(Long.MAX_VALUE, null))
    }

    /**
     * **The sentinel, in the direction that would fail silently.** A stored `0` read as an instant
     * is 1970, 1970 is in the past, and a past deadline is due — so the shade would come down the
     * moment it went up, on every start, for every user who chose `Never`.
     */
    @Test
    fun `the stored zero is absent rather than 1970`() {
        assertNull(deadlineOrNull(NO_DEADLINE))
        assertFalse("a stored zero must not read as due", isDue(now, deadlineOrNull(NO_DEADLINE)))
    }

    @Test
    fun `a stored instant survives the round trip`() {
        val deadline = autoOffDeadline(now, AutoOff.ofMinutes(30))!!
        assertEquals(deadline, deadlineOrNull(deadline))
    }

    @Test
    fun `every choice round-trips through the minutes storage holds`() {
        for (choice in choices) {
            assertEquals(choice, AutoOff.ofMinutes(choice.minutes))
        }
    }

    /**
     * Zero is *Never* and every other stored number is a length, kept rather than replaced by the
     * default: a typed *3 hours* must read back as three hours. Out of range is clamped, which errs
     * short, towards light — and a preference file from another build never crashes the read.
     */
    @Test
    fun `a stored number is Never at zero and otherwise a length, clamped`() {
        assertEquals(AutoOff.Never, AutoOff.ofMinutes(0))
        assertEquals(180, AutoOff.ofMinutes(180).minutes)
        assertEquals(AutoOff.MAX_MINUTES, AutoOff.ofMinutes(5_000).minutes)
        assertEquals(AutoOff.MIN_MINUTES, AutoOff.ofMinutes(-1).minutes)
    }

    @Test
    fun `a typed length is offered after the presets, and a preset adds nothing`() {
        assertEquals(AutoOff.Presets, AutoOff.choicesWith(AutoOff.ofMinutes(60)))
        assertEquals(AutoOff.Presets, AutoOff.choicesWith(AutoOff.Never))
        val typed = AutoOff.ofMinutes(90)
        assertEquals(AutoOff.Presets + typed, AutoOff.choicesWith(typed))
    }
}
