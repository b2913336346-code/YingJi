package com.yingji.app.domain.ai

import kotlinx.coroutines.sync.Mutex

/** Ephemeral TMDB ranking with local protocol fixtures only: no HTTP/key/history/database dependencies. */
data class TmdbMockRecommendationResult(val items: List<RankedCandidate>, val token: ProfileToken,
    val mockHintsApplied: Boolean, val problem: String? = null)
class TmdbMockRecommendationUseCase(
    private val demoProfile: suspend () -> ProfileSnapshot,
    private val localHints: (WorkerHintRequest) -> WorkerHints = { request -> WorkerHints(
        (request.profileSummary.explicit.filter { it.direction == PreferenceDirection.LIKE }.map { it.topic } +
            request.profileSummary.inferred.filter { it.direction == PreferenceDirection.LIKE }.map { it.topic }).distinct(), CandidateHardConditions()) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()
    private fun validated(value: CandidateCatalog) = validatedTmdbRankingCatalog(value, clock())
    suspend operator fun invoke(catalog: CandidateCatalog, input: AiHintInput = AiHintInput(AiMode.FOR_YOU),
        sessionCurrent: () -> Boolean = { true }): TmdbMockRecommendationResult {
        if (!running.tryLock()) throw WorkerProblem("REQUEST_IN_PROGRESS")
        try {
            require(!input.allowMock) { "Mock candidate facts are not accepted by TMDB ranking" }
            require(input.mode != AiMode.QUERY) { "Natural-language interpretation is outside this local ranking stage" }
            if (!sessionCurrent()) throw WorkerProblem("STALE_PROFILE")
            val snapshot = validated(catalog)
            val profiles = object : AiIntegrationProfileSource {
                override suspend fun snapshot(): AiIntegrationContext {
                    if (!sessionCurrent()) throw WorkerProblem("STALE_PROFILE")
                    // This protocol flag stays inside this local fixture engine; it grants no external consent.
                    return AiIntegrationContext(demoProfile(), externalProcessingAllowed = true)
                }
                override suspend fun isCurrent(context: AiIntegrationContext): Boolean = sessionCurrent() &&
                    runCatching { validated(snapshot) }.isSuccess && demoProfile() == context.profile
            }
            val service = object : AiWorkerService {
                override suspend fun quota() = AiQuotaSnapshot.mock(MockQuotaScenario.AVAILABLE)
                override suspend fun hints(request: WorkerHintRequest) = WorkerTaskResponse(request.requestId, request.token,
                    WorkerTaskStatus.SUCCESS, localHints(request), quota(), WorkerUsage(true, 0, 0, null, null, null), null)
            }
            // A new engine per action plus cacheAllowed=false prevents retaining provider metadata between actions.
            val engine = IntegratedRecommendationUseCase(service, profiles,
                object : CandidateCatalogRepository { override suspend fun load() = snapshot }, clock,
                mockService = true, serviceOwnsRetry = true, uncertainRequestReplay = false)
            val result = engine(input)
            if (!sessionCurrent()) throw WorkerProblem("STALE_PROFILE")
            return TmdbMockRecommendationResult(result.items, result.token,
                result.origin == AiRecommendationOrigin.WORKER && result.problem == null, result.problem)
        } finally { running.unlock() }
    }
}
