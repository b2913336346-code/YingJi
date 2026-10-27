package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** At most two discovery pages and six detail calls. No disk cache or profile/library query. */
class TmdbCandidateCatalogRepository(
    private val transport: TmdbCatalogTransport,
    private val permission: () -> TmdbSourcePermission = { TmdbSourcePermission() },
    private val clock: () -> Long = System::currentTimeMillis,
) : CandidateCatalogRepository {
    override suspend fun load(): CandidateCatalog {
        val context = currentCoroutineContext()
        val grant = permission()
        fun fence() {
            context.ensureActive()
            if (permission() != grant || !grant.permits(clock())) throw TmdbProblem(TmdbProblemCode.LICENSE_REQUIRED)
        }
        fence()
        val revision = transport.revision()
        fun current() { fence(); if (!transport.isCurrent(revision)) throw TmdbProblem(TmdbProblemCode.STALE_KEY) }
        val sourceId = if (transport.isMock) "tmdb-protocol-mock" else "tmdb"
        val source = CandidateSource(sourceId, if (transport.isMock) CandidateSourceKind.MOCK else CandidateSourceKind.VERIFIED_CATALOG,
            if (transport.isMock) "App-authored synthetic TMDB protocol fixtures; no real film metadata" else
                "TMDB official v3 discovery IDs rechecked against typed detail endpoints; retrieval time recorded separately",
            CandidateLicense(grant.state, grant.evidence, grant.displayAllowed, grant.importAllowed, false, grant.validUntil))
        val rows = mutableListOf<CatalogCandidate>()
        val codec = TmdbCatalogJsonCodec()
        for (kind in TmdbMediaKind.entries) {
            current(); val ids = codec.ids(transport.read(TmdbCatalogRequest(kind))); current()
            for (id in ids) {
                current(); val request = TmdbCatalogRequest(kind, id)
                val body = try { transport.read(request) } catch (e: CancellationException) { throw e }
                    catch (e: TmdbProblem) { if (e.code == TmdbProblemCode.NOT_FOUND) continue else throw e }
                current(); codec.detail(body, request, sourceId, clock())?.let { rows.add(it) }
            }
        }
        current(); val catalog = CandidateCatalog(listOf(source), rows)
        if (rows.any { CandidateValidator.error(it, catalog, clock(), transport.isMock) != null }) throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
        return catalog
    }
}
