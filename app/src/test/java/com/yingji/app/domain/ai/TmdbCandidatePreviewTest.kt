package com.yingji.app.domain.ai

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TmdbCandidatePreviewTest {
    private val license = CandidateLicense(CandidateLicenseState.ACTIVE, "TEST_ONLY preview permission", true, false, false)
    private val source = CandidateSource("tmdb", CandidateSourceKind.VERIFIED_CATALOG, "TEST_ONLY verified transport", license)
    private val row = CatalogCandidate(CandidateIdentity("tmdb", "movie:7"), "TEST_ONLY preview movie", verifiedAt = 100)
    private fun reader(data: CandidateCatalog, mock: Boolean = false) = TmdbCandidatePreview(object : CandidateCatalogRepository {
        override suspend fun load() = data
    }, { 100 }, mock)
    private suspend fun rejected(data: CandidateCatalog) {
        try { reader(data).read(); fail("Invalid candidate must not be displayed") }
        catch (e: TmdbProblem) { assertEquals(TmdbProblemCode.INVALID_RESPONSE, e.code) }
    }
    @Test fun emptyCatalogDoesNotInventCandidates() = runBlocking {
        assertTrue(reader(CandidateCatalog(listOf(source), emptyList())).read().isEmpty())
    }
    @Test fun verifiedRowsKeepExternalIdentityAndRetrievalTime() = runBlocking {
        val rows = reader(CandidateCatalog(listOf(source), listOf(row))).read()
        assertEquals(listOf(row), rows); assertEquals("movie:7", rows.single().identity.externalId)
        assertEquals(100L, rows.single().verifiedAt); assertFalse(source.license.importAllowed); assertFalse(source.license.cacheAllowed)
    }
    @Test fun unknownRevokedExpiredOrUnlicensedSourceFailsClosed() = runBlocking {
        for (grant in listOf(license.copy(state = CandidateLicenseState.UNKNOWN), license.copy(state = CandidateLicenseState.REVOKED),
            license.copy(validUntil = 100), license.copy(displayAllowed = false), license.copy(evidence = "")))
            rejected(CandidateCatalog(listOf(source.copy(license = grant)), listOf(row)))
    }
    @Test fun mockRequiresExplicitFixtureOptInAndCannotUseRealIdentity() = runBlocking {
        val fixture = row.copy(identity = CandidateIdentity("tmdb-protocol-mock", "movie:7"))
        val data = CandidateCatalog(listOf(source.copy(id = "tmdb-protocol-mock", kind = CandidateSourceKind.MOCK)), listOf(fixture))
        rejected(data); assertEquals(listOf(fixture), reader(data, true).read())
        rejected(CandidateCatalog(listOf(source.copy(id = "unreviewed")), listOf(row.copy(identity = CandidateIdentity("unreviewed", "movie:7")))))
    }
    @Test fun duplicateOversizedAndFutureRowsAreRejected() = runBlocking {
        rejected(CandidateCatalog(listOf(source), listOf(row, row)))
        rejected(CandidateCatalog(listOf(source), (1..7).map { row.copy(identity = CandidateIdentity("tmdb", "movie:" + it)) }))
        rejected(CandidateCatalog(listOf(source), listOf(row.copy(verifiedAt = 101))))
    }
    @Test fun cachedDataIsNeverReusedByReadOnlyPreview() = runBlocking {
        rejected(CandidateCatalog(listOf(source.copy(license = license.copy(cacheAllowed = true, maxCacheAgeMillis = 50))),
            listOf(row.copy(cache = CandidateCache(CandidateCacheState.CACHED, 90, 110)))))
    }
}
