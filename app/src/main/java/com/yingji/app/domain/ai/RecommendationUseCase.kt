package com.yingji.app.domain.ai

class RecommendationUseCase(private val catalog: CandidateCatalogRepository, private val clock: () -> Long = System::currentTimeMillis) {
    suspend operator fun invoke(request: CandidateRecommendationRequest): CandidateRecommendationResult {
        val conditions = request.conditions
        require(conditions.minYear == null || conditions.maxYear == null || conditions.minYear <= conditions.maxYear) { "年份区间无效" }
        require(conditions.maxRuntimeMinutes == null || conditions.maxRuntimeMinutes > 0) { "片长条件无效" }
        val data = catalog.load()
        val now = clock()
        val explicit = request.preferences.explicit.entries.groupBy { normalizedCandidateText(it.key) }.mapValues { (_, rows) ->
            if (rows.any { it.value == PreferenceDirection.DISLIKE }) PreferenceDirection.DISLIKE else PreferenceDirection.LIKE
        }
        val inferred = request.preferences.inferred.mapKeys { normalizedCandidateText(it.key) }
        val excluded = conditions.excludedTopics.map(::normalizedCandidateText).toSet() + explicit.filterValues { it == PreferenceDirection.DISLIKE }.keys
        val required = conditions.requiredTopics.map(::normalizedCandidateText).toSet()
        val blocked = data.sources.none { source -> source.kind == CandidateSourceKind.VERIFIED_CATALOG &&
            source.verificationEvidence.isNotBlank() && source.license.let { it.state == CandidateLicenseState.ACTIVE && it.displayAllowed &&
                it.evidence.isNotBlank() && (it.validUntil == null || now < it.validUntil) } }
        // Conflicting facts for one source identity are rejected instead of picking an arbitrary version.
        val valid = data.candidates.groupBy { it.identity }.values.mapNotNull { rows -> rows.distinct().singleOrNull() }
            .filter { CandidateValidator.error(it, data, now, request.allowMock) == null }
            .filter { candidate ->
                val topics = candidate.topics.map(::normalizedCandidateText).toSet()
                candidate.identity !in conditions.excludedIdentities && topics.none { it in excluded } && topics.containsAll(required) &&
                    (conditions.minYear == null || candidate.year?.let { it >= conditions.minYear } == true) &&
                    (conditions.maxYear == null || candidate.year?.let { it <= conditions.maxYear } == true) &&
                    (conditions.maxRuntimeMinutes == null || candidate.runtimeMinutes?.let { it <= conditions.maxRuntimeMinutes } == true)
            }.map { candidate ->
                val topics = candidate.topics.map(::normalizedCandidateText).toSet()
                val matches = topics.count { explicit[it] == PreferenceDirection.LIKE }
                val inferredScore = topics.filter { it !in explicit }.sumOf { (inferred[it] ?: 0).coerceIn(-10, 10) }
                RankedCandidate(candidate, matches, inferredScore)
            }.sortedWith(compareByDescending<RankedCandidate> { it.explicitMatches }
                .thenByDescending { it.localScore }
                .thenBy { request.hintOrder.indexOf(it.candidate.identity).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .thenBy { it.candidate.identity.sourceId }.thenBy { it.candidate.identity.externalId })
            // Same title/year across sources is one display candidate. Missing year is treated conservatively.
        val unique = mutableListOf<RankedCandidate>()
        valid.forEach { row -> if (unique.none { existing -> normalizedCandidateText(existing.candidate.title!!) == normalizedCandidateText(row.candidate.title!!) &&
                (existing.candidate.year == row.candidate.year || existing.candidate.year == null || row.candidate.year == null) }) unique.add(row) }
        val selected = mutableListOf<RankedCandidate>()
        val seenTopics = mutableSetOf<String>()
        while (selected.size < 3 && unique.isNotEmpty()) {
            val best = unique.first()
            // Diversity breaks ties only; it never overrides an explicit preference or local evidence score.
            val next = unique.firstOrNull { it.explicitMatches == best.explicitMatches && it.localScore == best.localScore &&
                it.candidate.topics.map(::normalizedCandidateText).any { topic -> topic !in seenTopics } } ?: best
            selected.add(next); unique.remove(next)
            seenTopics.addAll(next.candidate.topics.map(::normalizedCandidateText))
        }
        return CandidateRecommendationResult(selected, blocked)
    }
}
