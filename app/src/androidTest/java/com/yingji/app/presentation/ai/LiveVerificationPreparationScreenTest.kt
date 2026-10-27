package com.yingji.app.presentation.ai

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.io.DataInputStream
import java.security.KeyStore
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** The LIVE metadata here comes only from Fake verification; no HTTP client or real credential is used. */
class LiveVerificationPreparationScreenTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule val files=TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private val models=ViewModelStore()
    private val fake="FAKE_UI_RUNTIME_KEY_NEVER_SENT_123456"
    private class FakeService : AiWorkerKeyVerification {
        var calls=0;var failure: WorkerProblem?=null
        override suspend fun verify(key: AiSecret) { calls++;failure?.let { throw it } }
    }
    private val endpoint=AiLiveWorkerConfiguration.from(true,"https://approved-test.workers.dev")
    private fun configScroll(tag:String) { ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasTestTag(tag)) }
    private fun show(repo:AiKeyConfigurationRepository):AiKeyConfigurationViewModel {
        lateinit var vm:AiKeyConfigurationViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm=AiKeyConfigurationViewModel(repo);models.put("config",vm) }
        ui.setContent { YingJiTheme { AiKeyConfigurationScreen(vm) {} } }
        ui.waitUntil(5000) { vm.state.value.storage!=AiKeyStorageStatus.LOADING };return vm
    }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() } }
    @Test fun disabledVerificationExplainsTheGateAndCannotDispatchOrSave() {
        val service=FakeService();val store=AndroidKeystoreAiKeyStore(File(files.root,"disabled.enc"))
        val repo=AiKeyConfigurationRepository(store,WorkerLiveAiKeyVerifier(AiLiveWorkerConfiguration.from(false,""),service));show(repo)
        ui.onNodeWithText("正式验证未启用",substring=true).assertExists()
        configScroll("ai_key_input");ui.onNodeWithTag("ai_key_input").performTextInput(fake)
        configScroll("ai_key_verify_save");ui.onNodeWithTag("ai_key_verify_save").assertIsNotEnabled()
        assertEquals(0,service.calls);assertEquals(AiKeyStorageStatus.NOT_CONFIGURED,repo.state.value.storage)
    }
    @Test fun existingFormShowsLiveTrustNoticeAndEncryptedSuccessFailureReplacementAndDeletion() {
        val service=FakeService();val file=File(files.root,"live.enc");val store=AndroidKeystoreAiKeyStore(file)
        val repo=AiKeyConfigurationRepository(store,WorkerLiveAiKeyVerifier(endpoint,service));val vm=show(repo)
        try {
            ui.onNodeWithText("https://approved-test.workers.dev",substring=true).assertExists()
            configScroll("ai_key_input");ui.onNodeWithTag("ai_key_input").performTextInput(fake)
            ui.onNodeWithTag("ai_key_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            configScroll("ai_key_verify_save");ui.onNodeWithTag("ai_key_verify_save").assertTextEquals("验证并保存").performClick()
            ui.waitUntil(5000) { repo.state.value.storage==AiKeyStorageStatus.CONFIGURED&&!repo.state.value.verifying }
            configScroll("ai_key_status");ui.onNodeWithTag("ai_key_status").assertTextEquals("已验证（上次验证成功）")
            configScroll("ai_key_input");assertEquals("",ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals(AiKeyVerificationKind.LIVE,repo.state.value.kind);assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(fake))
            val reopened=AiKeyConfigurationRepository(AndroidKeystoreAiKeyStore(file),WorkerLiveAiKeyVerifier(endpoint,service))
            runBlocking { reopened.refresh() };assertEquals(repo.state.value,reopened.state.value);assertEquals(1,service.calls)
            val old=repo.state.value.revision;service.failure=WorkerProblem("UPSTREAM_AUTH")
            ui.onNodeWithTag("ai_key_input").performTextInput(fake+"_replacement")
            configScroll("ai_key_verify_save");ui.onNodeWithTag("ai_key_verify_save").performClick()
            ui.waitUntil(5000) { vm.actionState.value.message=="验证失败，原有 Key 保留" }
            assertEquals(old,repo.state.value.revision);runBlocking { store.read().use { assertEquals(fake,it!!.key!!.useValue { value->value }) } }
            service.failure=null;ui.onNodeWithTag("ai_key_verify_save").performClick()
            ui.waitUntil(5000) { repo.state.value.revision!=old&&!repo.state.value.verifying };assertEquals(3,service.calls)
            configScroll("ai_key_message");ui.onNodeWithTag("ai_key_message").assertTextContains("首页推荐仍为 Mock",substring=true)
            configScroll("ai_key_delete");ui.onNodeWithTag("ai_key_delete").performClick();ui.onNodeWithTag("ai_key_delete_confirm").performClick()
            ui.waitUntil(5000) { repo.state.value.storage==AiKeyStorageStatus.NOT_CONFIGURED }
            runBlocking { store.read().use { assertNull(it!!.key) } };assertFalse(repo.state.value.canRequestLiveAi);assertEquals(3,service.calls)
        } finally { if(file.exists())DataInputStream(file.inputStream()).use { it.readInt();KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(it.readUTF()) } }
    }
    @Test fun liveKeyMetadataCannotEnableRealRecommendationsOrUploadProfiles() {
        val service=FakeService();val file=File(files.root,"home.enc");val store=AndroidKeystoreAiKeyStore(file)
        val repo=AiKeyConfigurationRepository(store,WorkerLiveAiKeyVerifier(endpoint,service));runBlocking { repo.verifyAndSave(fake) }
        val demo=AiTestRepository();runBlocking { demo.saveSetup(AiDemoSetup()) }
        lateinit var home:AiHomeViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { home=AiHomeViewModel(demo,repo);models.put("home",home) }
        try {
            ui.setContent { YingJiTheme { HomeScreen(emptyList(),emptyMap(),{},{},{},aiViewModel=home) } }
            ui.waitUntil(5000) { !home.state.value.initializing&&home.state.value.keyConfiguration?.kind==AiKeyVerificationKind.LIVE }
            ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_key_home_status"))
            ui.onNodeWithTag("ai_key_home_status").assertTextContains("当前首页仍为 Mock",substring=true)
            ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_enter"));ui.onNodeWithTag("ai_enter").performClick()
            ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_mode_FOR_YOU"));ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
            ui.waitUntil(5000) { home.state.value.result==AiResultState.SUCCESS }
            assertEquals(3,home.state.value.recommendations.size);assertEquals(1,service.calls);assertFalse(home.state.value.workerDemoAvailable)
            assertEquals(MockScenario.SUCCESS,home.state.value.scenario);assertFalse(home.state.value.toString().contains(fake))
        } finally { if(file.exists())DataInputStream(file.inputStream()).use { it.readInt();KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(it.readUTF()) } }
    }
}
