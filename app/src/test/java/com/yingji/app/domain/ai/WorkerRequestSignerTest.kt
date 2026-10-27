package com.yingji.app.domain.ai

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class WorkerRequestSignerTest {
    private val id = "approved_android_client"
    private val nonce = "12345678-1234-4123-8123-123456789012"
    @Test fun emptyUtf8DigestsAndLfSeparatorsMatchIndependentProtocolVector() {
        val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val actual = WorkerRequestSigner.signingText("GET", "https://local.test/v1/ai/quota", "Signature " + id, "1792400000000", nonce, "", "")
        val expected = listOf("YINGJI-BYOK-1", "GET", "https://local.test/v1/ai/quota", "Signature " + id, "1792400000000", nonce, emptyHash, emptyHash).joinToString(10.toChar().toString())
        assertEquals(expected, actual); assertEquals(7, actual.count { it.code == 10 }); assertFalse(actual.endsWith(10.toChar().toString()))
    }
    @Test fun canonicalSignatureBindsRawUnicodeBodyCredentialTargetAndMethod() {
        val body = "{\"query\":\"科幻😀\"}"
        val base = WorkerRequestSigner.signingText("POST", "https://local.test/v1/ai/recommend", "Signature " + id, "1792400000000", nonce, body, "TEST_ONLY_BYOK_one")
        assertNotEquals(base, WorkerRequestSigner.signingText("POST", "https://local.test/v1/ai/recommend", "Signature " + id, "1792400000000", nonce, body + " ", "TEST_ONLY_BYOK_one"))
        assertNotEquals(base, WorkerRequestSigner.signingText("POST", "https://other.test/v1/ai/recommend", "Signature " + id, "1792400000000", nonce, body, "TEST_ONLY_BYOK_one"))
        assertNotEquals(base, WorkerRequestSigner.signingText("GET", "https://local.test/v1/ai/recommend", "Signature " + id, "1792400000000", nonce, body, "TEST_ONLY_BYOK_one"))
        assertNotEquals(base, WorkerRequestSigner.signingText("POST", "https://local.test/v1/ai/recommend", "Signature " + id, "1792400000000", nonce, body, "TEST_ONLY_BYOK_two"))
        assertFalse(base.contains("TEST_ONLY_BYOK_one")); assertFalse(base.contains("科幻"))
    }
    @Test fun derConversionHandlesHighBitPaddingAndShortIntegers() {
        val high = byteArrayOf(0) + ByteArray(32) { 0x80.toByte() }
        val der = byteArrayOf(0x30, 40, 2, 33) + high + byteArrayOf(2, 3, 1, 2, 3)
        val raw = WorkerRequestSigner.derToRaw(der)
        assertArrayEquals(ByteArray(32) { 0x80.toByte() }, raw.copyOfRange(0,32))
        assertArrayEquals(ByteArray(29) + byteArrayOf(1,2,3), raw.copyOfRange(32,64))
    }
    @Test fun malformedDerIsRejectedInsteadOfSigningAnAmbiguousProof() {
        val valid = byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 2)
        for (der in listOf(byteArrayOf(), valid + byteArrayOf(0), byteArrayOf(0x30,6,2,1,0x80.toByte(),2,1,2), byteArrayOf(0x30,7,2,2,0,1,2,1,2), byteArrayOf(0x30,6,2,1,0,2,1,2))) {
            assertTrue(runCatching { WorkerRequestSigner.derToRaw(der) }.isFailure)
        }
    }
    @Test fun p1363ProofVerifiesWithJcaAndEveryAttemptGetsAFreshNonce() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = object : WorkerClientIdentity {
            override val clientId = id
            override fun sign(message: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(message); sign() }
        }
        val signer = WorkerRequestSigner(identity, clock = { 1792400000000 })
        val first = signer.headers("POST", "https://local.test/v1/ai/recommend", "{}", "TEST_ONLY_BYOK_one")
        val next = signer.headers("POST", "https://local.test/v1/ai/recommend", "{}", "TEST_ONLY_BYOK_one")
        assertNotEquals(first["X-Yingji-Nonce"], next["X-Yingji-Nonce"])
        val message = WorkerRequestSigner.signingText("POST", "https://local.test/v1/ai/recommend", first.getValue("Authorization"), first.getValue("X-Yingji-Timestamp"), first.getValue("X-Yingji-Nonce"), "{}", "TEST_ONLY_BYOK_one")
        assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(pair.public); update(message.toByteArray(Charsets.UTF_8)); verify(Base64.getUrlDecoder().decode(first.getValue("X-Yingji-Signature"))) })
        assertEquals(86, first.getValue("X-Yingji-Signature").length)
    }
    @Test fun unsafeIdentityFailsBeforeCallingSigner() {
        var called = false
        val signer = WorkerRequestSigner(object : WorkerClientIdentity { override val clientId = "bad\nheader"; override fun sign(message: ByteArray): ByteArray { called = true; return byteArrayOf() } })
        assertEquals("CONFIGURATION_ERROR", (runCatching { signer.headers("GET", "https://local.test/v1/ai/quota", "") }.exceptionOrNull() as WorkerProblem).code)
        assertFalse(called)
    }
}
