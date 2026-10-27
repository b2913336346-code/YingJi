package com.yingji.app.domain.ai

import org.junit.Assert.*
import org.junit.Test

class AiDemoRulesTest {
    @Test fun defaultsAreEmptyAndDoNotAuthorizeAllSources() {
        val setup = AiDemoSetup()
        assertFalse(setup.completed)
        assertTrue(setup.preferences.isEmpty())
        assertEquals(ProfileSourceSettings(false, false, true), setup.sources)
    }
    @Test fun queryRequiresNonBlankInput() {
        assertEquals("请输入想看的内容", AiDemoRules.validateInput(AiMode.QUERY, "  "))
        assertNull(AiDemoRules.validateInput(AiMode.QUERY, "想看科幻"))
    }
    @Test fun exactInputLengthBoundaryIsEnforced() {
        assertNull(AiDemoRules.validateInput(AiMode.QUERY, "字".repeat(500)))
        assertEquals("输入最多 500 字", AiDemoRules.validateInput(AiMode.QUERY, "字".repeat(501)))
    }
    @Test fun shortcutsDoNotRequireFreeText() {
        listOf(AiMode.FOR_YOU, AiMode.TONIGHT, AiMode.EXPLORE).forEach {
            assertNull(AiDemoRules.validateInput(it, ""))
        }
    }
}
