package com.yingji.app.data.ai

import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.flow.combine

/** Local Room management only. Never depends on an API client, Key, Mock history or usage tracker. */
class RoomLocalProfileManagementRepository(
    private val profiles: ProfileRepository,
    private val categories: CategoryRepository,
) : LocalProfileManagementRepository {
    override fun observe() = combine(profiles.observeProfile(), categories.observeCategories(), categories.observeTags()) { profile, categoryRows, tagRows ->
        val features = linkedMapOf<String, ProfileFeature>()
        (profile.explicitPreferences.map { it.feature } + profile.inferredPreferences.map { it.feature } +
            AiDemoRules.topics.map(ProfileFeature::topic) + categoryRows.map { ProfileFeature.category(it.id, it.name) } +
            tagRows.map { ProfileFeature.tag(it.id, it.name) }).forEach { features[it.id] = it }
        profile.suppressedFeatureIds.filter { it !in features }.forEach { id -> features[id] = ProfileFeature(id, id.substringAfter(':')) }
        LocalProfileManagementSnapshot(profile, features.values.sortedWith(compareBy({ it.id.substringBefore(':') }, { it.label }, { it.id })))
    }
    override suspend fun setPreference(feature: ProfileFeature, direction: PreferenceDirection) = profiles.setExplicitPreference(feature, direction)
    override suspend fun removePreference(featureId: String) = profiles.removeExplicitPreference(featureId)
    override suspend fun setSources(settings: ProfileSourceSettings) = profiles.setSources(settings)
    override suspend fun correct(feature: ProfileFeature, suppressed: Boolean) =
        if (suppressed) profiles.suppressInference(feature) else profiles.undoSuppression(feature.id)
    override suspend fun reset() = profiles.resetLocalProfile()
}
