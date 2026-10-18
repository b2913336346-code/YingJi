package com.yingji.app.presentation

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.view.KeyEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.common.DatabaseProtectionHost
import com.yingji.app.presentation.common.DatabaseProtectionScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseProtectionScreenTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val folder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = runBlocking {
        background.cancel()
        background.coroutineContext[Job]?.join()
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test
    fun failedBackupDoesNotEnableExportAndClosingDialogDoesNotUnlockDatabase() {
        DatabaseHealthMonitor.resetForTesting()
        DatabaseHealthMonitor.stop(DatabaseIssue.CORRUPTION)
        val state = DatabaseProtectionUiState(false, DatabaseIssue.CORRUPTION,
            message = "保全失败：空间不足，原文件未清除")
        var requests = 0
        composeRule.setContent { YingJiTheme(themeMode = ThemeMode.DARK) {
            DatabaseProtectionScreen(state) { requests++ }
        } }
        dialogText("保全失败：空间不足，原文件未清除").assertIsDisplayed()
        dialogText("导出保全材料").assertIsNotEnabled()
        composeRule.onNodeWithText("关闭提示").performClick()
        composeRule.onNodeWithText("数据库保护中").assertIsDisplayed()
        assertTrue(DatabaseHealthMonitor.state.value is DatabaseHealthState.Unavailable)
        runBlocking { assertTrue(runCatching { DatabaseHealthMonitor.protectWrite { fail("must remain blocked") } }.isFailure) }
        composeRule.onNodeWithText("查看保护状态").performScrollTo().performClick()
        composeRule.onNodeWithText("关闭提示").assertIsDisplayed()
        assertEquals(0, requests)
    }

    @Test
    fun savedEvidenceCanBeExportedAndBusyStatePreventsRepeatClicks() {
        val source = File(folder.root, "fixture.db").apply { writeText("evidence") }
        val record = DatabaseBackupStore(source, File(folder.root, "backup")).capture()
        assertTrue(record.complete)
        val state = mutableStateOf(DatabaseProtectionUiState(false, DatabaseIssue.CORRUPTION, record))
        var requests = 0
        composeRule.setContent { YingJiTheme {
            DatabaseProtectionScreen(state.value) { requests++; state.value = state.value.copy(busy = true) }
        } }
        dialogText(record.message).assertIsDisplayed()
        dialogText("导出保全材料").performClick()
        dialogText("导出保全材料").assertIsNotEnabled()
        assertEquals(1, requests)
        assertEquals("evidence", source.readText())
    }



    private fun dialogText(text: String) = composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog()))


}
