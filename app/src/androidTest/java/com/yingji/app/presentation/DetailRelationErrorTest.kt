package com.yingji.app.presentation

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.presentation.category.CategoryTagViewModel
import com.yingji.app.presentation.movie.MovieDetailScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DetailRelationErrorTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var database: AppDatabase
    private lateinit var repository: CategoryRepository
    private lateinit var viewModel: CategoryTagViewModel
    private lateinit var movie: Movie
    private lateinit var original: Category
    private lateinit var next: Category
    private lateinit var tag: Tag
    private val store = ViewModelStore()

    @Before fun setup() = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        original = Category(database.categoryDao().insert(Category(name = "原分类", isSystem = false)), "原分类", false)
        next = Category(database.categoryDao().insert(Category(name = "目标分类", isSystem = false)), "目标分类", false)
        tag = Tag(database.tagDao().insert(Tag(name = "目标标签")), "目标标签")
        val value = Movie(name = "关联作品", type = "原分类", status = MovieStatus.WANT_TO_WATCH.value, createTime = 1)
        movie = value.copy(id = database.movieDao().insert(value))
        database.categoryDao().addMovieCategory(MovieCategory(movie.id, original.id))
        repository = CategoryRepository(database.categoryDao(), database.tagDao())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = CategoryTagViewModel(repository)
            store.put("relations", viewModel)
        }
    }

    @After fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        database.close()
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test fun failedCategoryChangeIsVisibleKeepsAssociationAndRetrySucceeds() = runBlocking {
        rejectInsert("movie_categories")
        showDetail()
        composeRule.onNodeWithText(next.name).performScrollTo().performClick()
        composeRule.onNodeWithText("更新分类失败").assertExists()
        assertEquals(listOf(original.id), database.categoryDao().getCategoryIdsForMovie(movie.id))
        composeRule.onNodeWithText(original.name).performScrollTo().assertIsSelected()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_relation")
        // Wait for the visible failure feedback to finish before clicking a chip underneath it.
        composeRule.waitUntil(10_000) { viewModel.actionState.value.message == null }
        composeRule.onNodeWithText(next.name).performScrollTo().performClick()
        withTimeout(5_000) { repository.observeCategoriesForMovie(movie.id).first { it.singleOrNull()?.id == next.id } }
        assertEquals(movie, database.movieDao().getById(movie.id))
    }

    @Test fun failedTagChangeIsVisibleKeepsAssociationAndRetrySucceeds() = runBlocking {
        rejectInsert("movie_tags")
        showDetail()
        composeRule.onNodeWithText(tag.name).performScrollTo().performClick()
        composeRule.onNodeWithText("更新标签失败").assertExists()
        assertTrue(database.tagDao().getTagIdsForMovie(movie.id).isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_relation")
        // Wait for the visible failure feedback to finish before clicking a chip underneath it.
        composeRule.waitUntil(10_000) { viewModel.actionState.value.message == null }
        composeRule.onNodeWithText(tag.name).performScrollTo().performClick()
        withTimeout(5_000) { repository.observeTagsForMovie(movie.id).first { it.singleOrNull()?.id == tag.id } }
        assertEquals(listOf(original.id), database.categoryDao().getCategoryIdsForMovie(movie.id))
        assertEquals(movie, database.movieDao().getById(movie.id))
    }

    private fun rejectInsert(table: String) {
        require(table in setOf("movie_categories", "movie_tags"))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_relation BEFORE INSERT ON " + table + " BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
    }

    private fun showDetail() {
        val categoryFlow = repository.observeCategoriesForMovie(movie.id)
        val tagFlow = repository.observeTagsForMovie(movie.id)
        composeRule.setContent {
            val state by viewModel.actionState.collectAsState()
            val selectedCategories by categoryFlow.collectAsState(initial = listOf(original))
            val selectedTags by tagFlow.collectAsState(initial = emptyList())
            YingJiTheme {
                MovieDetailScreen(movie, listOf(original, next), selectedCategories, listOf(tag), selectedTags,
                    onBack = {}, onHistory = {}, onEdit = {}, onDelete = {}, onStatusChange = {},
                    onCategoryChange = { category, selected -> viewModel.setMovieCategory(movie.id, category, selected) },
                    onTagChange = { value, selected -> viewModel.setMovieTag(movie.id, value, selected) },
                    relationMessage = state.message, onRelationMessageConsumed = viewModel::consumeActionResult)
            }
        }
    }
}
