package com.yingji.app.domain.ai

import org.junit.Assert.*
import org.junit.Test

class LocalAiUsageSnapshotTest {
    @Test fun thirtySuccessesBlockButErrorsDoNotConsumeSuccessfulTaskAllowance() {
        assertEquals(30, LocalAiUsageSnapshot("2026-10-22").remaining)
        assertEquals(1, LocalAiUsageSnapshot("2026-10-22", 32, 29, 3).remaining)
        assertFalse(LocalAiUsageSnapshot("2026-10-22", 33, 30, 3).canStart)
    }
    @Test fun unknownAttemptsReserveAllowanceWithoutClaimingSuccess() {
        val value = LocalAiUsageSnapshot("2026-10-22", 30, 28, 1)
        assertEquals(1L, value.unresolved); assertEquals(1, value.remaining); assertEquals(28L, value.succeeded)
        assertFalse(LocalAiUsageSnapshot("2026-10-22", 30, 0, 0).canStart)
    }
    @Test fun invalidAndOverflowingCountersAreRejected() {
        for (value in listOf(Triple(-1L,0L,0L),Triple(1L,2L,0L),Triple(1L,1L,1L),Triple(Long.MAX_VALUE,Long.MAX_VALUE,1L))) {
            assertTrue(runCatching { LocalAiUsageSnapshot("2026-10-22", value.first,value.second,value.third) }.isFailure)
        }
    }
}
