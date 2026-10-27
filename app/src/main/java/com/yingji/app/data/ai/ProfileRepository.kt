package com.yingji.app.data.ai

import androidx.room.withTransaction
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class ProfileStorageException(cause: Exception) : IllegalStateException("画像操作失败，已保留原有数据，请重试", cause)

class ProfileRepository(
    private val database: AppDatabase,
    private val library: ProfileLibrarySource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.aiProfileDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeProfile(): Flow<ProfileSnapshot> = DatabaseHealthMonitor.protectRead(
        merge(
            dao.observeState().map { Unit },
            dao.observeState().map { (it ?: AiProfileState()).settings() }.distinctUntilChanged()
                .flatMapLatest { settings ->
                    if (settings.ratingsEnabled || settings.behaviorEnabled) library.changes(settings) else emptyFlow()
                },
        ).map { getProfile() }.distinctUntilChanged(),
    )

    suspend fun getProfile(): ProfileSnapshot = commit()
    suspend fun isCurrent(token: ProfileToken): Boolean = getProfile().token == token

    suspend fun setSources(settings: ProfileSourceSettings): ProfileSnapshot = commit(settings)

    suspend fun setExplicitPreference(feature: ProfileFeature, direction: PreferenceDirection): ProfileSnapshot = commit {
        val old = dao.explicit().firstOrNull { it.featureId == feature.id }
        if (old?.direction == direction.name && old.label == feature.label) false
        else {
            dao.putExplicit(AiExplicitPreference(feature.id, feature.label, direction.name, clock()))
            true
        }
    }

    suspend fun removeExplicitPreference(featureId: String): ProfileSnapshot {
        require(featureId.isNotBlank()) { "偏好特征不能为空" }
        return commit { dao.removeExplicit(featureId) > 0 }
    }

    suspend fun suppressInference(feature: ProfileFeature): ProfileSnapshot = commit {
        dao.suppress(AiProfileCorrection(feature.id, clock())) != -1L
    }

    suspend fun undoSuppression(featureId: String): ProfileSnapshot {
        require(featureId.isNotBlank()) { "偏好特征不能为空" }
        return commit { dao.undoSuppression(featureId) > 0 }
    }

    suspend fun recordFeedback(eventId: String, feature: ProfileFeature, direction: PreferenceDirection): ProfileSnapshot {
        require(eventId.isNotBlank() && eventId.length <= 200) { "反馈标识不能为空且不能超过 200 字" }
        return commit {
            val old = dao.feedback().firstOrNull { it.eventId == eventId }
            if (old?.featureId == feature.id && old.label == feature.label && old.direction == direction.name) false
            else {
                dao.putFeedback(AiProfileFeedback(eventId, feature.id, feature.label, direction.name, clock()))
                true
            }
        }
    }

    suspend fun removeFeedback(eventId: String): ProfileSnapshot {
        require(eventId.isNotBlank()) { "反馈标识不能为空" }
        return commit { dao.removeFeedback(eventId) > 0 }
    }

    /** Only AI-owned rows. Original library data, Key, Mock history and local usage are outside this transaction. */
    suspend fun resetLocalProfile(): ProfileSnapshot = commit(ProfileSourceSettings()) {
        val changed = dao.clearExplicit() + dao.clearCorrections() + dao.clearFeedback() > 0
        // rebuild removes inferred rows using the restored source defaults (ratings/behavior off).
        changed || dao.inferred().isNotEmpty()
    }

    private suspend fun commit(
        newSettings: ProfileSourceSettings? = null,
        operation: suspend () -> Boolean = { false },
    ): ProfileSnapshot = try {
        DatabaseHealthMonitor.protectWrite {
            database.withTransaction {
                val state = dao.state() ?: AiProfileState()
                val settingsChanged = newSettings != null && newSettings != state.settings()
                val changed = operation()
                val next = if (settingsChanged) state.copy(
                    ratingsEnabled = newSettings!!.ratingsEnabled,
                    behaviorEnabled = newSettings.behaviorEnabled,
                    feedbackEnabled = newSettings.feedbackEnabled,
                    sourceSettingsVersion = Math.addExact(state.sourceSettingsVersion, 1L),
                ) else state
                rebuild(next, changed || settingsChanged)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw ProfileStorageException(error)
    }

    private suspend fun rebuild(state: AiProfileState, forceVersion: Boolean): ProfileSnapshot {
        val ratings = if (state.ratingsEnabled) library.ratings() else emptyList()
        val behavior = if (state.behaviorEnabled) library.behavior() else emptyList()
        val feedback = if (state.feedbackEnabled) dao.feedback() else emptyList()
        val suppressed = dao.corrections().map { it.featureId }.toSet()
        val feedbackInference = feedback.groupBy { it.featureId }.toSortedMap().map { (_, rows) ->
            InferredPreference(ProfileFeature(rows.last().featureId, rows.last().label), ProfileSource.FEEDBACK,
                evidenceCount = rows.size,
                positiveEvidence = rows.count { it.direction == PreferenceDirection.LIKE.name },
                negativeEvidence = rows.count { it.direction == PreferenceDirection.DISLIKE.name })
        }
        val inferred = (ProfileInference.ratings(ratings) + ProfileInference.behavior(behavior) + feedbackInference)
            .filterNot { it.feature.id in suppressed }
            .map { it.entity() }.sortedWith(compareBy({ it.featureId }, { it.source }))
        val fingerprint = fingerprint(ratings, behavior)
        val previous = dao.inferred()
        val changed = forceVersion || previous != inferred || state.libraryFingerprint != fingerprint
        val next = if (changed) state.copy(
            profileVersion = Math.addExact(state.profileVersion, 1L),
            libraryFingerprint = fingerprint,
            updatedAt = clock(),
        ) else state
        if (previous != inferred) {
            dao.clearInferred()
            dao.insertInferred(inferred)
        }
        if (changed) dao.putState(next)
        return ProfileSnapshot(
            settings = next.settings(),
            token = ProfileToken(next.profileVersion, next.sourceSettingsVersion),
            explicitPreferences = dao.explicit().map {
                ExplicitPreference(ProfileFeature(it.featureId, it.label), PreferenceDirection.valueOf(it.direction))
            },
            inferredPreferences = inferred.map { it.model() },
            suppressedFeatureIds = suppressed,
            updatedAt = next.updatedAt,
        )
    }

    private fun fingerprint(ratings: List<RatingFeatureEvidence>, behavior: List<BehaviorFeatureEvidence>): String {
        if (ratings.isEmpty() && behavior.isEmpty()) return ""
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(ratings.size)
            ratings.sortedWith(compareBy({ it.movieId }, { it.feature.id })).forEach {
                output.writeLong(it.movieId); output.writeText(it.feature.id); output.writeText(it.feature.label)
                output.writeDouble(it.rating)
            }
            output.writeInt(behavior.size)
            behavior.sortedWith(compareBy({ it.movieId }, { it.feature.id })).forEach {
                output.writeLong(it.movieId); output.writeText(it.feature.id); output.writeText(it.feature.label)
                output.writeInt(it.completedWatches); output.writeInt(it.repeatWatches)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private fun AiProfileState.settings() = ProfileSourceSettings(ratingsEnabled, behaviorEnabled, feedbackEnabled)
private fun InferredPreference.entity() = AiInferredPreference(feature.id, source.name, feature.label,
    evidenceCount, positiveEvidence, negativeEvidence, completedWatches, repeatWatches)
private fun AiInferredPreference.model() = InferredPreference(ProfileFeature(featureId, label), ProfileSource.valueOf(source),
    evidenceCount, positiveEvidence, negativeEvidence, completedWatches, repeatWatches)

private fun DataOutputStream.writeText(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}
