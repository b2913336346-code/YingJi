package com.yingji.app.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.yingji.app.domain.ai.WorkerClientIdentity
import com.yingji.app.domain.ai.WorkerProblem
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject

/** Independent signing identity; deleting a model Key does not reset the server owner or task ledger. */
class AndroidKeystoreWorkerIdentity(private val context: Context) : WorkerClientIdentity {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun publicKey(): ECPublicKey = synchronized(lock) {
        try {
            val store = store()
            if (!store.containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
            store().getCertificate(ALIAS).publicKey as ECPublicKey
        } catch (_: Exception) { throw WorkerProblem("CLIENT_IDENTITY_UNAVAILABLE") }
    }
    override val clientId: String get() = "android_" + MessageDigest.getInstance("SHA-256").digest(publicKey().encoded).joinToString("") { "%02x".format(it) }
    override fun sign(message: ByteArray): ByteArray {
        publicKey()
        try {
            val key = store().getKey(ALIAS, null) as PrivateKey
            return Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message); sign() }
        } catch (_: Exception) { throw WorkerProblem("CLIENT_IDENTITY_UNAVAILABLE") }
    }
    /** Public material only. Operator approval is performed outside the app; no enrollment HTTP request. */
    fun publicPin(): String {
        val key = publicKey()
        fun coordinate(value: BigInteger): String {
            val unsigned = value.toByteArray().let { if (it.size == 33 && it[0].toInt() == 0) it.copyOfRange(1, 33) else it }
            require(unsigned.size <= 32)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32 - unsigned.size) + unsigned)
        }
        return JSONObject().put("id", clientId).put("jwk", JSONObject().put("kty", "EC").put("crv", "P-256")
            .put("x", coordinate(key.w.affineX)).put("y", coordinate(key.w.affineY))).toString()
    }
    override fun toString() = "AndroidKeystoreWorkerIdentity(public-key-only)"
    companion object { private val lock = Any(); internal const val ALIAS = "yingji.byok.client-signing.v1" }
}
