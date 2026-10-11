package app.gloam.tip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TipAskTest {
    private val installedAt = 1_756_756_800_000L
    private val weekLater = installedAt + TIP_ASK_AFTER_MILLIS

    @Test
    fun `due once installed a week and started ten times`() {
        assertTrue(tipAskDue(asked = false, shadeStarts = TIP_ASK_AFTER_STARTS, installedAt, weekLater))
    }

    @Test
    fun `not due a millisecond short of the week`() {
        assertFalse(tipAskDue(asked = false, shadeStarts = 100, installedAt, weekLater - 1))
    }

    @Test
    fun `not due one start short`() {
        assertFalse(tipAskDue(asked = false, shadeStarts = TIP_ASK_AFTER_STARTS - 1, installedAt, weekLater * 2))
    }

    @Test
    fun `never due again once answered`() {
        assertFalse(tipAskDue(asked = true, shadeStarts = 1_000, installedAt, weekLater * 2))
    }
}
