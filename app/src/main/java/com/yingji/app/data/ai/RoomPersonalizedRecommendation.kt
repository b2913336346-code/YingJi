package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/** Actual Room profile + already verified TMDB facts + model directions. No history/cache/database writes. */
class RoomPersonalizedRecommendation internal constructor(
    private val profiles: ProfileRepository,
    private val keys: AiKeyConfigurationRepository,
    private val permission: () -> TmdbSourcePermission,
    override val usesMockKey: Boolean,
    private val serviceFactory: (() -> Boolean) -> AiWorkerService,
    private val cancelPending: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val localProfiles: LocalProfileManagementRepository? = null,
) : PersonalizedRecommendation {
    private val admission = Mutex()
    private fun configured() = keys.state.value.let { it.storage == AiKeyStorageStatus.CONFIGURED && !it.verifying &&
        it.kind == if (usesMockKey) AiKeyVerificationKind.MOCK else AiKeyVerificationKind.LIVE }
    private val snapshots = localProfiles?.observe()?.map { snapshot ->
        recommendationProfile(snapshot.profile, snapshot.features.associate { it.id to it.label })
    } ?: profiles.observeProfile().map { recommendationProfile(it) }
    private suspend fun currentProfile() = snapshots.first()
    override val changes = combine(snapshots.distinctUntilChanged(),
        keys.state.map { it.revision to (it.storage == AiKeyStorageStatus.CONFIGURED && !it.verifying &&
            it.kind == if (usesMockKey) AiKeyVerificationKind.MOCK else AiKeyVerificationKind.LIVE) }.distinctUntilChanged()) { profile, key ->
        PersonalizedRevision(profile, key.first, key.second)
    }.distinctUntilChanged()
    private fun validated(catalog: CandidateCatalog): CandidateCatalog {
        val grant = permission()
        if (!grant.permits(clock())) throw TmdbProblem(TmdbProblemCode.LICENSE_REQUIRED)
        val data = validatedTmdbRankingCatalog(catalog, clock())
        val license = data.sources.single().license
        if (license.state != grant.state || license.evidence != grant.evidence || license.validUntil != grant.validUntil ||
            license.displayAllowed != grant.displayAllowed) throw TmdbProblem(TmdbProblemCode.LICENSE_REQUIRED)
        return data
    }
    override suspend fun prepare(catalog: CandidateCatalog): PersonalizedConsent {
        val source = validated(catalog).sources.single()
        if (!configured()) throw WorkerProblem("KEY_REQUIRED")
        val key = keys.state.value.revision
        val profile = currentProfile()
        val summary = try { WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU),
            AiIntegrationContext(profile, true, credentialRevision = key)).profileSummary
        } catch (_: IllegalArgumentException) { throw WorkerProblem("INVALID_INPUT") }
        if (!configured() || key != keys.state.value.revision) throw WorkerProblem("STALE_KEY")
        return PersonalizedConsent(profile.token, key, source, summary)
    }
    override suspend fun recommend(catalog: CandidateCatalog, consent: PersonalizedConsent, sessionCurrent: () -> Boolean): AiIntegratedResult {
        if (!admission.tryLock()) throw WorkerProblem("REQUEST_IN_PROGRESS")
        val active = AtomicBoolean(true)
        try {
            val data = validated(catalog)
            if (data.sources.single() != consent.source) throw TmdbProblem(TmdbProblemCode.LICENSE_REQUIRED)
            val currentConsent = prepare(data)
            if (currentConsent != consent || !sessionCurrent()) throw WorkerProblem("STALE_PROFILE")
            val profile = currentProfile()
            if (profile.token != consent.profileToken || WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU),
                AiIntegrationContext(profile, true, credentialRevision = consent.credentialRevision)).profileSummary != consent.summary)
                throw WorkerProblem("STALE_PROFILE")
            fun current() = active.get() && sessionCurrent() && configured() && keys.state.value.revision == consent.credentialRevision &&
                runCatching { validated(data).sources.single() == consent.source }.getOrDefault(false)
            val source = object : AiIntegrationProfileSource {
                override suspend fun snapshot() = AiIntegrationContext(profile, current(), credentialRevision = consent.credentialRevision)
                override suspend fun isCurrent(context: AiIntegrationContext) = current() && currentProfile() == profile
            }
            return coroutineScope {
                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                    snapshots.collect { next ->
                        if (next != profile) { active.set(false); cancelPending() }
                    }
                }
                try {
                    val service = serviceFactory(::current)
                    val engine = IntegratedRecommendationUseCase(service, source,
                        object : CandidateCatalogRepository { override suspend fun load() = validated(data) }, clock,
                        mockService = usesMockKey, serviceOwnsRetry = true, uncertainRequestReplay = false, refreshQuotaBeforeTask = true)
                    val result = engine(AiHintInput(AiMode.FOR_YOU))
                    if (!current() || currentProfile() != profile) throw WorkerProblem("STALE_PROFILE")
                    result
                } finally { observer.cancelAndJoin() }
            }
        } finally { active.set(false); cancelPending(); admission.unlock() }
    }
}
