package com.yingji.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.repository.ThemeRepository
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

/** Opt-in, owned synthetic DBs only. Preparation is deliberately terminated by the host. */
class PortableBackupProcessDeathTest {
    @Test fun controlledProcessDeathRecovery() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val args = InstrumentationRegistry.getArguments()
        val id = requireNotNull(args.getString("backupDeathFixture")); require(Regex("backup-death-[a-f0-9]{32}").matches(id))
        val phase = requireNotNull(args.getString("backupDeathPhase"))
        require(phase in setOf("prepare-before", "verify-before", "prepare-after", "verify-after"))
        DatabaseHealthMonitor.resetForTesting()
        val area = File(app.cacheDir, id)
        val preparing = phase.startsWith("prepare")
        if (preparing) check(area.mkdir()) else check(area.isDirectory)
        check(area.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val databases = mutableListOf<AppDatabase>()
        fun context(name: String) = object : ContextWrapper(app) {
            private val base = File(area, name).apply { mkdirs() }
            override fun getFilesDir() = File(base, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(base, "no-backup").apply { mkdirs() }
        }
        fun database(ctx: Context, name: String) = Room.databaseBuilder(ctx, AppDatabase::class.java, File(area, name + ".db").absolutePath).build().also { databases.add(it) }
        fun themes(ctx: Context, name: String) = ThemeRepository(ctx, PreferenceDataStoreFactory.create(scope = scope) { File(area, name + ".preferences_pb") })
        val targetContext = context("target")
        val targetDb = database(targetContext, "target")
        val targetTheme = themes(targetContext, "target")
        if (preparing) {
            val sourceContext = context("source"); val sourceDb = database(sourceContext, "source"); val sourceTheme = themes(sourceContext, "source")
            val poster = File(sourceContext.filesDir, "posters/owned.png").apply { parentFile!!.mkdirs() }
            FileOutputStream(poster).use { out -> val b = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888); b.compress(Bitmap.CompressFormat.PNG, 100, out); b.recycle() }
            sourceDb.withTransaction {
                val sql = sourceDb.openHelper.writableDatabase
                sql.execSQL("INSERT INTO categories(id,name,isSystem) VALUES(500,'电影',1)")
                sql.execSQL("INSERT INTO movies(id,name,posterPath,type,status,currentEpisode,totalEpisode,createTime,rating) VALUES(5,'TEST_ONLY_PROCESS_DEATH',?,'电影','已看完',1,1,1000,8.5)", arrayOf(poster.absolutePath))
                sql.execSQL("INSERT INTO movie_categories(movieId,categoryId) VALUES(5,500)")
                sql.execSQL("INSERT INTO watch_history(id,movieId,startTime,endTime,duration,watchNumber) VALUES(6,5,1000,2000,1000,1)")
            }
            sourceTheme.applyBackupPreferences(BackupPreferences("DARK", "POSTER_WALL", "CREATED_ASC"))
            val source = PortableBackupCoordinator(sourceContext, sourceDb, sourceTheme, {})
            source.initialize(); source.prepareExport("TEST_ONLY_Process_Death_123".toCharArray())
            val file = File(area, "owned.yjb"); source.writeExport(Uri.fromFile(file))
            val stopPoint = if (phase.endsWith("before")) "before-commit" else "after-commit"
            val target = PortableBackupCoordinator(targetContext, targetDb, targetTheme, {}, fault = { point ->
                if (point == stopPoint) {
                    File(area, "ready").writeText(point)
                    CountDownLatch(1).await()
                    error("Host must terminate this preparation process")
                }
            })
            target.initialize(); assertTrue(target.checkDocument(Uri.fromFile(file), "TEST_ONLY_Process_Death_123".toCharArray()).canRestore)
            target.restore(true)
            fail("Expected controlled process termination")
        } else {
            try {
                assertEquals(if (phase.endsWith("before")) "before-commit" else "after-commit", File(area, "ready").readText())
                val target = PortableBackupCoordinator(targetContext, targetDb, targetTheme, {})
                target.initialize(); assertTrue(target.recovery.value.ready)
                val after = phase.endsWith("after")
                val data = targetDb.withTransaction { BackupSql.read(targetDb, targetTheme.backupPreferences()) }
                assertEquals(if (after) 1 else 0, data.movieCount)
                assertEquals(if (after) 1 else 0, data.rows("watch_history").size)
                assertEquals(if (after) 1 else 0, File(targetContext.filesDir, "posters").listFiles().orEmpty().size)
                assertEquals(after, target.recovery.value.pendingSettings)
                assertEquals("SYSTEM", targetTheme.backupPreferences().theme)
                if (after) {
                    val fingerprint = data.fingerprint(); target.retrySettings()
                    assertEquals("DARK", targetTheme.backupPreferences().theme)
                    assertEquals(fingerprint, targetDb.withTransaction { BackupSql.read(targetDb, targetTheme.backupPreferences()).fingerprint() })
                }
                assertFalse(File(targetContext.noBackupFilesDir, "portable-backup/restore-journal.json").exists())
            } finally {
                databases.forEach { it.close() }; scope.cancel(); scope.coroutineContext[Job]?.join()
                area.walkBottomUp().forEach { check(it.canonicalPath.startsWith(area.canonicalPath)); check(it.delete()) }
                DatabaseHealthMonitor.resetForTesting()
            }
        }
    }
}
