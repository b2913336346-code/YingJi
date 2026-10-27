package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import com.yingji.app.domain.usecase.AddMovieUseCase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** User-confirmed save only: recheck one typed detail, never fetch discovery or persist a metadata cache. */
class TmdbImportUseCase(
    private val transport: TmdbCatalogTransport,
    private val credentials: TmdbCredentialRepository,
    private val permission: () -> TmdbSourcePermission,
    private val imports: CandidateImportStore,
    private val addMovie: AddMovieUseCase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(input: RecommendedWorkInput): Long {
        if (input.identity.sourceId != "tmdb" || input.allowMock || transport.isMock)
            throw CandidateImportException("TMDB 候选身份无效，请重新读取")
        val match = Regex("(movie|tv):([1-9][0-9]*)").matchEntire(input.identity.externalId)
            ?: throw CandidateImportException("TMDB 候选身份无效，请重新读取")
        val id = match.groupValues[2].toIntOrNull() ?: throw CandidateImportException("TMDB 候选身份无效，请重新读取")
        val request = TmdbCatalogRequest(if (match.groupValues[1] == "movie") TmdbMediaKind.MOVIE else TmdbMediaKind.TV, id)
        var guard: () -> Unit = { throw CandidateImportException("请重新读取候选") }
        val catalog = object : CandidateCatalogRepository {
            override suspend fun load(): CandidateCatalog = credentials.withCurrentToken { _, current ->
                val context = currentCoroutineContext()
                val grant = permission(); val revision = transport.revision()
                fun fence() {
                    context.ensureActive()
                    if (!current() || !transport.isCurrent(revision)) throw TmdbProblem(TmdbProblemCode.STALE_KEY)
                    if (permission() != grant || !grant.permits(clock()) || !grant.importAllowed) throw TmdbProblem(TmdbProblemCode.LICENSE_REQUIRED)
                }
                fence(); val body = transport.read(request); fence()
                val row = TmdbCatalogJsonCodec().detail(body, request, "tmdb", clock())
                    ?: throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
                val source = CandidateSource("tmdb", CandidateSourceKind.VERIFIED_CATALOG,
                    "TMDB typed detail rechecked on user-confirmed save",
                    CandidateLicense(grant.state, grant.evidence, grant.displayAllowed, grant.importAllowed, false, grant.validUntil))
                guard = { fence() }
                CandidateCatalog(listOf(source), listOf(row))
            }
        }
        try {
            return AddRecommendedWorkUseCase(catalog, imports, addMovie, clock)(input, { guard() })
        } catch (problem: TmdbProblem) {
            throw CandidateImportException(when (problem.code) {
                TmdbProblemCode.KEY_REQUIRED, TmdbProblemCode.KEY_UNAVAILABLE, TmdbProblemCode.STALE_KEY, TmdbProblemCode.BUSY -> "TMDB 令牌不可用或已变化，请重新配置并读取候选"
                TmdbProblemCode.UNAUTHORIZED, TmdbProblemCode.FORBIDDEN -> "TMDB 令牌或权限无效，未保存作品"
                TmdbProblemCode.LICENSE_REQUIRED -> "TMDB 来源入库许可不可用，未保存作品"
                TmdbProblemCode.NOT_FOUND -> "TMDB 候选已不可用，请重新读取"
                TmdbProblemCode.RATE_LIMITED -> "TMDB 请求受限，请稍后手动重试保存"
                TmdbProblemCode.OFFLINE, TmdbProblemCode.TIMEOUT, TmdbProblemCode.UNAVAILABLE -> "无法完成 TMDB 详情校验，请检查网络后重试保存"
                else -> "TMDB 候选校验失败，未保存作品"
            })
        }
    }
}
