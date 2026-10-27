package com.yingji.app.presentation.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class WorkerByokScreenTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private val models = ViewModelStore()
    private fun state(): JSONObject {
        val c = URL("http://127.0.0.1:8787/__test/state").openConnection() as HttpURLConnection
        try { c.connectTimeout=5000; c.readTimeout=10000; assertEquals(200,c.responseCode); return JSONObject(c.inputStream.bufferedReader().use { it.readText() }) }
        finally { c.disconnect() }
    }
    private fun home(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    private fun config(tag: String) { ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasTestTag(tag)) }
    private fun screenshot(name: String) {
        val file = File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), name + ".png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { b -> file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
    }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() } }
    @Test fun realFactoryWiresNoKeyEntryNetworkValidationEncryptedReplacementRecommendationAndDeletion() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val file = File(files.root,"ui.enc")
        val store = AndroidKeystoreAiKeyStore(file)
        val keys = AiKeyConfigurationRepository(store,LegacyWorkerFixtureFactory.verifier(context))
        val demo = MockAiHomeRepository(context,AiTestStore()); runBlocking { demo.saveSetup(AiDemoSetup()) }
        val repo = LegacyWorkerFixtureFactory.create(demo,MockCandidateCatalogRepository(),context,keys)
        lateinit var vm: AiHomeViewModel; lateinit var configuration: AiKeyConfigurationViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm=AiHomeViewModel(repo,keys); configuration=AiKeyConfigurationViewModel(keys)
            models.put("home",vm); models.put("config",configuration)
        }
        try {
            val initial = state()
            ui.setContent {
                var show by remember { mutableStateOf(false) }
                LaunchedEffect(vm) { vm.keyConfigurationRequests.collect { show=true } }
                YingJiTheme(ThemeMode.DARK) { if(show) AiKeyConfigurationScreen(configuration) { show=false }
                    else HomeScreen(emptyList(),emptyMap(),{},{},{},aiViewModel=vm) }
            }
            ui.waitUntil(10000) { !vm.state.value.initializing && keys.state.value.storage == AiKeyStorageStatus.NOT_CONFIGURED }
            home("ai_enter"); ui.onNodeWithTag("ai_enter").performClick()
            home("ai_scenario"); ui.onNodeWithTag("ai_scenario").performClick(); ui.onNodeWithTag("ai_scenario_WORKER").performScrollTo().performClick()
            home("ai_mode_FOR_YOU"); ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithTag("ai_key_input").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(initial.getInt("posts"),state().getInt("posts")); assertEquals(initial.getInt("gets"),state().getInt("gets"))
            config("ai_key_input"); ui.onNodeWithTag("ai_key_input").performTextInput("TEST_ONLY_BYOK_ui_first")
            ui.onNodeWithTag("ai_key_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            config("ai_key_verify_save"); ui.onNodeWithTag("ai_key_verify_save").performClick()
            ui.waitUntil(10000) { keys.state.value.storage == AiKeyStorageStatus.CONFIGURED && !keys.state.value.verifying }
            assertEquals(initial.getInt("verifications")+1,state().getInt("verifications")); assertEquals(initial.getInt("modelCalls"),state().getInt("modelCalls"))
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("TEST_ONLY_BYOK_ui_first"))
            config("ai_key_input"); assertEquals("",ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            val old = keys.state.value.revision
            ui.onNodeWithTag("ai_key_input").performTextInput("TEST_ONLY_BYOK_INVALID")
            config("ai_key_verify_save"); ui.onNodeWithTag("ai_key_verify_save").performClick()
            ui.waitUntil(10000) { configuration.actionState.value.message?.contains("Mock 验证失败") == true }
            assertEquals(old,keys.state.value.revision)
            runBlocking { store.read().use { assertEquals("TEST_ONLY_BYOK_ui_first",it!!.key!!.useValue { value -> value }) } }
            config("ai_key_input"); ui.onNodeWithTag("ai_key_input").performTextClearance(); ui.onNodeWithTag("ai_key_input").performTextInput("TEST_ONLY_BYOK_ui_second")
            config("ai_key_verify_save"); ui.onNodeWithTag("ai_key_verify_save").performClick()
            ui.waitUntil(10000) { keys.state.value.revision != old && !keys.state.value.verifying }
            config("ai_key_status"); ui.onNodeWithTag("ai_key_status").assertTextEquals("已验证（仅 Mock）")
            config("ai_key_input"); assertEquals("",ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            ui.waitForIdle(); screenshot("byok-s3-config-verified")
            ui.onNodeWithContentDescription("返回").performClick()
            home("ai_mode_FOR_YOU"); ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
            ui.waitUntil(10000) { vm.state.value.result == AiResultState.SUCCESS }
            assertEquals(3,vm.state.value.recommendations.size); assertEquals(initial.getInt("modelCalls")+1,state().getInt("modelCalls"))
            assertTrue(vm.state.value.message!!.contains("本地 Worker 联调")); assertEquals(1,runBlocking { demo.readManagement().history.size })
            home("ai_key_configure"); ui.waitForIdle(); screenshot("byok-s3-home-worker-success"); ui.onNodeWithTag("ai_key_configure").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithTag("ai_key_scroll").fetchSemanticsNodes().isNotEmpty() }
            config("ai_key_delete"); ui.onNodeWithTag("ai_key_delete").performClick(); ui.onNodeWithTag("ai_key_delete_confirm").performClick()
            ui.waitUntil(5000) { keys.state.value.storage == AiKeyStorageStatus.NOT_CONFIGURED }
            ui.onNodeWithContentDescription("返回").performClick()
            ui.waitUntil(5000) { vm.state.value.recommendations.isEmpty() }
            assertNull(vm.state.value.pendingAdd); assertEquals(1,runBlocking { demo.readManagement().history.size })
            home("ai_mode_FOR_YOU"); ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithTag("ai_key_input").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(initial.getInt("modelCalls")+1,state().getInt("modelCalls")); assertFalse(keys.state.value.canRequestLiveAi)
        } finally {
            if(file.exists()) DataInputStream(file.inputStream()).use { it.readInt(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(it.readUTF()) }
        }
    }
}
