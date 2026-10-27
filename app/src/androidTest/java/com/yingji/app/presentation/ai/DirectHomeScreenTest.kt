package com.yingji.app.presentation.ai

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class DirectHomeScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore(); private lateinit var vm: AiHomeViewModel
    private lateinit var keys: AiKeyConfigurationRepository; private lateinit var demo: MockAiHomeRepository
    private val theme = mutableStateOf(ThemeMode.DARK); private val added = mutableListOf<MockRecommendation>()
    private var original = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    @Before fun viewport() {
        original = ui.activity.requestedOrientation
        val landscape = InstrumentationRegistry.getArguments().getString("a8Orientation") == "landscape"
        val orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        ui.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == orientation }; assertEquals(orientation, ui.activity.resources.configuration.orientation)
    }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }; ui.activityRule.scenario.onActivity { it.requestedOrientation = original } }
    private fun show(configured: Boolean = true) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        keys = AiKeyConfigurationRepository(DirectTestKeyStore(), AiHomeRepositoryFactory.verifier(context))
        val usage = LocalAiUsageRepository(context, AiTestStore())
        demo = MockAiHomeRepository(context, AiTestStore(), localUsage = usage)
        AiHomeRepositoryFactory.create(demo, MockCandidateCatalogRepository(), context, keys)
        runBlocking { keys.refresh(); if (configured) keys.verifyAndSave("TEST_ONLY_BYOK_a8_ui"); demo.saveSetup(AiDemoSetup()) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AiHomeViewModel(demo, keys); models.put("vm", vm) }
        ui.setContent { YingJiTheme(theme.value) { HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = vm, onAddAiMovie = { added.add(it) }) } }
        ui.waitUntil(5000) { !vm.state.value.initializing }; click("ai_enter")
    }
    private fun click(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)); ui.onNodeWithTag(tag).performClick() }
    private fun choose() { click("ai_scenario"); ui.onNodeWithTag("ai_scenario_DIRECT").performClick(); ui.onNodeWithText("确认直连协议联调范围").assertIsDisplayed() }
    private fun submit() { click("ai_mode_FOR_YOU"); ui.waitUntil(5000) { vm.state.value.result != AiResultState.LOADING } }
    @Test fun consentCanBeCanceledInBothThemesThenMockResultsRequireConfirmationBeforeAdd() {
        show()
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }; choose(); assertEquals(MockScenario.SUCCESS, vm.state.value.scenario)
            assertTrue(runBlocking { demo.readManagement().history.isEmpty() })
            ui.waitForIdle(); val automation = InstrumentationRegistry.getInstrumentation().uiAutomation; automation.waitForIdle(100, 5000)
            val image = requireNotNull(automation.takeScreenshot()); val orientation = if (ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
            File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "a8-direct-" + orientation + "-" + mode.name.lowercase() + ".png").outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
            ui.onNodeWithTag("ai_direct_consent_cancel").performClick(); assertEquals(MockScenario.SUCCESS, vm.state.value.scenario)
        }
        choose(); ui.onNodeWithTag("ai_direct_consent_confirm").performClick(); assertEquals(MockScenario.DIRECT, vm.state.value.scenario)
        submit(); assertEquals(AiResultState.SUCCESS, vm.state.value.result); assertTrue(vm.state.value.message!!.contains("不联网"))
        val item = vm.state.value.recommendations.first(); click("ai_add_" + item.id); assertTrue(added.isEmpty()); ui.onNodeWithTag("ai_add_confirm").performClick()
        assertEquals(listOf(item), added); submit(); assertTrue(vm.state.value.message!!.contains("不联网")); assertFalse(vm.state.value.message!!.contains("缓存")); assertEquals(0L, runBlocking { demo.readLocalUsage() }!!.started)
        runBlocking { keys.delete() }; ui.waitUntil(5000) { vm.state.value.recommendations.isEmpty() }; assertNull(vm.state.value.pendingAdd)
        submit(); assertTrue(vm.state.value.message!!.contains("请先")); assertEquals(2, runBlocking { demo.readManagement() }.history.size)
    }
    @Test fun absentKeyBlocksDirectButSwitchingBackStillAllowsFreeMockAndManagement() {
        show(configured = false); choose(); ui.onNodeWithTag("ai_direct_consent_confirm").performClick(); submit()
        assertTrue(vm.state.value.message!!.contains("请先")); assertTrue(runBlocking { demo.readManagement().history.isEmpty() })
        click("ai_scenario"); ui.onNodeWithTag("ai_scenario_SUCCESS").performClick(); submit(); assertEquals(AiResultState.SUCCESS, vm.state.value.result)
        click("ai_manage_open"); ui.waitUntil(5000) { !vm.state.value.managementLoading }; ui.onNodeWithTag("ai_manage_tab_HISTORY").performClick()
        assertEquals(1, runBlocking { demo.readManagement() }.history.size); assertEquals(0L, runBlocking { demo.readLocalUsage() }!!.started)
    }
}
