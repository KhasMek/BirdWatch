package com.khasmek.birdwatch.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRestartBudgetTest {

    private val budget = ScanRestartBudget(windowMs = 30_000L, maxStopsBeforeRestart = 3, minWaitMs = 100L)

    @Test
    fun `three stops inside the window still allow a restart`() {
        budget.recordStop(0); budget.recordStop(1_000); budget.recordStop(2_000)
        assertEquals(0L, budget.msUntilRestartAllowed(3_000))
    }

    @Test
    fun `fourth stop defers until the oldest ages out`() {
        listOf(0L, 1_000L, 2_000L, 3_000L).forEach(budget::recordStop)
        val wait = budget.msUntilRestartAllowed(4_000)
        assertEquals(30_000L - 4_000L + 1, wait) // stop at t=0 leaves the window just after t=30 000
        assertEquals(0L, budget.msUntilRestartAllowed(4_000 + wait))
    }

    @Test
    fun `two excess stops wait for the second oldest`() {
        listOf(0L, 5_000L, 6_000L, 7_000L, 8_000L).forEach(budget::recordStop)
        assertEquals(5_000L + 30_000L - 9_000L + 1, budget.msUntilRestartAllowed(9_000))
    }

    @Test
    fun `wait is never shorter than the minimum`() {
        listOf(0L, 1L, 2L, 3L).forEach(budget::recordStop)
        assertTrue(budget.msUntilRestartAllowed(30_000) >= 100L)
    }

    @Test
    fun `old stops are forgotten and reset clears everything`() {
        listOf(0L, 1L, 2L, 3L).forEach(budget::recordStop)
        assertEquals(0L, budget.msUntilRestartAllowed(40_000))
        listOf(40_000L, 40_001L, 40_002L, 40_003L).forEach(budget::recordStop)
        budget.reset()
        assertEquals(0L, budget.msUntilRestartAllowed(40_004))
    }
}
