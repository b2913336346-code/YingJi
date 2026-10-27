package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*

/** Adapts the existing hint/candidate contract; no Worker HTTP is used. The HTTP client owns retries. */
internal class DirectAiService(
    private val keys: AiKeyConfigurationRepository,
    private val usage: LocalAiUsageTracker,
    private val client: DeepSeekHttpClient = DeepSeekHttpClient(usage),
    private val current: () -> Boolean,
) : AiWorkerService {
    override suspend fun quota(): AiQuotaSnapshot {
        val value = usage.snapshot()
        return AiQuotaSnapshot(if (value.canStart) AiQuotaStatus.AVAILABLE else AiQuotaStatus.EXHAUSTED, false,
            LocalAiUsageSnapshot.DAILY_LIMIT, LocalAiUsageSnapshot.DAILY_LIMIT - value.remaining, value.remaining)
    }
    override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse = try {
        val result = client.hints(request, keys, current)
        WorkerTaskResponse(request.requestId, request.token, WorkerTaskStatus.SUCCESS, result.hints, quota(), result.usage, null)
    } catch (error: LocalAiUsageException) {
        throw WorkerProblem(when (error.problem) {
            LocalAiUsageProblem.LIMIT -> "LOCAL_LIMIT"
            LocalAiUsageProblem.BUSY -> "REQUEST_IN_PROGRESS"
            LocalAiUsageProblem.STORAGE -> "LOCAL_USAGE_STORAGE"
        })
    }
}
