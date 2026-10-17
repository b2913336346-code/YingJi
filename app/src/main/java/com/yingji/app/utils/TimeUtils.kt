package com.yingji.app.utils

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.max

object TimeUtils {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun format(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(formatter)

    fun parse(value: String): Long? = try {
        LocalDateTime.parse(value.trim(), formatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    fun calculateDuration(startTime: Long, endTime: Long): Long =
        max(0L, endTime - startTime)

    fun formatDuration(duration: Long): String {
        val totalMinutes = duration / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L -> "${hours}小时${minutes}分钟"
            totalMinutes > 0L -> "${totalMinutes}分钟"
            else -> "不足1分钟"
        }
    }

    fun watchLabel(watchNumber: Int): String = when (watchNumber) {
        1 -> "一刷"
        2 -> "二刷"
        3 -> "三刷"
        else -> "${watchNumber}刷"
    }
}
