package com.yingji.app.data.ai

import android.content.Context
import com.yingji.app.BuildConfig
import com.yingji.app.domain.ai.*

/** Direct access requires the opt-in build flag, configured live Key and explicit session consent. */
object AiHomeRepositoryFactory {
    fun verifier(context: Context): AiKeyVerifier = DeepSeekAiKeyVerifier(DeepSeekHttpClient(), enabled = BuildConfig.AI_DIRECT_VERIFICATION_ENABLED)
    fun create(demo: MockAiHomeRepository, catalog: CandidateCatalogRepository, context: Context, keys: AiKeyConfigurationRepository, usage: LocalAiUsageTracker = LocalAiUsageRepository(context)): AiHomeRepository = demo.also {
        if (BuildConfig.AI_DIRECT_VERIFICATION_ENABLED) {
            val client = DeepSeekHttpClient(usage)
            it.attachDirect(DirectHomeIntegration(demo, keys, catalog, mockKey = false,
                serviceFactory = { current -> DirectAiService(keys, usage, client, current) }, cancelPending = client::cancelPending))
        }
    }
    fun personalized(profiles: ProfileRepository, keys: AiKeyConfigurationRepository, permission: () -> TmdbSourcePermission,
        usage: LocalAiUsageTracker, localProfiles: LocalProfileManagementRepository? = null): PersonalizedRecommendation? {
        if (!BuildConfig.AI_DIRECT_VERIFICATION_ENABLED) return null
        val client = DeepSeekHttpClient(usage)
        return RoomPersonalizedRecommendation(profiles, keys, permission, false,
            { current -> DirectAiService(keys, usage, client, current) }, client::cancelPending, localProfiles = localProfiles)
    }
}
