package com.yingji.app.domain.ai

import org.junit.Assert.*
import org.junit.Test

class ProfileInferenceTest {
    private val feature = ProfileFeature.topic("科幻")

    @Test fun ratingThresholdsCoverEveryHalfPointIncludingZero() {
        for (step in 0..20) {
            val rating = step / 2.0
            val result = ProfileInference.ratings(listOf(RatingFeatureEvidence(1, feature, rating))).single()
            assertEquals("rating=$rating", when {
                rating >= 8 -> PreferenceDirection.LIKE
                rating <= 4 -> PreferenceDirection.DISLIKE
                else -> null
            }, result.direction)
            assertEquals(1, result.evidenceCount)
        }
    }

    @Test fun completedAndRepeatedViewingNeverCreatesALike() {
        val result = ProfileInference.behavior(listOf(BehaviorFeatureEvidence(1, feature, 4, 3))).single()
        assertNull(result.direction)
        assertEquals(4, result.completedWatches)
        assertEquals(3, result.repeatWatches)
    }

    @Test fun explicitPreferenceWinsOverConflictingInferenceAndRemovalRevealsInference() {
        val inferred = ProfileInference.ratings(listOf(RatingFeatureEvidence(1, feature, 10.0)))
        val profile = ProfileSnapshot(explicitPreferences = listOf(ExplicitPreference(feature, PreferenceDirection.DISLIKE)),
            inferredPreferences = inferred)
        assertEquals(PreferenceDirection.DISLIKE, profile.effectiveDirections[feature.id])
        assertEquals(PreferenceDirection.LIKE, profile.copy(explicitPreferences = emptyList()).effectiveDirections[feature.id])
        assertEquals(PreferenceDirection.LIKE, profile.inferredPreferences.single().direction)
    }

    @Test fun conflictingSourcesAreNeutralRatherThanSilentlyPickingOne() {
        val a = InferredPreference(feature, ProfileSource.RATING, 1, positiveEvidence = 1)
        val b = InferredPreference(feature, ProfileSource.FEEDBACK, 1, negativeEvidence = 1)
        assertFalse(ProfileSnapshot(inferredPreferences = listOf(a, b)).effectiveDirections.containsKey(feature.id))
    }

    @Test fun categoryAndTagWithSameIdNeverShareAFeature() {
        assertNotEquals(ProfileFeature.category(7, "电影").id, ProfileFeature.tag(7, "电影").id)
        assertEquals(ProfileFeature.topic(" SciFi ").id, ProfileFeature.topic("scifi").id)
    }

    @Test fun existingLongCategoryNamesRemainValidProfileEvidence() {
        val category = ProfileFeature.category(7, "长".repeat(1000))
        assertEquals(1000, category.label.length)
        assertEquals(PreferenceDirection.LIKE, ProfileInference.ratings(listOf(RatingFeatureEvidence(1, category, 8.0))).single().direction)
    }

    @Test fun blankAndInvalidFeaturesAreRejected() {
        for (action in listOf<() -> Unit>({ ProfileFeature.topic("  ") }, { ProfileFeature.category(0, "电影") },
            { ProfileFeature("unknown:1", "电影") }, { ProfileFeature.topic("a".repeat(201)) })) {
            try { action(); fail("Invalid feature accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
