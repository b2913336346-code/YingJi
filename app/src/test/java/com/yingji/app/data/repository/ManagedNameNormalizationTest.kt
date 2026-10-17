package com.yingji.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedNameNormalizationTest {
    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("国产动画", normalizeManagedName("  国产动画  "))
    }

    @Test
    fun rejectsBlankNames() {
        assertNull(normalizeManagedName(" \t\n "))
    }
}
