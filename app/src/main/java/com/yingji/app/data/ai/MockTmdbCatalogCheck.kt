package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Written by this app, not copied from TMDB. Never reads stored credentials or opens a socket. */
class MockTmdbCatalogCheck : TmdbMockCatalogCheck {
    override suspend fun check(): TmdbMockCatalogResult = withContext(Dispatchers.Default) {
        val repository = TmdbCandidateCatalogRepository(object : TmdbCatalogTransport {
            override val isMock = true
            override fun revision() = "synthetic-fixture-v1"
            override fun isCurrent(revision: String) = revision == revision()
            override suspend fun read(request: TmdbCatalogRequest): String = when {
                request.id == null && request.kind == TmdbMediaKind.MOVIE -> """{"page":1,"results":[{"id":7},{"id":8}]}"""
                request.id == null -> """{"page":1,"results":[{"id":7}]}"""
                request.kind == TmdbMediaKind.TV -> """{"id":7,"name":"虚构剧集 Mock","first_air_date":"2024-01-01","episode_run_time":[40],"genres":[{"id":35,"name":"喜剧"}]}"""
                request.id == 7 -> """{"id":7,"title":"虚构电影 Mock A","release_date":"2023-01-01","runtime":90,"genres":[{"id":878,"name":"科幻"}]}"""
                else -> """{"id":8,"title":"虚构电影 Mock B","release_date":"2022-01-01","runtime":80,"genres":[{"id":99,"name":"纪录片"}]}"""
            }
        }, permission = { TmdbSourcePermission(CandidateLicenseState.ACTIVE,
            "App-authored synthetic protocol fixtures only; no TMDB data or real film identity", true, true, false) })
        val useCase = RecommendationUseCase(repository)
        val allowed = useCase(CandidateRecommendationRequest(AiMode.FOR_YOU, allowMock = true))
        val blocked = useCase(CandidateRecommendationRequest(AiMode.FOR_YOU))
        TmdbMockCatalogResult(allowed.items.map { it.candidate.identity.externalId },
            blocked.realSourceBlocked && blocked.items.isEmpty())
    }
}
