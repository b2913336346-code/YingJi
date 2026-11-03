package com.yingji.app.presentation.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.backup.*
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.repository.ThemeRepository
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.settings.SettingsScreen
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BackupDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var area: File
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: PortableBackupCoordinator
    private lateinit var theme: ThemeRepository
    @Before fun setup() = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        area = File(app.cacheDir, "backup-ui-" + UUID.randomUUID()).apply { check(mkdir()) }
        val ctx = object : ContextWrapper(app) {
            override fun getFilesDir() = File(area, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(area, "no-backup").apply { mkdirs() }
        }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        theme = ThemeRepository(ctx, PreferenceDataStoreFactory.create(scope = scope) { File(area, "ui.preferences_pb") })
        db = Room.databaseBuilder(ctx, AppDatabase::class.java, File(area, "library.db").absolutePath).build()
        coordinator = PortableBackupCoordinator(ctx, db, theme, rebuildProfile = {})
        coordinator.initialize()
    }
    @After fun cleanup() = runBlocking {
        db.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
        check(area.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        area.walkBottomUp().forEach { check(it.canonicalPath.startsWith(area.canonicalPath)); check(it.delete()) }
        DatabaseHealthMonitor.resetForTesting()
    }
    @Test fun entryUsesExistingSettingsListAndOpensOnlyOnClick() {
        var clicked = false
        compose.setContent { YingJiTheme(ThemeMode.SYSTEM) {
            SettingsScreen(categories = emptyList(), tags = emptyList(), themeMode = ThemeMode.SYSTEM, onThemeModeChange = {}, onStatisticsClick = {},
                onAddCategory = {}, onDeleteCategory = {}, onAddTag = {}, onDeleteTag = {}, onAiModelConfigurationClick = {}, onBackupClick = { clicked = true })
        } }
        compose.onNodeWithTag("mine_settings_list").performScrollToNode(hasTestTag("backup_open"))
        assertFalse(clicked); compose.onNodeWithTag("backup_open").performClick(); compose.runOnIdle { assertTrue(clicked) }
    }
    @Test fun weakPasswordFailsBeforeFilePickerOrDatabaseChanges() {
        compose.setContent { YingJiTheme(ThemeMode.SYSTEM) { BackupHost(coordinator) {} } }
        compose.onNodeWithTag("backup_password").performScrollTo().performTextInput("short")
        compose.onNodeWithTag("backup_password_repeat").performScrollTo().performTextInput("short")
        compose.onNodeWithTag("backup_create").performScrollTo().performClick()
        compose.onNodeWithTag("backup_password_error").performScrollTo().assertTextEquals("备份密码需为 12–256 个字符")
        runBlocking { assertEquals(0, db.withTransaction { BackupSql.read(db, theme.backupPreferences()).movieCount }) }
    }
    @Test fun mismatchedPasswordsFailWithoutLaunchingFilePicker() {
        compose.setContent { YingJiTheme(ThemeMode.SYSTEM) { BackupHost(coordinator) {} } }
        compose.onNodeWithTag("backup_password").performScrollTo().performTextInput("TEST_ONLY_PASSWORD_123")
        compose.onNodeWithTag("backup_password_repeat").performScrollTo().performTextInput("TEST_ONLY_OTHER_456")
        compose.onNodeWithTag("backup_create").performScrollTo().performClick()
        compose.onNodeWithTag("backup_password_error").performScrollTo().assertTextEquals("两次备份密码不一致")
    }
    @Test fun dialogExplainsExclusionsAndCanCloseWithoutWriting() {
        var closed = false
        compose.setContent { YingJiTheme(ThemeMode.SYSTEM) { BackupHost(coordinator) { closed = true } } }
        compose.onNodeWithText("首版不支持非空资料库合并或覆盖恢复，不要通过清库或卸载绕过限制。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("backup_close").performClick()
        compose.waitUntil(5000) { closed }
        runBlocking { assertEquals(0, db.withTransaction { BackupSql.read(db, theme.backupPreferences()).movieCount }) }
    }
}
