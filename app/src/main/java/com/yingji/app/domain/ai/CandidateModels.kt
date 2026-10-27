package com.yingji.app.domain.ai

import java.text.Normalizer
import java.util.Locale

/** Catalogue identity is opaque and never a local Room movie ID. */
data class CandidateIdentity(val sourceId: String, val externalId: String)
enum class CandidateSourceKind { MOCK, VERIFIED_CATALOG }
enum class CandidateLicenseState { ACTIVE, UNKNOWN, REVOKED }
enum class CandidateCacheState { FRESH, CACHED }

data class CandidateLicense(
    val state: CandidateLicenseState,
    val evidence: String,
    val displayAllowed: Boolean,
    val importAllowed: Boolean,
    val cacheAllowed: Boolean,
    val validUntil: Long? = null,
    val maxCacheAgeMillis: Long? = null,
)

/** Supplied by a trusted catalogue adapter, never constructed from model hints. */
data class CandidateSource(val id: String, val kind: CandidateSourceKind, val verificationEvidence: String, val license: CandidateLicense)
data class CandidateCache(val state: CandidateCacheState = CandidateCacheState.FRESH, val storedAt: Long? = null, val expiresAt: Long? = null)
data class CatalogCandidate(
    val identity: CandidateIdentity,
    val title: String?,
    val topics: Set<String> = emptySet(),
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val description: String? = null,
    val verifiedAt: Long,
    val cache: CandidateCache = CandidateCache(),
    val poster: TmdbPoster? = null,
)
data class CandidateCatalog(val sources: List<CandidateSource>, val candidates: List<CatalogCandidate>)
interface CandidateCatalogRepository { suspend fun load(): CandidateCatalog }

data class CandidateHardConditions(
    val requiredTopics: Set<String> = emptySet(),
    val excludedTopics: Set<String> = emptySet(),
    val minYear: Int? = null,
    val maxYear: Int? = null,
    val maxRuntimeMinutes: Int? = null,
    val excludedIdentities: Set<CandidateIdentity> = emptySet(),
)
data class CandidatePreferences(
    val explicit: Map<String, PreferenceDirection> = emptyMap(),
    val inferred: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun fromProfile(profile: ProfileSnapshot): CandidatePreferences {
            fun topic(id: String) = id.takeIf { it.startsWith("topic:") }?.substringAfter(':')
            val inferred = profile.inferredPreferences.filter {
                it.feature.id !in profile.suppressedFeatureIds && when (it.source) {
                    ProfileSource.RATING -> profile.settings.ratingsEnabled
                    ProfileSource.BEHAVIOR -> false // Watching does not mean liking.
                    ProfileSource.FEEDBACK -> profile.settings.feedbackEnabled
                }
            }.groupBy { it.feature.id }.mapNotNull { (id, rows) ->
                topic(id)?.let { it to rows.sumOf { row -> row.positiveEvidence - row.negativeEvidence }.coerceIn(-10, 10) }
            }.toMap()
            return CandidatePreferences(profile.explicitPreferences.mapNotNull { row ->
                topic(row.feature.id)?.let { it to row.direction }
            }.toMap(), inferred)
        }
    }
}
data class CandidateRecommendationRequest(
    val mode: AiMode,
    val preferences: CandidatePreferences = CandidatePreferences(),
    val conditions: CandidateHardConditions = CandidateHardConditions(),
    /** Hints can order existing identities only; they cannot supply facts or candidates. */
    val hintOrder: List<CandidateIdentity> = emptyList(),
    val allowMock: Boolean = false,
)
data class RankedCandidate(val candidate: CatalogCandidate, val explicitMatches: Int, val localScore: Int)
data class CandidateRecommendationResult(val items: List<RankedCandidate>, val realSourceBlocked: Boolean)

fun normalizedCandidateText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

object CandidateValidator {
    fun error(candidate: CatalogCandidate, catalog: CandidateCatalog, now: Long, allowMock: Boolean, importing: Boolean = false): String? {
        val id = candidate.identity
        if (id.sourceId.isBlank() || id.externalId.isBlank() || id.sourceId != id.sourceId.trim() ||
            id.externalId != id.externalId.trim() || id.sourceId.length > 100 || id.externalId.length > 200) return "候选身份无效"
        val source = catalog.sources.singleOrNull { it.id == id.sourceId } ?: return "候选来源未确认"
        if (source.kind == CandidateSourceKind.MOCK && !allowMock) return "演示候选不能作为真实推荐"
        if (source.verificationEvidence.isBlank()) return "候选来源缺少核验依据"
        val license = source.license
        if (license.state != CandidateLicenseState.ACTIVE || license.evidence.isBlank() || !license.displayAllowed ||
            (license.validUntil != null && now >= license.validUntil) || (importing && !license.importAllowed)) return "候选许可无效或已到期"
        if (candidate.title.isNullOrBlank() || candidate.title.length > 500 || candidate.topics.any { it.isBlank() || it.length > 200 } ||
            candidate.year?.let { it !in 1800..3000 } == true || candidate.runtimeMinutes?.let { it !in 1..10000 } == true ||
            candidate.verifiedAt < 0 || candidate.verifiedAt > now) return "候选元数据无效"
        if (candidate.cache.state == CandidateCacheState.CACHED) {
            val stored = candidate.cache.storedAt ?: return "候选缓存缺少时间"
            val expiry = candidate.cache.expiresAt ?: return "候选缓存缺少期限"
            val maxAge = license.maxCacheAgeMillis ?: return "来源未许可缓存期限"
            if (!license.cacheAllowed || stored < 0 || stored > now || maxAge <= 0 || now >= expiry || expiry <= stored ||
                now - stored >= maxAge || expiry - stored > maxAge || (license.validUntil != null && expiry > license.validUntil)) return "候选缓存不可用"
        }
        return null
    }
}
