package com.yingji.app.domain.ai

import kotlinx.coroutines.flow.Flow

/** A preview of one bounded transfer. It is memory-only and never grants reusable consent. */
data class PersonalizedConsent(
    val profileToken: ProfileToken,
    val credentialRevision: String?,
    val source: CandidateSource,
    val summary: WorkerProfileSummary,
)
data class PersonalizedRevision(val profile: ProfileSnapshot, val credentialRevision: String?, val keyAvailable: Boolean)
interface PersonalizedRecommendation {
    val usesMockKey: Boolean
    val changes: Flow<PersonalizedRevision>
    suspend fun prepare(catalog: CandidateCatalog): PersonalizedConsent
    suspend fun recommend(catalog: CandidateCatalog, consent: PersonalizedConsent, sessionCurrent: () -> Boolean): AiIntegratedResult
}

/** Only labels are projected to topic directions; original entity IDs and evidence stay in Room. */
fun recommendationProfile(profile: ProfileSnapshot, labels: Map<String, String> = emptyMap()): ProfileSnapshot {
    fun topic(feature: ProfileFeature) = ProfileFeature.topic(WorkerRequestBuilder.topic(labels[feature.id] ?: feature.label))
    val explicit = profile.explicitPreferences.map { it.copy(feature = topic(it.feature)) }
        .groupBy { it.feature.id }.values.map { rows -> rows.first().copy(direction =
            if (rows.any { it.direction == PreferenceDirection.DISLIKE }) PreferenceDirection.DISLIKE else PreferenceDirection.LIKE) }
    val inferred = profile.inferredPreferences.filter { it.feature.id !in profile.suppressedFeatureIds }
        .map { it.copy(feature = topic(it.feature)) }
    return profile.copy(explicitPreferences = explicit, inferredPreferences = inferred, suppressedFeatureIds = emptySet())
}
