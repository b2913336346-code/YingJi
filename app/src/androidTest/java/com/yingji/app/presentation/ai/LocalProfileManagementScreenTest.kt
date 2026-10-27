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
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class LocalProfileManagementScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var f: LocalProfileTestFixture; private lateinit var vm: AiHomeViewModel
    private val models = ViewModelStore(); private lateinit var mock: AiTestRepository
    private val theme = mutableStateOf(ThemeMode.DARK); private var original = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    @Before fun setup() {
        DatabaseHealthMonitor.resetForTesting(); f = LocalProfileTestFixture(); original = ui.activity.requestedOrientation
        val landscape = InstrumentationRegistry.getArguments().getString("profileOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        ui.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == expected }; assertEquals(expected, ui.activity.resources.configuration.orientation)
    }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }; f.close(); ui.activityRule.scenario.onActivity { it.requestedOrientation = original } }
    private fun show(local: LocalProfileManagementRepository = f.manager) {
        mock = AiTestRepository(); runBlocking { mock.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE))); mock.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AiHomeViewModel(mock, localProfiles = local); models.put("vm", vm) }
        ui.setContent { YingJiTheme(theme.value) { HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = vm) } }
        ui.waitUntil(5000) { !vm.state.value.initializing }; open()
        ui.onNodeWithText("AI 管理 · Mock").assertIsDisplayed(); ui.onNodeWithTag("ai_manage_scope_LOCAL_PROFILE").performClick()
        ui.waitUntil(5000) { !vm.state.value.localProfile.loading }
    }
    private fun open() { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open")); ui.onNodeWithTag("ai_manage_open").performClick(); ui.waitUntil(5000) { !vm.state.value.managementLoading } }
    private fun click(tag: String) { ui.onNodeWithTag("real_profile_scroll").performScrollToNode(hasTestTag(tag)); ui.onNodeWithTag(tag).performClick() }
    private fun saved() { ui.waitUntil(5000) { !vm.state.value.localProfile.saving } }
    @Test fun sameIdEntitiesAreIndependentAndRemovalCancellationFailureRetryAndReopenAreWired() {
        runBlocking { f.seed() }; var fail = false
        val local = object : LocalProfileManagementRepository by f.manager { override suspend fun removePreference(featureId: String): ProfileSnapshot { if (fail) error("fixture"); return f.manager.removePreference(featureId) } }
        show(local); val raw = f.tables(); val mockBefore = runBlocking { mock.readManagement() }
        ui.onNodeWithTag("real_profile_scroll").performScrollToNode(hasTestTag("real_profile_pref_category:7_LIKE")); ui.onNodeWithTag("real_profile_pref_category:7_LIKE").assertIsSelected()
        ui.onNodeWithTag("real_profile_scroll").performScrollToNode(hasTestTag("real_profile_pref_tag:7_DISLIKE")); ui.onNodeWithTag("real_profile_pref_tag:7_DISLIKE").assertIsSelected()
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }; click("real_profile_pref_category:7_LIKE"); ui.onNodeWithText("确认移除真实本地偏好？").assertIsDisplayed()
            ui.waitForIdle(); val automation = InstrumentationRegistry.getInstrumentation().uiAutomation; automation.waitForIdle(100, 5000)
            val image = requireNotNull(automation.takeScreenshot()); val orientation = if (ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
            File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "local-profile-" + orientation + "-" + mode.name.lowercase() + ".png").outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
            ui.onNodeWithTag("real_profile_cancel").performClick(); assertEquals(2, runBlocking { f.profiles.getProfile() }.explicitPreferences.size)
        }
        fail = true; click("real_profile_pref_category:7_LIKE"); ui.onNodeWithTag("real_profile_confirm").performClick(); ui.waitUntil(5000) { vm.state.value.localProfile.error != null }
        ui.onNodeWithTag("real_profile_confirmation_error").assertIsDisplayed(); assertEquals(2, runBlocking { f.profiles.getProfile() }.explicitPreferences.size)
        fail = false; ui.onNodeWithTag("real_profile_confirm").performClick(); ui.waitUntil(5000) { vm.state.value.localProfile.pendingRemoval == null && !vm.state.value.localProfile.saving }
        assertEquals(listOf(f.tag.id), runBlocking { f.profiles.getProfile() }.explicitPreferences.map { it.feature.id })
        ui.onNodeWithTag("ai_manage_scope_MOCK").performClick(); assertEquals(mockBefore, runBlocking { mock.readManagement() })
        ui.onNodeWithTag("ai_manage_close").performClick(); open(); ui.onNodeWithTag("ai_manage_scope_LOCAL_PROFILE").performClick(); ui.waitUntil(5000) { !vm.state.value.localProfile.loading }
        ui.onNodeWithTag("real_profile_scroll").performScrollToNode(hasTestTag("real_profile_pref_category:7_LIKE")); ui.onNodeWithTag("real_profile_pref_category:7_LIKE").assertIsNotSelected(); assertEquals(raw, f.tables())
    }
    @Test fun sourcesCorrectionAndConfirmedResetPreserveOriginalLibraryAndMock() {
        runBlocking { f.seed() }; show(); val raw = f.tables(); val mockBefore = runBlocking { mock.readManagement() }
        click("real_profile_source_ratings"); saved(); click("real_profile_source_behavior"); saved()
        assertTrue(vm.state.value.localProfile.snapshot!!.profile.inferredPreferences.all { it.source == ProfileSource.FEEDBACK })
        click("real_profile_correct_topic:科幻_FEEDBACK"); saved(); assertTrue(vm.state.value.localProfile.snapshot!!.profile.suppressedFeatureIds.contains(f.topic.id))
        click("real_profile_undo_topic:科幻"); saved(); assertFalse(vm.state.value.localProfile.snapshot!!.profile.suppressedFeatureIds.contains(f.topic.id))
        val before = runBlocking { f.profiles.getProfile() }; click("real_profile_reset"); ui.onNodeWithTag("real_profile_cancel").performClick(); assertEquals(before, runBlocking { f.profiles.getProfile() })
        click("real_profile_reset"); ui.onNodeWithTag("real_profile_confirm").performClick(); ui.waitUntil(5000) { !vm.state.value.localProfile.pendingReset && !vm.state.value.localProfile.saving }
        val after = runBlocking { f.profiles.getProfile() }; assertEquals(ProfileSourceSettings(), after.settings); assertTrue(after.explicitPreferences.isEmpty()); assertTrue(after.inferredPreferences.isEmpty()); assertTrue(f.db.aiProfileDao().let { runBlocking { it.feedback() } }.isEmpty())
        assertEquals(raw, f.tables()); assertEquals(mockBefore, runBlocking { mock.readManagement() }); assertTrue(f.poster!!.isFile)
    }
    @Test fun failedReadCanRetryAndEmptyRealProfileDoesNotCopyMockPreferencesOrFeedback() {
        var fail = true
        val local = object : LocalProfileManagementRepository by f.manager { override fun observe(): Flow<LocalProfileManagementSnapshot> = flow { if (fail) error("fixture read"); emitAll(f.manager.observe()) } }
        show(local); ui.onNodeWithTag("real_profile_error").assertIsDisplayed(); assertNull(vm.state.value.localProfile.snapshot)
        fail = false; ui.onNodeWithTag("real_profile_retry").performClick(); ui.waitUntil(5000) { vm.state.value.localProfile.snapshot != null }
        val profile = vm.state.value.localProfile.snapshot!!.profile; assertEquals(ProfileSourceSettings(), profile.settings); assertTrue(profile.explicitPreferences.isEmpty()); assertTrue(profile.inferredPreferences.isEmpty())
        ui.onNodeWithTag("real_profile_scroll").performScrollToNode(hasTestTag("real_profile_inferred_empty")); ui.onNodeWithTag("real_profile_inferred_empty").assertIsDisplayed()
        assertEquals(PreferenceDirection.DISLIKE, runBlocking { mock.readSetup() }.preferences["科幻"]); assertTrue(runBlocking { f.db.aiProfileDao().feedback() }.isEmpty()); assertEquals(1, mock.requests)
    }
}
