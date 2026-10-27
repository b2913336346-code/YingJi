package com.yingji.app.domain.ai

/** Provider identity only; never a new local work-type/category system. */
enum class TmdbMediaKind(val path: String) { MOVIE("movie"), TV("tv") }
data class TmdbCatalogRequest(val kind: TmdbMediaKind, val id: Int? = null) {
    init { require(id == null || id > 0) }
    val endpoint: String get() = "https://api.themoviedb.org/3/" +
        (id?.let { kind.path + "/" + it } ?: "discover/" + kind.path) +
        "?language=zh-CN" + if (id == null) "&include_adult=false&page=1&sort_by=popularity.desc" else ""
}
enum class TmdbProblemCode { LIVE_DISABLED, KEY_REQUIRED, KEY_UNAVAILABLE, STALE_KEY, BUSY, LICENSE_REQUIRED,
    UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, UNAVAILABLE, TIMEOUT, OFFLINE, SECURE_CONNECTION, INVALID_RESPONSE }
class TmdbProblem(val code: TmdbProblemCode) : IllegalStateException(code.name)
interface TmdbCatalogTransport {
    val isMock: Boolean
    fun revision(): String
    fun isCurrent(revision: String): Boolean
    suspend fun read(request: TmdbCatalogRequest): String
}
data class TmdbSourcePermission(
    val state: CandidateLicenseState = CandidateLicenseState.UNKNOWN,
    val evidence: String = "",
    val aiRecommendationAllowed: Boolean = false,
    val displayAllowed: Boolean = false,
    val importAllowed: Boolean = false,
    val validUntil: Long? = null,
) {
    fun permits(now: Long) = now >= 0 && state == CandidateLicenseState.ACTIVE && evidence.isNotBlank() &&
        aiRecommendationAllowed && displayAllowed && (validUntil == null || now < validUntil)
}
/** UI diagnostic contract: only app-authored fixtures, no credential, persistence or network. */
fun interface TmdbMockCatalogCheck { suspend fun check(): TmdbMockCatalogResult }
data class TmdbMockCatalogResult(val identities: List<String>, val rejectedWithoutMockOptIn: Boolean)
