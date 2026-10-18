package com.yingji.app.presentation.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.dao.WatchHistoryDao
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.ThemeRepository
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.WatchTimeField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class SearchViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var settings: ThemeRepository
    private val store = ViewModelStore()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        settings = ThemeRepository(context, object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        })
        database.categoryDao().insert(Category(id = 1L, name = "电影", isSystem = false))
        database.tagDao().insert(Tag(id = 1L, name = "推荐"))
        listOf(
            Movie(id = 1, name = "碧蓝之海", type = "电影", status = MovieStatus.FINISHED.value, createTime = 100),
            Movie(id = 2, name = "碧蓝航线", type = "电影", status = MovieStatus.WATCHING.value, createTime = 200),
            Movie(id = 3, name = "无记录", type = "电影", status = MovieStatus.WANT_TO_WATCH.value, createTime = 300),
        ).forEach { database.movieDao().insert(it) }
        database.categoryDao().addMovieCategory(MovieCategory(1, 1))
        database.categoryDao().addMovieCategory(MovieCategory(2, 1))
        database.tagDao().addMovieTag(MovieTag(1, 1))
        database.tagDao().addMovieTag(MovieTag(3, 1))
        database.watchHistoryDao().insert(WatchHistory(movieId = 1, startTime = 1_000, endTime = 2_000, duration = 1_000, watchNumber = 1))
        database.watchHistoryDao().insert(WatchHistory(movieId = 2, startTime = 3_000, endTime = null, duration = 0, watchNumber = 1))
        Unit
    }

    @After
    fun tearDown() {
        onMain { store.clear() }
        database.close()
    }

    @Test
    fun combinedFiltersReachRoomDataAndClearingTimeRestoresUndatedMovies() = runBlocking {
        val vm = createViewModel()
        awaitSearch(vm) { it.movies.size == 3 }
        onMain {
            vm.updateQuery("bilan")
            vm.selectCategory(1)
            vm.selectTag(1)
            vm.selectStatus(MovieStatus.FINISHED)
            vm.selectTimeField(WatchTimeField.END_TIME)
            vm.setFromTime(2_000)
            vm.setToTime(2_000)
        }
        val filtered = awaitSearch(vm) { it.fromTime == 2_000L && it.movies.map { m -> m.id } == listOf(1L) }
        assertEquals(1L, filtered.selectedCategoryId)
        assertEquals(1L, filtered.selectedTagId)
        assertEquals("电影", filtered.categoryNamesByMovieId[1])
        onMain { vm.selectStatus(MovieStatus.WATCHING) }
        assertTrue(awaitSearch(vm) { it.selectedStatus == MovieStatus.WATCHING }.movies.isEmpty())
        onMain {
            vm.updateQuery(""); vm.selectCategory(null); vm.selectTag(null); vm.selectStatus(null)
            vm.selectTimeField(null)
        }
        val cleared = awaitSearch(vm) { it.movies.size == 3 && it.timeField == null }
        assertNull(cleared.fromTime)
        assertNull(cleared.toTime)
    }

    @Test
    fun catalogAndSearchShareSortButClassificationAndTagIdsRemainIndependent() = runBlocking {
        val vm = createViewModel()
        onMain { vm.selectSortOption(MovieSortOption.CREATED_ASC) }
        val catalog = withTimeout(5_000) { vm.catalogUiState.first { it.movies.size == 3 && it.sortOption == MovieSortOption.CREATED_ASC } }
        assertEquals(listOf(1L, 2L, 3L), catalog.movies.map { it.id })
        assertEquals(listOf(1L, 2L), catalog.moviesByCategory[1]?.map { it.id })
        assertEquals(listOf(1L, 3L), catalog.moviesByTag[1]?.map { it.id })
        assertEquals(catalog.movies, awaitSearch(vm) { it.movies.size == 3 }.movies)
        onMain { vm.selectSortOption(MovieSortOption.WATCH_END_DESC) }
        assertEquals(listOf(1L, 3L, 2L), awaitSearch(vm) { it.sortOption == MovieSortOption.WATCH_END_DESC }.movies.map { it.id })
        assertEquals(listOf(1L, 3L, 2L), withTimeout(5_000) { vm.catalogUiState.first { it.sortOption == MovieSortOption.WATCH_END_DESC } }.movies.map { it.id })
    }

    @Test
    fun defaultSortRestoresInNewViewModelAndSessionOverrideDoesNotRewriteIt() = runBlocking {
        settings.setMovieSortOption(MovieSortOption.CREATED_ASC)
        val first = createViewModel()
        assertEquals(listOf(1L, 2L, 3L), awaitSearch(first) { it.movies.size == 3 }.movies.map { it.id })
        onMain { first.selectSortOption(MovieSortOption.CREATED_DESC) }
        assertEquals(listOf(3L, 2L, 1L), awaitSearch(first) { it.sortOption == MovieSortOption.CREATED_DESC }.movies.map { it.id })
        assertEquals(MovieSortOption.CREATED_ASC, settings.movieSortOption.first())
        val reopened = createViewModel()
        assertEquals(MovieSortOption.CREATED_ASC, awaitSearch(reopened) { it.movies.size == 3 }.sortOption)
    }

    @Test
    fun rapidDateChangesRemainConsistentAndActiveFieldExcludesMissingTimes() = runBlocking {
        val vm = createViewModel()
        onMain {
            vm.selectTimeField(WatchTimeField.END_TIME)
            repeat(10) { vm.setFromTime(3_000); vm.setToTime(2_000) }
        }
        val state = awaitSearch(vm) { it.toTime == 2_000L }
        assertNull(state.fromTime)
        assertEquals(listOf(1L), state.movies.map { it.id })
        onMain { vm.setToTime(null) }
        assertEquals(listOf(1L), awaitSearch(vm) { it.toTime == null && it.timeField != null }.movies.map { it.id })
    }

    @Test
    fun readFailureReportsErrorAndDoesNotRetryInALoop() = runBlocking {
        var reads = 0
        val failingDao = object : WatchHistoryDao by database.watchHistoryDao() {
            override fun observeAll(): Flow<List<WatchHistory>> = flow {
                reads++
                throw IOException("Simulated history read failure")
            }
        }
        val vm = createViewModel(failingDao)
        val failed = awaitSearch(vm) { it.errorMessage != null }
        assertEquals("搜索数据加载失败，请重新打开应用重试", failed.errorMessage)
        assertTrue(failed.movies.isEmpty())
        onMain { repeat(10) { vm.selectSortOption(MovieSortOption.NAME_ASC) } }
        awaitSearch(vm) { it.sortOption == MovieSortOption.NAME_ASC }
        assertEquals(1, reads)
    }

    @Test
    fun historyWriteUpdatesSharedSortAndTimeFilteredSearchWithoutReopening() = runBlocking {
        val vm = createViewModel()
        onMain { vm.selectSortOption(MovieSortOption.WATCH_END_DESC) }
        assertEquals(listOf(1L, 3L, 2L), awaitSearch(vm) { it.movies.size == 3 }.movies.map { it.id })
        onMain { vm.selectTimeField(WatchTimeField.END_TIME) }
        awaitSearch(vm) { it.timeField == WatchTimeField.END_TIME && it.movies.size == 1 }
        HistoryRepository(database.watchHistoryDao()).insert(WatchHistory(
            movieId = 2, startTime = 3_000, endTime = 4_000, duration = 1_000, watchNumber = 2,
        ))
        assertEquals(listOf(2L, 1L), awaitSearch(vm) { it.movies.size == 2 }.movies.map { it.id })
        assertEquals(listOf(2L, 1L, 3L), withTimeout(5_000) {
            vm.catalogUiState.first { it.movies.firstOrNull()?.id == 2L }
        }.movies.map { it.id })
        assertEquals(3, MovieRepository(database).observeAll().first().size)
    }

    private fun createViewModel(dao: WatchHistoryDao = database.watchHistoryDao()): SearchViewModel {
        lateinit var vm: SearchViewModel
        onMain {
            vm = SearchViewModel(MovieRepository(database), CategoryRepository(database.categoryDao(), database.tagDao()), HistoryRepository(dao), settings)
            store.put("search-${store.keys().size}", vm)
        }
        return vm
    }

    private suspend fun awaitSearch(vm: SearchViewModel, condition: (SearchUiState) -> Boolean) =
        withTimeout(5_000) { vm.uiState.first(condition) }

    private fun onMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
