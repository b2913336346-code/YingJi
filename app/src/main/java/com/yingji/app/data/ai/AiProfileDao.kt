package com.yingji.app.data.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiProfileDao {
    @Query("SELECT * FROM ai_profile_state WHERE id=1")
    fun observeState(): Flow<AiProfileState?>
    @Query("SELECT * FROM ai_profile_state WHERE id=1")
    suspend fun state(): AiProfileState?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(value: AiProfileState)

    @Query("SELECT * FROM ai_explicit_preferences ORDER BY featureId")
    suspend fun explicit(): List<AiExplicitPreference>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putExplicit(value: AiExplicitPreference)
    @Query("DELETE FROM ai_explicit_preferences WHERE featureId=:id")
    suspend fun removeExplicit(id: String): Int

    @Query("DELETE FROM ai_explicit_preferences")
    suspend fun clearExplicit(): Int
    @Query("DELETE FROM ai_profile_corrections")
    suspend fun clearCorrections(): Int
    @Query("DELETE FROM ai_profile_feedback")
    suspend fun clearFeedback(): Int

    @Query("SELECT * FROM ai_profile_corrections ORDER BY featureId")
    suspend fun corrections(): List<AiProfileCorrection>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun suppress(value: AiProfileCorrection): Long
    @Query("DELETE FROM ai_profile_corrections WHERE featureId=:id")
    suspend fun undoSuppression(id: String): Int

    @Query("SELECT * FROM ai_profile_feedback ORDER BY eventId")
    suspend fun feedback(): List<AiProfileFeedback>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeedback(value: AiProfileFeedback)
    @Query("DELETE FROM ai_profile_feedback WHERE eventId=:id")
    suspend fun removeFeedback(id: String): Int

    @Query("SELECT * FROM ai_inferred_preferences ORDER BY featureId, source")
    suspend fun inferred(): List<AiInferredPreference>
    @Query("DELETE FROM ai_inferred_preferences")
    suspend fun clearInferred()
    @Insert
    suspend fun insertInferred(values: List<AiInferredPreference>)
}
