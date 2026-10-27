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

class AiPreferenceRemovalScreenTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val models=ViewModelStore();private lateinit var vm:AiHomeViewModel
    private val theme=mutableStateOf(ThemeMode.DARK);private var original=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    @Before fun viewport(){
        original=ui.activity.requestedOrientation
        val landscape=InstrumentationRegistry.getArguments().getString("a7Orientation")=="landscape"
        val requested=if(landscape)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val expected=if(landscape)Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        ui.activityRule.scenario.onActivity{it.requestedOrientation=requested};ui.waitUntil(10000){ui.activity.resources.configuration.orientation==expected};assertEquals(expected,ui.activity.resources.configuration.orientation)
    }
    @After fun close(){InstrumentationRegistry.getInstrumentation().runOnMainSync{models.clear()};ui.activityRule.scenario.onActivity{it.requestedOrientation=original}}
    private fun show(repo:AiHomeRepository){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{vm=AiHomeViewModel(repo);models.put("vm",vm)}
        ui.setContent{YingJiTheme(theme.value){HomeScreen(emptyList(),emptyMap(),{},{},{},aiViewModel=vm)}}
        ui.waitUntil(5000){!vm.state.value.initializing};open()
    }
    private fun open(){ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open"));ui.onNodeWithTag("ai_manage_open").performClick();ui.waitUntil(5000){!vm.state.value.managementLoading}}
    private fun click(tag:String){ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasTestTag(tag));ui.onNodeWithTag(tag).performClick()}
    private fun chooseRemoval(){click("ai_manage_pref_科幻_LIKE");ui.onNodeWithText("确认移除明确偏好？").assertIsDisplayed();ui.onNodeWithTag("ai_manage_remove_confirm").assertIsDisplayed();ui.onNodeWithTag("ai_manage_remove_cancel").assertIsDisplayed()}
    @Test fun confirmationCanBeCanceledInBothThemesThenOnlyConfirmedPreferenceIsRemoved(){
        val repo=AiTestRepository();runBlocking{repo.saveSetup(AiDemoSetup(preferences=mapOf("科幻" to PreferenceDirection.LIKE)));repo.saveFeedback("mock-0",AiFeedback.LIKE)};show(repo)
        val before=runBlocking{repo.readManagement()}
        for(mode in listOf(ThemeMode.DARK,ThemeMode.LIGHT)){
            ui.runOnIdle{theme.value=mode};chooseRemoval();assertEquals(before,runBlocking{repo.readManagement()})
            ui.waitForIdle()
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(100,5000)
            val image=requireNotNull(automation.takeScreenshot())
            val orientation=if(ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT)"portrait" else "landscape"
            File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null),"a7-removal-"+orientation+"-"+mode.name.lowercase()+".png").outputStream().use{assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};image.recycle()
            ui.onNodeWithTag("ai_manage_remove_cancel").performClick();assertEquals(before,runBlocking{repo.readManagement()})
        }
        chooseRemoval();ui.onNodeWithTag("ai_manage_remove_confirm").performClick();ui.waitUntil(5000){vm.state.value.pendingPreferenceRemoval==null&&!vm.state.value.managementSaving}
        assertTrue(runBlocking{repo.readSetup()}.preferences.isEmpty());assertEquals(before.inferred,runBlocking{repo.readManagement()}.inferred)
        ui.onNodeWithTag("ai_manage_close").performClick();open();click("ai_manage_pref_科幻_LIKE")
        ui.waitUntil(5000){!vm.state.value.managementSaving};assertEquals(PreferenceDirection.LIKE,runBlocking{repo.readSetup()}.preferences["科幻"]);assertEquals(0,repo.requests)
    }
    @Test fun failedRemovalKeepsDataAndDialogThenRetryPersistsEmptyPreference(){
        val repo=AiTestRepository();runBlocking{repo.saveSetup(AiDemoSetup(preferences=mapOf("科幻" to PreferenceDirection.LIKE)))};show(repo);chooseRemoval();repo.store.failWrites=true
        ui.onNodeWithTag("ai_manage_remove_confirm").performClick();ui.waitUntil(5000){vm.state.value.managementError!=null};ui.onNodeWithTag("ai_manage_remove_error").assertIsDisplayed()
        assertEquals(PreferenceDirection.LIKE,runBlocking{repo.readSetup()}.preferences["科幻"]);assertNull(vm.state.value.managementMessage)
        repo.store.failWrites=false;ui.onNodeWithTag("ai_manage_remove_confirm").performClick();ui.waitUntil(5000){vm.state.value.pendingPreferenceRemoval==null&&!vm.state.value.managementSaving}
        ui.onNodeWithTag("ai_manage_close").performClick();open();ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasTestTag("ai_manage_pref_科幻_LIKE"));ui.onNodeWithTag("ai_manage_pref_科幻_LIKE").assertIsNotSelected()
        ui.onNodeWithTag("ai_manage_tab_HISTORY").performClick();ui.onNodeWithTag("ai_manage_history_empty").assertIsDisplayed();assertEquals(0,repo.requests)
    }
    @Test fun unavailableUsageStillAllowsProfileAndResetWithoutClaimingServerQuotaOrResettingUsage(){
        val context=ApplicationProvider.getApplicationContext<Context>();val store=AiTestStore();val usage=LocalAiUsageRepository(context,store)
        val demo=MockAiHomeRepository(context,AiTestStore(),localUsage=usage);runBlocking{demo.saveSetup(AiDemoSetup());usage.task{}};val before=runBlocking{usage.snapshot()};store.failWrites=true;show(demo)
        click("ai_manage_pref_科幻_LIKE");ui.waitUntil(5000){!vm.state.value.managementSaving};assertEquals(PreferenceDirection.LIKE,runBlocking{demo.readSetup()}.preferences["科幻"])
        ui.onNodeWithTag("ai_manage_tab_QUOTA").performClick();ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasTestTag("ai_local_usage_error"));ui.onNodeWithTag("ai_local_usage_error").assertIsDisplayed()
        ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasText("Mock 额度场景仅用于演示",substring=true));ui.onNodeWithText("Mock 额度场景仅用于演示，不代表 DeepSeek 额度或账户费用；本地软额度可以被绕过。").assertIsDisplayed()
        ui.onNodeWithText("真实额度以服务端返回为准",substring=true).assertDoesNotExist()
        ui.onNodeWithTag("ai_manage_tab_CLEANUP").performClick();click("ai_manage_clear_ALL");ui.onNodeWithTag("ai_manage_clear_confirm").performClick();ui.waitUntil(5000){vm.state.value.pendingClear==null&&!vm.state.value.managementSaving}
        store.failWrites=false;assertEquals(before,runBlocking{usage.snapshot()});assertEquals(AiDemoSetup(),runBlocking{demo.readSetup()})
        ui.onNodeWithTag("ai_manage_tab_QUOTA").performClick();click("ai_manage_quota_refresh");ui.waitUntil(5000){!vm.state.value.managementSaving}
        ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasTestTag("ai_local_usage_counts"));ui.onNodeWithTag("ai_local_usage_counts").assertTextEquals("已发起：1；已成功：1；错误/取消：0；进行中或结果未知：0")
    }
}
