package com.yingji.app.domain.ai

/** A3 demonstration contract; these records are never verified catalogue candidates. */
enum class AiMode(val label: String) {
    FOR_YOU("猜你喜欢"), TONIGHT("适合今晚"), EXPLORE("探索新类型"), QUERY("自由输入")
}
enum class MockScenario(val label: String) {
    SUCCESS("推荐成功"), EMPTY("空结果"), ERROR("请求失败"), OFFLINE("离线"), QUOTA("额度耗尽"), WORKER("本地 Worker 联调（Mock）"), DIRECT("直连协议联调（演示摘要）")
}
enum class AiFeedback(val label: String) { LIKE("喜欢"), DISLIKE("不喜欢"), UNINTERESTED("不感兴趣") }

data class AiDemoSetup(
    val completed: Boolean = false,
    val preferences: Map<String, PreferenceDirection> = emptyMap(),
    val sources: ProfileSourceSettings = ProfileSourceSettings(),
    val feedback: Map<String, AiFeedback> = emptyMap(),
)

data class MockRecommendation(
    val id: String,
    val title: String,
    val topic: String,
    val description: String,
    val reason: String,
    val candidateIdentity: CandidateIdentity? = null,
)

class MockRequestException(val scenario: MockScenario) : IllegalStateException(scenario.label)

interface AiHomeRepository : AiManagementRepository {
    val directDemoAvailable: Boolean get() = false
    val directUsesMockKey: Boolean get() = true
    fun selectDirectMode(enabled: Boolean) {}
    val workerDemoAvailable: Boolean get() = false
    val workerUsesConfiguredKey: Boolean get() = false
    val integrationChanges: kotlinx.coroutines.flow.Flow<ProfileToken> get() = kotlinx.coroutines.flow.emptyFlow()
    fun selectIntegrationMode(enabled: Boolean) {}
    suspend fun recommendWithMetadata(mode: AiMode, input: String, scenario: MockScenario): AiHomeRecommendationBatch =
        AiHomeRecommendationBatch(recommend(mode, input, scenario))
    suspend fun readSetup(): AiDemoSetup
    /** Saves preferences, source switches and completion together, or throws without completing. */
    suspend fun saveSetup(setup: AiDemoSetup): AiDemoSetup
    suspend fun recommend(mode: AiMode, input: String, scenario: MockScenario): List<MockRecommendation>
    suspend fun saveFeedback(id: String, feedback: AiFeedback?): AiDemoSetup
}

object AiDemoRules {
    val topics = listOf("科幻", "悬疑", "喜剧", "纪录片", "动画")
    const val MAX_INPUT = 500
    fun validateInput(mode: AiMode, input: String): String? = when {
        mode == AiMode.QUERY && input.isBlank() -> "请输入想看的内容"
        input.length > MAX_INPUT -> "输入最多 500 字"
        else -> null
    }
}
