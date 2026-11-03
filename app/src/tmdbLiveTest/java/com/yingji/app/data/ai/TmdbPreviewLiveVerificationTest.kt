package com.yingji.app.data.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.ai.*
import com.yingji.app.ui.theme.YingJiTheme
import java.io.*
import java.net.HttpURLConnection
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
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Explicit opt-in source set only. Real data is never written to screenshots, test output or history. */
class TmdbPreviewLiveVerificationTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
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

    private fun hashes(context: Context) = listOf("ai-byok/config.enc", "tmdb-credentials/config.enc").associateWith { name ->
        File(context.noBackupFilesDir, name).takeIf { it.isFile }?.let { file ->
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
    }
    private fun write(context: Context, name: String, json: JSONObject) {
        val output = File(context.filesDir, "tmdb-preview-live-results/" + name + ".json")
        output.parentFile!!.mkdirs(); output.writeText(json.toString())
    }
    private class FixtureConnection(url: URL, private val body: String) : HttpURLConnection(url) {
        override fun connect() {}; override fun disconnect() {}; override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getContentType() = "application/json"
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
    private fun fixture(url: URL): HttpURLConnection {
        val path = url.path; val id = path.substringAfterLast('/').toIntOrNull()
        val body = if (path.contains("/discover/")) """{"page":1,"results":[{"id":7},{"id":8},{"id":9}]}"""
        else if (path.contains("/movie/")) """{"id":$id,"title":"TEST_ONLY synthetic preview movie","runtime":90,"release_date":"2024-01-01"}"""
        else """{"id":$id,"name":"TEST_ONLY synthetic preview TV","first_air_date":"2023-01-01","episode_run_time":[45]}"""
        return FixtureConnection(url, body)
    }
    private fun removeOwnedConfiguration(context: Context, directory: File, originalAlias: String? = null) {
        require(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile &&
            Regex("tmdb-preview-live-[a-f0-9]{32}").matches(directory.name))
        if (!directory.exists()) return
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val store = TmdbCredentialStore(isolated)
        runBlocking { TmdbCredentialRepository(store).delete(); store.read().use { assertNull("TEMP_TOKEN_NOT_REMOVED", it!!.key) } }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        originalAlias?.let { assertFalse("OLD_ALIAS_RETAINED", keys.containsAlias(it)) }
        val file = File(directory, "tmdb-credentials/config.enc")
        val tombstone = DataInputStream(file.inputStream()).use { it.readInt(); it.readUTF() }
        check(tombstone.startsWith("yingji.tmdb.v1.")); keys.deleteEntry(tombstone)
        check(file.delete()); check(file.parentFile!!.delete()); check(directory.delete())
    }
    @Test fun cleanupOwnedTemporaryFixtureWithoutNetwork() {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("tmdbPreviewCleanupApproved") == "owned-directory-only")
        val nonce = requireNotNull(args.getString("tmdbNonce")); require(Regex("[a-f0-9]{32}").matches(nonce))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val beforeFile = File(context.filesDir, "tmdb-preview-live-results/" + nonce + "-before.json")
        require(beforeFile.isFile)
        val before = JSONObject(beforeFile.readText()).getJSONObject("productionCredentialHashes")
        removeOwnedConfiguration(context, File(context.noBackupFilesDir, "tmdb-preview-live-" + nonce))
        val current = hashes(context)
        val unchanged = current.all { (name, value) -> if (value == null) before.isNull(name) else before.optString(name) == value }
        write(context, nonce + "-cleanup", JSONObject().put("temporaryConfigurationRemoved", true)
            .put("productionCredentialFilesUnchanged", unchanged).put("networkRequestSent", false))
        assertTrue("PRODUCTION_CREDENTIAL_FILES_CHANGED", unchanged)
    }
    private fun verify(live: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        if (live) require(args.getString("tmdbPreviewLiveApproved") == "read-only-eight-attempts")
        val nonce = args.getString("tmdbNonce") ?: java.util.UUID.randomUUID().toString().replace("-", "")
        require(Regex("[a-f0-9]{32}").matches(nonce))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = hashes(context)
        write(context, nonce + "-before", JSONObject().put("productionCredentialHashes", JSONObject(before.mapValues { it.value ?: JSONObject.NULL })))
        val directory = File(context.noBackupFilesDir, "tmdb-preview-live-" + nonce)
        require(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile && !directory.exists())
        check(directory.mkdir())
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val store = TmdbCredentialStore(isolated); val credentials = TmdbCredentialRepository(store)
        val file = File(directory, "tmdb-credentials/config.enc")
        val models = ViewModelStore(); val opened = mutableStateOf(true)
        val attempts = AtomicInteger(); var originalAlias: String? = null
        var complete = false; var cleanup = false; var displayed = false; var closed = false; var reopened = false
        var rowsCount = 0; var typedMovies = false; var typedTv = false; var tvRuntimeUnknown = false
        fun alias() = DataInputStream(file.inputStream()).use { it.readInt(); it.readUTF() }
        val summary = JSONObject().put("mode", if (live) "LIVE" else "PROTOCOL_MOCK").put("maxHttpAttempts", 8)
            .put("paidModelRequests", 0).put("profileTransferred", false).put("metadataPersisted", false)
            .put("screenshotsCaptured", false).put("homepageSourceActivated", false)
        try {
            val raw = if (live) {
                val port = requireNotNull(args.getString("tmdbBrokerPort")).toInt(); require(port in 1024..65535)
                receive(port, nonce)
            } else "TEST_ONLY_TMDB_preview_handoff".toByteArray()
            try {
                runBlocking { credentials.save(raw.toString(Charsets.UTF_8)) }; originalAlias = alias()
                val encrypted = file.readBytes()
                assertFalse("PLAINTEXT_CREDENTIAL_AT_REST", encrypted.indices.any { i -> i + raw.size <= encrypted.size && raw.indices.all { encrypted[i + it] == raw[it] } })
                summary.put("credentialEncryptedAtRest", true)
            } finally { raw.fill(0) }
            val client = TmdbHttpClient(true) { url ->
                check(url.protocol == "https" && url.host == "api.themoviedb.org" && url.port == -1 && url.userInfo == null)
                // Counts retries too; an unexpected ninth request is blocked before opening a socket.
                check(attempts.get() < 8) { "PREVIEW_REQUEST_BUDGET_EXHAUSTED" }; attempts.incrementAndGet()
                if (live) url.openConnection() as HttpsURLConnection else fixture(url)
            }
            val authenticated = TmdbAuthenticatedTransport(client, credentials)
            val transport = object : TmdbCatalogTransport by authenticated { override val isMock = !live }
            val catalog = TmdbCandidateCatalogRepository(transport, { TmdbSourcePermission(CandidateLicenseState.ACTIVE,
                if (live) "User-attested non-commercial TMDB display permission recorded 2026-10-23" else "App-authored synthetic protocol fixture permission",
                aiRecommendationAllowed = true, displayAllowed = true, importAllowed = false) })
            lateinit var vm: TmdbCredentialViewModel
            ui.runOnUiThread { vm = TmdbCredentialViewModel(credentials, TmdbCandidatePreview(catalog, allowMock = !live)); models.put("tmdb-preview", vm) }
            ui.setContent { YingJiTheme {
                if (opened.value) TmdbCredentialDialog(vm) { opened.value = false }
                else TextButton(onClick = { opened.value = true }, modifier = Modifier.testTag("tmdb_live_reopen")) { Text("预览已关闭") }
            } }
            ui.waitUntil(10000) { vm.state.value.status != TmdbCredentialStatus.LOADING }
            assertEquals("OPENING_MUST_NOT_READ", 0, attempts.get())
            ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag("tmdb_preview_read"))
            ui.onNodeWithTag("tmdb_preview_read").performClick()
            ui.waitUntil(120000) { vm.preview.value.status !in setOf(TmdbPreviewStatus.IDLE, TmdbPreviewStatus.LOADING) }
            assertEquals("PREVIEW_MUST_SUCCEED", TmdbPreviewStatus.SUCCESS, vm.preview.value.status)
            val rows = vm.preview.value.rows; rowsCount = rows.size
            assertTrue("CANDIDATE_COUNT_OUT_OF_BOUND", rows.size in 1..6)
            assertTrue("TITLE_OR_RETRIEVAL_TIME_MISSING", rows.all { !it.title.isNullOrBlank() && it.verifiedAt > 0 })
            typedMovies = rows.any { it.identity.externalId.startsWith("movie:") }
            typedTv = rows.any { it.identity.externalId.startsWith("tv:") }
            tvRuntimeUnknown = rows.filter { it.identity.externalId.startsWith("tv:") }.all { it.runtimeMinutes == null }
            assertTrue("BOTH_TYPED_MEDIA_EXPECTED", typedMovies && typedTv); assertTrue("TV_TOTAL_RUNTIME_MUST_NOT_BE_INVENTED", tvRuntimeUnknown)
            assertTrue("UNEXPECTED_SOURCE_OR_CACHE", rows.all { it.identity.sourceId == if (live) "tmdb" else "tmdb-protocol-mock" } && rows.all { it.cache.state == CandidateCacheState.FRESH })
            val firstTag = "tmdb_preview_" + rows.first().identity.externalId
            ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag(firstTag))
            displayed = ui.onAllNodesWithTag(firstTag).fetchSemanticsNodes().any { !it.boundsInRoot.isEmpty }
            assertTrue("VERIFIED_CARD_NOT_DISPLAYED", displayed)
            if (!live) { assertEquals("NORMAL_CATALOG_REQUEST_COUNT", 8, attempts.get()); assertEquals(6, rows.size) }
            ui.onNodeWithTag("tmdb_config_close").performClick()
            ui.onNodeWithTag("tmdb_live_reopen").assertIsDisplayed(); ui.waitForIdle()
            closed = vm.preview.value.status == TmdbPreviewStatus.IDLE && vm.preview.value.rows.isEmpty()
            assertTrue("CLOSE_MUST_CLEAR_MEMORY", closed)
            val requestCount = attempts.get(); ui.onNodeWithTag("tmdb_live_reopen").performClick()
            ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag("tmdb_preview_status"))
            reopened = vm.preview.value.status == TmdbPreviewStatus.IDLE && vm.preview.value.rows.isEmpty() && attempts.get() == requestCount
            assertTrue("REOPEN_MUST_NOT_READ_OR_RESTORE_RESULTS", reopened)
            complete = true
        } catch (error: Throwable) {
            summary.put("problem", when (error) { is TmdbProblem -> error.code.name; is AssertionError -> "UI_OR_VALIDATION_ASSERTION_FAILED"; else -> "LOCAL_OR_TRANSPORT_FAILURE" })
        } finally {
            try {
                ui.runOnUiThread { models.clear(); opened.value = false }; ui.waitForIdle()
                removeOwnedConfiguration(context, directory, originalAlias); cleanup = true
            } catch (_: Throwable) { summary.put("cleanupProblem", "TEMPORARY_CREDENTIAL_CLEANUP_FAILED") }
            summary.put("attemptsOpened", attempts.get()).put("candidateCount", rowsCount).put("movieIdentityValidated", typedMovies)
                .put("tvIdentityValidated", typedTv).put("tvTotalRuntimeUnknown", tvRuntimeUnknown).put("cardDisplayed", displayed)
                .put("closeClearedMemory", closed).put("reopenDidNotRead", reopened).put("passed", complete)
                .put("temporaryConfigurationRemoved", cleanup).put("productionCredentialFilesUnchanged", hashes(context) == before)
            write(context, nonce, summary)
        }
        assertTrue("PREVIEW_VERIFICATION_FAILED: " + summary.optString("problem", "NONE"), complete)
        assertTrue("TEMPORARY_CREDENTIAL_CLEANUP_FAILED", cleanup)
        assertTrue("PRODUCTION_CREDENTIAL_FILES_CHANGED", hashes(context) == before)
    }
    @Test fun protocolMockTraversesPreviewUiAndEncryptedStoreWithoutNetwork() = verify(false)
    @Test fun realPreviewUsesAtMostEightReadsAndClearsTemporaryState() = verify(true)
}
