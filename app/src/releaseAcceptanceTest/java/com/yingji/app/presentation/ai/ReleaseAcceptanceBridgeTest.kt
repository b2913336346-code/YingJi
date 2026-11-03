package com.yingji.app.presentation.ai

import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.YingJiApplication
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.*
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.*

/** Manual acceptance only. Neither secrets nor plaintext payloads leave the app process. */
internal object ReleaseAcceptanceGate {
    const val CLONE = "com.yingji.app.tmdb.uiacceptance"
    const val APPROVAL = "release-e2e-one-model-task"
    val app get() = ApplicationProvider.getApplicationContext<YingJiApplication>()
    fun nonce(): String {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("releaseApproved") == APPROVAL)
        return requireNotNull(args.getString("releaseNonce")).also { require(Regex("[a-f0-9]{32}").matches(it)) }
    }
    fun emit(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })
    fun bytes(value: String) = Base64.decode(value, Base64.NO_WRAP)
    fun encoded(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    fun digest(file: File): String? = file.takeIf { it.isFile }?.let { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) } }
    private val oaep get() = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    fun seal(raw: ByteArray, publicKey: PublicKey, nonce: String): JSONObject {
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }; val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv)); updateAAD((CLONE + ":" + nonce).toByteArray()) }
            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply { init(Cipher.ENCRYPT_MODE, publicKey, oaep) }
            return JSONObject().put("nonce", nonce).put("wrapped", encoded(rsa.doFinal(aes))).put("iv", encoded(iv)).put("ciphertext", encoded(cipher.doFinal(raw)))
        } finally { aes.fill(0) }
    }
    fun open(payload: JSONObject, key: PrivateKey, nonce: String): ByteArray {
        require(payload.getString("nonce") == nonce)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply { init(Cipher.DECRYPT_MODE, key, oaep) }
        val aes = rsa.doFinal(bytes(payload.getString("wrapped")))
        try { return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, bytes(payload.getString("iv")))); updateAAD((CLONE + ":" + nonce).toByteArray()); doFinal(bytes(payload.getString("ciphertext"))) } }
        finally { aes.fill(0) }
    }
}

