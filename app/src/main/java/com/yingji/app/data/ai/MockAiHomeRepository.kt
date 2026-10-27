package com.yingji.app.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.aiDemoStore by preferencesDataStore(name = "ai_home_mock")

/** Only this demo store: no Room, real profile, library, Worker or quota accounting. */
class MockAiHomeRepository(
    context: Context,
    private val store: DataStore<Preferences> = context.aiDemoStore,
    private val catalog: CandidateCatalogRepository = MockCandidateCatalogRepository(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val localUsage: LocalAiUsageTracker? = null,
) : AiHomeRepository {
    private var direct: DirectHomeIntegration? = null
    internal fun attachDirect(value: DirectHomeIntegration) { check(direct == null); direct = value }
    override val directDemoAvailable get() = direct != null
    override val directUsesMockKey get() = direct?.mockKey ?: true
    override val integrationChanges get() = observeIntegrationToken()
    override fun selectDirectMode(enabled: Boolean) { direct?.select(enabled) }
    override suspend fun recommendWithMetadata(mode: AiMode, input: String, scenario: MockScenario): AiHomeRecommendationBatch =
        if (scenario == MockScenario.DIRECT) (direct ?: throw WorkerProblem("DIRECT_UNAVAILABLE")).recommend(mode, input)
        else AiHomeRecommendationBatch(recommend(mode, input, scenario))
    override suspend fun readSetup(): AiDemoSetup = store.data.first().setup()
    override suspend fun readQuota(): AiQuotaSnapshot = if (direct?.active == true) direct!!.quota() else store.data.first().quota()
    override suspend fun readLocalUsage(): LocalAiUsageSnapshot? = localUsage?.snapshot()

    override suspend fun saveSetup(setup: AiDemoSetup): AiDemoSetup {
        require(setup.preferences.keys.all { it in AiDemoRules.topics })
        return editWithRevision {
            it[LIKES] = setup.preferences.filterValues { value -> value == PreferenceDirection.LIKE }.keys
            it[EXCLUDES] = setup.preferences.filterValues { value -> value == PreferenceDirection.DISLIKE }.keys
            it.sources(setup.sources)
            it[COMPLETED] = true
        }.setup()
    }

    override suspend fun recommend(mode: AiMode, input: String, scenario: MockScenario): List<MockRecommendation> =
        withContext(Dispatchers.Default) {
            if (scenario == MockScenario.DIRECT) return@withContext (direct ?: throw WorkerProblem("DIRECT_UNAVAILABLE")).recommend(mode, input).items
            require(AiDemoRules.validateInput(mode, input) == null)
            val values = store.data.first()
            val setup = values.setup()
            check(setup.completed)
            val reason = when (mode) {
                AiMode.FOR_YOU -> "演示“猜你喜欢”卡片；未使用真实画像或模型排序。"
                AiMode.TONIGHT -> "演示“适合今晚”卡片；没有真实片长或可观看平台信息。"
                AiMode.EXPLORE -> "演示“探索新类型”卡片；不代表已分析你的观看记录。"
                AiMode.QUERY -> "已接收输入：" + input.trim().take(80) + "。仅演示提交，尚未解析检索条件。"
            }
            val result = if (scenario == MockScenario.SUCCESS) {
                RecommendationUseCase(catalog)(CandidateRecommendationRequest(mode,
                    CandidatePreferences(setup.preferences, values.inference()), allowMock = true)).items.map { row ->
                    val candidate = row.candidate
                    MockRecommendation(candidate.identity.externalId, candidate.title!!, candidate.topics.firstOrNull().orEmpty(),
                        candidate.description.orEmpty(), reason, candidate.identity)
                }
            } else emptyList()
            val outcome = if (scenario == MockScenario.SUCCESS && result.isEmpty()) MockScenario.EMPTY else scenario
            val history = AiDemoHistory(UUID.randomUUID().toString(), clock(), mode, outcome, result.map { it.title })
            store.edit { current -> current[HISTORY] = encodeHistory((listOf(history) + current.history()).take(HISTORY_LIMIT)) }
            if (scenario !in setOf(MockScenario.SUCCESS, MockScenario.EMPTY)) throw MockRequestException(scenario)
            result
        }

    override suspend fun saveFeedback(id: String, feedback: AiFeedback?): AiDemoSetup {
        require(id in TOPICS_BY_ID)
        return editWithRevision { preferences ->
            val values = preferences[FEEDBACK].orEmpty().filterNot { it.substringBefore(":") == id }.toMutableSet()
            if (feedback != null) values.add(id + ":" + feedback.name)
            preferences[FEEDBACK] = values
            // A new explicit feedback action can be learned after an earlier inference clear.
            preferences[CLEARED_EVIDENCE] = preferences[CLEARED_EVIDENCE].orEmpty().filterNot { it.substringBefore(":") == id }.toSet()
        }.setup()
    }

    override suspend fun readManagement(): AiManagementSnapshot {
        val values = store.data.first()
        val history = values.history()
        return if (values[HISTORY] != null && values[HISTORY] != encodeHistory(history)) {
            managementEdit { it[HISTORY] = encodeHistory(it.history()) }
        } else values.management().withLocalUsage()
    }
    override suspend fun setManagementPreference(topic: String, direction: PreferenceDirection?): AiManagementSnapshot {
        require(topic in AiDemoRules.topics)
        return managementEdit {
            it[LIKES] = it[LIKES].orEmpty() - topic + if (direction == PreferenceDirection.LIKE) setOf(topic) else emptySet()
            it[EXCLUDES] = it[EXCLUDES].orEmpty() - topic + if (direction == PreferenceDirection.DISLIKE) setOf(topic) else emptySet()
        }
    }
    override suspend fun setManagementSources(settings: ProfileSourceSettings) = managementEdit { it.sources(settings) }
    override suspend fun correctInference(topic: String, suppressed: Boolean): AiManagementSnapshot {
        require(topic in AiDemoRules.topics)
        return managementEdit { it[CORRECTIONS] = if (suppressed) it[CORRECTIONS].orEmpty() + topic else it[CORRECTIONS].orEmpty() - topic }
    }
    override suspend fun setMockQuota(scenario: MockQuotaScenario): AiManagementSnapshot {
        if (direct?.active == true) throw WorkerProblem("LOCAL_QUOTA_READ_ONLY")
        return managementEdit { it[QUOTA] = scenario.name }
    }
    override suspend fun clearManagement(action: AiClearAction): AiManagementSnapshot {
        val result = managementEdit {
            when (action) {
                AiClearAction.INFERRED -> it[CLEARED_EVIDENCE] = it[FEEDBACK].orEmpty()
                AiClearAction.LEARNING -> { it.remove(FEEDBACK); it.remove(CLEARED_EVIDENCE); it.remove(CORRECTIONS) }
                AiClearAction.HISTORY -> it.remove(HISTORY)
                AiClearAction.ALL -> {
                    // Targeted keys only; the independent quota fixture and unknown future keys survive.
                    it.remove(COMPLETED); it.remove(LIKES); it.remove(EXCLUDES)
                    it.remove(RATINGS); it.remove(BEHAVIOR); it.remove(FEEDBACK_SOURCE)
                    it.remove(FEEDBACK); it.remove(CLEARED_EVIDENCE); it.remove(CORRECTIONS); it.remove(HISTORY)
                }
            }
        }

        if (action == AiClearAction.ALL) direct?.select(false)
        return if (action == AiClearAction.ALL) result.copy(directMode = false, quotaFixtureEditable = true, quota = store.data.first().quota()) else result
    }

    private suspend fun managementEdit(operation: (MutablePreferences) -> Unit): AiManagementSnapshot {
        var result: AiManagementSnapshot? = null
        editWithRevision { operation(it); result = it.management() }
        return checkNotNull(result).withLocalUsage()
    }

    private suspend fun AiManagementSnapshot.withLocalUsage(): AiManagementSnapshot = try {
        val value = copy(localUsage = readLocalUsage())
        direct?.managed(value) ?: value
    } catch (error: kotlinx.coroutines.CancellationException) { throw error
    } catch (_: LocalAiUsageException) {
        // Local accounting failures must not prevent free profile/history management.
        val value = copy(localUsageUnavailable = true)
        direct?.managed(value) ?: value
    }

    /** Atomic demo versions; adding keys is backward compatible and never touches Room. */
    private suspend fun editWithRevision(operation: (MutablePreferences) -> Unit): Preferences {
        var changed = false
        val result = store.edit { values ->
            val before = Triple(values.setup(), values[CORRECTIONS].orEmpty(), values[CLEARED_EVIDENCE].orEmpty())
            val beforeSources = before.first.sources
            operation(values)
            val after = Triple(values.setup(), values[CORRECTIONS].orEmpty(), values[CLEARED_EVIDENCE].orEmpty())
            fun bump(key: Preferences.Key<Long>) {
                val current = values[key] ?: 0L
                check(current in 0 until WorkerRequestBuilder.MAX_SAFE_INTEGER) { "演示版本无法更新，数据未修改" }
                values[key] = current + 1
            }
            if (before != after) { bump(PROFILE_VERSION); changed = true }
            if (beforeSources != after.first.sources) bump(SOURCE_VERSION)
        }
        if (changed) direct?.invalidate()
        return result
    }
    private fun Preferences.profileToken() = ProfileToken(this[PROFILE_VERSION] ?: 0L, this[SOURCE_VERSION] ?: 0L)
    fun observeIntegrationToken() = store.data.map { it.profileToken() }.distinctUntilChanged()
    suspend fun readIntegrationProfile(): ProfileSnapshot {
        val values = store.data.first(); val setup = values.setup()
        return ProfileSnapshot(setup.sources, values.profileToken(), setup.preferences.map { (topic, direction) -> ExplicitPreference(ProfileFeature.topic(topic), direction) },
            values.inference().map { (topic, vote) -> InferredPreference(ProfileFeature.topic(topic), ProfileSource.FEEDBACK, 1,
                positiveEvidence = if (vote > 0) 1 else 0, negativeEvidence = if (vote < 0) 1 else 0) },
            values[CORRECTIONS].orEmpty().map { ProfileFeature.topic(it).id }.toSet())
    }
    suspend fun recordIntegrationHistory(token: ProfileToken, mode: AiMode, titles: List<String>, outcome: MockScenario) {
        store.edit { values ->
            if (values.profileToken() != token) throw WorkerProblem("STALE_PROFILE")
            val row = AiDemoHistory(UUID.randomUUID().toString(), clock(), mode, outcome, titles)
            values[HISTORY] = encodeHistory((listOf(row) + values.history()).take(HISTORY_LIMIT))
        }
    }

    private fun MutablePreferences.sources(settings: ProfileSourceSettings) {
        this[RATINGS] = settings.ratingsEnabled; this[BEHAVIOR] = settings.behaviorEnabled; this[FEEDBACK_SOURCE] = settings.feedbackEnabled
    }
    private fun Preferences.inference(): Map<String, Int> {
        if (!setup().sources.feedbackEnabled) return emptyMap()
        val cleared = this[CLEARED_EVIDENCE].orEmpty()
        val corrections = this[CORRECTIONS].orEmpty()
        return setup().feedback.mapNotNull { (id, feedback) ->
            val topic = TOPICS_BY_ID[id] ?: return@mapNotNull null
            if (id + ":" + feedback.name in cleared || topic in corrections) return@mapNotNull null
            topic to if (feedback == AiFeedback.LIKE) 1 else -1
        }.toMap()
    }
    private fun Preferences.quota() = AiQuotaSnapshot.mock(this[QUOTA]?.let { raw -> MockQuotaScenario.entries.firstOrNull { it.name == raw } ?: MockQuotaScenario.UNAVAILABLE } ?: MockQuotaScenario.AVAILABLE)
    private fun Preferences.management() = AiManagementSnapshot(setup(), inference().map { (topic, vote) ->
        InferredPreference(ProfileFeature.topic(topic), ProfileSource.FEEDBACK, 1,
            positiveEvidence = if (vote > 0) 1 else 0, negativeEvidence = if (vote < 0) 1 else 0)
    }, this[CORRECTIONS].orEmpty(), history(), quota())
    private fun Preferences.history(): List<AiDemoHistory> {
        val json = JSONArray(this[HISTORY] ?: "[]")
        val now = clock()
        return (0 until json.length()).map { index ->
            val row = json.getJSONObject(index)
            val titles = row.getJSONArray("titles")
            AiDemoHistory(row.getString("id"), row.getLong("time"), AiMode.valueOf(row.getString("mode")),
                MockScenario.valueOf(row.getString("outcome")), (0 until titles.length()).map { titles.getString(it) })
        }.filter { it.createdAt > now - HISTORY_AGE }.take(HISTORY_LIMIT)
    }
    private fun encodeHistory(rows: List<AiDemoHistory>): String = JSONArray().apply {
        rows.forEach { row -> put(JSONObject().put("id", row.id).put("time", row.createdAt).put("mode", row.mode.name)
            .put("outcome", row.outcome.name).put("titles", JSONArray(row.titles))) }
    }.toString()
    private fun Preferences.setup(): AiDemoSetup = AiDemoSetup(
        completed = this[COMPLETED] ?: false,
        preferences = buildMap {
            this@setup[LIKES].orEmpty().filter { it in AiDemoRules.topics }.forEach { put(it, PreferenceDirection.LIKE) }
            this@setup[EXCLUDES].orEmpty().filter { it in AiDemoRules.topics }.forEach { put(it, PreferenceDirection.DISLIKE) }
        },
        sources = ProfileSourceSettings(this[RATINGS] ?: false, this[BEHAVIOR] ?: false, this[FEEDBACK_SOURCE] ?: true),
        feedback = this[FEEDBACK].orEmpty().mapNotNull { value ->
            val direction = AiFeedback.entries.firstOrNull { it.name == value.substringAfter(":") }
            direction?.let { value.substringBefore(":") to it }
        }.toMap(),
    )
    private companion object {
        const val HISTORY_LIMIT = 20
        const val HISTORY_AGE = 30L * 24 * 60 * 60 * 1000
        val TOPICS_BY_ID = mapOf("mock-0" to "科幻", "mock-1" to "喜剧", "mock-2" to "纪录片")
        val COMPLETED = booleanPreferencesKey("completed")
        val LIKES = stringSetPreferencesKey("likes")
        val EXCLUDES = stringSetPreferencesKey("excludes")
        val RATINGS = booleanPreferencesKey("ratings")
        val BEHAVIOR = booleanPreferencesKey("behavior")
        val FEEDBACK_SOURCE = booleanPreferencesKey("feedback_source")
        val FEEDBACK = stringSetPreferencesKey("feedback")
        val CLEARED_EVIDENCE = stringSetPreferencesKey("management_cleared_evidence")
        val CORRECTIONS = stringSetPreferencesKey("management_corrections")
        val HISTORY = stringPreferencesKey("management_history")
        val QUOTA = stringPreferencesKey("management_mock_quota")
        val PROFILE_VERSION = longPreferencesKey("integration_profile_version")
        val SOURCE_VERSION = longPreferencesKey("integration_source_version")
    }
}
