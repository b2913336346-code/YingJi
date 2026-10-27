package com.yingji.app.domain.ai

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

interface WorkerClientIdentity {
    val clientId: String
    /** Return SHA256withECDSA in ASN.1 DER format. Private key stays in Android Keystore. */
    fun sign(message: ByteArray): ByteArray
}
class WorkerRequestSigner(private val identity: WorkerClientIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonces: () -> String = { UUID.randomUUID().toString() }) {
    fun headers(method: String, target: String, body: String, key: String = ""): Map<String, String> {
        val id = identity.clientId
        if (!Regex("[A-Za-z0-9_-]{8,100}").matches(id)) throw WorkerProblem("CONFIGURATION_ERROR")
        val time = clock().toString(); val nonce = nonces()
        if (!Regex("[0-9]{13}").matches(time) || !Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}").matches(nonce)) throw WorkerProblem("CONFIGURATION_ERROR")
        val authorization = "Signature " + id
        val message = signingText(method, target, authorization, time, nonce, body, key)
        val signature = try { derToRaw(identity.sign(message.toByteArray(Charsets.UTF_8))) }
            catch (_: Exception) { throw WorkerProblem("CLIENT_IDENTITY_UNAVAILABLE") }
        return mapOf("Authorization" to authorization, "X-Yingji-Auth-Version" to "1", "X-Yingji-Timestamp" to time,
            "X-Yingji-Nonce" to nonce, "X-Yingji-Signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
    }
    companion object {
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun signingText(method: String, target: String, authorization: String, time: String, nonce: String, body: String, key: String): String =
            listOf("YINGJI-BYOK-1", method, target, authorization, time, nonce, sha256(body), sha256(key)).joinToString("\n")
        /** Strict DER ECDSA -> WebCrypto P1363 (32-byte unsigned r followed by s). */
        fun derToRaw(der: ByteArray): ByteArray {
            require(der.size in 8..72 && der[0].toInt() == 0x30 && (der[1].toInt() and 255) == der.size - 2)
            var offset = 2
            fun integer(): ByteArray {
                require(offset + 2 <= der.size && der[offset++].toInt() == 2)
                val length = der[offset++].toInt() and 255
                require(length in 1..33 && offset + length <= der.size)
                val bytes = der.copyOfRange(offset, offset + length); offset += length
                require(bytes[0].toInt() >= 0)
                require(length == 1 || bytes[0].toInt() != 0 || bytes[1].toInt() < 0)
                val value = if (length > 1 && bytes[0].toInt() == 0) bytes.copyOfRange(1, length) else bytes
                require(value.size <= 32 && value.any { it.toInt() != 0 })
                return ByteArray(32 - value.size) + value
            }
            val raw = integer() + integer(); require(offset == der.size); return raw
        }
    }
}
