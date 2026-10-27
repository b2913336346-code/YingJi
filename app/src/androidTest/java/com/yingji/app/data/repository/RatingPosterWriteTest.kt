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

class RatingPosterWriteTest {
    private lateinit var database: AppDatabase
    private lateinit var files: File
    private lateinit var posters: PosterRepository
    private lateinit var movies: MovieRepository
    private lateinit var viewModel: MovieViewModel
    private val store = ViewModelStore()
    private var category = 0L

    @Before fun setup() = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        val base = ApplicationProvider.getApplicationContext<Context>()
        files = File(base.cacheDir, "rating-poster-" + UUID.randomUUID()).apply { mkdirs() }
        posters = PosterRepository(object : ContextWrapper(base) { override fun getFilesDir() = files })
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        movies = MovieRepository(database)
        category = database.categoryDao().insert(Category(name = "电影", isSystem = false))
        viewModel = MovieViewModel(movies, AddMovieUseCase(movies), UpdateStatusUseCase(movies), posters)
        store.put("movie", viewModel)
    }
    @After fun teardown() {
        store.clear()
        database.close()
        files.deleteRecursively()
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test fun createEditAndClearRatingReachTheDatabaseWithoutChangingIdentityOrClassification() = runBlocking {
        val created = CompletableDeferred<Unit>()
        viewModel.addMovie(form(8.5)) { created.complete(Unit) }
        withTimeout(5_000) { created.await() }
        val original = database.movieDao().observeAll().first().single()
        assertEquals(8.5, original.rating)
        val edited = CompletableDeferred<Unit>()
        viewModel.updateMovie(original, form(9.5)) { edited.complete(Unit) }
        withTimeout(5_000) { edited.await() }
        val current = requireNotNull(database.movieDao().getById(original.id))
        assertEquals(9.5, current.rating)
        assertEquals(listOf(category), database.categoryDao().getCategoryIdsForMovie(current.id))
        val cleared = CompletableDeferred<Unit>()
        viewModel.updateMovie(current, form(null)) { cleared.complete(Unit) }
        withTimeout(5_000) { cleared.await() }
        assertNull(database.movieDao().getById(original.id)?.rating)
        assertEquals(1, database.movieDao().observeAll().first().size)
    }

    @Test fun invalidRatingCannotPartiallySaveMovieHistoryOrRelations() = runBlocking {
        val id = movies.insertWithRelations(Movie(name = "原作品", type = "电影", status = MovieStatus.WANT_TO_WATCH.value,
            createTime = 1, rating = 8.5), category, emptySet())
        val original = requireNotNull(database.movieDao().getById(id))
        listOf(Double.NaN, 8.25, -0.5, 10.5).forEach { invalid ->
            assertTrue(runCatching { movies.updateWithRelations(original.copy(rating = invalid,
                status = MovieStatus.WATCHING.value), category, emptySet()) }.isFailure)
            assertEquals(original, database.movieDao().getById(id))
            assertTrue(database.watchHistoryDao().getForMovie(id).isEmpty())
            assertEquals(listOf(category), database.categoryDao().getCategoryIdsForMovie(id))
            assertTrue(runCatching { movies.insert(original.copy(id = 0, rating = invalid)) }.isFailure)
            assertEquals(1, database.movieDao().observeAll().first().size)
        }
    }

    @Test fun posterRemovalFailureKeepsOriginalFileAndRatingUntilRetryCommits() = runBlocking {
        val source = File(files, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val path = requireNotNull(posters.persist(Uri.fromFile(source).toString()))
        val id = movies.insertWithRelations(Movie(name = "原作品", type = "电影", status = MovieStatus.WANT_TO_WATCH.value,
            posterPath = path, createTime = 1, rating = 8.5), category, emptySet())
        val original = requireNotNull(database.movieDao().getById(id))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_edit BEFORE UPDATE ON movies BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        var completed = false
        viewModel.updateMovie(original, form(8.5)) { completed = true }
        withTimeout(5_000) { viewModel.formActionState.first { it.message != null } }
        assertFalse(completed)
        assertEquals(original, database.movieDao().getById(id))
        assertTrue(File(path).isFile)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_edit")
        val saved = CompletableDeferred<Unit>()
        viewModel.updateMovie(original, form(8.5)) { saved.complete(Unit) }
        withTimeout(5_000) { saved.await() }
        assertNull(database.movieDao().getById(id)?.posterPath)
        assertEquals(8.5, database.movieDao().getById(id)?.rating)
        assertFalse(File(path).exists())
        assertEquals(listOf(category), database.categoryDao().getCategoryIdsForMovie(id))
    }

    private fun form(rating: Double?) = MovieFormData("评分作品", "电影", MovieStatus.WANT_TO_WATCH,
        0, null, null, category, emptySet(), rating)
}
