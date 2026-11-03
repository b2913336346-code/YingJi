package com.yingji.app.data.ai

import android.os.Process
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.MainActivity
import com.yingji.app.YingJiApplication
import com.yingji.app.domain.ai.TmdbCredentialStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Explicit manual opt-in. Reads existing production configuration; never saves/deletes a key or logs film metadata. */
class TmdbHomeLiveVerificationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun digest(file: File): String? = if (!file.isFile) null else
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun text(tag: String) = ui.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    private fun source(tmdb: Boolean) { val tag = if (tmdb) "ai_source_tmdb" else "ai_source_mock"; scroll(tag); ui.waitUntil(10000) { ui.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }; ui.onNodeWithTag(tag).performClick() }
    private fun assertIdle() { scroll("tmdb_home_status"); assertEquals("HOME_MUST_BE_IDLE", "尚未读取候选", text("tmdb_home_status")) }
    private fun problem(value: String) = when {
        value.contains("无法连接") -> "OFFLINE"
        value.contains("权限无效") -> "AUTHORIZATION"
        value.contains("请求受限") -> "RATE_LIMITED"
        value.contains("暂时不可用") -> "UNAVAILABLE"
        value.contains("安全连接失败") -> "SECURE_CONNECTION"
        value.contains("许可不可用") -> "LICENSE_REQUIRED"
        value.contains("响应无效") -> "INVALID_RESPONSE"
        else -> "UI_OR_VALIDATION_FAILURE"
    }
    @Test fun existingCredentialSupportsExplicitHomeReadAndRestartClearsSession() {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("tmdbHomeLiveApproved") == "one-home-read-existing-token")
        val nonce = requireNotNull(args.getString("tmdbNonce")); require(Regex("[a-f0-9]{32}").matches(nonce))
        val phase = requireNotNull(args.getString("tmdbHomePhase")); require(phase in setOf("read", "restart"))
        val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
        val directory = File(app.filesDir, "tmdb-home-live-results"); check(directory.exists() || directory.mkdir())
        val stateFile = File(directory, nonce + "-state.json")
        val credential = File(app.noBackupFilesDir, "tmdb-credentials/config.enc")
        val model = File(app.noBackupFilesDir, "ai-byok/config.enc")
        val beforeKey = digest(credential); val beforeModel = digest(model)
        val result = JSONObject().put("phase", phase).put("pid", Process.myPid())
            .put("readActions", 0).put("httpAttemptsCounted", false).put("httpAttemptUpperBound", if (phase == "read") 16 else 0)
            .put("deepSeekRequests", 0).put("profileTransferred", false).put("metadataPersisted", false)
            .put("screenshotsCaptured", false).put("credentialWrittenOrDeleted", false)
        var passed = false
        var failure = "UI_OR_VALIDATION_FAILURE"
        try {
            runBlocking { app.tmdbCredentialRepository.refresh() }
            assertEquals("TMDB_KEY_REQUIRED", TmdbCredentialStatus.SAVED_UNVERIFIED, app.tmdbCredentialRepository.state.value.status)
            assertNotNull("ENCRYPTED_CONFIGURATION_REQUIRED", beforeKey)
            assertTrue("MOCK_IMPORT_CATALOG_MUST_REMAIN", app.candidateCatalog is MockCandidateCatalogRepository)
            if (phase == "read") {
                require(!stateFile.exists())
                stateFile.writeText(JSONObject().put("nonce", nonce).put("pid", Process.myPid())
                    .put("credentialDigest", beforeKey).put("modelDigest", beforeModel ?: JSONObject.NULL).toString())
            } else {
                val state = JSONObject(stateFile.readText()); require(state.getString("nonce") == nonce)
                assertNotEquals("FRESH_PROCESS_REQUIRED", state.getInt("pid"), Process.myPid())
                assertEquals("KEY_CHANGED_BETWEEN_PHASES", state.getString("credentialDigest"), beforeKey)
                assertEquals("MODEL_CHANGED_BETWEEN_PHASES", if (state.isNull("modelDigest")) null else state.getString("modelDigest"), beforeModel)
            }
            ui.waitUntil(15000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
            scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
            result.put("defaultSourceMock", true)
            source(true); assertIdle(); result.put("sourceSelectionDidNotRead", true)
            if (phase == "read") {
                scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick(); result.put("readActions", 1)
                scroll("tmdb_home_status")
                ui.waitUntil(120000) { val s = text("tmdb_home_status"); s.isNotEmpty() && s != "正在校验 TMDB 候选" && s != "尚未读取候选" }
                val status = text("tmdb_home_status")
                val count = Regex("已校验 ([1-6]) 项").find(status)?.groupValues?.get(1)?.toInt()
                if (count == null) failure = problem(status)
                assertNotNull("LIVE_HOME_READ_MUST_SUCCEED", count)
                result.put("candidateCount", count)
                for (kind in listOf("movie:", "tv:")) {
                    val matcher = SemanticsMatcher("typed TMDB card") { node ->
                        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tmdb_home_" + kind) == true
                    }
                    ui.onNodeWithTag("movie_collection").performScrollToNode(matcher)
                    assertTrue("TYPED_CARD_MUST_BE_VISIBLE", ui.onAllNodes(matcher).fetchSemanticsNodes().any { !it.boundsInRoot.isEmpty })
                }
                result.put("movieAndTvCardsDisplayed", true)
                ui.onAllNodes(hasText("加入影迹")).assertCountEquals(0)
                ui.onAllNodes(hasText("喜欢", substring = false)).assertCountEquals(0)
                scroll("tmdb_home_credits_logo"); ui.onNodeWithTag("tmdb_home_credits_logo").assertIsDisplayed()
                scroll("tmdb_home_credits_notice"); ui.onNodeWithTag("tmdb_home_credits_notice").assertTextEquals("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                result.put("attributionDisplayed", true).put("feedbackAndImportAbsent", true)
                source(false); source(true); assertIdle(); result.put("sourceSwitchClearedResults", true)
                ui.onNodeWithText("我的").performClick(); ui.onNodeWithText("首页").performClick()
                ui.waitUntil(15000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
                scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
                source(true); assertIdle(); result.put("leavingHomeClearedSourceAndResults", true)
            } else result.put("restartDidNotRestoreResultsOrRead", true)
            source(false)
            passed = true
        } catch (_: Throwable) {
            // Never rethrow a Compose failure that could contain a provider title or full semantics tree.
            result.put("problem", failure)
        } finally {
            runCatching { source(false) }
            result.put("passed", passed).put("configurationCiphertextUnchanged", digest(credential) == beforeKey)
                .put("modelConfigurationUnchanged", digest(model) == beforeModel)
            File(directory, nonce + "-" + phase + ".json").writeText(result.toString())
        }
        assertTrue("HOME_LIVE_VERIFICATION_FAILED: " + result.optString("problem", "NONE"), passed)
        assertEquals("TMDB_CONFIGURATION_CHANGED", beforeKey, digest(credential))
        assertEquals("MODEL_CONFIGURATION_CHANGED", beforeModel, digest(model))
    }
}
