package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

internal class PosterTestKeys : TmdbSecureCredentialStore {
    private var key: String? = null
    private var version = 0
    override suspend fun read() = TmdbCredentialRecord(key?.let(TmdbSecret::fromInput), 100, version.toString())
    override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String {
        this.key = key?.useValue { it }; return (++version).toString()
    }
}

class TmdbPosterLookupTest {
    private val keys = TmdbCredentialRepository(PosterTestKeys()) { 100 }
    private val calls = mutableListOf<TmdbCatalogRequest>()
    private var grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "Synthetic poster lookup permission", true, true, true)
    private var mock = false
    private var afterRead: suspend () -> Unit = {}
    private var body: (TmdbCatalogRequest) -> String = { r ->
        "{\"id\":" + r.id + if (r.kind == TmdbMediaKind.MOVIE)
            ",\"title\":\"虚构电影\",\"poster_path\":\"/fictionalMovie.jpg\"}"
        else ",\"name\":\"虚构剧集\",\"poster_path\":\"/fictionalTv.png\"}"
    }
    private val transport = object : TmdbCatalogTransport {
        override val isMock get() = mock
        override fun revision() = keys.state.value.revision.orEmpty()
        override fun isCurrent(revision: String) = revision == this.revision() && !keys.state.value.saving
        override suspend fun read(request: TmdbCatalogRequest): String { calls.add(request); afterRead(); return body(request) }
    }
    private fun lookup() = TmdbPosterLookup(transport, keys, { grant }) { 100 }
    @Before fun setup() = runBlocking { keys.save("TEST_ONLY_poster_lookup") }

    @Test fun typedMovieAndTvRequestsOnlyReadTheirOwnDetail() = runBlocking {
        assertEquals(TmdbPoster("/fictionalMovie.jpg"), lookup()("movie:7"))
        assertEquals(TmdbPoster("/fictionalTv.png"), lookup()("tv:8"))
        assertEquals(listOf(TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7), TmdbCatalogRequest(TmdbMediaKind.TV, 8)), calls)
    }
    @Test fun invalidIdentityAndProtocolMockNeverReachTransport() = runBlocking {
        for (id in listOf("movie:0", "movie:-1", "movie:2147483648", "movie:01", "movie:7?x=1", "video:7"))
            assertTrue(runCatching { lookup()(id) }.exceptionOrNull() is TmdbProblem)
        mock = true
        assertTrue(runCatching { lookup()("movie:7") }.exceptionOrNull() is TmdbProblem)
        assertTrue(calls.isEmpty())
    }
    @Test fun missingKeyAndRevokedOrExpiredPermissionDoNotRead() = runBlocking {
        keys.delete()
        assertEquals(TmdbProblemCode.KEY_REQUIRED, (runCatching { lookup()("movie:7") }.exceptionOrNull() as TmdbProblem).code)
        keys.save("TEST_ONLY_poster_lookup")
        for (value in listOf(grant.copy(state = CandidateLicenseState.REVOKED), grant.copy(displayAllowed = false), grant.copy(validUntil = 100))) {
            grant = value
            assertEquals(TmdbProblemCode.LICENSE_REQUIRED, (runCatching { lookup()("movie:7") }.exceptionOrNull() as TmdbProblem).code)
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun noPosterOrUntrustedPathReturnsNoPosterWithoutInventingAnImage() = runBlocking {
        for (value in listOf("null", "\"https://example.invalid/unsafe.png\"", "\"/../unsafe.png\"")) {
            body = { "{\"id\":7,\"title\":\"虚构电影\",\"poster_path\":" + value + "}" }
            assertTrue(runCatching { lookup()("movie:7") }.exceptionOrNull() is NoTmdbPosterException)
        }
        assertEquals(3, calls.size)
    }
    @Test fun mismatchedIdentityAndMalformedDetailsFailClosed() = runBlocking {
        for (value in listOf("""{"id":8,"title":"其他电影","poster_path":"/other.jpg"}""", "not-json")) {
            body = { value }
            assertEquals(TmdbProblemCode.INVALID_RESPONSE, (runCatching { lookup()("movie:7") }.exceptionOrNull() as TmdbProblem).code)
        }
    }
    @Test fun credentialOrPermissionChangesDuringLookupDiscardTheResult() = runBlocking {
        afterRead = { keys.delete() }
        assertEquals(TmdbProblemCode.STALE_KEY, (runCatching { lookup()("movie:7") }.exceptionOrNull() as TmdbProblem).code)
        keys.save("TEST_ONLY_replaced")
        afterRead = { grant = grant.copy(state = CandidateLicenseState.REVOKED) }
        assertEquals(TmdbProblemCode.LICENSE_REQUIRED, (runCatching { lookup()("movie:7") }.exceptionOrNull() as TmdbProblem).code)
    }
    @Test fun cancellationAndNetworkProblemsPropagateWithoutFallback() = runBlocking {
        afterRead = { throw CancellationException("synthetic cancellation") }
        assertTrue(runCatching { lookup()("movie:7") }.exceptionOrNull() is CancellationException)
        afterRead = { throw TmdbProblem(TmdbProblemCode.OFFLINE) }
        assertEquals(TmdbProblemCode.OFFLINE, (runCatching { lookup()("movie:7") }.exceptionOrNull() as TmdbProblem).code)
    }
}
