package com.yingji.app.presentation.search

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchDateRangeTest {
    @Test
    fun pickerDateCreatesInclusiveLocalDayRange() {
        val date = LocalDate.of(2026, 10, 16)
        val selectedUtcMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val zone = ZoneId.of("Asia/Shanghai")

        assertEquals(
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            pickerDateToRangeStart(selectedUtcMillis, zone),
        )
        assertEquals(
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L,
            pickerDateToRangeEnd(selectedUtcMillis, zone),
        )
    }
}
