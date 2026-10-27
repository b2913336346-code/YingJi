package com.yingji.app.domain.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TmdbMockRecommendationUseCaseTest {
    private class Fixture {
        var now = 1000L
        var current = true
        var profile = ProfileSnapshot(token = ProfileToken(1, 1))
        val requests = mutableListOf<WorkerHintRequest>()
        var profileRead: suspend () -> ProfileSnapshot = { profile }
        var hints: (WorkerHintRequest) -> WorkerHints = { WorkerHints(emptyList(), CandidateHardConditions()) }
        val source = CandidateSource("tmdb", CandidateSourceKind.VERIFIED_CATALOG, "App-authored TMDB protocol fixture",
            CandidateLicense(CandidateLicenseState.ACTIVE, "Synthetic fixture permission", true, true, true, 10000, 1000))
        val rows = listOf(
            CatalogCandidate(CandidateIdentity("tmdb", "movie:1"), "Synthetic A", setOf("科幻"), 2020, 90, verifiedAt = 1000),
            CatalogCandidate(CandidateIdentity("tmdb", "movie:2"), "Synthetic B", setOf("喜剧"), 2023, 95, verifiedAt = 1000),
            CatalogCandidate(CandidateIdentity("tmdb", "tv:1"), "Synthetic C", setOf("纪录片"), 2024, null, verifiedAt = 1000),
            CatalogCandidate(CandidateIdentity("tmdb", "movie:3"), "Synthetic A", setOf("科幻"), 2020, 90, verifiedAt = 1000),
            CatalogCandidate(CandidateIdentity("tmdb", "movie:4"), "Synthetic Unknown", setOf("剧情"), null, 45, verifiedAt = 1000),
            CatalogCandidate(CandidateIdentity("tmdb", "movie:5"), "Synthetic Old", setOf("喜剧"), 1990, 180, verifiedAt = 1000))
        val catalog = CandidateCatalog(listOf(source), rows)
        val rank = TmdbMockRecommendationUseCase({ profileRead() }, { request -> requests.add(request); hints(request) }, { now })
        suspend fun run(data: CandidateCatalog = catalog, input: AiHintInput = AiHintInput(AiMode.FOR_YOU)) = rank(data, input) { current }
    }
    @Test fun reusesHardFilteringAndNeverLetsHintsRelaxExclusionsYearsOrRuntime() = runBlocking {
        val f = Fixture(); f.profile = f.profile.copy(explicitPreferences = listOf(ExplicitPreference(ProfileFeature.topic("科幻"), PreferenceDirection.DISLIKE)))
        f.hints = { WorkerHints(listOf("科幻", "Invented title"), CandidateHardConditions(minYear = 1900, maxRuntimeMinutes = 999)) }
        val result = f.run(input = AiHintInput(AiMode.FOR_YOU, conditions = CandidateHardConditions(minYear = 2000, maxRuntimeMinutes = 100)))
        assertTrue(result.mockHintsApplied); assertEquals(listOf("movie:2"), result.items.map { it.candidate.identity.externalId })
        assertEquals(1, f.requests.size)
    }
    @Test fun explicitPreferencesWinAndExistingDedupAndDiversityAreReused() = runBlocking {
        val f = Fixture(); f.profile = f.profile.copy(explicitPreferences = listOf(ExplicitPreference(ProfileFeature.topic("喜剧"), PreferenceDirection.LIKE)),
            inferredPreferences = listOf(InferredPreference(ProfileFeature.topic("科幻"), ProfileSource.FEEDBACK, 10, 10)))
        val result = f.run(); assertEquals(3, result.items.size); assertEquals("喜剧", result.items.first().candidate.topics.single())
        assertEquals(result.items.size, result.items.map { it.candidate.title to it.candidate.year }.distinct().size)
        assertTrue(result.items.all { it.candidate in f.rows })
    }
    @Test fun onlyEnabledTopicDirectionsEnterMockRequestAndNoMovieFactsOrRoomIdsAreSent() = runBlocking {
        val f = Fixture(); f.profile = f.profile.copy(settings = ProfileSourceSettings(false, true, false),
            explicitPreferences = listOf(ExplicitPreference(ProfileFeature.category(7, "PRIVATE CATEGORY"), PreferenceDirection.LIKE),
                ExplicitPreference(ProfileFeature.tag(7, "PRIVATE TAG"), PreferenceDirection.LIKE), ExplicitPreference(ProfileFeature.topic("科幻"), PreferenceDirection.LIKE)),
            inferredPreferences = listOf(InferredPreference(ProfileFeature.topic("喜剧"), ProfileSource.RATING, 10, 10),
                InferredPreference(ProfileFeature.topic("剧情"), ProfileSource.FEEDBACK, 10, 10), InferredPreference(ProfileFeature.topic("纪录片"), ProfileSource.BEHAVIOR, 10, completedWatches = 10)))
        f.run(); val request = f.requests.single()
        assertEquals(listOf(WorkerExplicit("科幻", PreferenceDirection.LIKE)), request.profileSummary.explicit)
        assertTrue(request.profileSummary.inferred.isEmpty()); assertEquals("", request.userQuery)
        assertFalse(request.toString().contains("PRIVATE")); assertFalse(request.toString().contains("category:7")); assertFalse(request.toString().contains("tag:7"))
        assertTrue(f.rows.none { request.toString().contains(it.title!!) })
    }
    @Test fun noCandidateCacheOrPendingTaskSurvivesAnActionEvenWhenSourceAllowsCaching() = runBlocking {
        val f = Fixture(); val first = f.run(); val second = f.run(); f.now++
        val third = f.run(); assertTrue(listOf(first, second, third).all { it.mockHintsApplied })
        assertEquals(3, f.requests.size); assertEquals(3, f.requests.map { it.requestId }.distinct().size)
        assertTrue(third.items.all { it.candidate.cache.state == CandidateCacheState.FRESH }); assertTrue(f.source.license.cacheAllowed)
    }
    @Test fun malformedDuplicateMockCachedOversizedAndConflictingCandidatesAreRejectedBeforeHints() = runBlocking {
        val f = Fixture()
        val invalid = listOf(f.catalog.copy(candidates = f.rows + f.rows.first()),
            f.catalog.copy(candidates = listOf(f.rows.first().copy(title = null))),
            f.catalog.copy(candidates = listOf(f.rows.first().copy(identity = CandidateIdentity("tmdb", "movie:0")))),
            f.catalog.copy(candidates = listOf(f.rows.first().copy(identity = CandidateIdentity("tmdb", "tv:2147483648")))),
            f.catalog.copy(candidates = listOf(f.rows.first().copy(cache = CandidateCache(CandidateCacheState.CACHED, 1000, 2000)))),
            f.catalog.copy(sources = listOf(f.source.copy(kind = CandidateSourceKind.MOCK))),
            f.catalog.copy(sources = listOf(f.source, f.source)),
            f.catalog.copy(candidates = listOf(f.rows.first(), f.rows.first().copy(title = "Conflicting"))))
        for (data in invalid) assertTrue(runCatching { f.run(data) }.exceptionOrNull() is TmdbProblem)
        assertTrue(f.requests.isEmpty())
    }
    @Test fun unknownRevokedExpiredAndDisplayDisallowedSourcesNeverReachHints() = runBlocking {
        val f = Fixture()
        for (license in listOf(f.source.license.copy(state = CandidateLicenseState.UNKNOWN), f.source.license.copy(state = CandidateLicenseState.REVOKED),
            f.source.license.copy(validUntil = f.now), f.source.license.copy(displayAllowed = false), f.source.license.copy(evidence = "")))
            assertEquals(TmdbProblemCode.LICENSE_REQUIRED, (runCatching { f.run(f.catalog.copy(sources = listOf(f.source.copy(license = license)))) }.exceptionOrNull() as TmdbProblem).code)
        assertTrue(f.requests.isEmpty())
    }
    @Test fun emptyOrEntirelyFilteredCatalogDoesNotGenerateHintsOrPadFacts() = runBlocking {
        val f = Fixture(); val empty = f.run(f.catalog.copy(candidates = emptyList()))
        val filtered = f.run(input = AiHintInput(AiMode.FOR_YOU, conditions = CandidateHardConditions(requiredTopics = setOf("Missing"))))
        for (result in listOf(empty, filtered)) { assertTrue(result.items.isEmpty()); assertFalse(result.mockHintsApplied); assertEquals("NO_RELIABLE_CANDIDATES", result.problem) }
        assertTrue(f.requests.isEmpty())
    }
    @Test fun profileSourceSessionAndLicenseChangesDiscardLateResultAndPermitFreshRetry() = runBlocking {
        for (kind in 0..3) {
            val f = Fixture(); f.hints = {
                when (kind) { 0 -> f.profile = f.profile.copy(token = ProfileToken(2, 1)); 1 -> f.profile = f.profile.copy(settings = ProfileSourceSettings(feedbackEnabled = false), token = ProfileToken(1, 2));
                    2 -> f.current = false; else -> f.now = 10000 }
                WorkerHints(emptyList(), CandidateHardConditions())
            }
            assertEquals("STALE_PROFILE", (runCatching { f.run() }.exceptionOrNull() as WorkerProblem).code)
            f.current = true; f.now = 1000; f.hints = { WorkerHints(emptyList(), CandidateHardConditions()) }
            assertTrue(f.run().mockHintsApplied); assertEquals(2, f.requests.size)
        }
    }
    @Test fun rapidSubmissionIsRejectedAndDoesNotQueueASecondTask() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var first = true
        f.profileRead = { if (first) { first = false; entered.complete(Unit); release.await() }; f.profile }
        val task = async { f.run() }; entered.await()
        assertEquals("REQUEST_IN_PROGRESS", (runCatching { f.run() }.exceptionOrNull() as WorkerProblem).code)
        release.complete(Unit); assertTrue(task.await().mockHintsApplied); assertEquals(1, f.requests.size)
        assertTrue(f.run().mockHintsApplied); assertEquals(2, f.requests.size)
    }
    @Test fun cancellationDoesNotBecomeFallbackAndReleasesAdmissionLock() = runBlocking {
        val f = Fixture(); f.hints = { throw CancellationException("synthetic cancellation") }
        assertTrue(runCatching { f.run() }.exceptionOrNull() is CancellationException)
        f.hints = { WorkerHints(emptyList(), CandidateHardConditions()) }; assertTrue(f.run().mockHintsApplied)
    }
    @Test fun hintFailureUsesOnlyVerifiedLocalFactsAndFreshRetryDoesNotReuseFailure() = runBlocking {
        val f = Fixture(); f.hints = { throw WorkerProblem("OFFLINE") }
        val local = f.run(); assertFalse(local.mockHintsApplied); assertEquals("OFFLINE", local.problem)
        assertTrue(local.items.all { it.candidate in f.rows }); assertTrue(local.items.size <= 3)
        f.hints = { WorkerHints(emptyList(), CandidateHardConditions()) }; assertTrue(f.run().mockHintsApplied); assertEquals(2, f.requests.size)
    }
    @Test fun candidateMockOptInAndUnsupportedFreeTextNeverStartHintProcessing() = runBlocking {
        val f = Fixture()
        assertTrue(runCatching { f.run(input = AiHintInput(AiMode.FOR_YOU, allowMock = true)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { f.run(input = AiHintInput(AiMode.QUERY, "not interpreted")) }.exceptionOrNull() is IllegalArgumentException)
        f.current = false; assertEquals("STALE_PROFILE", (runCatching { f.run() }.exceptionOrNull() as WorkerProblem).code)
        assertTrue(f.requests.isEmpty())
    }
    @Test fun previewReadCatalogPreservesEvidenceAndDefensivelyCopiesTopicsWithoutChangingReadApi() = runBlocking {
        val f = Fixture(); val mutableTopics = mutableSetOf("科幻"); var reads = 0
        val reader = TmdbCandidatePreview(object : CandidateCatalogRepository { override suspend fun load(): CandidateCatalog { reads++; return f.catalog.copy(candidates = listOf(f.rows.first().copy(topics = mutableTopics))) } }, { f.now })
        val snapshot = reader.readCatalog(); mutableTopics.add("喜剧")
        assertEquals(listOf(f.source), snapshot.sources); assertEquals(setOf("科幻"), snapshot.candidates.single().topics)
        assertEquals(setOf("科幻", "喜剧"), reader.read().single().topics); assertEquals(2, reads)
    }
}
