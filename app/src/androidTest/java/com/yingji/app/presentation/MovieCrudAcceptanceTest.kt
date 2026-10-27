package com.yingji.app.presentation

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.PosterRepository
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.usecase.AddMovieUseCase
import com.yingji.app.domain.usecase.UpdateStatusUseCase
import com.yingji.app.presentation.movie.MovieDetailScreen
import com.yingji.app.presentation.movie.MovieFormScreen
import com.yingji.app.presentation.movie.MovieViewModel
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Existing screens and real ViewModel/Room, with isolated test routing and storage. */
class MovieCrudAcceptanceTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var database: AppDatabase
    private lateinit var movies: MovieRepository
    private lateinit var categories: CategoryRepository
    private lateinit var viewModel: MovieViewModel
    private lateinit var files: File
    private val store = ViewModelStore()
    private val contentVisible = mutableStateOf(true)
    private var flowShown = false
    private val category = Category(7, "验收分类", false)
    private val tag = Tag(7, "验收标签")

    @Before fun setup() = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        files = File(context.cacheDir, "b4-crud-" + UUID.randomUUID()).apply { check(mkdirs()) }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        database.categoryDao().insert(category)
        database.tagDao().insert(tag)
        movies = MovieRepository(database)
        categories = CategoryRepository(database.categoryDao(), database.tagDao())
        val posters = PosterRepository(object : ContextWrapper(context) { override fun getFilesDir() = files })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = MovieViewModel(movies, AddMovieUseCase(movies), UpdateStatusUseCase(movies), posters)
            store.put("movie", viewModel)
        }
    }

    @After fun teardown() {
        // Stop the UI collectors before closing the Room database they observe.
        if (flowShown) {
            composeRule.runOnIdle { contentVisible.value = false }
            composeRule.waitForIdle()
        }
        val modelJob = viewModel.viewModelScope.coroutineContext[Job]
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        runBlocking { modelJob?.join() }
        database.close()
        files.deleteRecursively() // Only the UUID fixture directory created in setup.
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test fun createEditCancelDeleteAndFailedDeleteRetryKeepTheWholeChainConsistent() = runBlocking {
        showFlow()
        composeRule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("基础验收作品")
        composeRule.onNodeWithText(category.name).performScrollTo().performClick()
        composeRule.onNodeWithText(tag.name).performScrollTo().performClick()
        composeRule.onNodeWithText(MovieStatus.WATCHING.value).performScrollTo().performClick()
        composeRule.onNodeWithTag("movie_rating_input").performScrollTo().performTextReplacement("8.5")
        composeRule.onNodeWithText("保存").performScrollTo().performClick()
        awaitText("作品详情")
        awaitText("基础验收作品")
        val created = database.movieDao().observeAll().first().single()
        assertEquals(8.5, requireNotNull(created.rating), 0.0)
        assertEquals(MovieStatus.WATCHING.value, created.status)
        assertEquals(listOf(7L), database.categoryDao().getCategoryIdsForMovie(created.id))
        assertEquals(listOf(7L), database.tagDao().getTagIdsForMovie(created.id))
        val active = database.watchHistoryDao().getForMovie(created.id).single()
        assertNull(active.endTime)

        composeRule.onNodeWithContentDescription("编辑作品").performClick()
        awaitText("保存")
        composeRule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("基础验收已编辑")
        composeRule.onNodeWithText(MovieStatus.FINISHED.value).performScrollTo().performClick()
        composeRule.onNodeWithTag("movie_rating_input").performScrollTo().performTextReplacement("9")
        composeRule.onNodeWithText("保存").performScrollTo().performClick()
        awaitText("作品详情")
        awaitText("基础验收已编辑")
        val edited = requireNotNull(database.movieDao().getById(created.id))
        assertEquals(created.id, edited.id)
        assertEquals(created.createTime, edited.createTime)
        assertEquals("基础验收已编辑", edited.name)
        assertEquals(9.0, requireNotNull(edited.rating), 0.0)
        assertEquals(MovieStatus.FINISHED.value, edited.status)
        val ended = database.watchHistoryDao().getForMovie(created.id).single()
        assertEquals(active.id, ended.id)
        assertNotNull(ended.endTime)
        assertTrue(requireNotNull(ended.duration) >= 0)

        composeRule.onNodeWithText("删除作品").performScrollTo().performClick()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(edited, database.movieDao().getById(created.id))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER b4_reject_delete BEFORE DELETE ON movies BEGIN SELECT RAISE(ABORT, 'isolated failure'); END")
        composeRule.onNodeWithText("删除作品").performScrollTo().performClick()
        composeRule.onNodeWithText("确认删除").performClick()
        awaitText("删除失败，请稍后重试")
        assertEquals(edited, database.movieDao().getById(created.id))
        assertEquals(listOf(ended), database.watchHistoryDao().getForMovie(created.id))
        assertEquals(listOf(7L), database.categoryDao().getCategoryIdsForMovie(created.id))
        assertEquals(listOf(7L), database.tagDao().getTagIdsForMovie(created.id))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER b4_reject_delete")
        composeRule.onNodeWithText("确认删除").performClick()
        awaitText("验收删除完成")
        assertNull(database.movieDao().getById(created.id))
        assertTrue(database.watchHistoryDao().getForMovie(created.id).isEmpty())
        assertTrue(database.categoryDao().getCategoryIdsForMovie(created.id).isEmpty())
        assertTrue(database.tagDao().getTagIdsForMovie(created.id).isEmpty())
        assertEquals(0, database.movieDao().observeAll().first().size)
        assertEquals(listOf(category), categories.observeCategories().first())
        assertEquals(listOf(tag), categories.observeTags().first())
    }

    @Test fun intentionallyRepeatedTitlesKeepSeparateIdsAndDeletingOnePreservesTheOther() = runBlocking {
        val value = Movie(name = "同名作品", type = category.name, status = MovieStatus.WANT_TO_WATCH.value, createTime = 1)
        val first = movies.insertWithRelations(value.copy(rating = 0.0), category.id, setOf(tag.id))
        val second = movies.insertWithRelations(value.copy(rating = 10.0), category.id, emptySet())
        assertNotEquals(first, second)
        assertEquals(2, database.movieDao().observeAll().first().size)
        movies.delete(requireNotNull(database.movieDao().getById(first)))
        val remaining = database.movieDao().observeAll().first().single()
        assertEquals(second, remaining.id)
        assertEquals(10.0, requireNotNull(remaining.rating), 0.0)
        assertEquals(listOf(category.id), database.categoryDao().getCategoryIdsForMovie(second))
        assertTrue(database.tagDao().getTagIdsForMovie(second).isEmpty())
        assertEquals(listOf(tag), categories.observeTags().first())
    }


    @Test fun localPickerCancellationAndActualFileSelectionCropAndSaveUseTheIsolatedImage() = runBlocking {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val filename = "yingji-b4-" + UUID.randomUUID() + ".png"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        var pickerStep = "seed"
        fun step(value: String) { pickerStep = value; android.util.Log.i("YingJiB4Picker", value) }
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(128, 192, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(85, 85, 85))
            requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            showFlow()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            step("gallery")
            composeRule.onNodeWithText("相册选择").performScrollTo().performClick()
            composeRule.waitUntil(10_000) {
                automation.rootInActiveWindow?.packageName?.toString()?.let { it != ApplicationProvider.getApplicationContext<Context>().packageName } == true
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            composeRule.waitUntil(10_000) {
                runCatching { composeRule.onAllNodesWithText("文件选择").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
            composeRule.onAllNodesWithText("移除海报").assertCountEquals(0)
            assertTrue(database.movieDao().observeAll().first().isEmpty())

            step("file picker")
            composeRule.onNodeWithText("文件选择").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            composeRule.waitUntil(5_000) { automation.rootInActiveWindow?.let { root -> descendants(root).any { it.contentDescription?.toString() == "显示根目录" } } == true }
            step("roots button")
            val roots = descendants(requireNotNull(automation.rootInActiveWindow)).single { it.contentDescription?.toString() == "显示根目录" }
            tapSystemNode(roots)
            fun downloadsRoot() = automation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("com.android.documentsui:id/roots_list")?.singleOrNull()
                ?.findAccessibilityNodeInfosByViewId("android:id/title")
                ?.singleOrNull { it.text?.toString() in setOf("下载", "Download", "Downloads") }
            composeRule.waitUntil(5_000) { downloadsRoot() != null }
            step("Downloads root")
            tapSystemNode(requireNotNull(downloadsRoot()))
            step("open Download folder")
            fun fixtureImage() = automation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("com.android.documentsui:id/dir_list")?.singleOrNull()
                ?.let(::descendants)
                ?.filter { it.isVisibleToUser }
                ?.let { nodes -> nodes.singleOrNull { it.contentDescription?.toString()?.startsWith(filename + ",") == true }
                    ?: nodes.singleOrNull { it.text?.toString() == filename } }
            composeRule.waitUntil(5_000) { fixtureImage() != null }
            step("fixture image")
            tapSystemNode(requireNotNull(fixtureImage()))
            step("crop")
            fun cropButton() = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(ApplicationProvider.getApplicationContext<Context>().packageName + ":id/crop_image_menu_crop")?.singleOrNull()
            composeRule.waitUntil(10_000) { cropButton()?.isEnabled == true }
            tapSystemNode(requireNotNull(cropButton()))
            step("return from crop")
            awaitText("移除海报")
            composeRule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("本地海报验收")
            composeRule.onNodeWithText(category.name).performScrollTo().performClick()
            composeRule.onNodeWithText("保存").performScrollTo().performClick()
            awaitText("作品详情")
            awaitText("本地海报验收")
            val movie = database.movieDao().observeAll().first().single()
            val poster = File(requireNotNull(movie.posterPath))
            assertTrue(poster.canonicalPath.startsWith(File(files, PosterRepository.POSTER_DIRECTORY).canonicalPath + File.separator))
            assertTrue(poster.isFile)
            val saved = requireNotNull(android.graphics.BitmapFactory.decodeFile(poster.path))
            assertEquals(2.0 / 3.0, saved.width.toDouble() / saved.height, 0.02)
            val pixel = saved.getPixel(saved.width / 2, saved.height / 2)
            assertTrue(kotlin.math.abs(android.graphics.Color.red(pixel) - 85) <= 3)
            assertTrue(kotlin.math.abs(android.graphics.Color.green(pixel) - 85) <= 3)
            assertTrue(kotlin.math.abs(android.graphics.Color.blue(pixel) - 85) <= 3)
            saved.recycle()
            assertEquals(listOf(category.id), database.categoryDao().getCategoryIdsForMovie(movie.id))
        } catch (error: Throwable) {
            val window = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val nodes = window?.let(::descendants).orEmpty().take(100).map { "${it.viewIdResourceName}:${it.text}:${it.contentDescription}" }
            throw AssertionError("Picker step=$pickerStep; window=${window?.packageName}; nodes=$nodes", error)
        } finally {
            assertEquals(1, resolver.delete(uri, null, null)) // Only the image inserted by this test.
        }
    }

    private fun descendants(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }

    private fun tapSystemNode(node: android.view.accessibility.AccessibilityNodeInfo) {
        assertTrue(node.isVisibleToUser)
        var target: android.view.accessibility.AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val metrics = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics
        assertTrue("System target is outside the visible screen: $bounds",
            bounds.intersect(0, 0, metrics.widthPixels, metrics.heightPixels))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = android.os.SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
    }

    private fun awaitText(text: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun showFlow() {
        flowShown = true
        composeRule.setContent {
            if (!contentVisible.value) return@setContent
            var page by remember { mutableStateOf("add") }
            val ui by viewModel.uiState.collectAsState()
            val form by viewModel.formActionState.collectAsState()
            val action by viewModel.actionState.collectAsState()
            val movie = ui.movies.singleOrNull()
            val categoryFlow = remember(movie?.id) { categories.observeCategoriesForMovie(movie?.id ?: -1) }
            val tagFlow = remember(movie?.id) { categories.observeTagsForMovie(movie?.id ?: -1) }
            val selectedCategories by categoryFlow.collectAsState(initial = emptyList())
            val selectedTags by tagFlow.collectAsState(initial = emptyList())
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                when {
                    page == "deleted" -> Text("验收删除完成")
                    page == "add" -> MovieFormScreen("新增作品", categories = listOf(category), tags = listOf(tag),
                        isSaving = form.isSaving, saveMessage = form.message, onSaveMessageConsumed = viewModel::consumeFormMessage,
                        onBack = {}, onSave = { data -> viewModel.addMovie(data) { page = "detail" } })
                    movie == null -> Text("验收加载中")
                    page == "edit" -> MovieFormScreen("编辑作品", movie = movie, categories = listOf(category), tags = listOf(tag),
                        initialCategoryId = selectedCategories.singleOrNull()?.id, initialTagIds = selectedTags.map { it.id }.toSet(),
                        isSaving = form.isSaving, saveMessage = form.message, onSaveMessageConsumed = viewModel::consumeFormMessage,
                        onBack = { page = "detail" }, onSave = { data -> viewModel.updateMovie(movie, data) { page = "detail" } })
                    else -> MovieDetailScreen(movie, listOf(category), selectedCategories, listOf(tag), selectedTags,
                        onBack = {}, onHistory = {}, onEdit = { page = "edit" },
                        onDelete = { viewModel.deleteMovie(movie) { page = "deleted" } },
                        onStatusChange = { viewModel.updateStatus(movie, it) }, onCategoryChange = { _, _ -> }, onTagChange = { _, _ -> },
                        actionState = action, onActionMessageConsumed = viewModel::consumeActionMessage)
                }
            }
        }
    }
}
