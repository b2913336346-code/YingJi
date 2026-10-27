package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit detail-page lookup of one saved TMDB identity. No discovery, image or disk writes. */
class TmdbPosterLookup(
    private val transport: TmdbCatalogTransport,
    private val credentials: TmdbCredentialRepository,
    private val permission: () -> TmdbSourcePermission,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(externalId: String): TmdbPoster {
        val match = Regex("(movie|tv):([1-9][0-9]*)").matchEntire(externalId)
            ?: throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
        val id = match.groupValues[2].toIntOrNull() ?: throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
        if (transport.isMock) throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
        val request = TmdbCatalogRequest(if (match.groupValues[1] == "movie") TmdbMediaKind.MOVIE else TmdbMediaKind.TV, id)
        return credentials.withCurrentToken { _, current ->
            val context = currentCoroutineContext()
            val grant = permission()
            val revision = transport.revision()
            fun fence() {
                context.ensureActive()
                if (!current() || !transport.isCurrent(revision)) throw TmdbProblem(TmdbProblemCode.STALE_KEY)
                if (permission() != grant || !grant.permits(clock())) throw TmdbProblem(TmdbProblemCode.LICENSE_REQUIRED)
            }
            fence()
            val body = transport.read(request)
            fence()
            val candidate = TmdbCatalogJsonCodec().detail(body, request, "tmdb", clock())
                ?: throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
            candidate.poster ?: throw NoTmdbPosterException()
        }
    }
}

class NoTmdbPosterException : IllegalStateException("该作品暂无可用海报")
