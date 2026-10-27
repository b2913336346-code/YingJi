package com.yingji.app.presentation.ai

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.ai.PosterTestKeys
import com.yingji.app.domain.ai.TmdbCredentialRepository
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test

class TmdbPosterGuidanceScreenTest {
    @get:Rule val ui = createComposeRule()
    private val models = ViewModelStore()
    private fun model(): TmdbCredentialViewModel {
        lateinit var result: TmdbCredentialViewModel
        ui.runOnUiThread { result = TmdbCredentialViewModel(TmdbCredentialRepository(PosterTestKeys())); models.put("guidance", result) }
        return result
    }
    @After fun close() { ui.runOnUiThread { models.clear() } }
    private fun screenshot(name: String, node: SemanticsNodeInteraction) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.filesDir, "poster-fix-guidance").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun configurationExplainsNetworkActionsAndExplicitPosterPersistence() {
        val vm = model()
        ui.setContent { YingJiTheme(ThemeMode.LIGHT) { TmdbCredentialDialog(vm) {} } }
        val network = "仅限非商业开发。保存令牌不联网；首页默认使用 Mock，选择 TMDB 后主动读取。候选预览、入库校验及详情页海报操作会联网。"
        val persistence = "与 DeepSeek 分别加密存储于本机非备份区域。候选仅存内存；在线预览不保存海报，详情页确认后才保存到本机。切换来源不自动联网。"
        ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasText(network))
        ui.onNodeWithText(network).assertIsDisplayed()
        screenshot("configuration-network", ui.onNodeWithTag("tmdb_config_scroll"))
        ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasText(persistence))
        ui.onNodeWithText(persistence).assertIsDisplayed()
        screenshot("configuration-persistence", ui.onNodeWithTag("tmdb_config_scroll"))
    }
    @Test fun homeGuidanceSaysNoAutomaticPosterSaveWithAndWithoutImport() {
        val vm = model(); val imports = mutableStateOf(true); val theme = mutableStateOf(ThemeMode.LIGHT)
        ui.setContent { YingJiTheme(theme.value) { Column(Modifier.verticalScroll(rememberScrollState())) {
            TmdbHomeCard(vm, true, {}, {}, importAvailable = imports.value)
        } } }
        val enabled = "候选结果仅存内存，不写历史、不自动保存海报。点击“加入影迹”确认并填写表单后，最终保存才新增作品和来源 ID。"
        val disabled = "结果仅存内存；切换来源、离开首页或修改令牌后清除。不写历史、不导入作品、不自动保存海报。"
        ui.onNodeWithText(enabled).performScrollTo().assertIsDisplayed()
        screenshot("home-import-light", ui.onRoot())
        ui.runOnIdle { imports.value = false; theme.value = ThemeMode.DARK }
        ui.onNodeWithText(disabled).performScrollTo().assertIsDisplayed()
        screenshot("home-preview-dark", ui.onRoot())
    }
}
