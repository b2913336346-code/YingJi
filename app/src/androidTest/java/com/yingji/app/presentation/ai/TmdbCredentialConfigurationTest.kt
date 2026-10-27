package com.yingji.app.presentation.ai

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.TmdbCredentialStore
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import java.io.*
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class TmdbCredentialConfigurationTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private val models = ViewModelStore()
    private val open = mutableStateOf(true)
    private val theme = mutableStateOf(ThemeMode.DARK)
    private lateinit var vm: TmdbCredentialViewModel
    private var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private class Memory : TmdbSecureCredentialStore {
        var key: String? = null; var savedAt: Long? = null; var revision = "empty"; var fail = false
        override suspend fun read() = TmdbCredentialRecord(key?.let(TmdbSecret::fromInput), savedAt, revision)
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String {
            if (fail) throw IOException("TEST_ONLY_TMDB_failure_must_not_leak")
            this.key = key?.useValue { it }; this.savedAt = savedAt; revision = java.util.UUID.randomUUID().toString(); return revision
        }
    }
    @Before fun orientation() {
        val landscape = InstrumentationRegistry.getArguments().getString("tmdbOrientation") == "landscape"
        ui.runOnUiThread { oldOrientation = ui.activity.requestedOrientation; ui.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
    }
    @After fun close() { ui.runOnUiThread { models.clear(); ui.activity.requestedOrientation = oldOrientation } }
    private fun show(repo: TmdbCredentialRepository) {
        ui.runOnUiThread { vm = TmdbCredentialViewModel(repo); models.put("tmdb", vm) }
        ui.setContent { YingJiTheme(theme.value) {
            if (open.value) TmdbCredentialDialog(vm) { open.value = false }
            else TextButton(onClick = { open.value = true }, modifier = Modifier.testTag("tmdb_reopen")) { Text("重新打开") }
        } }
        ui.waitUntil(5000) { vm.state.value.status != TmdbCredentialStatus.LOADING }
    }
    private fun scroll(tag: String) { ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag(tag)) }
    private fun input(text: String) { scroll("tmdb_token_input"); ui.onNodeWithTag("tmdb_token_input").performTextClearance(); ui.onNodeWithTag("tmdb_token_input").performTextInput(text) }
    private fun save() { scroll("tmdb_token_save"); ui.onNodeWithTag("tmdb_token_save").performClick(); ui.waitUntil(5000) { !vm.state.value.saving && vm.actions.value.message != null } }
    private fun textInput() = ui.onNodeWithTag("tmdb_token_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    @Test fun encryptedSaveReplaceReopenAndConfirmedDeleteWorkWithoutNetworkVerification() {
        val file = File(folder.root, "tmdb.enc"); val repo = TmdbCredentialRepository(TmdbCredentialStore(file))
        try {
            show(repo); save(); scroll("tmdb_config_message"); ui.onNodeWithTag("tmdb_config_message").assertTextContains("空白输入不会保存", substring = true); assertFalse(file.exists())
            input("TEST_ONLY_TMDB_first"); ui.onNodeWithTag("tmdb_token_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            ui.onNodeWithTag("tmdb_token_visibility").performClick(); ui.onNodeWithTag("tmdb_token_input").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
            save(); assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status)
            scroll("tmdb_token_input"); assertEquals("", textInput()); ui.onNodeWithTag("tmdb_token_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("TEST_ONLY_TMDB_first"))
            val first = repo.state.value.revision; input("TEST_ONLY_TMDB_second"); save(); assertNotEquals(first, repo.state.value.revision)
            input("TEST_ONLY_TMDB_unsaved"); ui.onNodeWithTag("tmdb_config_close").performClick(); ui.onNodeWithTag("tmdb_reopen").performClick()
            scroll("tmdb_token_input"); assertEquals("", textInput())
            runBlocking { TmdbCredentialStore(file).read().use { assertEquals("TEST_ONLY_TMDB_second", it!!.key!!.useValue { s -> s }) } }
            scroll("tmdb_token_delete"); ui.onNodeWithTag("tmdb_token_delete").performClick(); ui.onNodeWithTag("tmdb_delete_cancel").performClick()
            assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status)
            scroll("tmdb_token_delete"); ui.onNodeWithTag("tmdb_token_delete").performClick(); ui.onNodeWithTag("tmdb_delete_confirm").performClick()
            ui.waitUntil(5000) { repo.state.value.status == TmdbCredentialStatus.NOT_CONFIGURED }
            runBlocking { TmdbCredentialStore(file).read().use { assertNull(it!!.key) } }
        } finally { if (file.exists()) DataInputStream(file.inputStream()).use { it.readInt(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(it.readUTF()) } }
    }
    @Test fun failedReplacementAndDeleteKeepOldTokenAndAllowRetry() {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory); runBlocking { repo.save("TEST_ONLY_TMDB_retained") }; show(repo)
        memory.fail = true; input("TEST_ONLY_TMDB_new"); save(); assertEquals("TEST_ONLY_TMDB_retained", memory.key)
        scroll("tmdb_config_message"); ui.onNodeWithTag("tmdb_config_message").assertTextContains("保存失败", substring = true)
        scroll("tmdb_token_delete"); ui.onNodeWithTag("tmdb_token_delete").performClick(); ui.onNodeWithTag("tmdb_delete_confirm").performClick()
        ui.waitUntil(5000) { vm.actions.value.message?.contains("删除失败") == true }
        ui.onNodeWithTag("tmdb_delete_error").assertTextContains("删除失败", substring = true); assertTrue(vm.actions.value.confirmDelete)
        assertEquals("TEST_ONLY_TMDB_retained", memory.key); assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status)
        memory.fail = false; ui.onNodeWithTag("tmdb_delete_confirm").performClick(); ui.waitUntil(5000) { memory.key == null }
    }
    @Test fun bothThemesRemainScrollableAndCaptureEmptyConfiguration() {
        show(TmdbCredentialRepository(Memory()))
        val context = ApplicationProvider.getApplicationContext<Context>(); val orientation = if (ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnUiThread { theme.value = mode }; scroll("tmdb_token_save"); ui.onNodeWithTag("tmdb_token_save").assertIsDisplayed()
            scroll("tmdb_config_status"); ui.onNodeWithTag("tmdb_config_status").assertTextEquals("未配置"); ui.onNodeWithTag("tmdb_config_close").assertIsDisplayed()
            ui.waitForIdle(); val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(); assertNotNull(screenshot)
            val output = File(context.filesDir, "tmdb-test-screenshots/" + orientation + "-" + mode.name.lowercase() + ".png"); output.parentFile!!.mkdirs()
            output.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }; screenshot.recycle()
        }
    }
}