class ReleaseAcceptanceBridgeTest {
    @Test fun syntheticEnvelopeRejectsTamperingAndWrongNonce() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val nonce = "a".repeat(32); val raw = "TEST_ONLY_RELEASE_BRIDGE".toByteArray()
        val payload = ReleaseAcceptanceGate.seal(raw, pair.public, nonce)
        assertArrayEquals(raw, ReleaseAcceptanceGate.open(payload, pair.private, nonce))
        assertFalse(payload.toString().contains("TEST_ONLY"))
        assertTrue(runCatching { ReleaseAcceptanceGate.open(payload, pair.private, "b".repeat(32)) }.isFailure)
        val changed = ReleaseAcceptanceGate.bytes(payload.getString("ciphertext")); changed[0] = (changed[0].toInt() xor 1).toByte()
        payload.put("ciphertext", ReleaseAcceptanceGate.encoded(changed))
        assertTrue(runCatching { ReleaseAcceptanceGate.open(payload, pair.private, nonce) }.isFailure)
    }
    @Test fun createCloneEnvelopePublicKey() {
        val nonce = ReleaseAcceptanceGate.nonce(); check(ReleaseAcceptanceGate.app.packageName == ReleaseAcceptanceGate.CLONE)
        val alias = "release-acceptance-" + nonce
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; check(!store.containsAlias(alias))
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT).setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
        }.generateKeyPair()
        ReleaseAcceptanceGate.emit("release_public", ReleaseAcceptanceGate.encoded(pair.public.encoded))
    }
    @Test fun exportOriginalAsCiphertextOnly() = runBlocking<Unit> {
        val nonce = ReleaseAcceptanceGate.nonce(); val app = ReleaseAcceptanceGate.app; check(app.packageName == "com.yingji.app")
        check(File(app.noBackupFilesDir, "release-export-consumed-" + nonce).createNewFile())
        val publicBytes = ReleaseAcceptanceGate.bytes(requireNotNull(InstrumentationRegistry.getArguments().getString("releasePublic")))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicBytes))
        require((publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength() == 2048)
        val modelFile = File(app.noBackupFilesDir, "ai-byok/config.enc"); val tokenFile = File(app.noBackupFilesDir, "tmdb-credentials/config.enc")
        val before = listOf(ReleaseAcceptanceGate.digest(modelFile), ReleaseAcceptanceGate.digest(tokenFile)); check(before.all { it != null })
        app.aiKeyConfigurationRepository.withConfiguredKey(false) { secret ->
            app.tmdbCredentialRepository.withCurrentToken { token, current ->
                check(current())
                val raw = secret.useValue { key -> token.useValue { value -> JSONObject().put("model", key).put("tmdb", value).toString().toByteArray() } }
                try { ReleaseAcceptanceGate.emit("release_envelope", ReleaseAcceptanceGate.encoded(ReleaseAcceptanceGate.seal(raw, publicKey, nonce).toString().toByteArray())) }
                finally { raw.fill(0) }
            }
        }
        check(before == listOf(ReleaseAcceptanceGate.digest(modelFile), ReleaseAcceptanceGate.digest(tokenFile)))
    }
    @Test fun installAndValidateInsideOwnedClone() = runBlocking<Unit> {
        val nonce = ReleaseAcceptanceGate.nonce(); val app = ReleaseAcceptanceGate.app; check(app.packageName == ReleaseAcceptanceGate.CLONE)
        check(!File(app.noBackupFilesDir, "ai-byok/config.enc").exists()); check(!File(app.noBackupFilesDir, "tmdb-credentials/config.enc").exists())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; val alias = "release-acceptance-" + nonce
        val payload = JSONObject(ReleaseAcceptanceGate.bytes(requireNotNull(InstrumentationRegistry.getArguments().getString("releaseEnvelope"))).toString(Charsets.UTF_8))
        val raw = ReleaseAcceptanceGate.open(payload, store.getKey(alias, null) as PrivateKey, nonce)
        try {
            val values = JSONObject(raw.toString(Charsets.UTF_8))
            app.tmdbCredentialRepository.save(values.getString("tmdb"))
            app.aiKeyConfigurationRepository.verifyAndSave(values.getString("model"))
            assertTrue(app.aiKeyConfigurationRepository.state.value.canRequestLiveAi)
        } finally { raw.fill(0); store.deleteEntry(alias) }
        check(!store.containsAlias(alias)); check(app.localAiUsageRepository.snapshot().started == 0L)
        app.categoryRepository.ensureSystemCategories(); app.categoryRepository.addTag("Release验收标签")
        app.mockAiHomeRepository.saveSetup(AiDemoSetup())
        ReleaseAcceptanceGate.emit("release_metrics", JSONObject().put("liveKeyValidated", true).put("privateRelayKeyRemoved", true).put("modelTasks", 0).toString())
    }

    @Test fun inspectOwnedCloneState() = runBlocking<Unit> {
        ReleaseAcceptanceGate.nonce(); val app = ReleaseAcceptanceGate.app; check(app.packageName == ReleaseAcceptanceGate.CLONE)
        val db = com.yingji.app.data.database.DatabaseModule.provideDatabase(app)
        val result = JSONObject().put("package", app.packageName)
        val movies = org.json.JSONArray()
        for (row in app.movieRepository.observeAll().first()) {
            val nameHash = MessageDigest.getInstance("SHA-256").digest(row.name.toByteArray()).joinToString("") { "%02x".format(it) }
            val item = JSONObject().put("id", row.id).put("nameSha256", nameHash).put("status", row.status).put("rating", row.rating ?: JSONObject.NULL).put("hasPoster", row.posterPath != null).put("watchSessions", db.watchHistoryDao().getForMovie(row.id).size).put("endedWatchSessions", db.watchHistoryDao().getForMovie(row.id).count { it.endTime != null })
                .put("categories", org.json.JSONArray(db.categoryDao().getCategoryIdsForMovie(row.id))).put("tags", org.json.JSONArray(db.tagDao().getTagIdsForMovie(row.id)))
            db.openHelper.readableDatabase.query("SELECT sourceId, externalId FROM ai_candidate_imports WHERE movieId=?", arrayOf(row.id)).use { c -> if(c.moveToFirst()) item.put("source", c.getString(0)).put("externalIdSha256", MessageDigest.getInstance("SHA-256").digest(c.getString(1).toByteArray()).joinToString("") { "%02x".format(it) }) }
            movies.put(item)
        }
        val integrity = db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) }
        val usage = app.localAiUsageRepository.snapshot()
        result.put("movies", movies).put("integrity", integrity).put("started", usage.started).put("succeeded", usage.succeeded).put("failed", usage.failed)
            .put("modelCipherSha256", ReleaseAcceptanceGate.digest(File(app.noBackupFilesDir,"ai-byok/config.enc")) ?: JSONObject.NULL).put("tmdbCipherSha256", ReleaseAcceptanceGate.digest(File(app.noBackupFilesDir,"tmdb-credentials/config.enc")) ?: JSONObject.NULL)
        ReleaseAcceptanceGate.emit("release_metrics", result.toString())
        assertEquals("ok", integrity)
    }

    @Test fun seedOwnedLoadFixture() = runBlocking<Unit> {
        val nonce = ReleaseAcceptanceGate.nonce(); val app = ReleaseAcceptanceGate.app; check(app.packageName == ReleaseAcceptanceGate.CLONE)
        check(File(app.noBackupFilesDir, "release-load-consumed-" + nonce).createNewFile())
        val before = app.movieRepository.observeAll().first(); check(before.size == 3)
        val category = app.categoryRepository.observeCategories().first().single { it.name == "电影" }
        val tagName = InstrumentationRegistry.getArguments().getString("releaseFixtureTagName") ?: "Release验收标签"
        require(tagName.isNotBlank() && tagName.length <= 64)
        val tag = app.categoryRepository.observeTags().first().single { it.name == tagName }
        val directory = File(app.filesDir, "release-owned-load").apply { check(mkdir()) }
        repeat(30) { i ->
            val bitmap = android.graphics.Bitmap.createBitmap(600, 900, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(25 + i * 5, 25 + i * 5, 25 + i * 5))
            val poster = File(directory, "fixture-" + i + ".png")
            try { poster.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
            app.movieRepository.insertWithRelations(com.yingji.app.data.entity.Movie(name = "Load" + i.toString().padStart(3,'0'), type = category.name, status = "想看", createTime = System.currentTimeMillis() - i * 86400000L, posterPath = poster.absolutePath), category.id, if(i % 2 == 0) setOf(tag.id) else emptySet())
        }
        assertEquals(33, app.movieRepository.observeAll().first().size)
        ReleaseAcceptanceGate.emit("release_metrics", JSONObject().put("fixtureWorks", 30).put("posterDimensions", "600x900").put("totalWorks", 33).put("usageStarted", app.localAiUsageRepository.snapshot().started).toString())
    }
}
