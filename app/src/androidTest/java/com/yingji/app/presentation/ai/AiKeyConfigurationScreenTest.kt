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
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.io.DataInputStream

class AiKeyConfigurationScreenTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private val models = ViewModelStore()
    private lateinit var vm: AiKeyConfigurationViewModel
    private val theme = mutableStateOf(ThemeMode.DARK)
    private class Memory : AiSecureKeyStore {
        var key: String? = null; var time: Long? = null; var kind: AiKeyVerificationKind? = null; var revision = "empty"; var fail = false
        override suspend fun read() = AiKeyRecord(key?.let(AiSecret::fromInput), time, kind, revision)
        override suspend fun replace(key: AiSecret?, verifiedAt: Long?, kind: AiKeyVerificationKind?): String {
            if (fail) throw IOException("Injected write failure")
            this.key = key?.useValue { it }; time = verifiedAt; this.kind = kind; revision = java.util.UUID.randomUUID().toString(); return revision
        }
    }
    private fun show(repo: AiKeyConfigurationRepository) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AiKeyConfigurationViewModel(repo); models.put("config", vm) }
        ui.setContent { YingJiTheme(theme.value) { AiKeyConfigurationScreen(vm) {} } }
        ui.waitUntil(5000) { vm.state.value.storage != AiKeyStorageStatus.LOADING }
        ui.waitUntil(5000) { ui.onAllNodesWithTag("ai_key_scroll").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
    private fun scroll(tag: String) { ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasTestTag(tag)) }
    private fun input(text: String) { scroll("ai_key_input"); val field = ui.onNodeWithTag("ai_key_input")
        field.performTextClearance()
        field.performTextInput(text) }
    private fun save() { scroll("ai_key_verify_save"); ui.onNodeWithTag("ai_key_verify_save").performClick(); ui.waitUntil(5000) { !vm.state.value.verifying } }
    private fun message(text: String) { scroll("ai_key_message"); ui.onNodeWithTag("ai_key_message").assertTextContains(text, substring = true) }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() } }
    @Test fun emptyInvalidMaskedInputAndSuccessfulEncryptedSaveAreWired() {
        val file = File(files.root, "secure.enc"); val store = AndroidKeystoreAiKeyStore(file)
        try {
            show(AiKeyConfigurationRepository(store, LocalMockAiKeyVerifier()))
            scroll("ai_key_status"); ui.onNodeWithTag("ai_key_status").assertTextEquals("未配置")
            save(); message("空白输入不会保存"); assertFalse(file.exists())
            input("TEST_ONLY_BYOK_INVALID"); save(); message("Mock 验证失败"); assertFalse(file.exists())
            input("TEST_ONLY_BYOK_ui_valid"); ui.onNodeWithTag("ai_key_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            ui.onNodeWithTag("ai_key_visibility").performClick()
            ui.onNodeWithTag("ai_key_input").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
            ui.onNodeWithTag("ai_key_visibility").performClick(); save()
            assertEquals(AiKeyStorageStatus.CONFIGURED, vm.state.value.storage); assertEquals(AiKeyVerificationKind.MOCK, vm.state.value.kind)
            scroll("ai_key_input"); ui.onNodeWithTag("ai_key_input").assertTextContains("")
            assertEquals("", ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("TEST_ONLY_BYOK_ui_valid"))
            assertFalse(vm.state.value.canRequestLiveAi); assertNotNull(vm.state.value.lastVerifiedAt)
        } finally {
            if (file.exists()) DataInputStream(file.inputStream()).use { it.readInt(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(it.readUTF()) }
        }
    }
    @Test fun failedReplacementAndDeleteCancellationPreserveOldKeyAndSuccessfulDeleteClearsIt() {
        val store = Memory(); val repo = AiKeyConfigurationRepository(store, LocalMockAiKeyVerifier())
        runBlocking { repo.verifyAndSave("TEST_ONLY_BYOK_old") }; show(repo)
        scroll("ai_key_input"); assertEquals("", ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        input("TEST_ONLY_BYOK_INVALID"); save(); assertEquals("TEST_ONLY_BYOK_old", store.key)
        input("TEST_ONLY_BYOK_new"); store.fail = true; save(); message("密钥保存失败"); assertEquals("TEST_ONLY_BYOK_old", store.key)
        store.fail = false; save(); assertEquals("TEST_ONLY_BYOK_new", store.key)
        scroll("ai_key_delete"); ui.onNodeWithTag("ai_key_delete").performClick(); ui.onNodeWithTag("ai_key_delete_cancel").performClick()
        assertEquals("TEST_ONLY_BYOK_new", store.key)
        ui.onNodeWithTag("ai_key_delete").performClick(); ui.onNodeWithTag("ai_key_delete_confirm").performClick()
        ui.waitUntil(5000) { vm.state.value.storage == AiKeyStorageStatus.NOT_CONFIGURED }
        assertNull(store.key); assertNull(vm.state.value.lastVerifiedAt); assertFalse(vm.state.value.canRequestLiveAi)
    }
    @Test fun deleteWriteFailureKeepsConfirmationAndDoesNotClaimSuccess() {
        val store = Memory(); val repo = AiKeyConfigurationRepository(store, LocalMockAiKeyVerifier()); runBlocking { repo.verifyAndSave("TEST_ONLY_BYOK_retained") }
        show(repo); store.fail = true; scroll("ai_key_delete"); ui.onNodeWithTag("ai_key_delete").performClick(); ui.onNodeWithTag("ai_key_delete_confirm").performClick()
        ui.waitUntil(5000) { vm.actionState.value.message?.contains("删除失败") == true }
        assertEquals("TEST_ONLY_BYOK_retained", store.key); assertEquals(AiKeyStorageStatus.CONFIGURED, vm.state.value.storage)
        assertTrue(vm.actionState.value.confirmDelete); ui.onNodeWithTag("ai_key_delete_cancel").performClick()
    }
    @Test fun bothThemesKeepTrustAndCostNoticeReadable() {
        show(AiKeyConfigurationRepository(Memory(), LocalMockAiKeyVerifier()))
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasText("费用与可信代理"))
            ui.onNodeWithText("费用与可信代理").assertIsDisplayed()
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasText("端到端保密", substring = true))
            ui.onNodeWithText("端到端保密", substring = true).assertIsDisplayed()
            val file = File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "byok-s1-config-" + mode.name + ".png")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { b -> file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
        }
    }
    @Test fun noKeyHomeShowsConfigurationEntryAndCannotReachOldWorkerEvenAfterMockSaveOrDelete() {
        val config = AiKeyConfigurationRepository(Memory(), LocalMockAiKeyVerifier())
        var calls = 0
        val service = object : AiWorkerService {
            override suspend fun quota(): AiQuotaSnapshot { calls++; error("Unexpected Worker call") }
            override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse { calls++; error("Unexpected Worker call") }
        }
        val demo = MockAiHomeRepository(ApplicationProvider.getApplicationContext<Context>(), AiTestStore())
        val home = WorkerDemoHomeRepository(demo, service, MockCandidateCatalogRepository()); runBlocking { demo.saveSetup(AiDemoSetup()) }
        lateinit var homeVm: AiHomeViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { homeVm = AiHomeViewModel(home, config); models.put("home", homeVm) }
        ui.setContent { YingJiTheme { HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = homeVm) } }
        ui.waitUntil(5000) { !homeVm.state.value.initializing && homeVm.state.value.keyConfiguration?.storage == AiKeyStorageStatus.NOT_CONFIGURED }
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_key_configure")); ui.onNodeWithTag("ai_key_configure").assertIsDisplayed()
        assertFalse(homeVm.state.value.workerDemoAvailable)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { homeVm.setScenario(MockScenario.WORKER) }
        assertEquals(MockScenario.SUCCESS, homeVm.state.value.scenario); assertEquals(0, calls)
        runBlocking { config.verifyAndSave("TEST_ONLY_BYOK_gate"); config.delete() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { homeVm.setScenario(MockScenario.WORKER); homeVm.submit(AiMode.FOR_YOU) }
        ui.waitUntil(5000) { homeVm.state.value.result == AiResultState.SUCCESS }
        assertEquals(0, calls); assertEquals(3, homeVm.state.value.recommendations.size)
    }
}
