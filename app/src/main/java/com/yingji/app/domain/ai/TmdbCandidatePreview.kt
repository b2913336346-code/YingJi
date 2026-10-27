package com.yingji.app.domain.ai

/** Read-only, uncached catalogue access; no profile, model, history or import dependency. */
class TmdbCandidatePreview(
    private val catalog: CandidateCatalogRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowMock: Boolean = false,
) {
    suspend fun read(): List<CatalogCandidate> = readCatalog().candidates

    /** Validated in-memory source evidence for local ranking; never a persistent cache. */
    suspend fun readCatalog(): CandidateCatalog {
        val result = catalog.load()
        if (result.candidates.size > 6 || result.candidates.map { it.identity }.distinct().size != result.candidates.size ||
            result.candidates.any { CandidateValidator.error(it, result, clock(), allowMock) != null ||
                it.cache.state != CandidateCacheState.FRESH ||
                it.identity.sourceId != if (allowMock) "tmdb-protocol-mock" else "tmdb" })
            throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
        return result.copy(sources = result.sources.toList(), candidates = result.candidates.map { it.copy(topics = it.topics.toSet()) })
    }
}
