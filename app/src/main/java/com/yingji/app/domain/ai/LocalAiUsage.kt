package com.yingji.app.domain.ai

/** Device-local reminders only. Neither this counter nor the date is a trusted billing boundary. */
data class LocalAiUsageSnapshot(
    val day: String,
    val started: Long = 0,
    val succeeded: Long = 0,
    val failed: Long = 0,
) {
    init { require(started >= 0 && succeeded in 0..started && failed in 0..(started - succeeded)) }
    val unresolved: Long get() = started - succeeded - failed
    val remaining: Int get() = (DAILY_LIMIT - succeeded - unresolved).coerceIn(0, DAILY_LIMIT.toLong()).toInt()
    val canStart: Boolean get() = remaining > 0
    companion object { const val DAILY_LIMIT = 30 }
}

enum class LocalAiUsageProblem { LIMIT, BUSY, STORAGE }
class LocalAiUsageException(val problem: LocalAiUsageProblem) : Exception(when (problem) {
    LocalAiUsageProblem.LIMIT -> "今日已达到本地软额度；可继续使用本地推荐、画像与历史。"
    LocalAiUsageProblem.BUSY -> "已有 AI 任务进行中，请勿重复点击。"
    LocalAiUsageProblem.STORAGE -> "本地用量无法保存或读取，已停止新请求；若请求已经发出，仍可能产生费用。"
})

interface LocalAiUsageTracker {
    suspend fun snapshot(): LocalAiUsageSnapshot
    /** One business task, including any HTTP retry. Do not wrap cache hits, local Mock or account lookup. */
    suspend fun <T> task(block: suspend () -> T): T
}
