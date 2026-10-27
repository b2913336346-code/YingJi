package com.yingji.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.AddMovieUseCase
import com.yingji.app.domain.usecase.UpdateStatusUseCase
import com.yingji.app.presentation.movie.MovieFormData
import com.yingji.app.presentation.movie.MovieViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class PosterSaveFailureTest {
    private lateinit var directory: File
    private lateinit var posters: PosterRepository
    private lateinit var database: AppDatabase
    private lateinit var viewModel: MovieViewModel
    private val store = ViewModelStore()
    private var categoryId = 0L

    @Before fun setup() = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        val base = ApplicationProvider.getApplicationContext<Context>()
        directory = File(base.cacheDir, "poster-test-" + UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
        posters = PosterRepository(context)
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        categoryId = database.categoryDao().insert(Category(name = "分类", isSystem = false))
        val movies = MovieRepository(database)
        viewModel = MovieViewModel(movies, AddMovieUseCase(movies), UpdateStatusUseCase(movies), posters)
        store.put("movie", viewModel)
    }

    @After fun teardown() {
        store.clear()
        database.close()
        directory.deleteRecursively() // Only this UUID-named test fixture.
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test fun missingSourceReportsFailureInsteadOfSavingWithoutPosterAndRetryWorks() = runBlocking {
        var completed = false
        viewModel.addMovie(form(File(directory, "missing.jpg").path)) { completed = true }
        val failure = withTimeout(5_000) { viewModel.formActionState.first { it.message != null } }
        assertEquals("海报保存失败，请重新选择图片后重试", failure.message)
        assertFalse(failure.isSaving)
        assertFalse(completed)
        assertTrue(database.movieDao().observeAll().first().isEmpty())
        val success = CompletableDeferred<Unit>()
        viewModel.addMovie(form(source())) { success.complete(Unit) }
        withTimeout(5_000) { success.await() }
        val saved = database.movieDao().observeAll().first().single()
        assertTrue(File(requireNotNull(saved.posterPath)).isFile)
    }

    @Test fun missingReplacementPreservesOriginalMoviePosterAndRelations() = runBlocking {
        val oldPoster = requireNotNull(posters.persist(source()))
        val movies = MovieRepository(database)
        val id = movies.insertWithRelations(Movie(name = "原作品", type = "分类", status = MovieStatus.WANT_TO_WATCH.value,
            posterPath = oldPoster, createTime = 1), categoryId, emptySet())
        val original = requireNotNull(database.movieDao().getById(id))
        var completed = false
        viewModel.updateMovie(original, form(File(directory, "missing.jpg").path)) { completed = true }
        withTimeout(5_000) { viewModel.formActionState.first { it.message != null } }
        assertFalse(completed)
        assertEquals(original, database.movieDao().getById(id))
        assertEquals(listOf(categoryId), database.categoryDao().getCategoryIdsForMovie(id))
        assertTrue(File(oldPoster).isFile)
    }

    @Test fun databaseFailureRemovesOnlyNewCopyAndKeepsTheSource() = runBlocking {
        val input = source()
        viewModel.addMovie(form(input).copy(tagIds = setOf(Long.MAX_VALUE))) { fail("must not report success") }
        withTimeout(5_000) { viewModel.formActionState.first { it.message != null } }
        assertTrue(database.movieDao().observeAll().first().isEmpty())
        assertTrue(File(Uri.parse(input).path!!).isFile)
        assertTrue(File(directory, "posters").listFiles().orEmpty().isEmpty())
    }

    @Test fun absolutePathsAreCopiedSoTwoMoviesNeverBorrowTheSameFile() = runBlocking {
        val first = requireNotNull(posters.persist(source()))
        val second = requireNotNull(posters.persist(first))
        assertNotEquals(first, second)
        assertArrayEquals(File(first).readBytes(), File(second).readBytes())
        posters.delete(first)
        assertTrue(File(second).isFile)
    }

    @Test fun blockedPosterDirectoryReportsFailureAndDoesNotModifySource() = runBlocking {
        File(directory, "posters").writeText("blocked")
        val input = source()
        assertTrue(runCatching { posters.persist(input) }.exceptionOrNull() is PosterSaveException)
        assertEquals("blocked", File(directory, "posters").readText())
        assertTrue(File(Uri.parse(input).path!!).isFile)
    }

    @Test fun noSelectedPosterRemainsOptional() = runBlocking {
        assertNull(posters.persist(null))
        assertNull(posters.persist("  "))
    }

    @Test fun rapidSaveAndMessageConsumptionCannotAllowADuplicateMovie() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        var callbacks = 0
        val data = form(source())
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.addMovie(data) { callbacks++; completed.complete(Unit) }
            viewModel.consumeFormMessage()
            repeat(8) { viewModel.addMovie(data) { callbacks++ } }
        }
        withTimeout(5_000) { completed.await() }
        assertEquals(1, callbacks)
        assertEquals(1, database.movieDao().observeAll().first().size)
    }

    @Test fun deletingOneOfTwoLegacyReferencesDoesNotDeleteTheOtherMoviesPoster() = runBlocking {
        val shared = requireNotNull(posters.persist(source()))
        val movies = MovieRepository(database)
        suspend fun create(name: String): Movie {
            val id = movies.insertWithRelations(Movie(name = name, type = "分类", status = MovieStatus.WANT_TO_WATCH.value,
                posterPath = shared, createTime = 1), categoryId, emptySet())
            return requireNotNull(database.movieDao().getById(id))
        }
        val first = create("作品一")
        val second = create("作品二")
        val deleted = CompletableDeferred<Unit>()
        viewModel.deleteMovie(first) { deleted.complete(Unit) }
        withTimeout(5_000) { deleted.await() }
        assertNull(database.movieDao().getById(first.id))
        assertEquals(shared, database.movieDao().getById(second.id)?.posterPath)
        assertTrue(File(shared).isFile)
        val lastDeleted = CompletableDeferred<Unit>()
        viewModel.deleteMovie(second) { lastDeleted.complete(Unit) }
        withTimeout(5_000) { lastDeleted.await() }
        assertFalse(File(shared).exists())
    }

    @Test fun blockedStatusChangeHasVisibleViewModelErrorsAndNeverReportsSaveSuccess() = runBlocking {
        val movies = MovieRepository(database)
        val id = movies.insertWithRelations(Movie(name = "观看中作品", type = "分类", status = MovieStatus.WATCHING.value,
            createTime = 1), categoryId, emptySet())
        val original = requireNotNull(database.movieDao().getById(id))
        var completed = false
        viewModel.updateMovie(original, form(null)) { completed = true }
        val formError = withTimeout(5_000) { viewModel.formActionState.first { it.message != null } }
        assertEquals("请先结束本次观看，再改为想看", formError.message)
        assertFalse(completed)
        viewModel.updateStatus(original, MovieStatus.WANT_TO_WATCH)
        val detailError = withTimeout(5_000) { viewModel.actionState.first { it.message != null } }
        assertEquals(formError.message, detailError.message)
        assertEquals(original, database.movieDao().getById(id))
        assertEquals(1, database.watchHistoryDao().getForMovie(id).count { it.endTime == null })
    }

    private fun source(): String = Uri.fromFile(File(directory, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }).toString()
    private fun form(poster: String?) = MovieFormData("新作品", "分类", MovieStatus.WANT_TO_WATCH,
        0, null, poster, categoryId, emptySet())
}
