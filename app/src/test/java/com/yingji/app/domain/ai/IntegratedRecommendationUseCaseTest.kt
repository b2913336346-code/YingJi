package com.yingji.app.domain.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IntegratedRecommendationUseCaseTest {
    private class Profiles(var value: AiIntegrationContext = AiIntegrationContext(ProfileSnapshot(token = ProfileToken(1, 1)), true, 1)) : AiIntegrationProfileSource {
        override suspend fun snapshot() = value
    }
    private class Catalog : CandidateCatalogRepository {
        var failure = false
        var value = CandidateCatalog(listOf(CandidateSource("demo", CandidateSourceKind.MOCK, "authored test fixture",
            CandidateLicense(CandidateLicenseState.ACTIVE, "test-only cache permission", true, true, true, 10000, 1000))),
            listOf("科幻", "喜剧", "纪录片").mapIndexed { index, topic -> CatalogCandidate(CandidateIdentity("demo", index.toString()), "Mock " + index, setOf(topic), 2020, 90, verifiedAt = 1000) })
        override suspend fun load(): CandidateCatalog { if (failure) throw IllegalStateException(); return value }
    }
    private class Service : AiWorkerService {
        val calls = mutableListOf<WorkerHintRequest>(); var quotaReads = 0
        var quotaValue = AiQuotaSnapshot(AiQuotaStatus.AVAILABLE, true, 30, 0, 30)
        var quotaFailure = false
        var answer: suspend (WorkerHintRequest) -> WorkerTaskResponse = { success(it) }
        override suspend fun quota(): AiQuotaSnapshot { quotaReads++; if (quotaFailure) throw WorkerProblem("STORAGE_UNAVAILABLE"); return quotaValue }
        override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse { calls.add(request); return answer(request) }
        companion object {
            fun success(request: WorkerHintRequest) = WorkerTaskResponse(request.requestId, request.token, WorkerTaskStatus.SUCCESS,
                WorkerHints(listOf("科幻"), CandidateHardConditions()), AiQuotaSnapshot(AiQuotaStatus.AVAILABLE, true, 30, 1, 29), WorkerUsage(true, 1, 0, 100, 40, 140), null)
        }
    }
    private class Fixture {
        val profiles = Profiles(); val catalog = Catalog(); val service = Service(); var now = 1000L
        val engine = IntegratedRecommendationUseCase(service, profiles, catalog, { now }, mockService = true)
        val input = AiHintInput(AiMode.FOR_YOU, allowMock = true)
    }
    private fun problem(block: () -> Unit): String = try { block(); fail("Expected rejection"); "" } catch (e: WorkerProblem) { e.code }

    @Test fun independentConsentIsRequiredAndOnlyEnabledUnsuppressedTopicDirectionsAreSent() {
        val feature = ProfileFeature.topic("科幻")
        val profile = ProfileSnapshot(token = ProfileToken(2, 3), explicitPreferences = listOf(ExplicitPreference(ProfileFeature.category(7, "private category"), PreferenceDirection.LIKE), ExplicitPreference(feature, PreferenceDirection.DISLIKE)),
            inferredPreferences = listOf(InferredPreference(feature, ProfileSource.RATING, 10, 10), InferredPreference(ProfileFeature.topic("喜剧"), ProfileSource.FEEDBACK, 1, 1), InferredPreference(ProfileFeature.topic("纪录片"), ProfileSource.BEHAVIOR, 9, completedWatches = 9)))
        assertEquals("CONSENT_REQUIRED", problem { WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU), AiIntegrationContext(profile)) })
        val request = WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU, "ignored"), AiIntegrationContext(profile, true, 1))
        assertEquals("", request.userQuery); assertEquals(listOf(WorkerExplicit("科幻", PreferenceDirection.DISLIKE)), request.profileSummary.explicit)
        assertEquals(listOf(WorkerInferred("喜剧", PreferenceDirection.LIKE, ProfileSource.FEEDBACK)), request.profileSummary.inferred)
        assertFalse(request.toString().contains("private category")); assertFalse(request.toString().contains("category:7")); assertEquals(ProfileToken(2, 3), request.token)
    }
    @Test fun unicodeLimitAndSafeVersionBoundsAreEnforced() {
        val context = Profiles().value
        assertEquals(1000, WorkerRequestBuilder.build(AiHintInput(AiMode.QUERY, "😀".repeat(500)), context).userQuery.length)
        assertTrue(runCatching { WorkerRequestBuilder.build(AiHintInput(AiMode.QUERY, "😀".repeat(501)), context) }.isFailure)
        assertTrue(runCatching { WorkerRequestBuilder.build(AiHintInput(AiMode.QUERY, " "), context) }.isFailure)
        assertTrue(runCatching { WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU), context.copy(profile = context.profile.copy(token = ProfileToken(-1, 0)))) }.isFailure)
    }
    @Test fun localRecommendationWithoutTransferConsentDoesNotReadQuotaOrCallWorker() = runBlocking {
        val f = Fixture(); f.profiles.value = f.profiles.value.copy(externalProcessingAllowed = false)
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin)
        assertEquals(0, f.service.quotaReads); assertTrue(f.service.calls.isEmpty())
    }
    @Test fun workerHintsCannotOverrideExplicitExclusionsOrRelaxHardBounds() = runBlocking {
        val f = Fixture(); f.profiles.value = f.profiles.value.copy(profile = f.profiles.value.profile.copy(explicitPreferences = listOf(ExplicitPreference(ProfileFeature.topic("科幻"), PreferenceDirection.DISLIKE))))
        f.catalog.value = f.catalog.value.copy(candidates = f.catalog.value.candidates + CatalogCandidate(CandidateIdentity("demo", "old"), "Mock old", setOf("喜剧"), 1990, 80, verifiedAt = 1000))
        f.service.answer = { Service.success(it).copy(hints = WorkerHints(listOf("科幻"), CandidateHardConditions(minYear = 1900, maxRuntimeMinutes = 999))) }
        val result = f.engine(f.input.copy(conditions = CandidateHardConditions(minYear = 2000, maxRuntimeMinutes = 100)))
        assertEquals(AiRecommendationOrigin.WORKER, result.origin); assertEquals(2, result.items.size)
        assertTrue(result.items.all { "科幻" !in it.candidate.topics && it.candidate.year!! >= 2000 && it.candidate.runtimeMinutes!! <= 100 })
        assertEquals(1, result.quota.used); assertEquals(29, result.quota.remaining)
    }
    @Test fun permittedCacheHitDoesNotReadQuotaOrCallModelAndRevocationBlocksIt() = runBlocking {
        val f = Fixture(); f.engine(f.input)
        assertEquals(AiRecommendationOrigin.CACHE, f.engine(f.input).origin); assertEquals(1, f.service.calls.size); assertEquals(1, f.service.quotaReads)
        f.catalog.value = f.catalog.value.copy(sources = f.catalog.value.sources.map { it.copy(license = it.license.copy(state = CandidateLicenseState.REVOKED)) })
        assertEquals(AiRecommendationOrigin.EMPTY, f.engine(f.input).origin); assertEquals(1, f.service.calls.size)
    }
    @Test fun exactCacheAgeBoundaryExpiresAndUsesFreshLocalFactsOnFailure() = runBlocking {
        val f = Fixture(); f.engine(f.input); f.now = 2000
        f.service.answer = { throw WorkerProblem("OFFLINE") }
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin); assertEquals(2, f.service.calls.size)
    }
    @Test fun allowedCacheSurvivesSourceFailureButNeverItsExpiry() = runBlocking {
        val f = Fixture(); f.engine(f.input); f.catalog.failure = true
        assertEquals(AiRecommendationOrigin.CACHE, f.engine(f.input).origin); assertEquals(1, f.service.calls.size)
        f.now = 2000
        assertEquals(AiRecommendationOrigin.EMPTY, f.engine(f.input).origin); assertEquals(1, f.service.calls.size)
    }
    @Test fun cacheDoesNotBypassNewConditionsOrAProfileVersion() = runBlocking {
        val f = Fixture(); f.engine(f.input)
        f.profiles.value = f.profiles.value.copy(profile = f.profiles.value.profile.copy(token = ProfileToken(2, 1), explicitPreferences = listOf(ExplicitPreference(ProfileFeature.topic("科幻"), PreferenceDirection.DISLIKE))))
        val result = f.engine(f.input.copy(conditions = CandidateHardConditions(requiredTopics = setOf("喜剧"))))
        assertEquals(AiRecommendationOrigin.WORKER, result.origin); assertEquals(listOf("Mock 1"), result.items.map { it.candidate.title }); assertEquals(2, f.service.calls.size)
    }
    @Test fun exhaustedQuotaDoesNotResetWithClientTimeOrNewClientInstance() = runBlocking {
        val f = Fixture(); f.catalog.value = f.catalog.value.copy(sources = f.catalog.value.sources.map { it.copy(license = it.license.copy(validUntil = Long.MAX_VALUE)) }); f.service.quotaValue = AiQuotaSnapshot(AiQuotaStatus.EXHAUSTED, true, 30, 30, 0)
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin); f.now += 86400000
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin)
        val restarted = IntegratedRecommendationUseCase(f.service, f.profiles, f.catalog, { f.now }, mockService = true)
        assertEquals(AiRecommendationOrigin.LOCAL, restarted(f.input).origin); assertTrue(f.service.calls.isEmpty()); assertEquals(2, f.service.quotaReads)
    }
    @Test fun quotaStorageFailureFailsClosedAndLocalCandidatesRemainAvailable() = runBlocking {
        val f = Fixture(); f.service.quotaFailure = true
        val result = f.engine(f.input); assertEquals(AiRecommendationOrigin.LOCAL, result.origin); assertEquals(AiQuotaStatus.UNAVAILABLE, result.quota.status); assertTrue(f.service.calls.isEmpty())
    }
    @Test fun transportTimeoutRetriesOnceWithSameIdAndExplicitRetryReplaysTheUncertainTask() = runBlocking {
        val f = Fixture(); f.service.answer = { throw WorkerProblem("TRANSPORT_TIMEOUT", true) }
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin); assertEquals(2, f.service.calls.size); assertEquals(1, f.service.calls.map { it.requestId }.distinct().size)
        f.service.answer = { Service.success(it) }; assertEquals(AiRecommendationOrigin.WORKER, f.engine(f.input).origin)
        assertEquals(3, f.service.calls.size); assertEquals(1, f.service.calls.map { it.requestId }.distinct().size)
    }
    @Test fun serverTimeoutIsNotAutomaticallyRetriedAndPendingReplayDoesNotCreateNewTask() = runBlocking {
        val f = Fixture(); f.service.answer = { Service.success(it).copy(status = WorkerTaskStatus.ERROR, hints = null, error = WorkerProblem("UPSTREAM_TIMEOUT", true)) }
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin); assertEquals(1, f.service.calls.size)
        val g = Fixture(); g.service.answer = { Service.success(it).copy(status = WorkerTaskStatus.PENDING, hints = null, quota = AiQuotaSnapshot(AiQuotaStatus.EXHAUSTED, true, 30, 29, 0)) }
        assertEquals(WorkerTaskStatus.PENDING, g.engine(g.input).taskStatus); assertEquals(1, g.service.calls.size)
        g.service.answer = { Service.success(it) }; g.engine(g.input)
        assertEquals(2, g.service.calls.size); assertEquals(1, g.service.calls.map { it.requestId }.distinct().size)
    }
    @Test fun lateResponseAfterProfileOrConsentChangeIsRejectedAndNotCached() = runBlocking {
        for (consent in listOf(false, true)) {
            val f = Fixture(); val started = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
            f.service.answer = { started.complete(Unit); released.await(); Service.success(it) }
            val task = async { runCatching { f.engine(f.input) } }; started.await()
            f.profiles.value = if (consent) f.profiles.value.copy(externalProcessingAllowed = false, consentVersion = 2)
                else f.profiles.value.copy(profile = f.profiles.value.profile.copy(token = ProfileToken(2, 1)))
            released.complete(Unit)
            assertEquals("STALE_PROFILE", (task.await().exceptionOrNull() as WorkerProblem).code)
        }
    }
    @Test fun versionChangeBeforeRetryPreventsTheSecondHttpAttempt() = runBlocking {
        val f = Fixture(); f.service.answer = { f.profiles.value = f.profiles.value.copy(consentVersion = 2); throw WorkerProblem("TRANSPORT_TIMEOUT", true) }
        assertEquals("STALE_PROFILE", runCatching { f.engine(f.input) }.exceptionOrNull().let { (it as WorkerProblem).code }); assertEquals(1, f.service.calls.size)
    }
    @Test fun offlineFreeTextDoesNotBecomeUnrelatedLocalRecommendations() = runBlocking {
        val f = Fixture(); f.service.answer = { throw WorkerProblem("OFFLINE") }
        val result = f.engine(f.input.copy(mode = AiMode.QUERY, query = "only a specific unavailable work"))
        assertTrue(result.items.isEmpty()); assertEquals(AiRecommendationOrigin.EMPTY, result.origin); assertEquals("OFFLINE", result.problem)
    }
    @Test fun missingOrUnverifiedCandidatesNeverTriggerAnUnnecessaryPaidTask() = runBlocking {
        val f = Fixture(); f.catalog.value = f.catalog.value.copy(candidates = f.catalog.value.candidates.map { it.copy(title = null) })
        assertTrue(f.engine(f.input).items.isEmpty()); assertTrue(f.service.calls.isEmpty()); assertEquals(0, f.service.quotaReads)
    }

    @Test fun credentialReplacementDoesNotReuseOldPendingIdOrCredentialCache() = runBlocking {
        val f = Fixture(); f.profiles.value = f.profiles.value.copy(credentialRevision = "old")
        f.engine(f.input); assertEquals(AiRecommendationOrigin.CACHE, f.engine(f.input).origin)
        f.profiles.value = f.profiles.value.copy(credentialRevision = "new")
        assertEquals(AiRecommendationOrigin.WORKER, f.engine(f.input).origin)
        assertEquals(2, f.service.calls.size); assertNotEquals(f.service.calls[0].requestId, f.service.calls[1].requestId)
        val g = Fixture(); g.profiles.value = g.profiles.value.copy(credentialRevision = "old")
        g.service.answer = { throw WorkerProblem("TRANSPORT_TIMEOUT", true) }; g.engine(g.input)
        g.profiles.value = g.profiles.value.copy(credentialRevision = "new"); g.service.answer = { Service.success(it) }; g.engine(g.input)
        assertEquals(3, g.service.calls.size); assertEquals(g.service.calls[0].requestId, g.service.calls[1].requestId)
        assertNotEquals(g.service.calls[0].requestId, g.service.calls[2].requestId)
    }
    @Test fun keyRevisionChangeWhileRequestIsHeldRejectsLateResult() = runBlocking {
        val f = Fixture(); f.profiles.value = f.profiles.value.copy(credentialRevision = "old")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.service.answer = { started.complete(Unit); release.await(); Service.success(it) }
        val task = async { runCatching { f.engine(f.input) } }; started.await()
        f.profiles.value = f.profiles.value.copy(credentialRevision = "deleted", externalProcessingAllowed = false)
        release.complete(Unit); assertEquals("STALE_PROFILE", (task.await().exceptionOrNull() as WorkerProblem).code)
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin); assertEquals(1, f.service.calls.size)
    }
}
