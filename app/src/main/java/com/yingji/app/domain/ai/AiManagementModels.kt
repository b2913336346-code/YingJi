package com.yingji.app.domain.ai

/** A7 UI contract. Mock values are never presented as authoritative server quota. */
enum class AiQuotaStatus { AVAILABLE, EXHAUSTED, BUDGET_BLOCKED, BILLING_UNCONFIGURED, REVIEW_REQUIRED, UNAVAILABLE }
enum class MockQuotaScenario(val label: String) { AVAILABLE("可用"), EXHAUSTED("耗尽"), UNAVAILABLE("不可用") }
data class AiQuotaSnapshot(
    val status: AiQuotaStatus,
    val isMock: Boolean,
    val limit: Int? = null,
    val used: Int? = null,
    val remaining: Int? = null,
    val resetsAt: String? = null,
) {
    val canRequestNewTask get() = status == AiQuotaStatus.AVAILABLE && (remaining ?: 0) > 0
    companion object {
        fun mock(scenario: MockQuotaScenario = MockQuotaScenario.AVAILABLE) = when (scenario) {
            MockQuotaScenario.AVAILABLE -> AiQuotaSnapshot(AiQuotaStatus.AVAILABLE, true, 30, 0, 30)
            MockQuotaScenario.EXHAUSTED -> AiQuotaSnapshot(AiQuotaStatus.EXHAUSTED, true, 30, 30, 0)
            MockQuotaScenario.UNAVAILABLE -> AiQuotaSnapshot(AiQuotaStatus.UNAVAILABLE, true)
        }
    }
}
interface QuotaStateSource { suspend fun readQuota(): AiQuotaSnapshot }

enum class AiClearAction(val label: String, val scope: String) {
    INFERRED("清除推断画像", "删除当前演示推断，保留明确偏好、来源开关、原始演示反馈、纠错和历史；后续新反馈可重新学习。"),
    LEARNING("重置学习记录", "删除演示反馈、推断及纠错，保留明确偏好、来源开关和推荐历史。"),
    HISTORY("清空历史", "仅删除本机 Mock 推荐历史，保留偏好、来源、演示反馈与纠错。"),
    ALL("一键重置", "重置演示引导、明确偏好和来源默认值，删除演示反馈、推断、纠错与历史；保留独立额度状态。"),
}
data class AiDemoHistory(
    val id: String,
    val createdAt: Long,
    val mode: AiMode,
    val outcome: MockScenario,
    val titles: List<String>,
)
data class AiManagementSnapshot(
    val setup: AiDemoSetup,
    val inferred: List<InferredPreference>,
    val corrections: Set<String>,
    val history: List<AiDemoHistory>,
    val quota: AiQuotaSnapshot,
    val quotaFixtureEditable: Boolean = true,
    val localUsage: LocalAiUsageSnapshot? = null,
    val localUsageUnavailable: Boolean = false,
    val directMode: Boolean = false,
)
interface AiManagementRepository : QuotaStateSource {
    override suspend fun readQuota(): AiQuotaSnapshot
    suspend fun readLocalUsage(): LocalAiUsageSnapshot? = null
    suspend fun readManagement(): AiManagementSnapshot
    suspend fun setManagementPreference(topic: String, direction: PreferenceDirection?): AiManagementSnapshot
    suspend fun setManagementSources(settings: ProfileSourceSettings): AiManagementSnapshot
    suspend fun correctInference(topic: String, suppressed: Boolean): AiManagementSnapshot
    suspend fun clearManagement(action: AiClearAction): AiManagementSnapshot
    suspend fun setMockQuota(scenario: MockQuotaScenario): AiManagementSnapshot
}
