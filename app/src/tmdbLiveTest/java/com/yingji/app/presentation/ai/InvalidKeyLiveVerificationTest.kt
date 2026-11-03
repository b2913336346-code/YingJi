package com.yingji.app.presentation.ai

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.BuildConfig
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Manual opt-in only: one invented credential, balance endpoint only, structurally read-only saved-key store. */
class InvalidKeyLiveVerificationTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @Test fun actualAuthenticationFailurePreservesSavedKeyAndShowsExistingFeedback() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("authLiveApproved") == "one-invented-key-verification-no-saved-key-write")
        val nonce = requireNotNull(args.getString("authLiveNonce"))
        require(Regex("[a-f0-9]{32}").matches(nonce))
        val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
        require(app.packageName == "com.yingji.app" && BuildConfig.AI_DIRECT_VERIFICATION_ENABLED)
        check(File(app.noBackupFilesDir, "auth-live-consumed-" + nonce).createNewFile())
        val file = File(app.noBackupFilesDir, "ai-byok/config.enc")
        fun digest() = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val beforeHash = digest()
        val beforeUsage = app.localAiUsageRepository.snapshot()
        val opens = AtomicInteger()
        val writes = AtomicInteger()
        var upstreamProblem: String? = null
        val client = DeepSeekHttpClient(open = { url: URL ->
            check(url.toString() == "https://api.deepseek.com/user/balance")
            check(opens.incrementAndGet() == 1)
            url.openConnection() as HttpsURLConnection
        })
        val validation = object : DeepSeekKeyValidation {
            override suspend fun verify(key: AiSecret, isCurrent: () -> Boolean) {
                try { client.verify(key, isCurrent) }
                catch (problem: WorkerProblem) { upstreamProblem = problem.code; throw problem }
            }
            override fun cancelPending() = client.cancelPending()
        }
        val disk = AndroidKeystoreAiKeyStore(app)
        val readOnly = object : AiSecureKeyStore {
            override suspend fun read() = disk.read()
            override suspend fun replace(key: AiSecret?, verifiedAt: Long?, kind: AiKeyVerificationKind?): String {
                writes.incrementAndGet()
                throw AiKeyException(AiKeyProblem.STORAGE_FAILED)
            }
        }
        val repository = AiKeyConfigurationRepository(readOnly, DeepSeekAiKeyVerifier(validation))
        repository.refresh()
        val before = repository.state.value
        assertEquals(AiKeyStorageStatus.CONFIGURED, before.storage)
        val models = ViewModelStore()
        var vm: AiKeyConfigurationViewModel? = null
        var passed = false
        try {
            ui.runOnUiThread { vm = AiKeyConfigurationViewModel(repository); models.put("auth-live", requireNotNull(vm)) }
            val model = requireNotNull(vm)
            ui.setContent { com.yingji.app.ui.theme.YingJiTheme { AiKeyConfigurationScreen(model, {}) } }
            ui.waitUntil(10000) { repository.state.value.storage != AiKeyStorageStatus.LOADING }
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasTestTag("ai_key_input"))
            ui.onNodeWithTag("ai_key_input").performTextInput("sk-INVALID_FOR_YINGJI_" + nonce)
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasTestTag("ai_key_verify_save"))
            ui.onNodeWithTag("ai_key_verify_save").performClick()
            ui.waitUntil(60000) { !repository.state.value.verifying && repository.state.value.error != null }
            assertTrue(upstreamProblem in setOf("UPSTREAM_AUTH", "UPSTREAM_FORBIDDEN"))
            val expected = if (upstreamProblem == "UPSTREAM_AUTH") "验证失败，原有 Key 保留" else "DeepSeek 权限或安全连接验证失败；原有 Key 保留"
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasTestTag("ai_key_message"))
            ui.onNodeWithTag("ai_key_message").assertTextEquals(expected).assertIsDisplayed()
            assertEquals(AiKeyStorageStatus.CONFIGURED, repository.state.value.storage)
            assertEquals(before.kind, repository.state.value.kind)
            assertEquals(before.lastVerifiedAt, repository.state.value.lastVerifiedAt)
            assertEquals(1, opens.get())
            assertEquals(0, writes.get())
            assertEquals(beforeHash, digest())
            assertEquals(beforeUsage, app.localAiUsageRepository.snapshot())
            passed = true
        } finally {
            client.cancelPending()
            vm?.let { model ->
                val job = model.viewModelScope.coroutineContext[Job]
                ui.runOnUiThread { models.clear() }
                job?.join()
            }
            val result = JSONObject().put("passed", passed).put("httpAttempts", opens.get())
                .put("providerProblem", upstreamProblem ?: JSONObject.NULL).put("savedKeyWriteAttempts", writes.get())
                .put("savedCiphertextUnchanged", digest() == beforeHash).put("modelGenerationCalls", 0)
            val directory = File(app.filesDir, "auth-live-results").apply { mkdirs() }
            File(directory, nonce + ".json").writeText(result.toString())
        }
    }
}
