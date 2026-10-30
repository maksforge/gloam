package app.gloam.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **Earlier-deadline-wins, written as an invariant rather than as eight examples.**
 *
 * `docs/phase-4.md`'s table of moments is what this invariant *produces*, not what proves it: a test
 * that walked the eight rows would pass on a build where the ninth case anybody adds is unbounded.
 * So the claim asserted here is the safety property itself — **the stored deadline is never later
 * than a promise the user has been given** — swept across every auto-off choice, both start reasons,
 * a handful of windows and every minute of a day.
 *
 * That direction is the whole argument. A deadline silently *shortened* is annoying, visible and
 * self-correcting; a deadline silently *lost* is the shade up all night, which is the app's named
 * fear. Where the two failures are asymmetric this errs toward light, and the test is what keeps a
 * later writer from quietly reversing it.
 */
class DeadlineTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val zones = listOf(warsaw, ZoneOffset.UTC, ZoneId.of("Asia/Kolkata"))

    private val overnight = Schedule(enabled = true, onAt = LocalTime.of(22, 0), offAt = LocalTime.of(7, 0))
    private val daytime = Schedule(enabled = true, onAt = LocalTime.of(13, 0), offAt = LocalTime.of(17, 0))
    private val disabled = overnight.copy(enabled = false)
    private val degenerate = Schedule(enabled = true, onAt = LocalTime.of(22, 0), offAt = LocalTime.of(22, 0))
    private val schedules = listOf(overnight, daytime, disabled, degenerate)

    /** Every preset, and typed lengths at both limits and between two presets. */
    private val autoOffs = AutoOff.Presets + listOf(1, 90, 720).map(AutoOff::ofMinutes)

    private fun at(
        date: String,
        time: String,
        zone: ZoneId,
    ): Long =
        LocalDateTime
            .parse("${date}T$time")
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    /** Every deadline that is live at [now], which the answer must be the earliest of. */
    private fun candidates(
        now: Long,
        zone: ZoneId,
        start: ShadeStart,
        autoOff: AutoOff,
        schedule: Schedule,
    ): List<Long> =
        listOfNotNull(
            if (start == ShadeStart.ByHand) autoOffDeadline(now, autoOff) else null,
            schedule.windowEnd(now, zone),
        )

    // ------------------------------------------------------------------------------- the invariant

    @Test
    fun `the stored deadline is never later than any promise that is live`() {
        for (zone in zones) {
            for (schedule in schedules) {
                for (start in ShadeStart.entries) {
                    for (autoOff in autoOffs) {
                        val midnight = at("2026-06-15", "00:00", zone)
                        for (minute in 0 until 24 * 60) {
                            val now = midnight + minute * 60_000L
                            val live = candidates(now, zone, start, autoOff, schedule)
                            val answer = deadlineFor(now, zone, start, autoOff, schedule)
                            val where = "$zone $start $autoOff ${schedule.onAt}-${schedule.offAt} at $now"

                            assertEquals("$where: null exactly when nothing is live", live.isEmpty(), answer == null)
                            if (answer != null) {
                                assertTrue("$where: the answer is not one of the live promises", answer in live)
                                for (promise in live) {
                                    assertTrue("$where: $answer is later than the promise $promise", answer <= promise)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The one unbounded configuration, asserted as **the only** one.
     *
     * The useful half is the "only": a test that merely checked this case would pass on a build
     * where every case was unbounded, which is the failure worth catching.
     */
    @Test
    fun `nothing bounds the shade only when a hand start meets Never with no window open`() {
        for (zone in zones) {
            for (schedule in schedules) {
                for (start in ShadeStart.entries) {
                    for (autoOff in autoOffs) {
                        val midnight = at("2026-06-15", "00:00", zone)
                        for (minute in 0 until 24 * 60 step 7) {
                            val now = midnight + minute * 60_000L
                            val unbounded = deadlineFor(now, zone, start, autoOff, schedule) == null
                            val expected =
                                !schedule.contains(now, zone) &&
                                    (start == ShadeStart.BySchedule || autoOff == AutoOff.Never)
                            assertEquals("$zone $start $autoOff at $now", expected, unbounded)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a scheduled start ignores auto-off, Never included`() {
        val insideTheWindow = at("2026-06-15", "23:00", warsaw)
        val end = overnight.windowEnd(insideTheWindow, warsaw)
        for (autoOff in autoOffs) {
            assertEquals(
                "auto-off $autoOff leaked into a scheduled episode",
                end,
                deadlineFor(insideTheWindow, warsaw, ShadeStart.BySchedule, autoOff, overnight),
            )
        }
    }

    /**
     * The rows that let checkpoint B call itself a `refactor:` with a straight face: with no
     * schedule and a hand start, the five-argument function reproduces Phase 2's exactly.
     */
    @Test
    fun `with the schedule off, a hand start is Phase 2's answer unchanged`() {
        for (zone in zones) {
            val midnight = at("2026-06-15", "00:00", zone)
            for (minute in 0 until 24 * 60 step 13) {
                val now = midnight + minute * 60_000L
                for (autoOff in autoOffs) {
                    assertEquals(
                        "$zone $autoOff at $now",
                        autoOffDeadline(now, autoOff),
                        deadlineFor(now, zone, ShadeStart.ByHand, autoOff, disabled),
                    )
                }
            }
        }
    }

    /** The table's own rows, kept because they are what a person reads to check the rule is sane. */
    @Test
    fun `the named moments land where the rule says they do`() {
        val zone = warsaw

        fun hand(
            time: String,
            autoOff: AutoOff,
        ) = deadlineFor(at("2026-06-15", time, zone), zone, ShadeStart.ByHand, autoOff, overnight)

        assertEquals(
            "21:00 is outside; auto-off alone",
            at("2026-06-15", "23:00", zone),
            hand("21:00", AutoOff.ofMinutes(120)),
        )
        assertEquals(
            "23:00 inside, but two hours is sooner",
            at("2026-06-16", "01:00", zone),
            hand("23:00", AutoOff.ofMinutes(120)),
        )
        assertEquals(
            "the row that earns the rule",
            at("2026-06-15", "07:00", zone),
            hand("06:00", AutoOff.ofMinutes(240)),
        )
        assertEquals(
            "13:00 is outside the window entirely",
            at("2026-06-15", "15:00", zone),
            hand("13:00", AutoOff.ofMinutes(120)),
        )
        assertNull("Never with no window is the unbounded one", hand("13:00", AutoOff.Never))
    }

    // ---------------------------------------------------------------------------- the end side

    @Test
    fun `an ending the person owns spends the night, and a reaping does not`() {
        val inside = at("2026-06-15", "23:00", warsaw)
        val opened = overnight.windowStart(inside, warsaw)
        assertEquals(opened, nightSpentBy(ShadeEnd.ByHand, overnight, inside, warsaw))
        assertEquals(opened, nightSpentBy(ShadeEnd.ByDeadline, overnight, inside, warsaw))
        assertNull(
            "a reaped shade must leave tonight unspent",
            nightSpentBy(ShadeEnd.Reaped, overnight, inside, warsaw),
        )
    }

    @Test
    fun `no ending spends a night outside a window`() {
        val outside = at("2026-06-15", "13:00", warsaw)
        for (reason in ShadeEnd.entries) {
            assertNull("$reason outside the window", nightSpentBy(reason, overnight, outside, warsaw))
            assertNull("$reason with no schedule", nightSpentBy(reason, disabled, outside, warsaw))
        }
    }

    // ---------------------------------------------------------------------------- monotonicity

    /**
     * **The third writer's whole safety argument**: no input exists that pushes a stored deadline
     * out. Stated as a property rather than as rows, because that is what is being claimed.
     *
     * `null` is *no deadline*, which is unbounded rather than zero — so it compares as later than
     * everything, and the window is the one thing it can lose to.
     */
    @Test
    fun `tightening can only ever bring a deadline forward`() {
        val stored = listOf(null, 0L, at("2026-06-15", "23:30", warsaw), at("2026-06-20", "07:00", warsaw))
        for (zone in zones) {
            for (schedule in schedules) {
                val midnight = at("2026-06-15", "00:00", zone)
                for (minute in 0 until 24 * 60 step 11) {
                    val now = midnight + minute * 60_000L
                    for (before in stored) {
                        val after = tightenedDeadline(before, schedule, now, zone)
                        val where = "$zone ${schedule.onAt}-${schedule.offAt} at $now, stored=$before"
                        if (schedule.windowEnd(now, zone) == null) {
                            assertEquals("$where: no window, so nothing may move", before, after)
                        } else if (before == null) {
                            assertEquals(
                                "$where: an unbounded shade takes the window's end",
                                schedule.windowEnd(now, zone),
                                after,
                            )
                        } else {
                            assertTrue("$where: $after is later than $before", after!! <= before)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `tightening preserves a chip override that is already sooner`() {
        val inside = at("2026-06-15", "23:00", warsaw)
        val chip = at("2026-06-15", "23:30", warsaw)
        assertEquals(chip, tightenedDeadline(chip, overnight, inside, warsaw))
    }
}
