package com.yingji.app.data.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import java.io.*
import java.net.Socket
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Manual opt-in source set only: never part of the default androidTest APK. No embedded token. */
class TmdbLiveReadOnlyVerificationTest {
    private fun receive(port: Int, nonce: String): ByteArray {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val request = JSONObject().put("nonce", nonce).put("publicKey", android.util.Base64.encodeToString(pair.public.encoded, android.util.Base64.NO_WRAP))
        val response = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10000
            socket.getOutputStream().write((request.toString() + "\n").toByteArray(Charsets.UTF_8)); socket.getOutputStream().flush()
            val input = socket.getInputStream(); val bytes = ByteArrayOutputStream()
            while (true) { val n = input.read(); if (n == 10) break; if (n < 0 || bytes.size() >= 8192) throw IOException("HANDOFF_FAILED"); bytes.write(n) }
            JSONObject(bytes.toString("UTF-8"))
        }
        fun decode(name: String) = android.util.Base64.decode(response.getString(name), android.util.Base64.NO_WRAP)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.DECRYPT_MODE, pair.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val aes = rsa.doFinal(decode("wrapped"))
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, decode("iv")))
            cipher.updateAAD(nonce.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(decode("ciphertext") + decode("tag"))
        } finally { aes.fill(0) }
    }
    @Test fun fourReadOnlyRequestsUseTemporaryKeystoreAndNeverActivateHomeSource() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("tmdbLiveApproved") == "read-only-four-requests")
        val nonce = requireNotNull(args.getString("tmdbNonce")); require(Regex("[a-f0-9]{32}").matches(nonce))
        val port = requireNotNull(args.getString("tmdbBrokerPort")).toInt(); require(port in 1024..65535)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "tmdb-live-" + nonce)
        require(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile && !directory.exists())
        check(directory.mkdir())
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val store = TmdbCredentialStore(isolated); val repo = TmdbCredentialRepository(store)
        val file = File(directory, "tmdb-credentials/config.enc")
        val summary = JSONObject().put("maxRequests", 4).put("provider", "TMDB").put("paidModelRequests", 0)
            .put("homepageSourceActivated", false).put("profileTransferred", false).put("detailsPersisted", false)
        val mainPaths = listOf("ai-byok/config.enc", "tmdb-credentials/config.enc")
        fun mainHashes() = mainPaths.associateWith { path -> File(context.noBackupFilesDir, path).takeIf { it.isFile }?.let {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        } }
        val before = mainHashes(); val attempts = AtomicInteger(); val outcomes = JSONArray()
        var complete = false; var cleanup = false; var originalAlias: String? = null
        fun alias() = DataInputStream(file.inputStream()).use { it.readInt(); it.readUTF() }
        try {
            val raw = receive(port, nonce)
            try {
                repo.save(raw.toString(Charsets.UTF_8)); originalAlias = alias()
                assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status)
                val encrypted = file.readBytes()
                val containsPlain = encrypted.indices.any { index -> index + raw.size <= encrypted.size && raw.indices.all { encrypted[index + it] == raw[it] } }
                assertFalse("PLAINTEXT_CREDENTIAL_AT_REST", containsPlain)
                summary.put("credentialEncryptedAtRest", true)
            } finally { raw.fill(0) }
            val client = TmdbHttpClient(true) { url: URL ->
                check(url.protocol == "https" && url.host == "api.themoviedb.org" && url.port == -1 && url.userInfo == null)
                // Includes retry opens; even an unexpected fifth attempt cannot open a socket.
                check(attempts.get() < 4) { "REQUEST_BUDGET_EXHAUSTED" }; attempts.incrementAndGet()
                url.openConnection() as HttpsURLConnection
            }
            val codec = TmdbCatalogJsonCodec()
            for (kind in TmdbMediaKind.entries) {
                val ids = codec.ids(client.read(TmdbCatalogRequest(kind), repo))
                assertTrue("NO_CANDIDATE_IN_DISCOVERY", ids.isNotEmpty())
                val request = TmdbCatalogRequest(kind, ids.first())
                val row = codec.detail(client.read(request, repo), request, "tmdb", System.currentTimeMillis())
                assertTrue("DETAIL_NOT_ELIGIBLE", row != null)
                assertTrue("TYPED_ID_MISMATCH", row!!.identity.externalId == kind.path + ":" + ids.first())
                assertTrue("TITLE_MISSING", !row.title.isNullOrBlank())
                if (kind == TmdbMediaKind.TV) assertNull("EPISODE_RUNTIME_IS_NOT_TOTAL_RUNTIME", row.runtimeMinutes)
                outcomes.put(JSONObject().put("kind", kind.path).put("idMatched", true).put("titlePresent", true)
                    .put("retrievalTimeRecorded", row.verifiedAt > 0).put("tvTotalRuntimeUninvented", kind != TmdbMediaKind.TV || row.runtimeMinutes == null))
            }
            assertEquals(4, attempts.get()); complete = true
        } catch (e: Throwable) {
            summary.put("problem", when (e) { is TmdbProblem -> e.code.name; is AssertionError -> "VALIDATION_ASSERTION_FAILED"; else -> "LOCAL_OR_TRANSPORT_FAILURE" })
        } finally {
            try {
                repo.delete(); store.read().use { assertNull("TOKEN_DELETION_FAILED", it!!.key) }
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                originalAlias?.let { assertFalse("OLD_DECRYPTION_KEY_RETAINED", keys.containsAlias(it)) }
                val tombstone = alias(); check(tombstone.startsWith("yingji.tmdb.v1.")); keys.deleteEntry(tombstone)
                check(file.delete()); check(file.parentFile!!.delete()); check(directory.delete()); cleanup = true
            } catch (_: Throwable) { summary.put("cleanupProblem", "TEMPORARY_CREDENTIAL_CLEANUP_FAILED") }
            summary.put("requestsOpened", attempts.get()).put("checks", outcomes).put("readOnlyValidationPassed", complete)
                .put("temporaryConfigurationRemoved", cleanup).put("existingCredentialFilesUnchanged", mainHashes() == before)
            val output = File(context.filesDir, "tmdb-live-results/" + nonce + ".json"); output.parentFile!!.mkdirs(); output.writeText(summary.toString())
        }
        assertTrue("TMDB_READ_ONLY_VALIDATION_FAILED: " + summary.optString("problem", "NONE"), complete)
        assertTrue("TEMPORARY_CREDENTIAL_NOT_REMOVED", cleanup)
        assertTrue("EXISTING_CREDENTIAL_FILES_CHANGED", mainHashes() == before)
    }
}
