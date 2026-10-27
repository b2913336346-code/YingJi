package com.yingji.app.presentation.ai

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import java.time.LocalDate

class LocalAiUsageScreenTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val models=ViewModelStore(); private lateinit var vm:AiHomeViewModel
    private val theme=mutableStateOf(ThemeMode.DARK)
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun open() {
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open"))
        ui.onNodeWithTag("ai_manage_open").performClick(); ui.waitUntil(5000){!vm.state.value.managementLoading}
        ui.onNodeWithTag("ai_manage_tab_QUOTA").performClick()
    }
    private fun scroll(tag:String)=ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasTestTag(tag))
    private fun show(repo:AiHomeRepository) {
        main { vm=AiHomeViewModel(repo); models.put("vm",vm) }
        ui.setContent { YingJiTheme(theme.value){HomeScreen(emptyList(),emptyMap(),{},{},{},aiViewModel=vm)} }
        ui.waitUntil(5000){!vm.state.value.initializing}; open()
    }
    private var originalOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    @Before fun establishViewport() {
        originalOrientation=ui.activity.requestedOrientation
        val portrait=InstrumentationRegistry.getArguments().getString("a6Orientation")=="portrait"
        val requested=if(portrait)ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val expected=if(portrait)Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        ui.activityRule.scenario.onActivity { it.requestedOrientation=requested }
        ui.waitUntil(10000){ui.activity.resources.configuration.orientation==expected}
        assertEquals(expected,ui.activity.resources.configuration.orientation)
    }
    @After fun close(){ main { models.clear() }; ui.activityRule.scenario.onActivity { it.requestedOrientation=originalOrientation } }
    @Test fun existingQuotaEntryShowsCountsAndCostNoticeInBothThemesAndRefreshesDate() {
        val context=ApplicationProvider.getApplicationContext<Context>(); var day=LocalDate.of(2026,10,22)
        val usage=LocalAiUsageRepository(context,AiTestStore()){day}; val demo=MockAiHomeRepository(context,AiTestStore(),localUsage=usage)
        runBlocking { demo.saveSetup(AiDemoSetup()); usage.task { }; runCatching { usage.task { error("fixture") } } }; show(demo)
        for(mode in listOf(ThemeMode.DARK,ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value=mode }; scroll("ai_local_usage_title"); ui.onNodeWithTag("ai_local_usage_title").assertIsDisplayed()
            scroll("ai_local_usage_counts"); ui.onNodeWithTag("ai_local_usage_counts").assertTextEquals("已发起：2；已成功：1；错误/取消：1；进行中或结果未知：0")
            ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasText("这不是 DeepSeek 强制限额",substring=true))
            ui.onNodeWithText("这不是 DeepSeek 强制限额",substring=true).assertIsDisplayed()
            val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(context.getExternalFilesDir(null),"a6-local-"+mode.name.lowercase()+".png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }; bitmap.recycle()
        }
        day=day.plusDays(1); scroll("ai_manage_quota_refresh"); ui.onNodeWithTag("ai_manage_quota_refresh").performClick()
        ui.waitUntil(5000){!vm.state.value.managementSaving}; scroll("ai_local_usage_day"); ui.onNodeWithTag("ai_local_usage_day").assertTextEquals("本地日期：2026-10-23；每日建议 30 次成功任务。")
        scroll("ai_local_usage_counts"); ui.onNodeWithTag("ai_local_usage_counts").assertTextEquals("已发起：0；已成功：0；错误/取消：0；进行中或结果未知：0")
    }
    @Test fun quotaRefreshFailureShowsErrorAndRetryDoesNotEraseStoredStatistics() {
        val context=ApplicationProvider.getApplicationContext<Context>(); val store=AiTestStore(); val usage=LocalAiUsageRepository(context,store)
        val demo=MockAiHomeRepository(context,AiTestStore(),localUsage=usage); runBlocking { demo.saveSetup(AiDemoSetup()); usage.task { } }; show(demo)
        store.failWrites=true; scroll("ai_manage_quota_refresh"); ui.onNodeWithTag("ai_manage_quota_refresh").performClick()
        ui.waitUntil(5000){vm.state.value.managementError!=null}; ui.onNodeWithTag("ai_manage_error").assertIsDisplayed(); assertNull(vm.state.value.managementMessage)
        store.failWrites=false; ui.onNodeWithTag("ai_manage_quota_refresh").performClick(); ui.waitUntil(5000){!vm.state.value.managementSaving&&vm.state.value.managementError==null}
        scroll("ai_local_usage_counts"); ui.onNodeWithTag("ai_local_usage_counts").assertTextEquals("已发起：1；已成功：1；错误/取消：0；进行中或结果未知：0")
        ui.onNodeWithTag("ai_manage_close").performClick(); open(); assertEquals(1L,vm.state.value.management!!.localUsage!!.succeeded)
    }
}
