package com.yingji.app.domain.ai

import java.util.Locale

enum class PreferenceDirection { LIKE, DISLIKE }
enum class ProfileSource { RATING, BEHAVIOR, FEEDBACK }

data class ProfileFeature(val id: String, val label: String) {
    init {
        require(label.isNotBlank()) { "偏好名称不能为空" }
        val parts = id.split(':', limit = 2)
        require(parts.size == 2 && when (parts[0]) {
            "category", "tag" -> (parts[1].toLongOrNull() ?: 0) > 0
            "topic" -> parts[1].isNotBlank() && parts[1].length <= 200
            else -> false
        }) { "偏好特征无效" }
    }

    companion object {
        fun category(id: Long, name: String) = ProfileFeature("category:$id", name.trim())
        fun tag(id: Long, name: String) = ProfileFeature("tag:$id", name.trim())
        fun topic(name: String): ProfileFeature {
            val normalized = name.trim()
            return ProfileFeature("topic:" + normalized.lowercase(Locale.ROOT), normalized)
        }
    }
}

data class ProfileSourceSettings(
    val ratingsEnabled: Boolean = false,
    val behaviorEnabled: Boolean = false,
    val feedbackEnabled: Boolean = true,
)

data class ProfileToken(val profileVersion: Long, val sourceSettingsVersion: Long)
data class ExplicitPreference(val feature: ProfileFeature, val direction: PreferenceDirection)

data class InferredPreference(
    val feature: ProfileFeature,
    val source: ProfileSource,
    val evidenceCount: Int,
    val positiveEvidence: Int = 0,
    val negativeEvidence: Int = 0,
    val completedWatches: Int = 0,
    val repeatWatches: Int = 0,
) {
    val direction: PreferenceDirection? get() = when {
        positiveEvidence > negativeEvidence -> PreferenceDirection.LIKE
        negativeEvidence > positiveEvidence -> PreferenceDirection.DISLIKE
        else -> null
    }
}

data class ProfileSnapshot(
    val settings: ProfileSourceSettings = ProfileSourceSettings(),
    val token: ProfileToken = ProfileToken(0, 0),
    val explicitPreferences: List<ExplicitPreference> = emptyList(),
    val inferredPreferences: List<InferredPreference> = emptyList(),
    val suppressedFeatureIds: Set<String> = emptySet(),
    val updatedAt: Long = 0,
) {
    // A tie has no inferred direction. Behavior never creates positive/negative votes.
    val effectiveDirections: Map<String, PreferenceDirection> get() {
        val result = inferredPreferences.groupBy { it.feature.id }.mapNotNull { (id, rows) ->
            val difference = rows.sumOf { it.positiveEvidence - it.negativeEvidence }
            when {
                difference > 0 -> id to PreferenceDirection.LIKE
                difference < 0 -> id to PreferenceDirection.DISLIKE
                else -> null
            }
        }.toMap().toMutableMap()
        explicitPreferences.forEach { result[it.feature.id] = it.direction }
        return result
    }
}

data class RatingFeatureEvidence(val movieId: Long, val feature: ProfileFeature, val rating: Double)
data class BehaviorFeatureEvidence(
    val movieId: Long,
    val feature: ProfileFeature,
    val completedWatches: Int,
    val repeatWatches: Int,
)

object ProfileInference {
    fun ratings(rows: List<RatingFeatureEvidence>): List<InferredPreference> = rows
        .groupBy { it.feature.id }.toSortedMap().map { (_, items) ->
            InferredPreference(items.first().feature, ProfileSource.RATING, items.size,
                positiveEvidence = items.count { it.rating >= 8.0 },
                negativeEvidence = items.count { it.rating <= 4.0 })
        }

    fun behavior(rows: List<BehaviorFeatureEvidence>): List<InferredPreference> = rows
        .groupBy { it.feature.id }.toSortedMap().map { (_, items) ->
            InferredPreference(items.first().feature, ProfileSource.BEHAVIOR,
                evidenceCount = items.sumOf { it.completedWatches },
                completedWatches = items.sumOf { it.completedWatches },
                repeatWatches = items.sumOf { it.repeatWatches })
        }
}
