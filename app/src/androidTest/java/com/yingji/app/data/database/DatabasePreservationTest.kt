package com.yingji.app.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

class DatabasePreservationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val folder = TemporaryFolder(context.cacheDir)
    @get:Rule val migration = androidx.room.testing.MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
    )
    private lateinit var scope: CoroutineScope
    private lateinit var source: File
    private lateinit var marker: File
    private lateinit var backupRoot: File

    @Before
    fun setUp() {
        DatabaseHealthMonitor.resetForTesting()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        source = File(folder.root, "fixture.db")
        marker = File(folder.root, "guard.state")
        backupRoot = File(folder.root, "preserved")
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        scope.coroutineContext[Job]?.join()
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test
    fun corruptFileIsNotDeletedOrRecreatedByTheActualRoomOpenPath() = runBlocking {
        val bytes = ByteArray(8_192) { 37 }
        source.writeBytes(bytes)
        val controller = controller()
        controller.initialize()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, source.path)
            .openHelperFactory(PreservingOpenHelperFactory()).addMigrations(DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4,DatabaseModule.MIGRATION_4_5).build()
        try {
            assertTrue(runCatching { database.openHelper.writableDatabase }.isFailure)
            assertTrue(source.exists())
            assertArrayEquals(bytes, source.readBytes())
            assertTrue(marker.readBytes().single() != 0.toByte())
            val backup = requireNotNull(controller.state.value.backup)
            assertTrue(backup.message, backup.complete)
            assertArrayEquals(bytes, File(backup.directory, source.name).readBytes())
            assertTrue(DatabaseHealthMonitor.state.value is DatabaseHealthState.Unavailable)
            assertTrue(runCatching { database.openHelper.readableDatabase }.exceptionOrNull() is DatabaseUnavailableException)
            repeat(10) { DatabaseHealthMonitor.reportIfCorruption(SQLiteDatabaseCorruptException("repeat")) }
            assertEquals(1, backupRoot.listFiles().orEmpty().count { !it.name.startsWith("validation-") })
        } finally { database.close() }
    }

    @Test
    fun restartKeepsProtectionWithoutOpeningOrBackingUpAgain() = runBlocking {
        source.writeBytes(ByteArray(4_096) { 2 })
        val first = controller()
        first.initialize()
        DatabaseHealthMonitor.reportIfCorruption(SQLiteDatabaseCorruptException("fixture"))
        val directory = requireNotNull(first.state.value.backup).directory
        DatabaseHealthMonitor.resetForTesting()
        val reopened = controller()
        reopened.initialize()
        assertEquals(DatabaseIssue.CORRUPTION, reopened.state.value.issue)
        assertEquals(directory, reopened.state.value.backup?.directory)
        assertTrue(runCatching { DatabaseHealthMonitor.protectWrite { fail("must not write") } }.isFailure)
        assertEquals(1, backupRoot.listFiles().orEmpty().count { !it.name.startsWith("validation-") })
    }

    @Test
    fun walShmAndMainArePreservedAndExportContainsTheManifest() {
        val expected = listOf("", "-wal", "-shm").associate { suffix ->
            (source.name + suffix) to ("evidence$suffix".repeat(100).toByteArray())
        }
        expected.forEach { (name, bytes) -> File(source.parentFile, name).writeBytes(bytes) }
        val store = DatabaseBackupStore(source, backupRoot)
        val record = store.capture()
        assertTrue(record.message, record.complete)
        assertEquals(expected.keys, record.files.map { it.name }.toSet())
        expected.forEach { (name, bytes) ->
            assertArrayEquals(bytes, File(source.parentFile, name).readBytes())
            assertArrayEquals(bytes, File(record.directory, name).readBytes())
        }
        val output = ByteArrayOutputStream()
        store.export(record, output)
        val zipFiles = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                zipFiles[entry.name] = zip.readBytes()
            }
        }
        assertEquals(expected.keys + "manifest.json", zipFiles.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, zipFiles[name]) }
        assertTrue(zipFiles.getValue("manifest.json").toString(Charsets.UTF_8).contains("UNCONFIRMED"))
    }

    @Test
    fun diskFullAndInterruptedCopyNeverClearOriginalsOrClaimSuccess() {
        source.writeText("untouched".repeat(1_000))
        val original = source.readBytes()
        val full = DatabaseBackupStore(source, backupRoot, availableBytes = { 0 }).capture()
        assertFalse(full.complete)
        assertTrue(full.message.contains("空间不足"))
        val interrupted = DatabaseBackupStore(source, backupRoot,
            copyFile = { _, destination -> destination.writeText("partial"); throw IOException("injected copy failure") }).capture()
        assertFalse(interrupted.complete)
        assertFalse(interrupted.exportable)
        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun changingSourceAndTamperedEvidenceCannotBecomeVerifiedExports() {
        source.writeText("original".repeat(100))
        val changed = DatabaseBackupStore(source, backupRoot, copyFile = { input, target ->
            target.writeBytes(input.readBytes()); input.appendText("changed")
        }).capture()
        assertFalse(changed.complete)
        val store = DatabaseBackupStore(source, backupRoot)
        val record = store.capture()
        assertTrue(record.complete)
        File(record.directory, source.name).appendText("tampered")
        assertTrue(runCatching { store.export(record, ByteArrayOutputStream()) }.isFailure)
        assertTrue(source.exists())
    }

    @Test
    fun writeFailureDuringExportIsNotReportedAsSuccess() {
        source.writeText("evidence")
        val store = DatabaseBackupStore(source, backupRoot)
        val record = store.capture()
        assertTrue(runCatching { store.export(record, object : OutputStream() {
            override fun write(value: Int) { throw IOException("injected export failure") }
        }) }.isFailure)
        assertTrue(source.exists())
        assertTrue(File(record.directory, source.name).exists())
    }

    @Test
    fun schemaMismatchIsClassifiedSeparatelyAndDoesNotRebuildTheFile() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, source.path).build()
        db.openHelper.writableDatabase
        db.close()
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE room_master_table SET identity_hash = 'mismatch' WHERE id=42")
        }
        val before = source.readBytes()
        val controller = controller()
        controller.initialize()
        val protectedDb = Room.databaseBuilder(context, AppDatabase::class.java, source.path)
            .openHelperFactory(PreservingOpenHelperFactory()).build()
        try {
            assertTrue(runCatching { protectedDb.openHelper.writableDatabase }.isFailure)
            assertEquals(DatabaseIssue.MIGRATION, controller.state.value.issue)
            assertArrayEquals(before, source.readBytes())
        } finally { protectedDb.close() }
    }

    @Test
    fun validWorkingCopyPassesChecksWithoutOpeningTheEvidence() {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, source.path).build()
        database.openHelper.writableDatabase
        database.close()
        val store = DatabaseBackupStore(source, backupRoot)
        val record = store.capture()
        assertTrue(record.complete)
        val bytes = File(record.directory, source.name).readBytes()
        val checked = store.verifyWorkingCopy(record)
        assertTrue(checked.validation, checked.validation.startsWith("副本检查通过"))
        assertTrue(checked.validation.contains("一致性仍未确认"))
        assertArrayEquals(bytes, File(record.directory, source.name).readBytes())
    }

    @Test
    fun corruptedMarkerFailsClosedAndDifferentFailureKindsAreNotConfused() = runBlocking {
        marker.writeBytes(byteArrayOf(1, 2))
        val controller = controller()
        controller.initialize()
        assertEquals(DatabaseIssue.ACCESS, controller.state.value.issue)
        assertTrue(DatabaseHealthMonitor.state.value is DatabaseHealthState.Unavailable)
        assertEquals(DatabaseIssue.DISK_FULL, classifyDatabaseIssue(SQLiteFullException("full")))
        assertEquals(DatabaseIssue.UNKNOWN, classifyDatabaseIssue(IOException("unknown")))
        assertEquals(DatabaseIssue.MIGRATION, classifyDatabaseIssue(DatabaseMigrationException(IllegalStateException())))
    }

    private fun controller() = DatabaseProtectionCoordinator(marker, DatabaseBackupStore(source, backupRoot), scope)

    @Test
    fun validWalSnapshotIncludesCommittedMoviesAndKeepsOriginalFileGroupUnchanged() = runBlocking {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, source.path)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val id = database.movieDao().insert(com.yingji.app.data.entity.Movie(
                name = "WAL 已提交作品", type = "电影", status = "观看中", createTime = 100,
            ))
            database.watchHistoryDao().insert(com.yingji.app.data.entity.WatchHistory(movieId = id, startTime = 1_000, watchNumber = 1))
            val originals = listOf("", "-wal", "-shm").associate { it to File(source.path + it).readBytes() }
            assertTrue(originals.getValue("-wal").isNotEmpty())
            DatabaseBackupStore.preflightExisting(source, File(folder.root, "open-check"))
            val store = DatabaseBackupStore(source, backupRoot)
            val record = store.capture()
            assertTrue(record.message, record.complete)
            assertTrue(store.verifyWorkingCopy(record).validation.startsWith("副本检查通过"))
            val inspection = folder.newFolder("inspection")
            record.files.forEach { entry -> File(inspection, entry.name).writeBytes(File(record.directory, entry.name).readBytes()) }
            val evidenceBytes = record.files.associate { it.name to File(record.directory, it.name).readBytes() }
            val copy = File(inspection, source.name)
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                snapshot.rawQuery("SELECT name FROM movies WHERE id = ?", arrayOf(id.toString())).use {
                    assertTrue(it.moveToFirst()); assertEquals("WAL 已提交作品", it.getString(0))
                }
                snapshot.rawQuery("SELECT movieId FROM watch_history", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(id, it.getLong(0))
                }
            }
            originals.forEach { (suffix, bytes) -> assertArrayEquals(bytes, File(source.path + suffix).readBytes()) }
            evidenceBytes.forEach { (name, bytes) -> assertArrayEquals(bytes, File(record.directory, name).readBytes()) }
        } finally { database.close() }
    }

    @Test
    fun coordinatorReportsExportSuccessFailureAndCancellationAccurately() = runBlocking {
        source.writeText("original data")
        val controller = controller()
        controller.initialize()
        DatabaseHealthMonitor.stop(DatabaseIssue.CORRUPTION)
        val target = File(folder.root, "export.zip")
        controller.export(context.contentResolver, android.net.Uri.fromFile(target))
        assertTrue(controller.state.value.message.orEmpty().startsWith("保全材料已写入"))
        assertTrue(target.length() > 0)
        controller.export(context.contentResolver, android.net.Uri.parse("content://invalid.fixture.provider/denied"))
        assertTrue(controller.state.value.message.orEmpty().startsWith("导出失败"))
        assertFalse(controller.state.value.busy)
        controller.cancelExport()
        assertTrue(controller.state.value.message.orEmpty().startsWith("已取消导出"))
        assertEquals("original data", source.readText())
    }

    @Test
    fun deniedExportKeepsEvidenceShowsReselectActionAndCanRetry() = runBlocking {
        source.writeText("original evidence")
        val backups = DatabaseBackupStore(source, File(folder.root, "permission-backup"))
        var deny = true
        val controller = DatabaseProtectionCoordinator(marker, backups, scope, openExport = { resolver, uri ->
            if (deny) throw SecurityException("injected missing URI write grant")
            resolver.openOutputStream(uri, "w")
        })
        controller.initialize()
        DatabaseHealthMonitor.stop(DatabaseIssue.CORRUPTION)
        val record = requireNotNull(controller.state.value.backup)
        val evidence = record.files.associate { it.name to File(record.directory, it.name).readBytes() }
        val target = File(folder.root, "retry-export.zip")
        controller.export(context.contentResolver, android.net.Uri.fromFile(target))
        assertTrue(controller.state.value.message.orEmpty().contains("重新选择内部存储中的文件夹"))
        assertFalse(controller.state.value.busy)
        assertFalse(target.exists())
        assertEquals("original evidence", source.readText())
        evidence.forEach { (name, bytes) -> assertArrayEquals(bytes, File(record.directory, name).readBytes()) }
        deny = false
        controller.export(context.contentResolver, android.net.Uri.fromFile(target))
        assertTrue(controller.state.value.message.orEmpty().startsWith("保全材料已写入"))
        assertFalse(controller.state.value.busy)
        java.util.zip.ZipFile(target).use { zip ->
            assertEquals("original evidence", zip.getInputStream(zip.getEntry("fixture.db")).bufferedReader().use { it.readText() })
        }
        assertEquals("original evidence", source.readText())
    }

    @Test
    fun failedMarkerWriteRemainsVisibleAndDoesNotPreventFilePreservation() = runBlocking {
        source.writeText("original data")
        val controller = controller()
        controller.initialize()
        check(marker.delete() && marker.mkdir()) // Isolated test marker, never a user database.
        DatabaseHealthMonitor.stop(DatabaseIssue.CORRUPTION)
        assertTrue(controller.state.value.persistenceWarning.orEmpty().contains("无法保证跨重启"))
        assertTrue(controller.state.value.backup?.complete == true)
        controller.cancelExport()
        assertTrue(controller.state.value.persistenceWarning.orEmpty().contains("无法保证跨重启"))
        assertEquals("original data", source.readText())
    }

    @Test
    fun corruptionCallbackNeverDelegatesDeletesOrClosesAndDisablesLossyRecovery() = runBlocking {
        source.writeText("original data")
        File(source.path + "-wal").writeText("wal evidence")
        File(source.path + "-shm").writeText("shm evidence")
        val controller = controller()
        controller.initialize()
        var intercepted: SupportSQLiteOpenHelper.Configuration? = null
        val delegate = SupportSQLiteOpenHelper.Factory { configuration ->
            intercepted = configuration
            androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(configuration)
        }
        var originalCalled = false
        val helper = PreservingOpenHelperFactory(delegate).create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(source.path).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                override fun onCorruption(db: androidx.sqlite.db.SupportSQLiteDatabase) { originalCalled = true }
            }).build())
        val memory = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val opened = memory.openHelper.writableDatabase
            requireNotNull(intercepted).callback.onCorruption(opened)
            assertFalse(originalCalled)
            assertFalse(requireNotNull(intercepted).allowDataLossOnRecovery)
            assertTrue(opened.isOpen)
            assertEquals("original data", source.readText())
            assertEquals("wal evidence", File(source.path + "-wal").readText())
            assertEquals(setOf("fixture.db", "fixture.db-wal", "fixture.db-shm"), controller.state.value.backup?.files?.map { it.name }?.toSet())
        } finally { memory.close(); helper.close() }
    }

    @Test
    fun safeFactoryMigratesVersionOneAndPreservesIdsHistoryAndRelations() = runBlocking {
        migration.createDatabase(source.path, 1).use { old ->
            old.execSQL("INSERT INTO movies (id,name,posterPath,type,status,currentEpisode,totalEpisode,createTime) VALUES (41,'旧作品',NULL,'电影','已看完',0,NULL,1)")
            old.execSQL("INSERT INTO categories (id,name,isSystem) VALUES (7,'旧分类',0)")
            old.execSQL("INSERT INTO tags (id,name) VALUES (7,'旧标签')")
            old.execSQL("INSERT INTO movie_categories (movieId,categoryId) VALUES (41,7)")
            old.execSQL("INSERT INTO movie_tags (movieId,tagId) VALUES (41,7)")
            old.execSQL("INSERT INTO watch_history (id,movieId,startTime,endTime,duration,watchNumber) VALUES (9,41,100,200,100,1)")
        }
        val controller = controller()
        controller.initialize()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, source.path)
            .openHelperFactory(PreservingOpenHelperFactory()).addMigrations(DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4,DatabaseModule.MIGRATION_4_5).build()
        try {
            assertEquals("旧作品", withTimeout(5_000) { db.movieDao().observeById(41).first() }?.name)
            assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(41))
            assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(41))
            assertEquals(41L, db.watchHistoryDao().getById(9)?.movieId)
            assertEquals(5, db.openHelper.readableDatabase.version)
            assertTrue(DatabaseHealthMonitor.state.value is DatabaseHealthState.Available)
            assertNull(controller.state.value.backup)
        } finally { db.close() }
    }
}
