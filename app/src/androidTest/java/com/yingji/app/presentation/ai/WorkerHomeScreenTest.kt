package com.yingji.app.presentation.ai

import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class WorkerHomeScreenTest {
    @get:Rule val ui = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var vm: AiHomeViewModel
    private val session = "ui-" + UUID.randomUUID().toString()
    private val added = mutableListOf<String>()
    private fun fixture(fault: String? = null): JSONObject {
        val c = URL("http://127.0.0.1:8787/__test/state").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 10000; c.setRequestProperty("X-Yingji-Mock-Session", session)
            if (fault != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(JSONObject().put("fault", fault).toString().toByteArray()) } }
            assertEquals(200, c.responseCode); return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    private fun show() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val demo = MockAiHomeRepository(context, AiTestStore())
        val catalog = object : CandidateCatalogRepository {
            override suspend fun load(): CandidateCatalog {
                val data = MockCandidateCatalogRepository().load()
                return data.copy(sources = data.sources.map { it.copy(license = it.license.copy(cacheAllowed = true, maxCacheAgeMillis = 60000)) })
            }
        }
        val repo = WorkerDemoHomeRepository(demo, workerTestClient(session), catalog)
        runBlocking { repo.saveSetup(AiDemoSetup()) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AiHomeViewModel(repo); models.put("ai", vm) }
        ui.setContent { YingJiTheme(ThemeMode.DARK) { HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = vm, onAddAiMovie = { added.add(it.title) }) } }
        ui.waitUntil(5000) { !vm.state.value.initializing }
        scroll("ai_enter"); ui.onNodeWithTag("ai_enter").performClick()
    }
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    private fun worker() {
        scroll("ai_scenario"); ui.onNodeWithTag("ai_scenario").performClick()
        ui.onNodeWithTag("ai_scenario_WORKER").performScrollTo().performClick()
    }
    private fun submit(mode: AiMode = AiMode.FOR_YOU) {
        scroll("ai_mode_" + mode.name); ui.onNodeWithTag("ai_mode_" + mode.name).performClick()
        ui.waitUntil(10000) { vm.state.value.result != AiResultState.LOADING }
    }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() } }
    @Test fun explicitMenuSelectionCallsWorkerCacheAndHistoryDoNotAndAddStillNeedsConfirmation() {
        show(); assertEquals(0, fixture().getInt("modelCalls")); worker(); assertEquals(0, fixture().getInt("modelCalls"))
        submit(); assertEquals(AiResultState.SUCCESS, vm.state.value.result); assertEquals(3, vm.state.value.recommendations.size)
        scroll("ai_message"); ui.onNodeWithTag("ai_message").assertTextContains("本地 Worker 联调", substring = true)
        assertEquals(1, fixture().getInt("modelCalls")); submit(); assertEquals(1, fixture().getInt("modelCalls"))
        assertTrue(vm.state.value.message!!.contains("缓存"))
        scroll("ai_manage_open"); ui.onNodeWithTag("ai_manage_open").performClick(); ui.waitUntil(10000) { vm.state.value.management != null }
        ui.onNodeWithTag("ai_manage_tab_QUOTA").performClick(); assertFalse(vm.state.value.management!!.quotaFixtureEditable)
        ui.onNodeWithTag("ai_manage_quota_AVAILABLE").assertDoesNotExist()
        ui.onNodeWithTag("ai_manage_tab_HISTORY").performClick(); assertEquals(2, vm.state.value.management!!.history.size)
        assertEquals(1, fixture().getInt("modelCalls"))
        ui.onNodeWithTag("ai_manage_tab_CLEANUP").performClick()
        ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasTestTag("ai_manage_clear_HISTORY"))
        ui.onNodeWithTag("ai_manage_clear_HISTORY").performClick(); ui.onNodeWithTag("ai_manage_clear_confirm").performClick()
        ui.waitUntil(10000) { !vm.state.value.managementSaving && vm.state.value.management!!.history.isEmpty() }
        ui.onNodeWithTag("ai_manage_close").performClick()
        submit(); assertTrue(vm.state.value.message!!.contains("缓存")); assertEquals(1, fixture().getInt("modelCalls"))
        val item = vm.state.value.recommendations.first()
        scroll("ai_add_" + item.id); ui.onNodeWithTag("ai_add_" + item.id).performClick(); assertTrue(added.isEmpty())
        ui.onNodeWithText("取消").performClick(); assertTrue(added.isEmpty())
        scroll("ai_add_" + item.id); ui.onNodeWithTag("ai_add_" + item.id).performClick()
        ui.onNodeWithTag("ai_add_confirm").performClick(); assertEquals(listOf(item.title), added)
        scroll("ai_feedback_" + item.id + "_LIKE"); ui.onNodeWithTag("ai_feedback_" + item.id + "_LIKE").performClick()
        ui.waitUntil(5000) { vm.state.value.setup.feedback[item.id] == AiFeedback.LIKE }
        assertEquals(1, fixture().getInt("modelCalls"))
        capture("worker-menu-success")
    }
    @Test fun modelFormatFailureShowsReliableLocalFallbackAndSwitchingBackUsesOriginalMock() {
        show(); fixture("model-format"); worker(); submit()
        assertEquals(AiResultState.SUCCESS, vm.state.value.result); assertEquals(3, vm.state.value.recommendations.size)
        assertTrue(vm.state.value.message!!.contains("格式无效")); assertEquals(1, fixture().getInt("modelCalls"))
        scroll("ai_message"); ui.onNodeWithTag("ai_message").assertTextContains("本地可信", substring = true); capture("worker-failure-fallback")
        scroll("ai_scenario"); ui.onNodeWithTag("ai_scenario").performClick(); ui.onNodeWithTag("ai_scenario_SUCCESS").performClick()
        ui.waitUntil(5000) { vm.state.value.quota.canRequestNewTask }; submit(); assertEquals(AiResultState.SUCCESS, vm.state.value.result)
        assertEquals(1, fixture().getInt("modelCalls")); assertNull(vm.state.value.message)
    }
    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getExternalFilesDir(null), "a8-" + name + ".png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
