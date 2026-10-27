package com.yingji.app.presentation.ai

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.*

class DirectConfigurationScreenTest {
    @get:Rule val ui=createComposeRule()
    private val models=ViewModelStore()
    private val theme=mutableStateOf(ThemeMode.DARK)
    private class Store:AiSecureKeyStore {
        var value:String?=null;var time:Long?=null;var kind:AiKeyVerificationKind?=null;var revision="empty"
        override suspend fun read()=AiKeyRecord(value?.let(AiSecret::fromInput),time,kind,revision)
        override suspend fun replace(key:AiSecret?,verifiedAt:Long?,kind:AiKeyVerificationKind?):String {
            value=key?.useValue { it };time=verifiedAt;this.kind=kind;revision=java.util.UUID.randomUUID().toString();return revision
        }
    }
    private fun scroll(tag:String)=ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasTestTag(tag))
    private fun show(repo:AiKeyConfigurationRepository) {
        lateinit var vm:AiKeyConfigurationViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm=AiKeyConfigurationViewModel(repo);models.put("configuration",vm) }
        ui.setContent { YingJiTheme(theme.value){AiKeyConfigurationScreen(vm){}} }
        ui.waitUntil(5000){repo.state.value.storage!=AiKeyStorageStatus.LOADING}
    }
    private fun screenshot(name:String) {
        ui.waitForIdle()
        val file=java.io.File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null),name+".png")
        // PixelCopy waits for drawing; display screenshots can capture a blank frame after rotation.
        ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val colors=mutableSetOf<Int>()
            for(y in 0 until bitmap.height step 8) for(x in 0 until bitmap.width step 8) colors.add(bitmap.getPixel(x,y))
            assertTrue("Screenshot must contain rendered UI: "+name,colors.size>4)
            file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
        }
    }
    @After fun close(){InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }}
    @Test fun realDebugFactoryUsesLocalDirectMockAndCannotEnableWorkerRecommendationOrRealKeys()=runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>();val store=Store();val repo=AiKeyConfigurationRepository(store,AiHomeRepositoryFactory.verifier(context))
        val demo=MockAiHomeRepository(context,AiTestStore());val home=AiHomeRepositoryFactory.create(demo,MockCandidateCatalogRepository(),context,repo)
        assertTrue(repo.verificationDirectApi);assertEquals(AiKeyVerificationKind.MOCK,repo.verificationKind);assertSame(demo,home);assertFalse(home.workerDemoAvailable)
        val error=runCatching { repo.verifyAndSave("FAKE_DIRECT_CREDENTIAL_00000000") }.exceptionOrNull() as AiKeyException
        assertEquals(AiKeyProblem.MOCK_ONLY,error.problem);assertNull(store.value)
        repo.verifyAndSave("TEST_ONLY_BYOK_direct");assertEquals(AiKeyVerificationKind.MOCK,store.kind);assertFalse(repo.state.value.canRequestLiveAi)
    }
    @Test fun existingFormShowsDirectNotProxyNoticeAndKeepsMaskedInputSaveAndDelete() {
        val store=Store();val context=ApplicationProvider.getApplicationContext<Context>();val repo=AiKeyConfigurationRepository(store,AiHomeRepositoryFactory.verifier(context));show(repo)
        ui.onNodeWithText("直连协议 Mock 验证",substring=true).assertExists()
        ui.onNodeWithText("直连协议 Mock 验证",substring=true).assertTextContains("经逐次确认的本地画像摘要",substring=true).assertTextContains("仍不向外发送",substring=true)
        scroll("ai_key_input");ui.onNodeWithTag("ai_key_input").performTextInput("TEST_ONLY_BYOK_direct")
        ui.onNodeWithTag("ai_key_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        scroll("ai_key_verify_save");ui.onNodeWithTag("ai_key_verify_save").performClick();ui.waitUntil(5000){store.value!=null&&!repo.state.value.verifying}
        scroll("ai_key_input");assertEquals("",ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        scroll("ai_key_delete");ui.onNodeWithTag("ai_key_delete").performClick();ui.onNodeWithTag("ai_key_delete_cancel").performClick();assertNotNull(store.value)
        ui.onNodeWithTag("ai_key_delete").performClick();ui.onNodeWithTag("ai_key_delete_confirm").performClick();ui.waitUntil(5000){repo.state.value.storage==AiKeyStorageStatus.NOT_CONFIGURED};assertNull(store.value)
    }
    @Test fun directCostAndSecurityNoticeIsReadableInBothExistingThemes() {
        val context=ApplicationProvider.getApplicationContext<Context>();show(AiKeyConfigurationRepository(Store(),AiHomeRepositoryFactory.verifier(context)))
        for(mode in listOf(ThemeMode.DARK,ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value=mode }
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasText("费用与直连 API"));ui.onNodeWithText("费用与直连 API").assertIsDisplayed()
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasText("不能保证不超支",substring=true));ui.onNodeWithText("不能保证不超支",substring=true).assertIsDisplayed()
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasText("不需要 Worker 或设备公钥批准",substring=true));ui.onNodeWithText("不需要 Worker 或设备公钥批准",substring=true).assertIsDisplayed()
            ui.onNodeWithText("来源开关不等于外传授权",substring=true).assertIsDisplayed().assertTextContains("Mock 不联网",substring=true).assertTextContains("逐次确认后发送最小画像摘要",substring=true)
            screenshot("a5-direct-config-"+mode.name)
        }
    }
    @Test fun disabledDirectVerifierHasExplicitMessageAndCannotDispatchOrSave() {
        var calls=0;val service=object:DeepSeekKeyValidation{override suspend fun verify(key:AiSecret,isCurrent:()->Boolean){calls++}}
        val store=Store();show(AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service,enabled=false)))
        ui.onNodeWithText("正式验证未启用",substring=true).assertExists();scroll("ai_key_verify_save");ui.onNodeWithTag("ai_key_verify_save").assertIsNotEnabled()
        ui.onNodeWithText("本机 Mock 可独立使用",substring=true).assertExists()
        assertEquals(0,calls);assertNull(store.value)
    }
    @Test fun liveNoticeSeparatesKeyValidationFromPerRequestProfileConsentAndAccountCostsWithoutDispatch() {
        var calls=0;val service=object:DeepSeekKeyValidation{override suspend fun verify(key:AiSecret,isCurrent:()->Boolean){calls++}}
        val store=Store();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service));show(repo)
        assertEquals(AiKeyVerificationKind.LIVE,repo.verificationKind)
        for(mode in listOf(ThemeMode.DARK,ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value=mode }
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasText("验证不发送画像或生成推荐",substring=true))
            val notice=ui.onNodeWithText("验证不发送画像或生成推荐",substring=true).assertIsDisplayed()
            notice.assertTextContains("须另行逐次确认后",substring=true).assertTextContains("才发送最小画像摘要",substring=true)
            notice.assertTextContains("费用由你的 DeepSeek 账户承担",substring=true).assertTextContains("重试可能额外收费",substring=true)
            screenshot("i1-p2-live-config-"+mode.name)
            scroll("ai_key_verify_save");ui.onNodeWithTag("ai_key_verify_save").assertIsEnabled()
            ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasText("来源开关不等于外传授权",substring=true))
            ui.onNodeWithText("来源开关不等于外传授权",substring=true).assertIsDisplayed().assertTextContains("Mock 不联网",substring=true)
            ui.onAllNodesWithText("首页仍为 Mock",substring=true).assertCountEquals(0)
            ui.onAllNodesWithText("本阶段不外传真实画像",substring=true).assertCountEquals(0)
            assertEquals(0,calls);assertNull(store.value)
        }
    }

}
