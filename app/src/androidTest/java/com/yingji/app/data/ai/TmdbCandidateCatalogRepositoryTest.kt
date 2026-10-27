package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TmdbCandidateCatalogRepositoryTest {
    private val grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "App-authored protocol fixture license", true, true, true)
    private class Transport : TmdbCatalogTransport {
        override var isMock = true
        var generation = "one"; var reads = 0; var after: (() -> Unit)? = null
        var bodies = mutableMapOf(
            TmdbCatalogRequest(TmdbMediaKind.MOVIE) to """{"page":1,"results":[{"id":7},{"id":7},{"id":8}]}""",
            TmdbCatalogRequest(TmdbMediaKind.TV) to """{"page":1,"results":[{"id":7}]}""",
            TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7) to """{"id":7,"title":"虚构科幻电影","release_date":"2024-02-29","runtime":90,"genres":[{"id":878,"name":"Science Fiction"}],"poster_path":"/must-not-download.jpg","vote_average":9.9}""",
            TmdbCatalogRequest(TmdbMediaKind.MOVIE, 8) to """{"id":8,"title":"虚构喜剧电影","release_date":"2020-01-01","runtime":80,"genres":[{"id":35,"name":"Comedy"}]}""",
            TmdbCatalogRequest(TmdbMediaKind.TV, 7) to """{"id":7,"name":"虚构剧集","first_air_date":"2023-01-01","episode_run_time":[45],"number_of_episodes":10,"genres":[{"id":10765,"name":"Sci-Fi & Fantasy"}]}""",
        )
        var missing: TmdbCatalogRequest? = null
        override fun revision() = generation
        override fun isCurrent(revision: String) = generation == revision
        override suspend fun read(request: TmdbCatalogRequest): String {
            reads++; after?.invoke(); if (request == missing) throw TmdbProblem(TmdbProblemCode.NOT_FOUND)
            return bodies[request] ?: error("Unexpected extra request")
        }
    }
    private fun repo(t: Transport, permission: () -> TmdbSourcePermission = { grant }) = TmdbCandidateCatalogRepository(t, permission) { 100 }
    private suspend fun problem(expected: TmdbProblemCode, block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: TmdbProblem) { assertEquals(expected, e.code) }
    }
    @Test fun typedIdentitiesDatesGenresAndRetrievalTimeAreMappedWithoutModelFacts() = runBlocking {
        val t = Transport(); val data = repo(t).load()
        assertEquals(5, t.reads); assertEquals(3, data.candidates.size)
        assertEquals(setOf("movie:7", "movie:8", "tv:7"), data.candidates.map { it.identity.externalId }.toSet())
        val movie = data.candidates.first(); assertEquals("tmdb-protocol-mock", movie.identity.sourceId)
        assertEquals(2024, movie.year); assertEquals(90, movie.runtimeMinutes); assertEquals(setOf("科幻"), movie.topics); assertEquals(100L, movie.verifiedAt)
        val tv = data.candidates.last(); assertNull(tv.runtimeMinutes); assertEquals(setOf("科幻与奇幻"), tv.topics)
        assertEquals(CandidateSourceKind.MOCK, data.sources.single().kind); assertFalse(data.sources.single().license.cacheAllowed)
        assertNull(data.sources.single().license.maxCacheAgeMillis); assertTrue(data.candidates.all { it.cache.state == CandidateCacheState.FRESH })
    }
    @Test fun missingYearAndZeroRuntimeStayUnknownAndHardFiltersRejectThem() = runBlocking {
        val t = Transport(); t.bodies[TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7)] = """{"id":7,"title":"未知信息","release_date":"","runtime":0}"""
        val data = repo(t).load(); assertNull(data.candidates.first().year); assertNull(data.candidates.first().runtimeMinutes)
        val result = RecommendationUseCase(repo(t)) { 100 }(CandidateRecommendationRequest(AiMode.FOR_YOU,
            conditions = CandidateHardConditions(minYear = 2021, maxRuntimeMinutes = 100), allowMock = true))
        assertTrue(result.items.isEmpty())
    }
    @Test fun unknownRevokedExpiredAndAiDisallowedLicensesNeverReadTransport() = runBlocking {
        for (permission in listOf(TmdbSourcePermission(), grant.copy(state = CandidateLicenseState.REVOKED), grant.copy(validUntil = 100), grant.copy(aiRecommendationAllowed = false), grant.copy(evidence = ""))) {
            val t = Transport(); problem(TmdbProblemCode.LICENSE_REQUIRED) { repo(t) { permission }.load() }; assertEquals(0, t.reads)
        }
    }
    @Test fun metadataCannotOverrideTrustedLicenseOrSourceAndMockRequiresOptIn() = runBlocking {
        val t = Transport(); t.bodies[TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7)] = """{"id":7,"title":"虚构作品","sourceId":"tmdb","license":{"state":"ACTIVE","cacheAllowed":true}}"""
        val data = repo(t).load(); assertEquals("tmdb-protocol-mock", data.candidates.first().identity.sourceId); assertFalse(data.sources.single().license.cacheAllowed)
        val result = RecommendationUseCase(repo(t)) { 100 }(CandidateRecommendationRequest(AiMode.FOR_YOU))
        assertTrue(result.realSourceBlocked); assertTrue(result.items.isEmpty())
    }
    @Test fun conflictingIdsMalformedDatesAndWrongTypesFailClosed() = runBlocking {
        for (body in listOf("""{"id":9,"title":"wrong"}""", """{"id":7,"title":"x","release_date":"2023-02-29"}""",
            """{"id":"7","title":"x"}""", """{"id":7,"title":4}""", """{"id":7,"title":"x","runtime":-1}""",
            """{"id":7,"id":7,"title":"x"}""", """{"id":7,"title":"x"}{}""")) {
            val t = Transport(); t.bodies[TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7)] = body
            problem(TmdbProblemCode.INVALID_RESPONSE) { repo(t).load() }
        }
    }
    @Test fun oversizedDeepOrMalformedDiscoveryNeverCausesUnboundedDetailCalls() = runBlocking {
        val invalid = listOf("""{"page":2,"results":[]}""", """{"page":1,"results":[{"id":0}]}""",
            """{"page":1,"results":[{"id":2147483648}]}""", """{"page":1,"results":[{"id":7.5}]}""",
            """{"page":1,"results":[] , "nested":""" + "[".repeat(18) + "0" + "]".repeat(18) + "}",
            """{"page":1,"results":[],"padding":""" + "\"" + "x".repeat(131072) + "\"}")
        for (body in invalid) { val t = Transport(); t.bodies[TmdbCatalogRequest(TmdbMediaKind.MOVIE)] = body
            problem(TmdbProblemCode.INVALID_RESPONSE) { repo(t).load() }; assertEquals(1, t.reads) }
    }
    @Test fun discoveryIsBoundedDeduplicatedAndMissingOrAdultDetailsAreNotInvented() = runBlocking {
        val t = Transport(); t.bodies[TmdbCatalogRequest(TmdbMediaKind.MOVIE)] = """{"page":1,"results":[{"id":7},{"id":7},{"id":8},{"id":9,"adult":true}]}"""
        t.missing = TmdbCatalogRequest(TmdbMediaKind.MOVIE, 8)
        t.bodies[TmdbCatalogRequest(TmdbMediaKind.TV, 7)] = """{"id":7,"name":"隐藏候选","adult":true}"""
        val data = repo(t).load(); assertEquals(listOf("movie:7"), data.candidates.map { it.identity.externalId }); assertEquals(5, t.reads)
    }
    @Test fun changedCredentialOrPermissionDiscardsResults() = runBlocking {
        val t = Transport(); t.after = { t.generation = "changed" }; problem(TmdbProblemCode.STALE_KEY) { repo(t).load() }; assertEquals(1, t.reads)
        val second = Transport(); var permission = grant; second.after = { permission = grant.copy(state = CandidateLicenseState.REVOKED) }
        problem(TmdbProblemCode.LICENSE_REQUIRED) { repo(second) { permission }.load() }; assertEquals(1, second.reads)
    }
    @Test fun existingExclusionAndRankingApplyAndNoCacheOrFakePaddingIsAdded() = runBlocking {
        val t = Transport(); val result = RecommendationUseCase(repo(t)) { 100 }(CandidateRecommendationRequest(AiMode.FOR_YOU,
            preferences = CandidatePreferences(explicit = mapOf("喜剧" to PreferenceDirection.DISLIKE)), allowMock = true))
        assertEquals(2, result.items.size); assertFalse(result.items.any { "喜剧" in it.candidate.topics })
        val data = repo(t).load(); assertNotNull(CandidateValidator.error(data.candidates.first().copy(cache = CandidateCache(CandidateCacheState.CACHED, 90, 110)), data, 100, true))
    }
    @Test fun publicMockDiagnosticNeverUnblocksRealCandidates() = runBlocking {
        val result = MockTmdbCatalogCheck().check(); assertEquals(3, result.identities.size); assertEquals(setOf("movie:7", "movie:8", "tv:7"), result.identities.toSet()); assertTrue(result.rejectedWithoutMockOptIn)
    }

    @Test fun optionalPosterMetadataRemainsSeparateFromWorkDataAndCannotChooseAHost() = runBlocking {
        val transport = Transport()
        val request = TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7)
        for (poster in listOf("null", "12", "\"https://example.invalid/a.jpg\"", "\"/../a.jpg\"", "\"/a.jpg?secret=x\"")) {
            transport.bodies[request] = "{\"id\":7,\"title\":\"虚构海报电影\",\"poster_path\":" + poster + "}"
            val candidate = repo(transport).load().candidates.first()
            assertEquals("虚构海报电影", candidate.title)
            assertNull(candidate.poster)
        }
        transport.bodies[request] = """{"id":7,"title":"虚构海报电影","poster_path":"/fictionalPoster.jpg"}"""
        val candidate = repo(transport).load().candidates.first()
        assertEquals("/fictionalPoster.jpg", candidate.poster?.path)
        assertEquals("https://image.tmdb.org/t/p/w500/fictionalPoster.jpg", candidate.poster?.previewUrl)
        assertEquals(CandidateCacheState.FRESH, candidate.cache.state)
        assertFalse(repo(transport).load().sources.single().license.cacheAllowed)
    }
}
