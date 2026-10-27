package com.yingji.app.presentation.ai

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.yingji.app.BuildConfig
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Actual build-selected factories; synthetic TMDB transport and memory-only fake credentials. */
class DirectDevelopmentModeScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TmdbRankingUiFixture()
    private val local = LocalProfileTestFixture()
    private val models = ViewModelStore()
    private val screen = mutableStateOf("home")
    private val theme = mutableStateOf(ThemeMode.DARK)
    private val live get() = BuildConfig.AI_DIRECT_VERIFICATION_ENABLED
    private lateinit var store: DirectTestKeyStore
    private lateinit var keys: AiKeyConfigurationRepository
    private lateinit var usage: LocalAiUsageRepository
    private lateinit var home: AiHomeViewModel
    private lateinit var configuration: AiKeyConfigurationViewModel
    private lateinit var preview: TmdbCredentialViewModel
    @Before fun open() = runBlocking<Unit> {
        f.prepare(); local.seed()
        store = DirectTestKeyStore(if (live) "FAKE_BUILD_MODE_KEY_NOT_DISPATCHED" else "TEST_ONLY_BYOK_build_mode",
            if (live) AiKeyVerificationKind.LIVE else AiKeyVerificationKind.MOCK)
        keys = AiKeyConfigurationRepository(store, AiHomeRepositoryFactory.verifier(f.context)); keys.refresh()
        usage = LocalAiUsageRepository(f.context, AiTestStore())
        val repository = AiHomeRepositoryFactory.create(f.demo, MockCandidateCatalogRepository(), f.context, keys, usage)
        val personal = AiHomeRepositoryFactory.personalized(local.profiles, keys, { f.grant }, usage, local.manager)
        val reader = TmdbCandidatePreview(TmdbCandidateCatalogRepository(f.transport, { f.grant }))
        ui.runOnUiThread {
            home = AiHomeViewModel(repository, keys, local.manager, f.keys, reader, f.ranking, personal)
            configuration = AiKeyConfigurationViewModel(keys)
            models.put("home", home); models.put("configuration", configuration)
        }
        ui.setContent { YingJiTheme(theme.value) {
            when (screen.value) {
                "home" -> HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = home)
                "configuration" -> AiKeyConfigurationScreen(configuration) {}
                else -> Text("测试页面已关闭")
            }
        } }
        ui.waitUntil(10000) { !home.state.value.initializing }
        ui.runOnUiThread { preview = ViewModelProvider(ui.activity, TmdbCredentialViewModel.factory(f.keys)).get("tmdb-home-preview", TmdbCredentialViewModel::class.java) }
        ui.waitUntil(10000) { preview.state.value.status != TmdbCredentialStatus.LOADING }
    }
    @After fun close() {
        ui.runOnIdle { screen.value = "closed" }
        val jobs = listOf(home, configuration, preview).mapNotNull { it.viewModelScope.coroutineContext[Job] }
        ui.runOnUiThread { models.clear(); ui.activity.viewModelStore.clear() }
        runBlocking { withTimeout(10000) { jobs.joinAll() } }; local.close()
    }
    private fun homeClick(tag: String) {
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun configurationScroll(tag: String) {
        ui.onNodeWithTag("ai_key_scroll").performScrollToNode(hasTestTag(tag))
    }
    private fun capture(mode: ThemeMode) {
        val bitmap = ui.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val file = File(f.context.filesDir, "development-mode/" + (if (live) "live" else "mock") + "-" + mode.name.lowercase() + ".png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @Test fun actualFactoryUsesMatchingButtonAndConsentAndCancelDoesNotDispatchOrCountUsage() = runBlocking<Unit> {
        val before = local.tables(); val previous = f.demo.readManagement()
        homeClick("ai_source_tmdb"); homeClick("tmdb_home_read")
        ui.waitUntil(10000) { preview.preview.value.status == TmdbPreviewStatus.SUCCESS }
        assertEquals(8, f.calls.get())
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("tmdb_personal_recommend"))
        ui.onNodeWithTag("tmdb_personal_recommend").assertTextEquals(if (live) "真实画像推荐 · DeepSeek" else "真实画像推荐 · 协议 Mock")
        homeClick("tmdb_personal_recommend")
        try { ui.waitUntil(10000) { preview.personalizedConsent.value != null } }
        catch (error: AssertionError) { throw AssertionError("Consent preparation: " + preview.ranking.value.message, error) }
        assertEquals(!live, home.personalizedRecommendation!!.usesMockKey)
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }
            ui.onNodeWithTag("tmdb_personal_consent_scroll").performScrollToNode(
                hasText(if (live) "承担费用" else "本机协议 Mock", substring = true))
            ui.onNodeWithText(if (live) "承担费用" else "本机协议 Mock", substring = true).assertIsDisplayed()
            ui.onNodeWithTag("tmdb_personal_consent_confirm").assertTextEquals(if (live) "确认并请求" else "确认并本机验证")
            capture(mode)
        }
        ui.onNodeWithTag("tmdb_personal_consent_cancel").performClick()
        assertNull(preview.personalizedConsent.value); assertEquals(8, f.calls.get())
        assertEquals(0L, usage.snapshot().started); assertEquals(before, local.tables())
        assertEquals(previous, f.demo.readManagement())
    }
    @Test fun switchingBuildModesShowsRevalidationAndFailedLiveReplacementPreservesTheOldKey() = runBlocking<Unit> {
        val oldKind = if (live) AiKeyVerificationKind.MOCK else AiKeyVerificationKind.LIVE
        val old = "FAKE_PREVIOUS_MODE_KEY_NO_NETWORK"
        store.value = old; store.kind = oldKind; store.revision = java.util.UUID.randomUUID().toString()
        keys.refresh(); ui.runOnIdle { screen.value = "configuration" }
        configurationScroll("ai_key_status")
        ui.onNodeWithTag("ai_key_status").assertTextEquals("已保存的 Key 属于其他验证模式，请在当前模式重新验证并保存")
        ui.onNodeWithText(if (live) "验证不发送画像或生成推荐" else "直连协议 Mock 验证", substring = true).assertExists()
        configurationScroll("ai_key_input")
        ui.onNodeWithTag("ai_key_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertEquals("", ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        ui.onNodeWithTag("ai_key_input").performTextInput("TEST_ONLY_BYOK_switched_mode")
        configurationScroll("ai_key_verify_save"); ui.onNodeWithTag("ai_key_verify_save").performClick()
        ui.waitUntil(10000) { configuration.actionState.value.message != null && !keys.state.value.verifying }
        if (live) {
            assertEquals(AiKeyProblem.INVALID_KEY, keys.state.value.error)
            assertEquals(old, store.value); assertEquals(oldKind, store.kind)
            configurationScroll("ai_key_status")
            ui.onNodeWithTag("ai_key_status").assertTextEquals("已保存的 Key 属于其他验证模式，请在当前模式重新验证并保存")
        } else {
            assertEquals("TEST_ONLY_BYOK_switched_mode", store.value); assertEquals(AiKeyVerificationKind.MOCK, store.kind)
            assertEquals("已保存通过 Mock 验证的测试 Key；当前构建使用本机协议 Mock", configuration.actionState.value.message)
            configurationScroll("ai_key_input")
            assertEquals("", ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
        assertEquals(0L, usage.snapshot().started)
    }
}
