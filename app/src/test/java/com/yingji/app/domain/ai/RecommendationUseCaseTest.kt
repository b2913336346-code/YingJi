package com.yingji.app.domain.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecommendationUseCaseTest {
    private val now = 1000L
    private val license = CandidateLicense(CandidateLicenseState.ACTIVE, "test fixture license", true, true, true, 2000, 500)
    private val source = CandidateSource("fixture", CandidateSourceKind.VERIFIED_CATALOG, "test adapter only", license)
    private fun candidate(id: String, topic: String = "科幻") = CatalogCandidate(CandidateIdentity(source.id, id), "测试 " + id, setOf(topic), 2020, 90, verifiedAt = 900)
    private fun rank(rows: List<CatalogCandidate>, request: CandidateRecommendationRequest = CandidateRecommendationRequest(AiMode.FOR_YOU),
                     sources: List<CandidateSource> = listOf(source)): CandidateRecommendationResult = runBlocking {
        RecommendationUseCase(object : CandidateCatalogRepository { override suspend fun load() = CandidateCatalog(sources, rows) }) { now }(request)
    }
    @Test fun hardExcludesAndRequiredTopicsCannotBeOverriddenByHintsOrInferredLikes() {
        val a = candidate("a"); val b = candidate("b", "喜剧")
        val result = rank(listOf(a,b), CandidateRecommendationRequest(AiMode.QUERY,
            CandidatePreferences(mapOf("科幻" to PreferenceDirection.DISLIKE), mapOf("科幻" to 10)),
            CandidateHardConditions(requiredTopics = setOf("喜剧")), hintOrder = listOf(a.identity)))
        assertEquals(listOf(b), result.items.map { it.candidate })
    }
    @Test fun normalizedPreferenceAliasesKeepExclusionRatherThanLastLike() {
        val request=CandidateRecommendationRequest(AiMode.FOR_YOU,CandidatePreferences(linkedMapOf(
            "科幻" to PreferenceDirection.DISLIKE," 科幻 " to PreferenceDirection.LIKE)))
        assertTrue(rank(listOf(candidate("a")),request).items.isEmpty())
    }
    @Test fun yearAndRuntimeBoundsAreInclusiveAndMissingRequiredFieldsAreRejected() {
        val a = candidate("a"); val missing = candidate("missing").copy(year = null)
        val long = candidate("long").copy(runtimeMinutes = 91)
        val request = CandidateRecommendationRequest(AiMode.TONIGHT, conditions = CandidateHardConditions(minYear = 2020, maxYear = 2020, maxRuntimeMinutes = 90))
        assertEquals(listOf(a), rank(listOf(a,missing,long,candidate("old").copy(year=2019)), request).items.map { it.candidate })
        assertTrue(rank(listOf(a.copy(runtimeMinutes=null)), request).items.isEmpty())
    }
    @Test fun excludedIdentityAndContradictoryHardConditionsProduceNoPadding() {
        val a=candidate("a")
        assertTrue(rank(listOf(a), CandidateRecommendationRequest(AiMode.FOR_YOU, conditions=CandidateHardConditions(excludedIdentities=setOf(a.identity)))).items.isEmpty())
        assertTrue(rank(listOf(a), CandidateRecommendationRequest(AiMode.FOR_YOU, conditions=CandidateHardConditions(requiredTopics=setOf("科幻"),excludedTopics=setOf("科幻")))).items.isEmpty())
    }
    @Test fun explicitLikeRanksAboveInferredAndModelHintScores() {
        val a=candidate("a");val b=candidate("b","喜剧")
        val result=rank(listOf(a,b),CandidateRecommendationRequest(AiMode.FOR_YOU,
            CandidatePreferences(mapOf("喜剧" to PreferenceDirection.LIKE),mapOf("科幻" to 10,"喜剧" to -10)),hintOrder=listOf(a.identity)))
        assertEquals(b,result.items.first().candidate)
        assertEquals(0,result.items.first().localScore)
    }
    @Test fun inferenceRanksBeforeHintsAndDiversityOnlyBreaksEqualScores() {
        val rows=listOf(candidate("a"),candidate("b"),candidate("c","喜剧"),candidate("d","纪录片"))
        assertEquals(listOf("a","c","d"),rank(rows).items.map { it.candidate.identity.externalId })
        val result=rank(rows,CandidateRecommendationRequest(AiMode.FOR_YOU,CandidatePreferences(inferred=mapOf("科幻" to 1)),hintOrder=listOf(rows[2].identity)))
        assertEquals(listOf("a","b","c"),result.items.map { it.candidate.identity.externalId })
    }
    @Test fun hintsCannotInventCandidatesOrOverrideFiltering() {
        val a=candidate("a")
        val result=rank(listOf(a),CandidateRecommendationRequest(AiMode.QUERY,hintOrder=listOf(CandidateIdentity("model","invented"))))
        assertEquals(listOf(a),result.items.map { it.candidate })
    }
    @Test fun exactDuplicateIsOneAndConflictingSourceIdentityIsRejected() {
        val a=candidate("a")
        assertEquals(1,rank(listOf(a,a)).items.size)
        assertTrue(rank(listOf(a,a.copy(title="冲突元数据"))).items.isEmpty())
    }
    @Test fun sameTitleAcrossSourcesIsDeduplicatedConservativelyButDifferentYearsRemainDistinct() {
        val a=candidate("a").copy(title=" ＦＩＬＭ  作品 ")
        val b=candidate("b").copy(identity=CandidateIdentity("fixture-two","b"),title="film 作品")
        val sources=listOf(source,source.copy(id="fixture-two"))
        assertEquals(1,rank(listOf(a,b),sources=sources).items.size)
        assertEquals(2,rank(listOf(a,b.copy(year=2021)),sources=sources).items.size)
        assertEquals(1,rank(listOf(a,b.copy(year=null)),sources=sources).items.size)
    }
    @Test fun zeroOrTwoCandidatesRemainActualCountAndMaximumIsThree() {
        assertTrue(rank(emptyList()).items.isEmpty())
        assertEquals(2,rank(listOf(candidate("a"),candidate("b"))).items.size)
        assertEquals(3,rank((1..8).map { candidate(it.toString()) }).items.size)
    }
    @Test fun missingIdentityTitleSourceOrVerificationIsRejectedButOptionalFieldsCanBeAbsent() {
        val a=candidate("a")
        val invalid=listOf(a.copy(title=null),a.copy(title=" "),a.copy(identity=CandidateIdentity("fixture","")),a.copy(identity=CandidateIdentity("unknown","a")),a.copy(verifiedAt=1001),a.copy(year=0),a.copy(runtimeMinutes=0))
        assertTrue(rank(invalid).items.isEmpty())
        assertTrue(rank(listOf(a),sources=listOf(source.copy(verificationEvidence=""))).items.isEmpty())
        assertEquals(1,rank(listOf(a.copy(year=null,runtimeMinutes=null,description=null,topics=emptySet()))).items.size)
    }
    @Test fun unknownRevokedExpiredAndUnlicensedSourcesFailClosed() {
        for (bad in listOf(license.copy(state=CandidateLicenseState.UNKNOWN),license.copy(state=CandidateLicenseState.REVOKED),
            license.copy(validUntil=now),license.copy(displayAllowed=false),license.copy(evidence=""))) {
            val result=rank(listOf(candidate("a")),sources=listOf(source.copy(license=bad)))
            assertTrue(result.items.isEmpty());assertTrue(result.realSourceBlocked)
        }
        assertTrue(rank(listOf(candidate("a")),sources=listOf(source,source)).items.isEmpty())
    }
    @Test fun cacheNeedsPermissionAndBothTimeLimitsAndIsRejectedAtExpiry() {
        val a=candidate("a").copy(cache=CandidateCache(CandidateCacheState.CACHED,800,1200))
        assertEquals(1,rank(listOf(a)).items.size)
        val invalid=listOf(a.copy(cache=a.cache.copy(expiresAt=now)),a.copy(cache=a.cache.copy(storedAt=null)),
            a.copy(cache=a.cache.copy(storedAt=500)),a.copy(cache=a.cache.copy(storedAt=1001)),a.copy(cache=a.cache.copy(expiresAt=1400)))
        assertTrue(rank(invalid).items.isEmpty())
        assertTrue(rank(listOf(a),sources=listOf(source.copy(license=license.copy(cacheAllowed=false)))).items.isEmpty())
        assertTrue(rank(listOf(a),sources=listOf(source.copy(license=license.copy(validUntil=1100)))).items.isEmpty())
    }
    @Test fun mockRequiresExplicitOptInAndNeverUnblocksRealRecommendations() {
        val mock=source.copy(kind=CandidateSourceKind.MOCK)
        assertTrue(rank(listOf(candidate("a")),sources=listOf(mock)).items.isEmpty())
        val result=rank(listOf(candidate("a")),CandidateRecommendationRequest(AiMode.FOR_YOU,allowMock=true),listOf(mock))
        assertEquals(1,result.items.size);assertTrue(result.realSourceBlocked)
    }
    @Test fun profileAdapterHonorsSourceSwitchesCorrectionAndExplicitPreference() {
        val topic=ProfileFeature.topic("科幻")
        val rows=listOf(InferredPreference(topic,ProfileSource.RATING,1,positiveEvidence=1),
            InferredPreference(topic,ProfileSource.FEEDBACK,1,negativeEvidence=1),
            InferredPreference(topic,ProfileSource.BEHAVIOR,1,positiveEvidence=10))
        val defaults=ProfileSnapshot(inferredPreferences=rows)
        assertEquals(-1,CandidatePreferences.fromProfile(defaults).inferred["科幻"])
        assertTrue(CandidatePreferences.fromProfile(defaults.copy(settings=ProfileSourceSettings(false,false,false))).inferred.isEmpty())
        assertTrue(CandidatePreferences.fromProfile(defaults.copy(suppressedFeatureIds=setOf(topic.id))).inferred.isEmpty())
        val explicit=defaults.copy(explicitPreferences=listOf(ExplicitPreference(topic,PreferenceDirection.LIKE)))
        assertEquals(PreferenceDirection.LIKE,CandidatePreferences.fromProfile(explicit).explicit["科幻"])
    }
    @Test(expected=IllegalArgumentException::class) fun invertedDateRangeIsRejected() {
        rank(emptyList(),CandidateRecommendationRequest(AiMode.FOR_YOU,conditions=CandidateHardConditions(minYear=2021,maxYear=2020)))
    }
}
