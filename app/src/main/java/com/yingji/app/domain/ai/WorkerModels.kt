package com.yingji.app.domain.ai

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Local source permissions and consent to send a summary are deliberately independent. */
data class AiIntegrationContext(val profile: ProfileSnapshot, val externalProcessingAllowed: Boolean = false, val consentVersion: Long = 0, val credentialRevision: String? = null)
interface AiIntegrationProfileSource {
    suspend fun snapshot(): AiIntegrationContext
    suspend fun isCurrent(context: AiIntegrationContext): Boolean = snapshot() == context
}
data class WorkerExplicit(val topic: String, val direction: PreferenceDirection)
data class WorkerInferred(val topic: String, val direction: PreferenceDirection, val source: ProfileSource)
data class WorkerProfileSummary(val enabledSources: ProfileSourceSettings, val explicit: List<WorkerExplicit>, val inferred: List<WorkerInferred>)
data class AiHintInput(val mode: AiMode, val query: String = "", val conditions: CandidateHardConditions = CandidateHardConditions(), val allowMock: Boolean = false)
data class WorkerHintRequest(val requestId: String, val mode: AiMode, val userQuery: String, val token: ProfileToken,
    val profileSummary: WorkerProfileSummary, val conditions: CandidateHardConditions)
data class WorkerHints(val preferredTopics: List<String>, val conditions: CandidateHardConditions)
enum class WorkerTaskStatus { SUCCESS, PENDING, ERROR }
data class WorkerUsage(val complete: Boolean, val httpRequests: Int, val retries: Int, val promptTokens: Long?, val completionTokens: Long?, val totalTokens: Long?)
data class WorkerTaskResponse(val requestId: String?, val token: ProfileToken?, val status: WorkerTaskStatus,
    val hints: WorkerHints?, val quota: AiQuotaSnapshot, val usage: WorkerUsage, val error: WorkerProblem?)
open class WorkerProblem(val code: String, val retryable: Boolean = false) : IllegalStateException(code)
interface AiWorkerService {
    suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse
    suspend fun quota(): AiQuotaSnapshot
}
enum class AiRecommendationOrigin { WORKER, CACHE, LOCAL, EMPTY }
data class AiIntegratedResult(val items: List<RankedCandidate>, val origin: AiRecommendationOrigin,
    val quota: AiQuotaSnapshot, val token: ProfileToken, val taskStatus: WorkerTaskStatus? = null, val problem: String? = null)
data class AiHomeRecommendationBatch(val items: List<MockRecommendation>, val quota: AiQuotaSnapshot? = null, val notice: String? = null)

object WorkerRequestBuilder {
    const val MAX_SAFE_INTEGER = 9007199254740991L
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    fun topic(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun validTopic(value: String) = value.isNotBlank() && value.codePointCount(0, value.length) <= 60 && value.none { it.code < 32 }
    fun build(input: AiHintInput, context: AiIntegrationContext, requestId: String = UUID.randomUUID().toString()): WorkerHintRequest {
        if (!context.externalProcessingAllowed) throw WorkerProblem("CONSENT_REQUIRED")
        require(uuid.matches(requestId))
        require(context.profile.token.let { it.profileVersion in 0..MAX_SAFE_INTEGER && it.sourceSettingsVersion in 0..MAX_SAFE_INTEGER })
        require(context.consentVersion >= 0)
        val query = if (input.mode == AiMode.QUERY) input.query.trim() else ""
        require(query.codePointCount(0, query.length) <= 500 && (input.mode != AiMode.QUERY || query.isNotBlank()))
        val profile = context.profile
        // Topic labels only: never Room keys, scores, movie titles, viewing dates or evidence rows.
        val explicit = profile.explicitPreferences.filter { it.feature.id.startsWith("topic:") }
            .groupBy { topic(it.feature.label) }.map { (name, rows) ->
                WorkerExplicit(name, if (rows.any { it.direction == PreferenceDirection.DISLIKE }) PreferenceDirection.DISLIKE else PreferenceDirection.LIKE)
            }.onEach { require(validTopic(it.topic)) }
            .sortedWith(compareBy<WorkerExplicit> { it.direction != PreferenceDirection.DISLIKE }.thenBy { it.topic }).take(20)
        val inferred = profile.inferredPreferences.filter { row -> row.feature.id.startsWith("topic:") && row.feature.id !in profile.suppressedFeatureIds &&
            when (row.source) { ProfileSource.RATING -> profile.settings.ratingsEnabled; ProfileSource.FEEDBACK -> profile.settings.feedbackEnabled; ProfileSource.BEHAVIOR -> false } }
            .groupBy { it.source to topic(it.feature.label) }.mapNotNull { (key, rows) ->
                val score = rows.sumOf { it.positiveEvidence.toLong() - it.negativeEvidence }
                if (score == 0L) null else WorkerInferred(key.second, if (score > 0) PreferenceDirection.LIKE else PreferenceDirection.DISLIKE, key.first)
            }.onEach { require(validTopic(it.topic)) }.sortedWith(compareBy<WorkerInferred> { it.source.name }.thenBy { it.topic }).take(20)
        fun clean(values: Set<String>) = values.map(::topic).toSet().also { require(it.size <= 12 && it.all(::validTopic)) }
        val hard = input.conditions.copy(requiredTopics = clean(input.conditions.requiredTopics), excludedTopics = clean(input.conditions.excludedTopics),
            excludedIdentities = input.conditions.excludedIdentities.sortedWith(compareBy<CandidateIdentity> { it.sourceId }.thenBy { it.externalId }).take(50).toSet())
        require(hard.minYear == null || hard.minYear in 1800..3000); require(hard.maxYear == null || hard.maxYear in 1800..3000)
        require(hard.maxRuntimeMinutes == null || hard.maxRuntimeMinutes in 1..10000)
        require(hard.minYear == null || hard.maxYear == null || hard.minYear <= hard.maxYear)
        if (hard.requiredTopics.any { it in hard.excludedTopics || explicit.any { row -> row.topic == it && row.direction == PreferenceDirection.DISLIKE } }) throw WorkerProblem("CONFLICTING_CONDITIONS")
        hard.excludedIdentities.forEach { require(it.sourceId.isNotBlank() && it.sourceId == it.sourceId.trim() && it.sourceId.length <= 100 && it.externalId.isNotBlank() && it.externalId == it.externalId.trim() && it.externalId.length <= 200) }
        return WorkerHintRequest(requestId.lowercase(Locale.ROOT), input.mode, query, profile.token,
            WorkerProfileSummary(profile.settings, explicit, inferred), hard)
    }
}
