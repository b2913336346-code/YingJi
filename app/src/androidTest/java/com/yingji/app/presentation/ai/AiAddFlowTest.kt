package com.yingji.app.presentation.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.*
import com.yingji.app.data.ai.*
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.usecase.*
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.presentation.movie.*
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiAddFlowTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private val contentVisible = mutableStateOf(true)
    @Test fun confirmedMockUsesRealSharedFormAndFailedWriteRetriesWithoutDuplicate() = runBlocking<Unit> { verifyAddFlow(false) }
    @Test fun offlineWorkerStillAllowsSharedFormWriteRetryAndDuplicateProtection() = runBlocking<Unit> { verifyAddFlow(true) }
    private suspend fun verifyAddFlow(worker: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseHealthMonitor.resetForTesting()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val models = ViewModelStore()
        val modelJobs = mutableListOf<Job>()
        try {
            val category = Category(7, "验收分类", false); val tag = Tag(7, "验收标签")
            db.categoryDao().insert(category); db.tagDao().insert(tag)
            val movies = MovieRepository(db)
            val demo: AiHomeRepository = if (worker) WorkerDemoHomeRepository(MockAiHomeRepository(context, AiTestStore()),
                workerTestClient(baseUrl = "http://127.0.0.1:1"), MockCandidateCatalogRepository())
                else AiTestRepository()
            demo.saveSetup(AiDemoSetup())
            lateinit var ai: AiHomeViewModel; lateinit var vm: MovieViewModel
            val posters = PosterRepository(object : ContextWrapper(context) { override fun getFilesDir() = files.root })
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ai = AiHomeViewModel(demo); models.put("ai", ai)
                vm = MovieViewModel(movies, AddMovieUseCase(movies), UpdateStatusUseCase(movies), posters, AddRecommendedWorkUseCase(MockCandidateCatalogRepository(), CandidateImportRepository(db,movies), AddMovieUseCase(movies))); models.put("movies", vm)
                listOf(ai, vm).forEach { model -> model.viewModelScope.coroutineContext[Job]?.let { modelJobs.add(it) } }
            }
            ui.setContent {
                if (!contentVisible.value) return@setContent
                var draft by remember { mutableStateOf<MockRecommendation?>(null) }
                val catalogue by vm.uiState.collectAsState()
                val action by vm.formActionState.collectAsState()
                YingJiTheme {
                    if (draft == null) HomeScreen(catalogue.movies, emptyMap(), {}, {}, {}, aiViewModel = ai, onAddAiMovie = { draft = it })
                    else MovieFormScreen("新增作品", categories = listOf(category), tags = listOf(tag), initialName = draft!!.title,
                        isSaving = action.isSaving, saveMessage = action.message, onSaveMessageConsumed = vm::consumeFormMessage,
                        onBack = { draft = null }, onSave = { data -> vm.addMovie(data, recommendedIdentity = draft!!.candidateIdentity) { draft = null } })
                }
            }
            ui.waitUntil(5000) { !ai.state.value.initializing }
            ui.onNodeWithTag("movie_collection").performScrollToIndex(1)
            ui.onNodeWithTag("ai_enter").performClick()
            if (worker) {
                ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_scenario"))
                ui.onNodeWithTag("ai_scenario").performClick(); ui.onNodeWithTag("ai_scenario_WORKER").performScrollTo().performClick()
                ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_mode_FOR_YOU"))
            }
            ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
            ui.waitUntil(5000) { ai.state.value.result == AiResultState.SUCCESS }
            if (worker) { assertTrue(ai.state.value.message!!.contains("本地可信")); assertEquals(AiQuotaStatus.UNAVAILABLE, ai.state.value.quota.status) }
            ui.onNodeWithTag("movie_collection").performScrollToIndex(2)
            ui.onNodeWithTag("ai_add_mock-0").performClick()
            assertTrue(movies.observeAll().first().isEmpty())
            ui.onNodeWithTag("ai_add_confirm").performClick()
            ui.onNodeWithText("Mock 演示作品 1").assertExists()
            ui.onNodeWithText(category.name).performScrollTo().performClick()
            ui.onNodeWithText(tag.name).performScrollTo().performClick()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER a3_reject_insert BEFORE INSERT ON movies BEGIN SELECT RAISE(ABORT, 'A3 failure'); END")
            ui.onNodeWithText("保存").performScrollTo().performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText("保存失败，请重试").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("保存失败，请重试").assertIsDisplayed()
            assertTrue(movies.observeAll().first().isEmpty())
            ui.waitUntil(10000) { ui.onAllNodesWithText("保存失败，请重试").fetchSemanticsNodes().isEmpty() }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER a3_reject_insert")
            ui.onNodeWithText("保存").performScrollTo().performClick()
            ui.waitUntil(5000) { vm.uiState.value.movies.size == 1 }
            val saved = movies.observeAll().first().single()
            assertEquals("Mock 演示作品 1", saved.name)
            assertEquals("想看", saved.status); assertNull(saved.posterPath)
            assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(saved.id))
            assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(saved.id))
            assertEquals(saved, movies.observeById(saved.id).first())
            assertTrue(db.watchHistoryDao().getForMovie(saved.id).isEmpty())
            assertEquals(saved.id, db.candidateImportDao().movieId(MockCandidateCatalogRepository.SOURCE_ID, "mock-0"))
            // Re-enter through the same visible recommendation and confirm again; no overwrite or new row.
            ui.onNodeWithTag("movie_collection").performScrollToIndex(2)
            ui.onNodeWithTag("ai_add_mock-0").performClick()
            ui.onNodeWithTag("ai_add_confirm").performClick()
            ui.onNodeWithText(category.name).performScrollTo().performClick()
            ui.onNodeWithText("保存").performScrollTo().performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText("此候选已加入影迹，请查看已有作品").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("此候选已加入影迹，请查看已有作品").assertIsDisplayed()
            assertEquals(listOf(saved), movies.observeAll().first())
        } finally {
            ui.runOnIdle { contentVisible.value = false }
            ui.waitForIdle()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            modelJobs.forEach { it.join() }
            db.close(); DatabaseHealthMonitor.resetForTesting()
        }
    }
}
