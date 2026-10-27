package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

internal data class DeepSeekHintResult(val hints: WorkerHints, val usage: WorkerUsage)

/** Only the two fixed official HTTPS endpoints can receive a runtime Key. No public Base URL option. */
class DeepSeekHttpClient internal constructor(
    private val usage: LocalAiUsageTracker? = null,
    private val open: (URL) -> HttpURLConnection,
) : DeepSeekKeyValidation {
    constructor() : this(null, { url -> url.openConnection() as HttpsURLConnection })
    constructor(usage: LocalAiUsageTracker) : this(usage, { url -> url.openConnection() as HttpsURLConnection })
    private val codec = DeepSeekJsonCodec()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val pendingIds = ConcurrentHashMap.newKeySet<String>()
    override fun toString() = "DeepSeekHttpClient(redacted)"
    override fun cancelPending() { calls.forEach { it.cancel() } }

    private class Call(private val current: () -> Boolean) {
        private val cancelled = AtomicBoolean()
        private val connection = AtomicReference<HttpURLConnection?>()
        fun check() { if (cancelled.get() || !current()) throw WorkerProblem("STALE_KEY") }
        fun attach(value: HttpURLConnection) { connection.set(value); check() }
        fun detach() { connection.set(null) }
        fun cancel() { cancelled.set(true); runCatching { connection.get()?.disconnect() } }
    }
    override suspend fun verify(key: AiSecret, isCurrent: () -> Boolean) {
        val call = Call(isCurrent)
        // Account lookup only: no chat generation and no automatic retry during verification.
        exchange(call, key, BALANCE, null, 8192, 0) { text, secret -> codec.verification(text,secret) }
    }
    internal suspend fun hints(request: WorkerHintRequest, keys: AiKeyConfigurationRepository, isCurrent: () -> Boolean = { true }): DeepSeekHintResult {
        if (!Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}").matches(request.requestId)) throw WorkerProblem("INVALID_INPUT")
        if (!pendingIds.add(request.requestId)) throw WorkerProblem("REQUEST_IN_PROGRESS")
        val call = Call(isCurrent)
        try {
            return keys.withConfiguredKey(mock = false, onRevoke = call::cancel) { key ->
                val body = codec.request(request)
                val execute: suspend () -> DeepSeekHintResult = {
                    exchange(call,key,CHAT,body,32768,1) { text, secret -> codec.hints(text,secret) }
                }
                usage?.task(execute) ?: execute()
            }
        } finally { pendingIds.remove(request.requestId) }
    }
    private suspend fun <T> exchange(call: Call, key: AiSecret, endpoint: String, body: String?, maxBytes: Int, maxRetries: Int,
        decode: (String,String) -> T): T = suspendCancellableCoroutine { continuation ->
        val secret = key.useValue { it }
        if (!Regex("[!-~]{16,512}").matches(secret) || secret.startsWith("TEST_ONLY_")) {
            continuation.resumeWith(Result.failure(WorkerProblem("KEY_INVALID"))); return@suspendCancellableCoroutine
        }
        calls.add(call)
        continuation.invokeOnCancellation { call.cancel() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            try {
                var attemptIndex = 0
                while (true) {
                    call.check()
                    try {
                        val result = attempt(call,endpoint,body,secret,maxBytes,decode)
                        call.check()
                        val adjusted = if (result is DeepSeekHintResult) result.copy(usage = result.usage.copy(httpRequests = attemptIndex + 1,retries = attemptIndex,
                            complete = attemptIndex == 0, promptTokens = result.usage.promptTokens.takeIf { attemptIndex == 0 },
                            completionTokens = result.usage.completionTokens.takeIf { attemptIndex == 0 }, totalTokens = result.usage.totalTokens.takeIf { attemptIndex == 0 })) else result
                        @Suppress("UNCHECKED_CAST") continuation.resumeWith(Result.success(adjusted as T))
                        break
                    } catch (e: WorkerProblem) {
                        call.check()
                        if (!e.retryable || attemptIndex++ >= maxRetries) throw e
                    }
                }
            } catch (e: WorkerProblem) { continuation.resumeWith(Result.failure(e))
            } catch (_: Exception) { continuation.resumeWith(Result.failure(WorkerProblem("UPSTREAM_UNAVAILABLE")))
            } finally { calls.remove(call) }
        })
    }
    private fun <T> attempt(call: Call, endpoint: String, body: String?, secret: String, maxBytes: Int, decode: (String,String) -> T): T {
        var connection: HttpURLConnection? = null
        try {
            call.check()
            connection = open(URL(endpoint)); call.attach(connection)
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 10000; connection.readTimeout = 30000
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Authorization","Bearer " + secret)
            connection.setRequestProperty("Accept","application/json")
            connection.setRequestProperty("Accept-Encoding","identity")
            connection.setRequestProperty("Cache-Control","no-store")
            call.check()
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type","application/json")
                val bytes = body.toByteArray(Charsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { call.check(); it.write(bytes) }
            }
            call.check()
            val status = connection.responseCode
            call.check()
            if (status != 200) throw when(status) {
                401 -> WorkerProblem("UPSTREAM_AUTH")
                403 -> WorkerProblem("UPSTREAM_FORBIDDEN")
                402 -> WorkerProblem("UPSTREAM_BILLING")
                429 -> WorkerProblem("UPSTREAM_RATE_LIMIT")
                500,502,503,504 -> WorkerProblem("UPSTREAM_UNAVAILABLE",true)
                in 300..399 -> WorkerProblem("INVALID_RESPONSE")
                else -> WorkerProblem("UPSTREAM_REQUEST_REJECTED")
            }
            if (connection.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) != "application/json" ||
                connection.contentEncoding?.let { !it.equals("identity",true) } == true || connection.contentLengthLong > maxBytes) throw WorkerProblem("INVALID_RESPONSE")
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { call.check(); val n = stream.read(buffer); if (n < 0) break; if (output.size() + n > maxBytes) throw WorkerProblem("INVALID_RESPONSE"); output.write(buffer,0,n) }
                output.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            call.check()
            return decode(text,secret)
        } catch (_: SocketTimeoutException) { call.check(); throw WorkerProblem("TRANSPORT_TIMEOUT",true)
        } catch (_: SSLException) { call.check(); throw WorkerProblem("SECURE_CONNECTION_FAILED")
        } catch (_: java.nio.charset.CharacterCodingException) { throw WorkerProblem("INVALID_RESPONSE")
        } catch (e: WorkerProblem) { throw e
        } catch (_: java.io.IOException) { call.check(); throw WorkerProblem("OFFLINE",true)
        } catch (_: Exception) { call.check(); throw WorkerProblem("INVALID_RESPONSE")
        } finally { call.detach(); runCatching { connection?.disconnect() } }
    }
    private companion object {
        const val CHAT = "https://api.deepseek.com/chat/completions"
        const val BALANCE = "https://api.deepseek.com/user/balance"
    }
}
