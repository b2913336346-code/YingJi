package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/** Fixed official HTTPS GETs only. Preview creation performs no I/O; read requires an explicit UI action. */
class TmdbHttpClient internal constructor(
    private val enabled: Boolean,
    private val open: (URL) -> HttpURLConnection,
) {
    constructor() : this(false, { it.openConnection() as HttpsURLConnection })
    companion object {
        internal fun forReadOnlyPreview() = TmdbHttpClient(true) { it.openConnection() as HttpsURLConnection }
    }
    override fun toString() = "TmdbHttpClient([REDACTED])"
    internal class Call(private val current: () -> Boolean) {
        private val cancelled = AtomicBoolean()
        private val connection = AtomicReference<HttpURLConnection?>()
        fun check() { if (cancelled.get() || !current()) throw TmdbProblem(TmdbProblemCode.STALE_KEY) }
        fun attach(value: HttpURLConnection) { connection.set(value); check() }
        fun detach() { connection.set(null) }
        fun cancel() { cancelled.set(true); runCatching { connection.get()?.disconnect() } }
    }
    internal suspend fun read(request: TmdbCatalogRequest, credentials: TmdbCredentialRepository): String {
        if (!enabled) throw TmdbProblem(TmdbProblemCode.LIVE_DISABLED)
        val active = AtomicReference<Call?>()
        return credentials.withCurrentToken(onRevoke = { active.get()?.cancel() }) { secret, current ->
            val call = Call(current); active.set(call)
            exchange(request, secret, call)
        }
    }
    private suspend fun exchange(request: TmdbCatalogRequest, token: TmdbSecret, call: Call): String = suspendCancellableCoroutine { continuation ->
        val secret = token.useValue { it }
        continuation.invokeOnCancellation { call.cancel() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            try {
                var retry = 0
                while (true) {
                    call.check()
                    try { val result = attempt(request, secret, call); call.check(); continuation.resumeWith(Result.success(result)); break }
                    catch (e: TmdbProblem) {
                        call.check()
                        if (e.code !in setOf(TmdbProblemCode.TIMEOUT, TmdbProblemCode.OFFLINE, TmdbProblemCode.UNAVAILABLE) || retry++ >= 1) throw e
                    }
                }
            } catch (e: TmdbProblem) { continuation.resumeWith(Result.failure(e)) }
            catch (_: Exception) { continuation.resumeWith(Result.failure(TmdbProblem(TmdbProblemCode.INVALID_RESPONSE))) }
        })
    }
    private fun attempt(request: TmdbCatalogRequest, secret: String, call: Call): String {
        var connection: HttpURLConnection? = null
        try {
            call.check(); val url = URL(request.endpoint)
            check(url.protocol == "https" && url.host == "api.themoviedb.org" && url.port == -1 && url.userInfo == null)
            connection = open(url); call.attach(connection)
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 10000; connection.readTimeout = 15000; connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer " + secret)
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store"); call.check()
            when (connection.responseCode) {
                200 -> Unit
                401 -> throw TmdbProblem(TmdbProblemCode.UNAUTHORIZED)
                403 -> throw TmdbProblem(TmdbProblemCode.FORBIDDEN)
                404 -> throw TmdbProblem(TmdbProblemCode.NOT_FOUND)
                429 -> throw TmdbProblem(TmdbProblemCode.RATE_LIMITED) // Do not retry a throttled request.
                in 500..599 -> throw TmdbProblem(TmdbProblemCode.UNAVAILABLE)
                else -> throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
            }
            call.check()
            val limit = 131072
            if (connection.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) != "application/json" ||
                connection.contentEncoding?.let { !it.equals("identity", true) } == true || connection.contentLengthLong > limit)
                throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
            val bytes = connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { call.check(); val n = stream.read(buffer); if (n < 0) break
                    if (out.size() + n > limit) throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
                    out.write(buffer, 0, n)
                }; out.toByteArray()
            }
            val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            if (body.contains(secret, ignoreCase = true)) throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
            return body
        } catch (_: SocketTimeoutException) { call.check(); throw TmdbProblem(TmdbProblemCode.TIMEOUT) }
        catch (_: SSLException) { call.check(); throw TmdbProblem(TmdbProblemCode.SECURE_CONNECTION) }
        catch (_: java.nio.charset.CharacterCodingException) { throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE) }
        catch (e: TmdbProblem) { throw e }
        catch (_: java.io.IOException) { call.check(); throw TmdbProblem(TmdbProblemCode.OFFLINE) }
        catch (_: Exception) { call.check(); throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE) }
        finally { call.detach(); runCatching { connection?.disconnect() } }
    }
}

class TmdbAuthenticatedTransport(private val client: TmdbHttpClient, private val credentials: TmdbCredentialRepository) : TmdbCatalogTransport {
    override val isMock = false
    override fun revision() = credentials.state.value.revision ?: "unconfigured"
    override fun isCurrent(revision: String) = !credentials.state.value.saving && this.revision() == revision
    override suspend fun read(request: TmdbCatalogRequest) = client.read(request, credentials)
}
