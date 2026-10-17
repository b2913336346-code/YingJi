package com.yingji.app.presentation.category

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.dao.TagDao
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.repository.CategoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryTagViewModelTest {
    private lateinit var database: AppDatabase
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        onMain { store.clear() }
        database.close()
    }

    @Test
    fun consumingResultWhileSavingDoesNotAllowDuplicateWrites() = runBlocking {
        val tag = Tag(database.tagDao().insert(Tag(name = "原标签")), "原标签")
        var writes = 0
        val dao = database.tagDao()
        val countingDao = object : TagDao by dao {
            override suspend fun update(tag: Tag): Int {
                writes += 1
                return dao.update(tag)
            }
        }
        val viewModel = createViewModel(countingDao)

        onMain {
            viewModel.renameTag(tag, "新标签")
            assertTrue(viewModel.actionState.value.isSaving)
            viewModel.consumeActionResult()
            assertTrue(viewModel.actionState.value.isSaving)
            repeat(10) { viewModel.renameTag(tag, "重复提交") }
        }

        val result = awaitSave(viewModel)
        assertTrue(result.saveSucceeded)
        assertEquals(1, writes)
        assertEquals("新标签", database.tagDao().observeById(tag.id).first()?.name)
    }

    @Test
    fun failedWriteReleasesSavingStateAndAllowsRetry() = runBlocking {
        val tag = Tag(database.tagDao().insert(Tag(name = "原标签")), "原标签")
        var failWrite = true
        val dao = database.tagDao()
        val failingDao = object : TagDao by dao {
            override suspend fun update(tag: Tag): Int {
                check(!failWrite) { "Simulated write failure" }
                return dao.update(tag)
            }
        }
        val viewModel = createViewModel(failingDao)

        onMain { viewModel.renameTag(tag, "新标签") }
        val failure = awaitSave(viewModel)
        assertFalse(failure.saveSucceeded)
        assertEquals("保存失败，请稍后重试", failure.message)
        assertEquals("原标签", dao.observeById(tag.id).first()?.name)

        failWrite = false
        onMain { viewModel.renameTag(tag, "新标签") }
        assertTrue(awaitSave(viewModel).saveSucceeded)
        assertEquals("新标签", dao.observeById(tag.id).first()?.name)
    }

    @Test
    fun cancelledWriteReleasesSavingStateWithoutReportingFailure() = runBlocking {
        val tag = Tag(database.tagDao().insert(Tag(name = "原标签")), "原标签")
        val dao = database.tagDao()
        val cancellingDao = object : TagDao by dao {
            override suspend fun update(tag: Tag): Int {
                throw CancellationException("Simulated cancellation")
            }
        }
        val viewModel = createViewModel(cancellingDao)

        onMain { viewModel.renameTag(tag, "新标签") }
        val result = awaitSave(viewModel)
        assertFalse(result.saveSucceeded)
        assertNull(result.message)
        assertEquals("原标签", dao.observeById(tag.id).first()?.name)
    }

    private suspend fun createViewModel(tagDao: TagDao): CategoryTagViewModel {
        val repository = CategoryRepository(database.categoryDao(), tagDao)
        lateinit var viewModel: CategoryTagViewModel
        onMain {
            viewModel = CategoryTagViewModel(repository)
            store.put("category-tags", viewModel)
        }
        withTimeout(5_000L) {
            repository.observeCategories().first { categories ->
                categories.count { it.isSystem } == CategoryRepository.SYSTEM_CATEGORY_NAMES.size
            }
        }
        return viewModel
    }

    private suspend fun awaitSave(viewModel: CategoryTagViewModel): CategoryTagActionState =
        withTimeout(5_000L) { viewModel.actionState.first { !it.isSaving } }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }
}
