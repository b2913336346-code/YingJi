package com.yingji.app.data.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_profile_state")
data class AiProfileState(
    @PrimaryKey val id: Int = 1,
    val ratingsEnabled: Boolean = false,
    val behaviorEnabled: Boolean = false,
    val feedbackEnabled: Boolean = true,
    val profileVersion: Long = 0,
    val sourceSettingsVersion: Long = 0,
    val libraryFingerprint: String = "",
    val updatedAt: Long = 0,
)

@Entity(tableName = "ai_explicit_preferences")
data class AiExplicitPreference(
    @PrimaryKey val featureId: String,
    val label: String,
    val direction: String,
    val updatedAt: Long,
)

@Entity(tableName = "ai_profile_corrections")
data class AiProfileCorrection(@PrimaryKey val featureId: String, val updatedAt: Long)

@Entity(tableName = "ai_profile_feedback")
data class AiProfileFeedback(
    @PrimaryKey val eventId: String,
    val featureId: String,
    val label: String,
    val direction: String,
    val updatedAt: Long,
)

@Entity(tableName = "ai_inferred_preferences", primaryKeys = ["featureId", "source"])
data class AiInferredPreference(
    val featureId: String,
    val source: String,
    val label: String,
    val evidenceCount: Int,
    val positiveEvidence: Int,
    val negativeEvidence: Int,
    val completedWatches: Int,
    val repeatWatches: Int,
)
