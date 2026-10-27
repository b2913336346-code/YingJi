package com.yingji.app.domain.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DirectRecommendationPolicyTest {
    private class Service : AiWorkerService {
        val ids = mutableListOf<String>(); var reads = 0; var available = true; var code: String? = null
        override suspend fun quota(): AiQuotaSnapshot { reads++; return AiQuotaSnapshot.mock(if (available) MockQuotaScenario.AVAILABLE else MockQuotaScenario.EXHAUSTED) }
        override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse {
            ids.add(request.requestId); code?.let { throw WorkerProblem(it, true) }
            return WorkerTaskResponse(request.requestId, request.token, WorkerTaskStatus.SUCCESS, WorkerHints(emptyList(), CandidateHardConditions()),
                AiQuotaSnapshot.mock(), WorkerUsage(true, 1, 0, 1, 1, 2), null)
        }
    }
    private class Fixture {
        val service = Service()
        var context = AiIntegrationContext(ProfileSnapshot(), true, 1)
        val catalog = object : CandidateCatalogRepository {
            override suspend fun load() = CandidateCatalog(listOf(CandidateSource("demo", CandidateSourceKind.MOCK, "test fixture",
                CandidateLicense(CandidateLicenseState.ACTIVE, "fixture permission", true, true, true, 10000, 1000))),
                listOf(CatalogCandidate(CandidateIdentity("demo", "1"), "Demo", setOf("科幻"), 2020, 90, verifiedAt = 1000)))
        }
        val engine = IntegratedRecommendationUseCase(service, object : AiIntegrationProfileSource { override suspend fun snapshot() = context }, catalog,
            clock = { 1000 }, mockService = true, serviceOwnsRetry = true, uncertainRequestReplay = false, refreshQuotaBeforeTask = true)
        val input = AiHintInput(AiMode.FOR_YOU, allowMock = true)
    }
    @Test fun serviceOwnsTheOnlyRetryAndManualSubmissionUsesANewTaskId() = runBlocking {
        val f = Fixture(); f.service.code = "TRANSPORT_TIMEOUT"
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin); assertEquals(1, f.service.ids.size)
        f.engine(f.input); assertEquals(2, f.service.ids.size); assertEquals(2, f.service.ids.distinct().size)
    }
    @Test fun directCacheIsFreeButEveryUncachedTaskReadsCurrentLocalQuota() = runBlocking {
        val f = Fixture(); f.engine(f.input); assertEquals(1, f.service.reads)
        f.service.available = false
        assertEquals(AiRecommendationOrigin.CACHE, f.engine(f.input).origin); assertEquals(1, f.service.ids.size); assertEquals(1, f.service.reads)
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input.copy(mode = AiMode.TONIGHT)).origin); assertEquals(2, f.service.reads); assertEquals(1, f.service.ids.size)
        f.service.available = true
        assertEquals(AiRecommendationOrigin.WORKER, f.engine(f.input.copy(mode = AiMode.TONIGHT)).origin); assertEquals(3, f.service.reads); assertEquals(2, f.service.ids.size)
    }
    @Test fun changedCredentialsAndConsentNeverReuseAnOldCache() = runBlocking {
        val f = Fixture(); f.context = f.context.copy(credentialRevision = "one"); f.engine(f.input)
        f.context = f.context.copy(credentialRevision = "two"); f.engine(f.input); assertEquals(2, f.service.ids.size)
        f.context = f.context.copy(externalProcessingAllowed = false, consentVersion = 2)
        assertEquals(AiRecommendationOrigin.LOCAL, f.engine(f.input).origin); assertEquals(2, f.service.ids.size)
    }
    @Test fun authorizationErrorsDoNotRetryAndQueryFailureDoesNotInventResults() = runBlocking {
        for (code in listOf("UPSTREAM_AUTH", "UPSTREAM_FORBIDDEN", "UPSTREAM_BILLING", "UPSTREAM_RATE_LIMIT", "MODEL_FORMAT")) {
            val f = Fixture(); f.service.code = code
            val result = f.engine(f.input.copy(mode = AiMode.QUERY, query = "科幻"))
            assertTrue(result.items.isEmpty()); assertEquals(code, result.problem); assertEquals(1, f.service.ids.size)
        }
    }
}
