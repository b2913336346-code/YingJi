package com.yingji.app.data.ai

import android.content.Context
import com.yingji.app.BuildConfig
import com.yingji.app.domain.ai.*

/** Debug uses the same direct build flag as Release; default builds keep the local protocol Mock. */
object AiHomeRepositoryFactory {
    fun verifier(context: Context): AiKeyVerifier {
        if (BuildConfig.AI_DIRECT_VERIFICATION_ENABLED) return DeepSeekAiKeyVerifier(DeepSeekHttpClient())
        return DeepSeekAiKeyVerifier(object : DeepSeekKeyValidation {
            override suspend fun verify(key: AiSecret, isCurrent: () -> Boolean) {
                if (!isCurrent()) throw WorkerProblem("STALE_KEY")
                LocalMockAiKeyVerifier().verify(key)
            }
        }, mock = true)
    }
    fun create(demo: MockAiHomeRepository, catalog: CandidateCatalogRepository, context: Context, keys: AiKeyConfigurationRepository, usage: LocalAiUsageTracker = LocalAiUsageRepository(context)): AiHomeRepository = demo.also {
        if (BuildConfig.AI_DIRECT_VERIFICATION_ENABLED) {
            val client = DeepSeekHttpClient(usage)
            it.attachDirect(DirectHomeIntegration(demo, keys, catalog, mockKey = false,
                serviceFactory = { current -> DirectAiService(keys, usage, client, current) }, cancelPending = client::cancelPending))
        } else {
            it.attachDirect(DirectHomeIntegration(demo, keys, catalog, mockKey = true,
                serviceFactory = { current -> DirectMockService(keys, current) }))
        }
    }
    fun personalized(profiles: ProfileRepository, keys: AiKeyConfigurationRepository, permission: () -> TmdbSourcePermission,
        usage: LocalAiUsageTracker, localProfiles: LocalProfileManagementRepository? = null): PersonalizedRecommendation {
        if (BuildConfig.AI_DIRECT_VERIFICATION_ENABLED) {
            val client = DeepSeekHttpClient(usage)
            return RoomPersonalizedRecommendation(profiles, keys, permission, false,
                { current -> DirectAiService(keys, usage, client, current) }, client::cancelPending, localProfiles = localProfiles)
        }
        return RoomPersonalizedRecommendation(profiles, keys, permission, true,
            { current -> DirectMockService(keys, current) }, localProfiles = localProfiles)
    }
}
