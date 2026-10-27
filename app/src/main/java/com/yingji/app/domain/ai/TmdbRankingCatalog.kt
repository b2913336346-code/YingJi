package com.yingji.app.domain.ai

/** Shared, fail-closed validation for both local Mock and personalized ranking. */
internal fun validatedTmdbRankingCatalog(value: CandidateCatalog, now: Long): CandidateCatalog {
        val source = value.sources.singleOrNull() ?: throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
        val license = source.license
        if (now < 0 || source.id != "tmdb" || source.kind != CandidateSourceKind.VERIFIED_CATALOG || source.verificationEvidence.isBlank())
            throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
        if (license.state != CandidateLicenseState.ACTIVE || license.evidence.isBlank() || !license.displayAllowed ||
            license.validUntil?.let { now >= it } == true) throw TmdbProblem(TmdbProblemCode.LICENSE_REQUIRED)
        if (value.candidates.size > 6 || value.candidates.map { it.identity }.distinct().size != value.candidates.size ||
            value.candidates.any { row ->
                val typed = Regex("(movie|tv):([1-9][0-9]*)").matchEntire(row.identity.externalId)
                row.identity.sourceId != "tmdb" || typed == null || typed.groupValues[2].toIntOrNull() == null ||
                    row.cache.state != CandidateCacheState.FRESH || CandidateValidator.error(row, value, now, false) != null
            }) throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
        return value.copy(sources = listOf(source.copy(license = license.copy(cacheAllowed = false, maxCacheAgeMillis = null))),
            candidates = value.candidates.map { it.copy(topics = it.topics.toSet()) })
    }
