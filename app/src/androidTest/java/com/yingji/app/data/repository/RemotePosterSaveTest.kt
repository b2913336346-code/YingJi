package com.yingji.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.ai.AiCandidateImport
import com.yingji.app.data.ai.TmdbPosterPreviewLoader
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.*
import com.yingji.app.domain.ai.TmdbPoster
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemotePosterSaveTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "remote-poster-save-" + UUID.randomUUID() + ".db"
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var files: PosterRepository
    private var filesRoot: File? = null
    private val reads = AtomicInteger()
    private var fetch: suspend () -> Bitmap = { image() }
    private val source = TmdbPosterPreviewLoader { reads.incrementAndGet(); fetch() }
    private val poster = TmdbPoster("/fictionalPoster.png")
    @Before fun setup() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting()
        directory = File(base.cacheDir, "remote-poster-save-" + UUID.randomUUID()).apply { check(mkdirs()) }
        context = object : ContextWrapper(base) { override fun getFilesDir() = filesRoot ?: directory }
        files = PosterRepository(context)
        openDatabase()
        db.categoryDao().insert(Category(7, "既有分类", false))
        db.tagDao().insert(Tag(7, "既有标签"))
    }
    private fun openDatabase() {
        db = Room.databaseBuilder(base, AppDatabase::class.java, databaseName).build()
        movies = MovieRepository(db)
    }
    @After fun cleanup() {
        DatabaseHealthMonitor.resetForTesting()
        db.close(); assertTrue(base.deleteDatabase(databaseName)); directory.deleteRecursively()
    }
    private fun image() = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
    private fun service(store: PosterRepository = files) = SaveRemotePosterUseCase(movies, store, source)
    private suspend fun oldPath(): String {
        val bitmap = image()
        return try { files.persistRemote(bitmap) } finally { bitmap.recycle() }
    }
    private suspend fun movie(path: String? = null, mapped: Boolean = true, name: String = "既有作品", withPoster: Boolean = true): Movie {
        val localPath = if (withPoster) path ?: oldPath() else null
        val id = movies.insertWithRelations(Movie(name = name, posterPath = localPath, type = "既有分类",
            status = MovieStatus.WATCHING.value, currentEpisode = 2, totalEpisode = 12, createTime = 100, rating = 8.5), 7, setOf(7))
        if (mapped) db.candidateImportDao().insert(AiCandidateImport("tmdb", "movie:" + id, id))
        return requireNotNull(db.movieDao().getById(id))
    }
    private suspend fun unchanged(original: Movie) {
        assertEquals(original, db.movieDao().getById(original.id))
        assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(original.id))
        assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(original.id))
        assertTrue(File(requireNotNull(original.posterPath)).exists())
    }
    private fun posterNames() = File(directory, "posters").list().orEmpty().toSet()

    @Test fun noConfirmationMissingMovieOrMissingTmdbMappingNeverDownloadsOrWrites() = runBlocking {
        val original = movie()
        val plain = movie(mapped = false, name = "本地作品")
        val before = posterNames()
        assertTrue(runCatching { service()(original.id, poster, false) }.isFailure)
        assertTrue(runCatching { service()(Long.MAX_VALUE, poster, true) }.isFailure)
        assertTrue(runCatching { service()(plain.id, poster, true) }.isFailure)
        assertEquals(0, reads.get()); assertEquals(before, posterNames())
        unchanged(original); unchanged(plain)
    }
    @Test fun successChangesOnlyThePosterAndRemainsValidAfterReopeningTheDatabase() = runBlocking {
        val original = movie(); val history = db.watchHistoryDao().getForMovie(original.id)
        val result = service()(original.id, poster, true)
        assertFalse(result.previousFileCleanupDeferred)
        assertEquals(original.copy(posterPath = result.localPath), db.movieDao().getById(original.id))
        assertEquals(history, db.watchHistoryDao().getForMovie(original.id))
        assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(original.id))
        assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(original.id))
        assertFalse(File(original.posterPath!!).exists()); assertTrue(File(result.localPath).isFile)
        requireNotNull(BitmapFactory.decodeFile(result.localPath)).let { bitmap -> assertEquals(Color.GRAY, bitmap.getPixel(0, 0)); bitmap.recycle() }
        assertEquals(setOf(File(result.localPath).name), posterNames())
        db.close(); openDatabase()
        assertEquals(result.localPath, db.movieDao().getById(original.id)?.posterPath)
        assertEquals(original.id, db.candidateImportDao().movieId("tmdb", "movie:" + original.id))
        assertEquals(history, db.watchHistoryDao().getForMovie(original.id))
        assertEquals(1, reads.get())
    }

    @Test fun firstPosterSaveSupportsANullPreviousReferenceWithoutChangingOtherFields() = runBlocking {
        val original = movie(withPoster = false)
        val result = service()(original.id, poster, true)
        assertEquals(original.copy(posterPath = result.localPath), db.movieDao().getById(original.id))
        assertFalse(result.previousFileCleanupDeferred)
        assertTrue(File(result.localPath).isFile)
        assertEquals(setOf(File(result.localPath).name), posterNames())
    }

    @Test fun networkFailurePreservesTheWholeOriginalRecordAndItsFile() = runBlocking {
        val original = movie(); val bytes = File(original.posterPath!!).readBytes(); val before = posterNames()
        fetch = { throw IOException("Synthetic network failure") }
        assertTrue(runCatching { service()(original.id, poster, true) }.exceptionOrNull() is RemotePosterSaveException)
        unchanged(original); assertArrayEquals(bytes, File(original.posterPath).readBytes()); assertEquals(before, posterNames())
    }
    @Test fun invalidDecodedImageAndBlockedDirectoryPreserveOldData() = runBlocking {
        val original = movie(); val before = posterNames()
        fetch = { image().apply { recycle() } }
        assertTrue(runCatching { service()(original.id, poster, true) }.isFailure)
        unchanged(original); assertEquals(before, posterNames())
        fetch = { image() }
        filesRoot = File(directory, "blocked").apply { writeText("not a directory") }
        assertTrue(runCatching { service()(original.id, poster, true) }.isFailure)
        filesRoot = null
        unchanged(original); assertEquals(before, posterNames())
    }
    @Test fun noSpaceBeforeWritingPreservesDatabaseAndOldPosterAcrossReopenThenManualRetrySucceeds() = runBlocking {
        assertNoSpaceSaveRollbackAndRetry(0)
    }

    @Test fun noSpaceAfterPartialWritingPreservesDatabaseAndOldPosterAcrossReopenThenManualRetrySucceeds() = runBlocking {
        assertNoSpaceSaveRollbackAndRetry(32)
    }

    private suspend fun assertNoSpaceSaveRollbackAndRetry(bytesBeforeFailure: Int) {
        val original = movie()
        val bytes = File(original.posterPath!!).readBytes()
        val before = posterNames()
        val history = db.watchHistoryDao().getForMovie(original.id)
        var writeCalls = 0
        var partialLength = -1L
        val store = PosterRepository(context, writeRemote = { _, staged ->
            writeCalls++
            staged.outputStream().use { output ->
                output.write(ByteArray(bytesBeforeFailure) { 85 })
                output.flush()
                partialLength = staged.length()
                throw IOException("Injected write failure", android.system.ErrnoException("write", android.system.OsConstants.ENOSPC))
            }
        }, publishRemote = { _, _ -> fail("No-space write must not publish a new file") })
        val failure = runCatching { service(store)(original.id, poster, true) }.exceptionOrNull()
        assertTrue(failure is RemotePosterSaveException)
        assertEquals(android.system.OsConstants.ENOSPC, (failure?.cause?.cause as android.system.ErrnoException).errno)
        assertEquals(1, writeCalls)
        assertEquals(bytesBeforeFailure.toLong(), partialLength)
        assertEquals(1, reads.get()) // No automatic retry after storage failure.
        unchanged(original)
        assertArrayEquals(bytes, File(original.posterPath).readBytes())
        assertEquals(before, posterNames()) // Includes both .part and final filenames.
        db.close(); openDatabase()
        unchanged(original)
        assertEquals(history, db.watchHistoryDao().getForMovie(original.id))
        assertEquals(original.id, db.candidateImportDao().movieId("tmdb", "movie:" + original.id))
        assertArrayEquals(bytes, File(original.posterPath).readBytes())
        assertEquals(before, posterNames())
        val saved = service()(original.id, poster, true) // New, explicit user retry after space recovers.
        assertEquals(original.copy(posterPath = saved.localPath), db.movieDao().getById(original.id))
        assertEquals(history, db.watchHistoryDao().getForMovie(original.id))
        assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(original.id))
        assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(original.id))
        assertFalse(File(original.posterPath).exists())
        assertEquals(setOf(File(saved.localPath).name), posterNames())
        assertEquals(2, reads.get())
        requireNotNull(BitmapFactory.decodeFile(saved.localPath)).let { decoded ->
            assertEquals(Color.GRAY, decoded.getPixel(0, 0)); decoded.recycle()
        }
    }

    @Test fun failedDatabaseUpdateRemovesOnlyTheNewFileAndCanRetry() = runBlocking {
        val original = movie(); val before = posterNames(); val history = db.watchHistoryDao().getForMovie(original.id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_remote_poster BEFORE UPDATE ON movies BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        assertTrue(runCatching { service()(original.id, poster, true) }.isFailure)
        unchanged(original); assertEquals(before, posterNames()); assertEquals(history, db.watchHistoryDao().getForMovie(original.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_remote_poster")
        val result = service()(original.id, poster, true)
        assertEquals(result.localPath, db.movieDao().getById(original.id)?.posterPath)
        assertFalse(File(original.posterPath!!).exists()); assertEquals(2, reads.get())
    }
    @Test fun aSharedOldPosterSurvivesUntilTheLastReferenceIsReplaced() = runBlocking {
        val first = movie(); val second = movie(first.posterPath, name = "另一作品")
        val firstResult = service()(first.id, poster, true)
        assertTrue(File(first.posterPath!!).isFile)
        assertEquals(first.posterPath, db.movieDao().getById(second.id)?.posterPath)
        val secondResult = service()(second.id, poster, true)
        assertFalse(File(first.posterPath).exists())
        assertNotEquals(firstResult.localPath, secondResult.localPath)
        assertTrue(File(firstResult.localPath).isFile); assertTrue(File(secondResult.localPath).isFile)
    }
    @Test fun concurrentRatingAndNameEditsArePreservedByThePosterOnlyUpdate() = runBlocking {
        val original = movie(); val ready = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { ready.complete(Unit); release.await(); image() }
        val pending = async { service()(original.id, poster, true) }
        ready.await(); movies.update(original.copy(name = "并发改名", rating = 9.5))
        val edited = requireNotNull(db.movieDao().getById(original.id)); release.complete(Unit)
        val result = pending.await()
        assertEquals(edited.copy(posterPath = result.localPath), db.movieDao().getById(original.id))
        assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(original.id))
    }
    @Test fun aConcurrentPosterChangeIsNotOverwrittenAndOnlyOurNewFileIsDiscarded() = runBlocking {
        val original = movie(); val ready = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { ready.complete(Unit); release.await(); image() }
        val pending = async { runCatching { service()(original.id, poster, true) } }
        ready.await(); val differentPath = oldPath(); movies.update(original.copy(posterPath = differentPath))
        val before = posterNames(); release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertEquals(differentPath, db.movieDao().getById(original.id)?.posterPath)
        assertTrue(File(differentPath).isFile); assertTrue(File(original.posterPath!!).isFile); assertEquals(before, posterNames())
    }
    @Test fun deletionDuringDownloadCannotRecreateTheMovieOrDeleteItsOldFile() = runBlocking {
        val original = movie(); val ready = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { ready.complete(Unit); release.await(); image() }
        val pending = async { runCatching { service()(original.id, poster, true) } }
        ready.await(); movies.delete(original); val before = posterNames(); release.complete(Unit)
        assertTrue(pending.await().isFailure); assertNull(db.movieDao().getById(original.id))
        assertTrue(File(original.posterPath!!).isFile); assertEquals(before, posterNames())
        assertNull(db.candidateImportDao().movieId("tmdb", "movie:" + original.id))
    }
    @Test fun aChangedSourceDuringDownloadRejectsTheCommit() = runBlocking {
        val original = movie(); val ready = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { ready.complete(Unit); release.await(); image() }
        val pending = async { runCatching { service()(original.id, poster, true) } }
        ready.await(); db.openHelper.writableDatabase.execSQL("DELETE FROM ai_candidate_imports WHERE movieId=" + original.id)
        val before = posterNames(); release.complete(Unit)
        assertTrue(pending.await().isFailure); unchanged(original); assertEquals(before, posterNames())
    }
    @Test fun rapidDuplicateRequestsDoNotQueueASecondDownloadAndCancellationPreservesOldData() = runBlocking {
        val original = movie(); val useCase = service(); val ready = CompletableDeferred<Unit>()
        fetch = { ready.complete(Unit); awaitCancellation() }
        val pending = launch { useCase(original.id, poster, true) }
        ready.await()
        assertTrue(runCatching { useCase(original.id, poster, true) }.exceptionOrNull() is RemotePosterSaveException)
        assertEquals(1, reads.get()); pending.cancelAndJoin()
        unchanged(original); assertEquals(setOf(File(original.posterPath!!).name), posterNames())
    }
    @Test fun cancellationAfterFilePublicationCleansTheNewFileBeforeAnyDatabaseWrite() = runBlocking {
        val original = movie(); val before = posterNames()
        lateinit var pending: Job
        val store = PosterRepository(context) { temporary, target ->
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            pending.cancel()
        }
        pending = launch(start = CoroutineStart.LAZY) { service(store)(original.id, poster, true) }
        pending.start(); pending.join()
        assertTrue(pending.isCancelled); unchanged(original); assertEquals(before, posterNames())
    }

    @Test fun cancellationWhileWaitingForTheDatabaseCannotCommitAQueuedPoster() = runBlocking {
        val original = movie(); val before = posterNames()
        val downloaded = CompletableDeferred<Unit>(); val continueDownload = CompletableDeferred<Unit>()
        val databaseLocked = CompletableDeferred<Unit>(); val releaseDatabase = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        fetch = { downloaded.complete(Unit); continueDownload.await(); image() }
        val store = PosterRepository(context) { temporary, target ->
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            published.complete(Unit)
        }
        val pending = launch { service(store)(original.id, poster, true) }
        downloaded.await()
        val blockingWrite = launch { DatabaseHealthMonitor.protectWrite { databaseLocked.complete(Unit); releaseDatabase.await() } }
        databaseLocked.await(); continueDownload.complete(Unit); published.await()
        pending.cancel(); releaseDatabase.complete(Unit)
        blockingWrite.join(); pending.join()
        assertTrue(pending.isCancelled); unchanged(original); assertEquals(before, posterNames())
    }

    @Test fun oldCleanupFailureIsReportedWithoutUndoingAnAlreadyCommittedPoster() = runBlocking {
        val legacy = File(File(directory, "posters"), "legacy-directory").apply { mkdirs() }
        File(legacy, "keep.txt").writeText("keep existing evidence")
        val original = movie(legacy.path)
        val result = service()(original.id, poster, true)
        assertTrue(result.previousFileCleanupDeferred)
        assertEquals(result.localPath, db.movieDao().getById(original.id)?.posterPath)
        assertTrue(File(result.localPath).isFile)
        assertEquals("keep existing evidence", File(legacy, "keep.txt").readText())
    }
}
