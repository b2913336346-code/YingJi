package com.yingji.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.repository.*
import com.yingji.app.data.ai.ObserveLibraryRepository
import com.yingji.app.data.ai.ProfileRepository
import com.yingji.app.domain.model.*
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortableBackupTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var area: File
    private val fixtures = mutableListOf<Fixture>()
    private val secret get() = "TEST_ONLY_BackupRoundTrip_123".toCharArray()
    @Before fun setup() { check(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0); DatabaseHealthMonitor.resetForTesting(); area = File(app.cacheDir, "portable-backup-test-" + UUID.randomUUID()).apply { check(mkdir()) } }
    @After fun close() = runBlocking {
        fixtures.forEach { it.db.close(); it.scope.cancel(); it.scope.coroutineContext[Job]?.join() }
        check(area.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        area.walkBottomUp().forEach { check(it.canonicalPath.startsWith(area.canonicalPath)); check(it.delete()) }
        DatabaseHealthMonitor.resetForTesting()
    }
    private inner class Fixture(name: String) {
        val dir = File(area, name).apply { mkdir() }
        val ctx = object : ContextWrapper(app) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(dir, "no-backup").apply { mkdirs() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val delegate = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "ui.preferences_pb") }
        var failSettings = false
        val store = object : DataStore<Preferences> {
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failSettings) throw IOException("TEST_ONLY_SETTINGS_FAILURE")
                return delegate.updateData(transform)
            }
        }
        val theme = ThemeRepository(ctx, store)
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, File(dir, "library.db").absolutePath).build()
        val profile = ProfileRepository(db, ObserveLibraryRepository(MovieRepository(db)))
        fun engine(space: () -> Long = { ctx.noBackupFilesDir.usableSpace }, hook: (String) -> Unit = {}) = PortableBackupCoordinator(ctx, db, theme, rebuildProfile = { profile.getProfile(); Unit }, fault = hook, availableBytes = space)
        suspend fun data() = db.withTransaction { BackupSql.read(db, theme.backupPreferences()) }
        suspend fun seed() = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val png = File(ctx.filesDir, "posters/shared.png").apply { parentFile!!.mkdirs() }
            FileOutputStream(png).use { out -> val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.GRAY); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle() }
            sql.execSQL("INSERT INTO categories(id,name,isSystem) VALUES(500,'电影',1)")
            sql.execSQL("INSERT INTO tags(id,name) VALUES(900,'TEST_ONLY_TAG')")
            sql.execSQL("INSERT INTO movies(id,name,posterPath,type,status,currentEpisode,totalEpisode,createTime,rating) VALUES(30,'TEST_ONLY_ONE',?,'电影','观看中',3,12,123000,8.5)", arrayOf(png.absolutePath))
            sql.execSQL("INSERT INTO movies(id,name,posterPath,type,status,currentEpisode,totalEpisode,createTime,rating) VALUES(80,'TEST_ONLY_TWO',?,'电影','已看完',12,12,456000,NULL)", arrayOf(png.absolutePath))
            sql.execSQL("INSERT INTO movie_categories(movieId,categoryId) VALUES(30,500),(80,500)")
            sql.execSQL("INSERT INTO movie_tags(movieId,tagId) VALUES(30,900)")
            sql.execSQL("INSERT INTO watch_history(id,movieId,startTime,endTime,duration,watchNumber) VALUES(70,30,1000,NULL,500,1),(71,80,1000,9000,8000,2)")
            sql.execSQL("INSERT INTO ai_candidate_imports(sourceId,externalId,movieId) VALUES('tmdb','movie:550',30)")
            sql.execSQL("INSERT INTO ai_explicit_preferences(featureId,label,direction,updatedAt) VALUES('category:500','电影','LIKE',1000)")
            sql.execSQL("INSERT INTO ai_profile_corrections(featureId,updatedAt) VALUES('tag:900',1000)")
            sql.execSQL("INSERT INTO ai_profile_feedback(eventId,featureId,label,direction,updatedAt) VALUES('TEST_ONLY_EVENT','tag:900','TEST_ONLY_TAG','DISLIKE',1000)")
        }
    }
    private fun fixture(name: String) = Fixture(name).also { fixtures.add(it) }
    private suspend fun archive(source: Fixture): File {
        val engine = source.engine(); engine.initialize()
        engine.prepareExport(secret)
        val file = File(area, UUID.randomUUID().toString() + ".yjb")
        engine.writeExport(Uri.fromFile(file)); return file
    }
    private suspend fun reject(block: suspend () -> Unit) { var failed = false; try { block() } catch (_: Exception) { failed = true }; assertTrue("Invalid operation must fail", failed) }
    @Test fun completeRoundTripPreservesHistoryRelationsImagesAndPreferences() = runBlocking {
        val source = fixture("source"); source.seed(); source.theme.applyBackupPreferences(BackupPreferences("DARK", "POSTER_WALL", "CREATED_ASC"))
        val key = File(source.ctx.noBackupFilesDir, "tmdb-credentials/config.enc").apply { parentFile!!.mkdirs(); writeText("TEST_ONLY_DO_NOT_BACK_UP_TMDB") }
        val before = source.data().fingerprint(); val file = archive(source)
        val target = fixture("target"); target.db.withTransaction { target.db.openHelper.writableDatabase.execSQL("INSERT INTO categories(id,name,isSystem) VALUES(42,'电影',1)") }
        val targetKey = File(target.ctx.noBackupFilesDir, "ai-byok/config.enc").apply { parentFile!!.mkdirs(); writeText("TEST_ONLY_KEEP_EXISTING_KEY") }
        val engine = target.engine(); engine.initialize(); val preview = engine.checkDocument(Uri.fromFile(file), secret)
        assertEquals(2, preview.movies); assertEquals(1, preview.posters); assertTrue(preview.canRestore)
        val initiallyEmpty = CompletableDeferred<Unit>()
        val observed = async(Dispatchers.IO) { target.db.movieDao().observeAll().onEach { if (it.isEmpty()) initiallyEmpty.complete(Unit) }.first { it.size == 2 } }
        withTimeout(5000) { initiallyEmpty.await() }
        engine.restore(true)
        assertEquals(2, withTimeout(5000) { observed.await() }.size)
        val restored = target.data(); assertEquals(2, restored.movieCount); assertEquals(2, restored.rows("watch_history").size)
        assertEquals(42L, restored.rows("categories").single().getLong(0))
        assertEquals("category:42", restored.rows("ai_explicit_preferences").single().getString(0))
        val tagId = restored.rows("tags").single().getLong(0)
        assertEquals("tag:" + tagId, restored.rows("ai_profile_corrections").single().getString(0))
        assertEquals("tag:" + tagId, restored.rows("ai_profile_feedback").single().getString(1))
        assertEquals(source.theme.backupPreferences(), target.theme.backupPreferences())
        val paths = restored.rows("movies").map { it.getString(2) }.toSet(); assertEquals(1, paths.size)
        val picture = File(paths.single()); BackupArchive.image(picture)
        assertArrayEquals(File(source.ctx.filesDir, "posters/shared.png").readBytes(), picture.readBytes())
        assertEquals("TEST_ONLY_KEEP_EXISTING_KEY", targetKey.readText()); assertEquals("TEST_ONLY_DO_NOT_BACK_UP_TMDB", key.readText()); assertEquals(before, source.data().fingerprint())
        assertEquals(2, withTimeout(5000) { target.db.movieDao().observeAll().first { it.size == 2 } }.size)
        val inspect = File(area, "decoded.zip"); FileOutputStream(inspect).use { file.inputStream().use { source -> BackupCipher.decrypt(source, it, secret) } }
        java.util.zip.ZipInputStream(inspect.inputStream()).use { input ->
            while (true) { val entry = input.nextEntry ?: break; assertFalse(entry.name.contains("config.enc")); assertFalse(String(input.readBytes(), Charsets.ISO_8859_1).contains("TEST_ONLY_DO_NOT_BACK_UP_TMDB")); input.closeEntry() }
        }
    }
    @Test fun nonEmptyTargetIsRejectedWithoutChangingOriginalRows() = runBlocking {
        val source = fixture("source"); source.seed(); val file = archive(source); val target = fixture("target"); target.seed()
        val before = target.data().fingerprint(); val engine = target.engine(); engine.initialize()
        assertFalse(engine.checkDocument(Uri.fromFile(file), secret).canRestore); reject { engine.restore(false) }; assertEquals(before, target.data().fingerprint())
    }
    @Test fun targetThatChangesAfterPreviewIsRecheckedAtomically() = runBlocking {
        val source = fixture("source"); source.seed(); val file = archive(source); val target = fixture("target")
        val engine = target.engine(); engine.initialize(); assertTrue(engine.checkDocument(Uri.fromFile(file), secret).canRestore)
        target.db.withTransaction { target.db.openHelper.writableDatabase.execSQL("INSERT INTO tags(name) VALUES('TEST_ONLY_ADDED_LATER')") }
        val before = target.data().fingerprint(); reject { engine.restore(false) }; assertEquals(before, target.data().fingerprint())
        assertTrue(File(target.ctx.filesDir, "posters").listFiles().orEmpty().isEmpty())
    }
    @Test fun wrongPasswordAndTamperingNeverMutateTarget() = runBlocking {
        val source = fixture("source"); source.seed(); val file = archive(source); val target = fixture("target"); val before = target.data().fingerprint(); val engine = target.engine(); engine.initialize()
        reject { engine.checkDocument(Uri.fromFile(file), "TEST_ONLY_BAD_PASSWORD".toCharArray()) }
        val bytes = file.readBytes(); bytes[60] = (bytes[60].toInt() xor 1).toByte(); file.writeBytes(bytes)
        reject { engine.checkDocument(Uri.fromFile(file), secret) }; assertEquals(before, target.data().fingerprint())
    }
    private class SimulatedDeath : Error()
    @Test fun deathBeforeCommitRollsBackAndOnlyRemovesOwnedNewFiles() = runBlocking {
        val source = fixture("source"); source.seed(); val file = archive(source); val target = fixture("target"); val before = target.data().fingerprint()
        val engine = target.engine { if (it == "before-commit") throw SimulatedDeath() }; engine.initialize(); engine.checkDocument(Uri.fromFile(file), secret)
        try { engine.restore(false); fail("Fault not exercised") } catch (_: SimulatedDeath) { }
        val restarted = target.engine(); restarted.initialize(); assertTrue(restarted.recovery.value.ready); assertEquals(before, target.data().fingerprint())
        assertTrue(File(target.ctx.filesDir, "posters").listFiles().orEmpty().isEmpty())
    }
    @Test fun deathAfterCommitIsRecognizedWithoutDuplicatingRows() = runBlocking {
        val source = fixture("source"); source.seed(); val file = archive(source); val target = fixture("target")
        val engine = target.engine { if (it == "after-commit") throw SimulatedDeath() }; engine.initialize(); engine.checkDocument(Uri.fromFile(file), secret)
        try { engine.restore(true); fail("Fault not exercised") } catch (_: SimulatedDeath) { }
        val restarted = target.engine(); restarted.initialize(); assertTrue(restarted.recovery.value.ready); assertTrue(restarted.recovery.value.pendingSettings); assertEquals(2, target.data().movieCount)
        restarted.keepSettings(); assertEquals("SYSTEM", target.theme.backupPreferences().theme); assertEquals(2, target.data().movieCount)
    }
    @Test fun ambiguousCommitStateBlocksAndPreservesAllData() = runBlocking {
        val source = fixture("source"); source.seed(); val file = archive(source); val target = fixture("target")
        val engine = target.engine { if (it == "after-commit") throw SimulatedDeath() }; engine.initialize(); engine.checkDocument(Uri.fromFile(file), secret)
        try { engine.restore(false); fail("Fault not exercised") } catch (_: SimulatedDeath) { }
        target.db.withTransaction { target.db.openHelper.writableDatabase.execSQL("UPDATE movies SET name='TEST_ONLY_CHANGED_AFTER_INTERRUPTION' WHERE id=(SELECT MIN(id) FROM movies)") }
        val before = target.data().fingerprint(); val restarted = target.engine(); restarted.initialize(); assertFalse(restarted.recovery.value.ready); assertEquals(before, target.data().fingerprint()); assertTrue(File(target.ctx.noBackupFilesDir, "portable-backup/restore-journal.json").exists())
    }
    @Test fun missingPosterFailsExportWithoutChangingSource() = runBlocking {
        val source = fixture("source"); source.seed(); File(source.ctx.filesDir, "posters/shared.png").delete(); val before = source.data().fingerprint()
        val engine = source.engine(); engine.initialize(); reject { engine.prepareExport(secret) }; assertEquals(before, source.data().fingerprint())
    }
    @Test fun zipTraversalIsRejectedBeforeAnyDatabaseInsert() = runBlocking {
        val malicious = File(area, "malicious.zip")
        ZipOutputStream(malicious.outputStream()).use { it.putNextEntry(ZipEntry("../outside")); it.write(byteArrayOf(1)); it.closeEntry() }
        val encrypted = File(area, "malicious.yjb"); encrypted.outputStream().use { out -> malicious.inputStream().use { BackupCipher.encrypt(it, out, secret) } }
        val target = fixture("target"); val before = target.data().fingerprint(); val engine = target.engine(); engine.initialize(); reject { engine.checkDocument(Uri.fromFile(encrypted), secret) }; assertEquals(before, target.data().fingerprint()); assertFalse(File(target.ctx.noBackupFilesDir, "outside").exists())
    }
    @Test fun alreadyInitializedCoordinatorCanReenterWithoutDestroyingPreparedExport() = runBlocking {
        val source = fixture("source"); source.seed(); val engine = source.engine(); engine.initialize(); engine.prepareExport(secret); engine.initialize()
        val file = File(area, "after-rotation.yjb"); engine.writeExport(Uri.fromFile(file)); assertTrue(file.length() > 0)
    }

    @Test fun recordedCommitSurvivesRestartAndClearsFinishedJournal() = runBlocking {
        val source = fixture("source"); source.seed(); val file = archive(source); val target = fixture("target")
        val engine = target.engine { if (it == "committed-recorded") throw SimulatedDeath() }; engine.initialize(); engine.checkDocument(Uri.fromFile(file), secret)
        try { engine.restore(false); fail("Fault not exercised") } catch (_: SimulatedDeath) { }
        val restarted = target.engine(); restarted.initialize(); assertTrue(restarted.recovery.value.ready); assertEquals(2, target.data().movieCount)
        assertFalse(File(target.ctx.noBackupFilesDir, "portable-backup/restore-journal.json").exists())
    }
    @Test fun ordinaryPostCommitFailureReportsKnownSuccessRatherThanDuplicateImport() = runBlocking {
        val source = fixture("source"); source.seed(); val file = archive(source); val target = fixture("target")
        val engine = target.engine { if (it == "after-commit") throw IOException("TEST_ONLY_IO_FAILURE") }; engine.initialize(); engine.checkDocument(Uri.fromFile(file), secret)
        assertTrue(engine.restore(false).startsWith("资料已恢复")); assertEquals(2, target.data().movieCount)
    }
    @Test fun pendingSettingsRetryDoesNotImportRowsAgain() = runBlocking {
        val source = fixture("source"); source.seed(); source.theme.applyBackupPreferences(BackupPreferences("DARK", "POSTER_WALL", "CREATED_ASC")); val file = archive(source); val target = fixture("target")
        val engine = target.engine { if (it == "after-commit") throw SimulatedDeath() }; engine.initialize(); engine.checkDocument(Uri.fromFile(file), secret)
        try { engine.restore(true); fail("Fault not exercised") } catch (_: SimulatedDeath) { }
        val restarted = target.engine(); restarted.initialize(); assertTrue(restarted.recovery.value.pendingSettings); restarted.retrySettings()
        assertEquals("DARK", target.theme.backupPreferences().theme); assertEquals(2, target.data().movieCount); assertFalse(restarted.recovery.value.pendingSettings)
    }

    private fun rewrite(file: File, mutate: (org.json.JSONObject) -> Unit): File {
        val plain = ByteArrayOutputStream(); file.inputStream().use { BackupCipher.decrypt(it, plain, secret) }
        val entries = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(plain.toByteArray().inputStream()).use { input -> while (true) { val entry = input.nextEntry ?: break; entries[entry.name] = input.readBytes(); input.closeEntry() } }
        val data = org.json.JSONObject(String(entries.getValue("data.json"), Charsets.UTF_8)); mutate(data)
        entries["data.json"] = data.toString().toByteArray(Charsets.UTF_8)
        val manifest = org.json.JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8)).put("dataSha256", sha256(entries.getValue("data.json")))
        entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
        val zip = ByteArrayOutputStream(); ZipOutputStream(zip).use { out -> entries.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() } }
        return File(area, UUID.randomUUID().toString() + ".yjb").also { result -> result.outputStream().use { BackupCipher.encrypt(zip.toByteArray().inputStream(), it, secret) } }
    }
    @Test fun authenticatedBrokenForeignKeyIsStillRejectedBeforeLiveInsert() = runBlocking {
        val source = fixture("source"); source.seed(); val file = rewrite(archive(source)) { it.getJSONObject("tables").getJSONArray("movie_categories").getJSONArray(0).put(1, 999999L) }
        val target = fixture("target"); val before = target.data().fingerprint(); val engine = target.engine(); engine.initialize()
        reject { engine.checkDocument(Uri.fromFile(file), secret) }; assertEquals(before, target.data().fingerprint())
    }
    @Test fun authenticatedInvalidDateRangeIsRejectedWithoutChangingTarget() = runBlocking {
        val source = fixture("source"); source.seed(); val file = rewrite(archive(source)) { it.getJSONObject("tables").getJSONArray("watch_history").getJSONArray(1).put(3, 999L) }
        val target = fixture("target"); val before = target.data().fingerprint(); val engine = target.engine(); engine.initialize()
        reject { engine.checkDocument(Uri.fromFile(file), secret) }; assertEquals(before, target.data().fingerprint())
    }
    @Test fun concurrentWriterWaitsForSnapshotAndDuplicateExportFailsSafely() = runBlocking {
        val source = fixture("source"); source.seed()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val engine = source.engine { if (it == "snapshot-start") { entered.countDown(); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)) } }
        engine.initialize()
        val first = async(Dispatchers.IO) { engine.prepareExport(secret) }
        check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val secondPassword = secret; reject { engine.prepareExport(secondPassword) }; assertTrue(secondPassword.all { it == 0.toChar() })
        val attempted = CompletableDeferred<Unit>(); val writerEntered = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = async(Dispatchers.IO) { attempted.complete(Unit); source.db.withTransaction { writerEntered.set(true); source.db.openHelper.writableDatabase.execSQL("INSERT INTO tags(name) VALUES('TEST_ONLY_CONCURRENT')") } }
        attempted.await(); assertFalse(writerEntered.get()); release.countDown(); first.await(); writer.await()
        val out = File(area, "snapshot.yjb"); engine.writeExport(Uri.fromFile(out))
        val target = fixture("target"); val reader = target.engine(); reader.initialize()
        assertEquals(1, reader.checkDocument(Uri.fromFile(out), secret).tags); assertEquals(2, source.data().rows("tags").size)
    }

    @Test fun deletedEntityProfileFeatureRemainsDetachedAfterIdRemapping() = runBlocking {
        val source = fixture("source"); source.seed()
        source.db.withTransaction { source.db.openHelper.writableDatabase.execSQL("INSERT INTO ai_explicit_preferences(featureId,label,direction,updatedAt) VALUES('category:999999','TEST_ONLY_DELETED_CATEGORY','LIKE',1000)") }
        val file = archive(source); val target = fixture("target"); val engine = target.engine(); engine.initialize(); engine.checkDocument(Uri.fromFile(file), secret); engine.restore(false)
        val data = target.data(); val feature = data.rows("ai_explicit_preferences").single { it.getString(1) == "TEST_ONLY_DELETED_CATEGORY" }.getString(0)
        assertTrue(feature.startsWith("category:")); assertFalse(data.rows("categories").any { "category:" + it.getLong(0) == feature })
        assertEquals(2, data.movieCount)
    }

    @Test fun existingNonemptyBackupDocumentIsNeverTruncatedOrDeleted() = runBlocking {
        val source = fixture("source"); source.seed(); val engine = source.engine(); engine.initialize(); engine.prepareExport(secret)
        val old = File(area, "old-backup.yjb").apply { writeText("TEST_ONLY_KEEP_ORIGINAL_BACKUP") }
        val original = old.readBytes(); reject { engine.writeExport(Uri.fromFile(old)) }; assertArrayEquals(original, old.readBytes()); engine.discard()
    }

    @Test fun insufficientSpaceRejectsExportBeforeProducingAnyArchive() = runBlocking {
        val source = fixture("low-space-source"); source.seed()
        val before = source.data().fingerprint()
        val engine = source.engine(space = { 0L }); engine.initialize()
        val password = secret
        reject { engine.prepareExport(password) }
        assertTrue(password.all { it == 0.toChar() })
        assertEquals(before, source.data().fingerprint())
        assertTrue(File(source.ctx.noBackupFilesDir, "portable-backup").listFiles().orEmpty().isEmpty())
    }
    @Test fun spaceLostAfterPreviewDoesNotPublishImagesOrInsertRows() = runBlocking {
        val source = fixture("space-source"); source.seed(); val file = archive(source)
        val target = fixture("space-target"); var space = Long.MAX_VALUE
        val engine = target.engine(space = { space }); engine.initialize()
        assertTrue(engine.checkDocument(Uri.fromFile(file), secret).canRestore)
        val before = target.data().fingerprint(); space = 0
        reject { engine.restore(false) }
        assertEquals(before, target.data().fingerprint())
        assertTrue(File(target.ctx.filesDir, "posters").listFiles().orEmpty().isEmpty())
        engine.discard()
    }
    @Test fun settingsWriteFailureLeavesCommittedDataAndRetryOnlyWritesSettings() = runBlocking {
        val source = fixture("settings-source"); source.seed()
        source.theme.applyBackupPreferences(BackupPreferences("DARK", "POSTER_WALL", "CREATED_ASC"))
        val file = archive(source); val target = fixture("settings-target")
        val beforeSettings = target.theme.backupPreferences()
        val engine = target.engine(); engine.initialize(); engine.checkDocument(Uri.fromFile(file), secret)
        target.failSettings = true
        assertEquals("资料已恢复；显示设置未应用，请单独重试，不要再次导入", engine.restore(true))
        assertTrue(engine.recovery.value.pendingSettings)
        assertEquals(beforeSettings, target.theme.backupPreferences())
        val committed = target.data().fingerprint(); assertEquals(2, target.data().movieCount)
        target.failSettings = false; engine.retrySettings()
        assertFalse(engine.recovery.value.pendingSettings)
        assertEquals(source.theme.backupPreferences(), target.theme.backupPreferences())
        assertEquals(committed, target.data().fingerprint())
    }

    @Test fun databaseProtectionActivatedAfterPreviewBlocksRestoreBeforePublishingFiles() = runBlocking {
        val source = fixture("protected-source"); source.seed(); val file = archive(source)
        val target = fixture("protected-target"); val engine = target.engine(); engine.initialize()
        assertTrue(engine.checkDocument(Uri.fromFile(file), secret).canRestore)
        val before = target.data().fingerprint()
        DatabaseHealthMonitor.stop(com.yingji.app.data.database.DatabaseIssue.ACCESS)
        reject { engine.restore(false) }
        assertEquals(before, target.data().fingerprint())
        assertTrue(File(target.ctx.filesDir, "posters").listFiles().orEmpty().isEmpty())
        DatabaseHealthMonitor.resetForTesting(); engine.discard()
    }
    @Test fun stoppedDatabaseCannotCreateANewBackup() = runBlocking {
        val source = fixture("stopped-source"); source.seed(); val before = source.data().fingerprint()
        val engine = source.engine(); engine.initialize()
        DatabaseHealthMonitor.stop(com.yingji.app.data.database.DatabaseIssue.ACCESS)
        val password = secret; reject { engine.prepareExport(password) }
        assertTrue(password.all { it == 0.toChar() }); assertEquals(before, source.data().fingerprint())
        assertTrue(File(source.ctx.noBackupFilesDir, "portable-backup").listFiles().orEmpty().isEmpty())
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test fun unavailableStagingDirectoryShowsRetryStateInsteadOfCrashingInitialization() = runBlocking {
        val target = fixture("blocked-staging"); val before = target.data().fingerprint()
        val parent = target.ctx.noBackupFilesDir
        assertTrue(parent.delete()); parent.writeText("TEST_ONLY_BLOCKED_DIRECTORY")
        val engine = target.engine(); engine.initialize()
        assertFalse(engine.recovery.value.ready); assertNotNull(engine.recovery.value.error)
        assertEquals(before, target.data().fingerprint()); assertEquals("TEST_ONLY_BLOCKED_DIRECTORY", parent.readText())
        assertTrue(parent.delete()); assertTrue(parent.mkdir())
        engine.initialize(); assertTrue(engine.recovery.value.ready)
        assertEquals(before, target.data().fingerprint())
    }
}
