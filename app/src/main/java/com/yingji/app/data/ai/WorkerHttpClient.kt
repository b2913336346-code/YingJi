package com.yingji.app.data.ai

import com.yingji.app.BuildConfig
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class WorkerHttpClient(baseUrl: String, private val signer: WorkerRequestSigner, private val keys: AiKeyConfigurationRepository? = null, private val localDemo: Boolean = false, private val demoSession: String? = null) : AiWorkerService, AiWorkerKeyVerification {
    private val base = URI(baseUrl)
    private val codec = WorkerJsonCodec(localDemo)
    init {
        require(base.userInfo == null && base.rawQuery == null && base.rawFragment == null && base.rawPath in listOf("", "/"))
        val worker = base.scheme == "https" && base.host?.endsWith(".workers.dev") == true && base.port in listOf(-1, 443)
        val loopback = localDemo && BuildConfig.DEBUG && base.scheme == "http" && base.host in listOf("127.0.0.1", "localhost") && base.port in 1..65535
        require(worker || loopback) { "Only a configured Worker or debug loopback is allowed" }
        require(!localDemo || loopback)
        require(demoSession == null || (localDemo && Regex("[A-Za-z0-9_-]{1,64}").matches(demoSession)))
    }
    override fun toString() = "WorkerHttpClient(redacted)"
    override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse {
        val encoded = codec.encode(request)
        val repository = keys ?: throw WorkerProblem("KEY_REQUIRED")
        return repository.withConfiguredKey(localDemo) { key ->
            val (status, text) = exchange("/v1/ai/recommend", encoded, key)
            codec.decodeTask(text, status, request)
        }
    }
    override suspend fun quota(): AiQuotaSnapshot {
        val (status, text) = exchange("/v1/ai/quota", null)
        return codec.decodeQuota(text, status)
    }
    override suspend fun verify(key: AiSecret) {
        val id = java.util.UUID.randomUUID().toString()
        val body = org.json.JSONObject().put("schemaVersion", 1).put("requestId", id).toString()
        val (status, text) = exchange("/v1/ai/verify", body, key)
        codec.decodeVerification(text, status, id)
    }
    internal fun signingTarget(path: String): String = if (localDemo) "https://local.test" + path else "https://" + base.host.lowercase(java.util.Locale.ROOT) + path
    private suspend fun exchange(path: String, body: String?, key: AiSecret? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        // No real credentials are permitted in the debug loopback harness.
        val rawKey = key?.useValue { it }.orEmpty()
        if (key != null && (!Regex("[!-~]{16,512}").matches(rawKey) || (localDemo && !Regex("TEST_ONLY_BYOK_[A-Za-z0-9_-]{1,128}").matches(rawKey)))) throw WorkerProblem("KEY_INVALID")
        val method = if (body == null) "GET" else "POST"
        // The local harness preserves this controlled HTTPS origin when forwarding into workerd.
        val target = signingTarget(path)
        val proof = signer.headers(method, target, body.orEmpty(), rawKey)
        val connection = base.resolve(path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 5000; connection.readTimeout = 18000
            connection.requestMethod = method
            proof.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (key != null) connection.setRequestProperty("X-DeepSeek-Key", rawKey)
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Accept", "application/json")
            demoSession?.let { connection.setRequestProperty("X-Yingji-Mock-Session", it) }
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 300..399 || connection.getHeaderField("X-Yingji-Api-Contract") != "byok-1") throw WorkerProblem("INVALID_RESPONSE")
            if (connection.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json" || connection.contentEncoding?.let { it != "identity" } == true) throw WorkerProblem("INVALID_RESPONSE")
            if (connection.contentLengthLong > 32768) throw WorkerProblem("INVALID_RESPONSE")
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = (input ?: throw WorkerProblem("INVALID_RESPONSE")).use { stream ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val count = stream.read(buffer); if (count < 0) break; if (output.size() + count > 32768) throw WorkerProblem("INVALID_RESPONSE"); output.write(buffer, 0, count) }
                output.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            codec.rejectSecret(text, rawKey)
            status to text
        } catch (_: SocketTimeoutException) { throw WorkerProblem("TRANSPORT_TIMEOUT", true)
        } catch (e: WorkerProblem) { throw e
        } catch (_: java.nio.charset.CharacterCodingException) { throw WorkerProblem("INVALID_RESPONSE")
        } catch (_: java.io.IOException) { throw WorkerProblem("OFFLINE")
        } finally { connection.disconnect() }
    }
}
