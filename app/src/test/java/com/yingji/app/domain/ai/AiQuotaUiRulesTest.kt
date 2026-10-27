package com.yingji.app.domain.ai

import org.junit.Assert.*
import org.junit.Test

class AiQuotaUiRulesTest {
    @Test fun onlyAnAvailablePositiveSnapshotAllowsANewTask() {
        for (status in AiQuotaStatus.entries) {
            assertEquals(status == AiQuotaStatus.AVAILABLE, AiQuotaSnapshot(status, false, 30, 0, 30).canRequestNewTask)
            assertFalse(AiQuotaSnapshot(status, false, 30, 30, 0).canRequestNewTask)
            assertFalse(AiQuotaSnapshot(status, false).canRequestNewTask)
        }
    }
    @Test fun mocksAreExplicitAndDoNotComputeCountersOrResetTimes() {
        assertTrue(AiQuotaSnapshot.mock().isMock)
        assertNull(AiQuotaSnapshot.mock().resetsAt)
        assertEquals(0, AiQuotaSnapshot.mock(MockQuotaScenario.EXHAUSTED).remaining)
        assertNull(AiQuotaSnapshot.mock(MockQuotaScenario.UNAVAILABLE).remaining)
    }
}
