package com.yingji.app.presentation.search

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchDateRangeTest {
    @Test
    fun localRangeRespectsDaylightSavingInsteadOfAssumingTwentyFourHours() {
        val zone = ZoneId.of("America/New_York")
        listOf(LocalDate.of(2026, 3, 8) to 23L, LocalDate.of(2026, 11, 1) to 25L)
            .forEach { (date, hours) ->
                val picker = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                assertEquals(hours * 60L * 60L * 1_000L,
                    pickerDateToRangeEnd(picker, zone) - pickerDateToRangeStart(picker, zone) + 1L)
            }
    }

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
