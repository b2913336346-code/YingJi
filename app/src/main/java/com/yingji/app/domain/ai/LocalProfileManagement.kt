package com.yingji.app.domain.ai

import kotlinx.coroutines.flow.Flow

enum class AiManagementScope { MOCK, LOCAL_PROFILE }
data class LocalProfileManagementSnapshot(val profile: ProfileSnapshot, val features: List<ProfileFeature>)
interface LocalProfileManagementRepository {
    fun observe(): Flow<LocalProfileManagementSnapshot>
    suspend fun setPreference(feature: ProfileFeature, direction: PreferenceDirection): ProfileSnapshot
    suspend fun removePreference(featureId: String): ProfileSnapshot
    suspend fun setSources(settings: ProfileSourceSettings): ProfileSnapshot
    suspend fun correct(feature: ProfileFeature, suppressed: Boolean): ProfileSnapshot
    suspend fun reset(): ProfileSnapshot
}
data class LocalProfileManagementState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val snapshot: LocalProfileManagementSnapshot? = null,
    val error: String? = null,
    val message: String? = null,
    val pendingRemoval: ProfileFeature? = null,
    val pendingReset: Boolean = false,
)
