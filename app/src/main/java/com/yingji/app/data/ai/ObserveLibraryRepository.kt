package com.yingji.app.data.ai

import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.BehaviorFeatureEvidence
import com.yingji.app.domain.ai.ProfileFeature
import com.yingji.app.domain.ai.RatingFeatureEvidence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ProfileLibrarySource {
    suspend fun ratings(): List<RatingFeatureEvidence>
    suspend fun behavior(): List<BehaviorFeatureEvidence>
    fun changes(settings: com.yingji.app.domain.ai.ProfileSourceSettings): Flow<Unit>
}

class ObserveLibraryRepository(private val movies: MovieRepository) : ProfileLibrarySource {
    override suspend fun ratings() = movies.readRatingFeatures().map {
        RatingFeatureEvidence(it.movieId, ProfileFeature(it.featureId, it.label), it.rating)
    }
    override suspend fun behavior() = movies.readBehaviorFeatures().map {
        BehaviorFeatureEvidence(it.movieId, ProfileFeature(it.featureId, it.label), it.completedWatches, it.repeatWatches)
    }
    override fun changes(settings: com.yingji.app.domain.ai.ProfileSourceSettings): Flow<Unit> =
        movies.observeProfileInputs(includeBehavior = settings.behaviorEnabled).map { Unit }
}
