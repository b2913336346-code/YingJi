package com.yingji.app.presentation

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

import com.yingji.app.YingJiApplication
import com.yingji.app.MainActivity
import androidx.test.core.app.ActivityScenario

class DatabaseProtectionExportTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    @get:Rule val folder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() = runBlocking {
        scenario?.close()
        background.cancel()
        background.coroutineContext[Job]?.join()
        ApplicationProvider.getApplicationContext<YingJiApplication>().databaseProtectionForTesting = null
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test
    fun actualSystemDocumentPickerCancellationReturnsToProtectedScreen() = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        val source = File(folder.root, "fixture.db").apply { writeText("evidence") }
        val coordinator = DatabaseProtectionCoordinator(File(folder.root, "guard"),
            DatabaseBackupStore(source, File(folder.root, "backup")), background)
        coordinator.initialize()
        DatabaseHealthMonitor.reportIfCorruption(SQLiteDatabaseCorruptException("isolated fixture"))
        ApplicationProvider.getApplicationContext<YingJiApplication>().databaseProtectionForTesting = coordinator
        scenario = ActivityScenario.launch(MainActivity::class.java)
        dialogText("导出保全材料").performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        composeRule.waitUntil(5_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
        }
        // The first Back can dismiss the system picker keyboard instead of cancelling.
        repeat(3) {
            if (coordinator.state.value.message == null &&
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
            }
        }
        composeRule.waitUntil(5_000) { coordinator.state.value.message?.startsWith("已取消导出") == true }
        val message = "已取消导出；原文件及保全材料保持不变"
        val activeDialog = composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        val feedback = if (activeDialog) dialogText(message) else composeRule.onNodeWithText(message)
        feedback.performScrollTo().assertIsDisplayed()
        assertTrue(DatabaseHealthMonitor.state.value is DatabaseHealthState.Unavailable)
        assertEquals("evidence", source.readText())
    }

    @Test
    fun actualSystemDocumentPickerSavesAnArchiveWhoseContentsCanBeReadBack() = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        val source = File(folder.root, "fixture.db").apply { writeText("verified fixture evidence") }
        val coordinator = DatabaseProtectionCoordinator(File(folder.root, "guard"),
            DatabaseBackupStore(source, File(folder.root, "backup")), background)
        coordinator.initialize()
        DatabaseHealthMonitor.stop(DatabaseIssue.CORRUPTION)
        ApplicationProvider.getApplicationContext<YingJiApplication>().databaseProtectionForTesting = coordinator
        scenario = ActivityScenario.launch(MainActivity::class.java)
        dialogText("导出保全材料").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        composeRule.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        // The app supplies an initial internal-storage directory, independent of picker history.
        composeRule.waitUntil(5000) { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Download")?.isNotEmpty() == true }
        val root = requireNotNull(automation.rootInActiveWindow)
        val name = "yingji-fixture-${java.util.UUID.randomUUID()}.zip"
        val field = root.findAccessibilityNodeInfosByViewId("android:id/title")
            .single { it.className == "android.widget.EditText" }
        val arguments = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }
        assertTrue(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        val save = requireNotNull(automation.rootInActiveWindow).findAccessibilityNodeInfosByViewId("android:id/button1").single()
        assertTrue(save.isVisibleToUser && save.isEnabled)
        assertTrue(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        try {
            composeRule.waitUntil(10_000) { coordinator.state.value.message != null }
        } catch (error: AssertionError) {
            val screenshot = automation.takeScreenshot()
            if (screenshot != null) java.io.FileOutputStream(File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "stage5-picker-failure.png")).use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            throw AssertionError("Save callback state: ${coordinator.state.value}; window=${automation.rootInActiveWindow}", error)
        }
        if (coordinator.state.value.message?.startsWith("保全材料已写入") != true) {
            val grants = automation.executeShellCommand("dumpsys uri_grants").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            android.util.Log.e("YingJiExportTest", "URI grants at failure: $grants")
        }
        assertTrue(coordinator.state.value.toString(), coordinator.state.value.message?.startsWith("保全材料已写入") == true)
        val exportedPath = "/sdcard/Download/$name"
        val bytes = automation.executeShellCommand("cat $exportedPath").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("manifest.json", "fixture.db"), entries.keys)
        assertEquals("verified fixture evidence", entries.getValue("fixture.db").toString(Charsets.UTF_8))
        assertTrue(entries.getValue("manifest.json").toString(Charsets.UTF_8).contains("UNCONFIRMED"))
        assertEquals("verified fixture evidence", source.readText())
        android.util.Log.i("YingJiExportTest", "Verified isolated test export: $exportedPath")
        Unit
    }

    private fun dialogText(text: String) = composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog()))
}
