package com.yingji.app.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.YingJiApplication
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.navigation.YingJiNavGraph
import com.yingji.app.presentation.ai.AiHomeViewModel
import com.yingji.app.presentation.ai.AiResultState
import com.yingji.app.presentation.ai.AiTestRepository
import com.yingji.app.ui.theme.YingJiTheme
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Only the opt-in B4 source set: actual navigation and local Room with an unavailable AI boundary. */
class B4AiIsolationAcceptanceTest {
    @get:Rule val ui = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
    private val db get() = DatabaseModule.provideDatabase(app)
    private val models = ViewModelStore()
    private val shown = mutableStateOf(true)
    private val token = UUID.randomUUID().toString()
    private var categoryId = 0L
    private var tagId = 0L
    private val categoryName = "B4隔离分类-" + token
    private val tagName = "B4隔离标签-" + token
    @After fun cleanup() = runBlocking<Unit> {
        ui.runOnIdle { shown.value = false; models.clear() }; ui.waitForIdle()
        if (categoryId > 0) {
            app.movieRepository.observeAll().first().filter { it.name.startsWith("B4隔离作品-" + token) }.forEach { app.movieRepository.delete(it) }
            db.categoryDao().delete(Category(categoryId, categoryName, false))
            db.tagDao().delete(Tag(tagId, tagName))
        }
    }
    private fun waitTag(tag: String) { ui.waitUntil(10000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun openWork(title: String) {
        waitTag("movie_collection"); ui.onNodeWithTag("movie_collection").performScrollToNode(hasText(title))
        ui.onNodeWithText(title).performClick(); waitTag("movie_detail_content")
    }
    private suspend fun exercise(failure: String) {
        check(app.packageName == "com.yingji.app.b4regression.acceptance") { "ISOLATED_B4_PACKAGE_REQUIRED" }
        categoryId = db.categoryDao().insert(Category(name = categoryName, isSystem = false))
        tagId = db.tagDao().insert(Tag(name = tagName))
        val demo = AiTestRepository()
        demo.saveSetup(AiDemoSetup(completed = true))
        val attempts = AtomicInteger()
        val source: AiHomeRepository = if (failure == "timeout") object : AiHomeRepository by demo {
            override suspend fun recommendWithMetadata(mode: AiMode, input: String, scenario: MockScenario): AiHomeRecommendationBatch {
                attempts.incrementAndGet(); throw SocketTimeoutException("CONTROLLED_AI_TIMEOUT")
            }
        } else demo.apply { failReads = failure == "configuration" }
        lateinit var ai: AiHomeViewModel
        ui.runOnUiThread { ai = AiHomeViewModel(source); models.put("ai", ai) }
        ui.setContent { if (shown.value) YingJiTheme { YingJiNavGraph(aiHomeViewModelOverride = ai) } }
        ui.waitUntil(10000) { !ai.state.value.initializing }
        if (failure == "configuration") assertTrue(ai.state.value.readFailed)
        else {
            assertTrue(ai.state.value.quota.canRequestNewTask)
            ui.runOnIdle { if (failure == "offline") ai.setScenario(MockScenario.OFFLINE); ai.submit(AiMode.FOR_YOU) }
            ui.waitUntil(10000) { ai.state.value.result !in setOf(AiResultState.IDLE, AiResultState.LOADING) }
            assertEquals(if (failure == "offline") AiResultState.OFFLINE else AiResultState.ERROR, ai.state.value.result)
            assertEquals(1, if (failure == "offline") demo.requests else attempts.get())
        }
        val title = "B4隔离作品-" + token
        ui.onNodeWithContentDescription("新增作品").performClick()
        ui.onNode(hasSetTextAction() and hasText("请输入作品名称")).performScrollTo().performTextReplacement(title)
        ui.onNodeWithText(categoryName).performScrollTo().performClick()
        ui.onNodeWithText(tagName).performScrollTo().performClick()
        ui.onNodeWithText(MovieStatus.WATCHING.value).performScrollTo().performClick()
        ui.onNodeWithTag("movie_rating_input").performScrollTo().performTextReplacement("8.5")
        ui.onNodeWithText("保存").performScrollTo().performClick()
        ui.waitUntil(10000) { runBlocking { app.movieRepository.observeAll().first().any { it.name == title } } }
        val created = app.movieRepository.observeAll().first().single { it.name == title }
        assertEquals(8.5, created.rating)
        assertEquals(listOf(categoryId), db.categoryDao().getCategoryIdsForMovie(created.id))
        assertEquals(listOf(tagId), db.tagDao().getTagIdsForMovie(created.id))
        assertEquals(1, db.watchHistoryDao().getForMovie(created.id).count { it.endTime == null })
        openWork(title); ui.onNodeWithContentDescription("编辑作品").performClick()
        ui.onNode(hasSetTextAction() and hasText(title)).performScrollTo().performTextReplacement(title + "-编辑")
        ui.onNodeWithText(MovieStatus.FINISHED.value).performScrollTo().performClick()
        ui.onNodeWithTag("movie_rating_input").performScrollTo().performTextReplacement("9")
        ui.onNodeWithText("保存").performScrollTo().performClick()
        waitTag("movie_detail_content")
        ui.waitUntil(10000) { runBlocking { db.movieDao().getById(created.id)?.name == title + "-编辑" } }
        val edited = requireNotNull(db.movieDao().getById(created.id))
        assertEquals(created.id, edited.id); assertEquals(created.createTime, edited.createTime)
        assertEquals(9.0, edited.rating); assertEquals(MovieStatus.FINISHED.value, edited.status)
        assertEquals(listOf(categoryId), db.categoryDao().getCategoryIdsForMovie(created.id))
        assertEquals(listOf(tagId), db.tagDao().getTagIdsForMovie(created.id))
        assertEquals(0, db.watchHistoryDao().getForMovie(created.id).count { it.endTime == null })
        ui.onNodeWithText("删除作品").performScrollTo().performClick(); ui.onNodeWithText("确认删除").performClick()
        ui.waitUntil(10000) { runBlocking { db.movieDao().getById(created.id) == null } }
        waitTag("movie_collection")
        assertTrue(db.watchHistoryDao().getForMovie(created.id).isEmpty())
        assertTrue(db.categoryDao().getCategoryIdsForMovie(created.id).isEmpty())
        assertTrue(db.tagDao().getTagIdsForMovie(created.id).isEmpty())
        assertEquals(if (failure == "configuration") 0 else 1, if (failure == "timeout") attempts.get() else demo.requests)
        assertEquals(if (failure == "offline") AiResultState.OFFLINE else if (failure == "timeout") AiResultState.ERROR else AiResultState.IDLE,
            ai.state.value.result)
    }
    @Test fun configurationFailureDoesNotBlockActualBaseCrud() = runBlocking<Unit> { exercise("configuration") }
    @Test fun recommendationTimeoutDoesNotBlockActualBaseCrud() = runBlocking<Unit> { exercise("timeout") }
    @Test fun offlineRecommendationDoesNotBlockActualBaseCrud() = runBlocking<Unit> { exercise("offline") }
}
