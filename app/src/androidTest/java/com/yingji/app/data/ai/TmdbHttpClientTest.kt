package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class TmdbHttpClientTest {
    private val fake = "FAKE_TMDB_CREDENTIAL_TEST_ONLY"
    private class Memory(var value: String? = "FAKE_TMDB_CREDENTIAL_TEST_ONLY") : TmdbSecureCredentialStore {
        var revision = "fixture"
        override suspend fun read() = TmdbCredentialRecord(value?.let(TmdbSecret::fromInput), if (value == null) null else 1, revision)
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String { value = key?.useValue { it }; revision = java.util.UUID.randomUUID().toString(); return revision }
    }
    private class Reply(var status: Int = 200, var bytes: ByteArray = "{\"id\":7}".toByteArray()) : HttpURLConnection(URL("https://api.themoviedb.org")) {
        var mime = "application/json"; var encoding: String? = null; var length = -1L; var failure: IOException? = null
        var disconnected = false; var hold = false; val started = CountDownLatch(1); val released = CountDownLatch(1); var seen: URL? = null
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun getResponseCode(): Int { started.countDown(); if (hold && !released.await(5, TimeUnit.SECONDS)) throw SocketTimeoutException(); failure?.let { throw it }; return status }
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getContentType() = mime
        override fun getContentEncoding() = encoding
        override fun getContentLengthLong() = length
    }
    private class Harness(vararg replies: Reply) {
        val queue = java.util.ArrayDeque(replies.toList()); val opened = mutableListOf<Reply>()
        val client = TmdbHttpClient(true) { url -> synchronized(this) { check(queue.isNotEmpty()); queue.removeFirst().also { it.seen = url; opened.add(it) } } }
    }
    private val request = TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7)
    private suspend fun repo(memory: Memory = Memory()) = TmdbCredentialRepository(memory).also { it.refresh() }
    private suspend fun problem(expected: TmdbProblemCode, block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (e: TmdbProblem) { assertEquals(expected, e.code); assertFalse(e.toString().contains(fake)) }
    }
    @Test fun publicClientIsDisabledAndMissingCredentialsNeverOpenAConnection() = runBlocking {
        problem(TmdbProblemCode.LIVE_DISABLED) { TmdbHttpClient().read(request, repo()) }
        val h = Harness(); problem(TmdbProblemCode.KEY_REQUIRED) { h.client.read(request, repo(Memory(null))) }; assertTrue(h.opened.isEmpty())
    }
    @Test fun getUsesOnlyOfficialHostBearerAndNoCacheOrBody() = runBlocking {
        val reply = Reply(); val h = Harness(reply); val value = h.client.read(request, repo())
        assertEquals("{\"id\":7}", value); assertEquals(request.endpoint, reply.seen.toString()); assertEquals("GET", reply.requestMethod)
        assertEquals("Bearer " + fake, reply.getRequestProperty("Authorization")); assertEquals("no-store", reply.getRequestProperty("Cache-Control"))
        assertFalse(reply.seen.toString().contains(fake)); assertFalse(reply.seen.toString().contains("api_key")); assertFalse(reply.doOutput)
        assertFalse(reply.instanceFollowRedirects); assertFalse(reply.useCaches); assertEquals(10000, reply.connectTimeout); assertEquals(15000, reply.readTimeout); assertTrue(reply.disconnected)
    }
    @Test fun authPermissionMissingRateAndRedirectErrorsDoNotRetryOrReadErrorBodies() = runBlocking {
        for ((status, code) in mapOf(401 to TmdbProblemCode.UNAUTHORIZED, 403 to TmdbProblemCode.FORBIDDEN, 404 to TmdbProblemCode.NOT_FOUND,
            429 to TmdbProblemCode.RATE_LIMITED, 302 to TmdbProblemCode.INVALID_RESPONSE, 400 to TmdbProblemCode.INVALID_RESPONSE)) {
            val reply = Reply(status, fake.toByteArray()); val h = Harness(reply)
            problem(code) { h.client.read(request, repo()) }; assertEquals(1, h.opened.size); assertTrue(reply.disconnected)
        }
    }
    @Test fun transientErrorsRetryAtMostOnceAndTlsDoesNotRetry() = runBlocking {
        for (error in listOf<IOException>(SocketTimeoutException(), IOException())) {
            val first = Reply().apply { failure = error }; val second = Reply().apply { failure = error }; val h = Harness(first, second)
            problem(if (error is SocketTimeoutException) TmdbProblemCode.TIMEOUT else TmdbProblemCode.OFFLINE) { h.client.read(request, repo()) }
            assertEquals(2, h.opened.size); assertTrue(first.disconnected && second.disconnected)
        }
        val good = Harness(Reply(503), Reply()); assertEquals("{\"id\":7}", good.client.read(request, repo())); assertEquals(2, good.opened.size)
        val bad = Harness(Reply(503), Reply(503)); problem(TmdbProblemCode.UNAVAILABLE) { bad.client.read(request, repo()) }; assertEquals(2, bad.opened.size)
        val tls = Harness(Reply().apply { failure = SSLException("Fixture TLS failure") }); problem(TmdbProblemCode.SECURE_CONNECTION) { tls.client.read(request, repo()) }; assertEquals(1, tls.opened.size)
    }
    @Test fun mimeEncodingLengthOverflowUtf8AndCredentialEchoAreRejected() = runBlocking {
        val cases = listOf(Reply().apply { mime = "text/html" }, Reply().apply { encoding = "gzip" }, Reply().apply { length = 131073 },
            Reply(bytes = ByteArray(131073) { 65 }), Reply(bytes = byteArrayOf(0xc3.toByte(), 0x28)), Reply(bytes = ("{\"echo\":\"" + fake + "\"}").toByteArray()))
        for (reply in cases) { val h = Harness(reply); problem(TmdbProblemCode.INVALID_RESPONSE) { h.client.read(request, repo()) }; assertEquals(1, h.opened.size); assertTrue(reply.disconnected) }
    }
    @Test fun deletingCredentialDisconnectsHeldCallAndBlocksNewRequests() = runBlocking {
        val held = Reply().apply { hold = true }; val h = Harness(held); val repo = repo()
        val request = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.client.read(request, repo) }.exceptionOrNull() }
        assertTrue(held.started.await(5, TimeUnit.SECONDS)); repo.delete()
        assertEquals(TmdbProblemCode.STALE_KEY, (request.await() as TmdbProblem).code); assertTrue(held.disconnected)
        problem(TmdbProblemCode.KEY_REQUIRED) { h.client.read(this@TmdbHttpClientTest.request, repo) }; assertEquals(1, h.opened.size)
    }
    @Test fun replacingCredentialDiscardsHeldResponseAndUsesNewBearerOnlyForNewRequest() = runBlocking {
        val held = Reply().apply { hold = true }; val next = Reply(); val h = Harness(held, next); val repo = repo()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.client.read(request, repo) }.exceptionOrNull() }
        assertTrue(held.started.await(5, TimeUnit.SECONDS)); repo.save("FAKE_TMDB_REPLACEMENT_TEST_ONLY")
        assertEquals(TmdbProblemCode.STALE_KEY, (pending.await() as TmdbProblem).code)
        h.client.read(request, repo); assertEquals("Bearer FAKE_TMDB_REPLACEMENT_TEST_ONLY", next.getRequestProperty("Authorization")); assertEquals(2, h.opened.size)
    }
    @Test fun coroutineCancellationDisconnectsWithoutDeletingSavedToken() = runBlocking {
        val held = Reply().apply { hold = true }; val h = Harness(held); val repo = repo()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { h.client.read(request, repo) }
        assertTrue(held.started.await(5, TimeUnit.SECONDS)); job.cancelAndJoin(); assertTrue(held.disconnected); assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status)
    }
}
