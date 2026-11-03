package com.yingji.app.data.ai

import android.net.ConnectivityManager
import android.os.Process
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.MainActivity
import com.yingji.app.YingJiApplication
import com.yingji.app.domain.ai.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Manual opt-in only. Uses actual MainActivity/configuration, never logs/enters a real token into UI. */
class TmdbProductionVerificationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
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


    private fun digest(file: File): String? = if (!file.isFile) null else
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun text(tag: String): String = ui.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun scroll(tag: String) { ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag(tag)) }
    private fun open() {
        ui.waitUntil(15000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open"))
        ui.onNodeWithTag("ai_manage_open").performClick()
        ui.waitUntil(10000) { ui.onAllNodes(hasTestTag("ai_manage_tmdb_config") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("ai_manage_tmdb_config").performClick()
        scroll("tmdb_preview_status"); assertEquals("OPEN_MUST_BE_IDLE", "尚未读取候选", text("tmdb_preview_status"))
    }
    @Test fun productionConfigurationIsVerifiedInExplicitPhases() {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("tmdbProductionApproved") == "persist-tmdb-token-and-verify")
        val nonce = requireNotNull(args.getString("tmdbNonce")); require(Regex("[a-f0-9]{32}").matches(nonce))
        val phase = requireNotNull(args.getString("tmdbProductionPhase")); require(phase in setOf("install", "exercise", "restore", "restart"))
        val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
        val repo = app.tmdbCredentialRepository; val store = TmdbCredentialStore(app)
        val file = File(app.noBackupFilesDir, "tmdb-credentials/config.enc")
        val model = File(app.noBackupFilesDir, "ai-byok/config.enc")
        val directory = File(app.filesDir, "tmdb-production-results"); directory.mkdirs()
        val stateFile = File(directory, nonce + "-state.json")
        val state = if (stateFile.isFile) JSONObject(stateFile.readText()) else JSONObject()
        val result = JSONObject().put("phase", phase).put("pid", Process.myPid()).put("deepSeekRequests", 0)
            .put("homepageSourceActivated", false).put("screenshotsCaptured", false).put("metadataPersisted", false)
        var complete = false
        try {
            runBlocking { repo.refresh() }
            assertTrue("HOMEPAGE_CATALOG_CHANGED", app.candidateCatalog is MockCandidateCatalogRepository)
            if (phase == "install" || phase == "restore") {
                if (phase == "install") {
                    require(!stateFile.exists()); require(repo.state.value.status == TmdbCredentialStatus.NOT_CONFIGURED)
                    state.put("nonce", nonce).put("modelDigest", digest(model) ?: JSONObject.NULL)
                    stateFile.writeText(state.toString())
                } else require(state.optString("nonce") == nonce)
                val port = requireNotNull(args.getString("tmdbBrokerPort")).toInt(); require(port in 1024..65535)
                val raw = receive(port, nonce)
                try {
                    val existing = runBlocking { store.read() }
                    val same = existing.use { record -> record?.key?.useValue { it == raw.toString(Charsets.UTF_8) } ?: false }
                    if (repo.state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED) {
                        require(same) { "EXISTING_DIFFERENT_CREDENTIAL_PRESERVED" }
                    } else runBlocking { repo.save(raw.toString(Charsets.UTF_8)) }
                    val encrypted = file.readBytes()
                    assertFalse("PLAINTEXT_CREDENTIAL_AT_REST", encrypted.indices.any { i -> i + raw.size <= encrypted.size && raw.indices.all { encrypted[i + it] == raw[it] } })
                    runBlocking { store.read().use { assertTrue("SAVED_VALUE_MISMATCH", it!!.key!!.useValue { value -> value == raw.toString(Charsets.UTF_8) }) } }
                    state.put("fileDigest", digest(file)).put("revision", repo.state.value.revision).put("savedAt", repo.state.value.savedAt)
                    state.put("restoreRequired", false); stateFile.writeText(state.toString())
                    result.put("encryptedAtRest", true).put("productionCredentialSaved", true).put("existingDifferentCredentialOverwritten", false)
                } finally { raw.fill(0) }
            } else {
                require(state.optString("nonce") == nonce)
                assertEquals("CONFIGURATION_NOT_RESTORED", TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status)
                assertEquals("CONFIGURATION_BYTES_CHANGED", state.getString("fileDigest"), digest(file))
                assertEquals("CONFIGURATION_REVISION_CHANGED", state.getString("revision"), repo.state.value.revision)
                assertEquals("CONFIGURATION_TIME_CHANGED", state.getLong("savedAt"), repo.state.value.savedAt)
                open()
                scroll("tmdb_token_input")
                val input = ui.onNodeWithTag("tmdb_token_input").fetchSemanticsNode().config
                assertTrue("TOKEN_FIELD_NOT_MASKED", input.contains(SemanticsProperties.Password))
                assertTrue("SAVED_TOKEN_ECHOED", input[SemanticsProperties.EditableText].text.isEmpty())
                if (phase == "exercise") {
                    scroll("tmdb_preview_read"); ui.onNodeWithTag("tmdb_preview_read").performClick()
                    scroll("tmdb_preview_status")
                    ui.waitUntil(120000) { val value = text("tmdb_preview_status"); value.isNotEmpty() && value != "正在校验 TMDB 候选" && value != "尚未读取候选" }
                    val count = Regex("已校验 ([1-6]) 项").find(text("tmdb_preview_status"))?.groupValues?.get(1)?.toInt()
                    assertNotNull("REAL_PREVIEW_NOT_SUCCESSFUL", count); result.put("candidateCount", count)
                    for (kind in listOf("movie:", "tv:")) {
                        val matcher = SemanticsMatcher("typed TMDB card") { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tmdb_preview_" + kind) == true }
                        ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(matcher)
                        assertTrue("TYPED_CARD_NOT_VISIBLE", ui.onAllNodes(matcher).fetchSemanticsNodes().any { !it.boundsInRoot.isEmpty })
                    }
                    result.put("movieAndTvCardsDisplayed", true).put("readActions", 1).put("httpAttemptUpperBound", 16)
                    ui.onNodeWithTag("tmdb_config_close").performClick(); ui.onNodeWithTag("ai_manage_tmdb_config").performClick()
                    scroll("tmdb_preview_status"); assertEquals("CLOSE_DID_NOT_CLEAR", "尚未读取候选", text("tmdb_preview_status"))
                    result.put("closeAndReopenClearedPreview", true)
                    val before = digest(file)
                    scroll("tmdb_token_input"); ui.onNodeWithTag("tmdb_token_input").performTextInput(" ")
                    scroll("tmdb_token_save"); ui.onNodeWithTag("tmdb_token_save").performClick()
                    ui.waitUntil(10000) { repo.state.value.error == TmdbCredentialProblem.EMPTY_INPUT }
                    scroll("tmdb_config_message"); assertTrue("FAILED_SAVE_MESSAGE_MISSING", text("tmdb_config_message").contains("空白输入不会保存"))
                    assertEquals("FAILED_SAVE_OVERWROTE_CREDENTIAL", before, digest(file))
                    result.put("failedSavePreservedCredential", true)
                    scroll("tmdb_token_delete"); ui.onNodeWithTag("tmdb_token_delete").performClick()
                    ui.onNodeWithTag("tmdb_delete_cancel").performClick()
                    assertEquals("CANCELED_DELETE_CHANGED_CREDENTIAL", before, digest(file))
                    val oldAlias = DataInputStream(file.inputStream()).use { it.readInt(); it.readUTF() }
                    state.put("restoreRequired", true); stateFile.writeText(state.toString())
                    scroll("tmdb_token_delete"); ui.onNodeWithTag("tmdb_token_delete").performClick(); ui.onNodeWithTag("tmdb_delete_confirm").performClick()
                    ui.waitUntil(10000) { repo.state.value.status == TmdbCredentialStatus.NOT_CONFIGURED }
                    runBlocking { store.read().use { assertNull("DELETION_DID_NOT_REMOVE_KEY", it!!.key) } }
                    assertFalse("DELETED_DECRYPTION_ALIAS_RETAINED", KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(oldAlias))
                    scroll("tmdb_preview_read"); ui.onNodeWithTag("tmdb_preview_read").performClick()
                    scroll("tmdb_preview_status")
                    ui.waitUntil(10000) { text("tmdb_preview_status").contains("请先保存") }
                    result.put("canceledDeletePreservedCredential", true).put("confirmedDeleteRemovedCredential", true)
                        .put("deletedKeyReadBlockedByUi", true).put("deletedAliasRemoved", true)
                } else {
                    val proxy = app.getSystemService(ConnectivityManager::class.java).defaultProxy
                    assertNotNull("APP_RESTART_LOST_PROXY", proxy); assertEquals("10.0.2.2", proxy!!.host); assertEquals(7897, proxy.port)
                    assertTrue("RESTART_PROXY_SELECTOR_MISMATCH", ProxySelector.getDefault().select(URI("https://api.themoviedb.org")).any {
                        it.type() == Proxy.Type.HTTP && (it.address() as? InetSocketAddress)?.let { a -> a.hostString == "10.0.2.2" && a.port == 7897 } == true })
                    result.put("restartKeptEncryptedConfiguration", true).put("savedTokenNotEchoed", true)
                        .put("previewIdleAfterRestart", true).put("proxyRetainedAfterAppRestart", true)
                }
            }
            val expectedModel = if (state.isNull("modelDigest")) null else state.getString("modelDigest")
            assertEquals("DEEPSEEK_CONFIGURATION_CHANGED", expectedModel, digest(model))
            result.put("deepSeekConfigurationUnchanged", true)
            complete = true
        } catch (error: Throwable) {
            result.put("problem", when (error) { is AssertionError -> "PRODUCTION_ASSERTION_FAILED"; is TmdbCredentialException -> error.problem.name; else -> "LOCAL_OR_TRANSPORT_FAILURE" })
        } finally {
            result.put("passed", complete)
            File(directory, nonce + "-" + phase + ".json").writeText(result.toString())
        }
        assertTrue("PRODUCTION_VERIFICATION_FAILED: " + result.optString("problem", "NONE"), complete)
    }
}
