package com.yingji.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.dao.CategoryDao
import com.yingji.app.data.database.dao.TagDao
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.MovieStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CategoryRepositoryRenameTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CategoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = CategoryRepository(database.categoryDao(), database.tagDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun renameTrimsNameAndKeepsIdsAndMovieRelations() = runBlocking {
        val categoryId = database.categoryDao().insert(Category(name = "旧分类", isSystem = false))
        val tagId = database.tagDao().insert(Tag(name = "旧标签"))
        val movieId = database.movieDao().insert(
            Movie(
                name = "关联测试",
                type = "电影",
                status = MovieStatus.WANT_TO_WATCH.value,
                createTime = 1L,
            ),
        )
        database.categoryDao().addMovieCategory(MovieCategory(movieId, categoryId))
        database.tagDao().addMovieTag(MovieTag(movieId, tagId))

        assertEquals(
            SaveNameResult.Success,
            repository.renameCategory(Category(categoryId, "旧分类", false), "  新分类  "),
        )
        assertEquals(
            SaveNameResult.Success,
            repository.renameTag(Tag(tagId, "旧标签"), "  新标签  "),
        )

        val category = database.categoryDao().observeById(categoryId).first()
        val tag = database.tagDao().observeById(tagId).first()
        assertEquals(categoryId, category?.id)
        assertEquals("新分类", category?.name)
        assertEquals(tagId, tag?.id)
        assertEquals("新标签", tag?.name)
        assertEquals(listOf(categoryId), database.categoryDao().getCategoryIdsForMovie(movieId))
        assertEquals(listOf(tagId), database.tagDao().getTagIdsForMovie(movieId))
    }

    @Test
    fun renameRejectsBlankDuplicateAndSystemCategory() = runBlocking {
        val categoryId = database.categoryDao().insert(Category(name = "分类一", isSystem = false))
        database.categoryDao().insert(Category(name = "分类二", isSystem = false))
        val tagId = database.tagDao().insert(Tag(name = "标签一"))
        database.tagDao().insert(Tag(name = "标签二"))

        assertEquals(
            SaveNameResult.InvalidName,
            repository.renameCategory(Category(categoryId, "分类一", false), "   "),
        )
        assertEquals(
            SaveNameResult.AlreadyExists,
            repository.renameCategory(Category(categoryId, "分类一", false), " 分类二 "),
        )
        assertEquals(
            SaveNameResult.InvalidName,
            repository.renameTag(Tag(tagId, "标签一"), "\t"),
        )
        assertEquals(
            SaveNameResult.AlreadyExists,
            repository.renameTag(Tag(tagId, "标签一"), " 标签二 "),
        )
        assertEquals(
            SaveNameResult.NotEditable,
            repository.renameCategory(Category(99L, "电影", true), "影片"),
        )

        assertTrue(database.categoryDao().observeById(categoryId).first()?.name == "分类一")
        assertTrue(database.tagDao().observeById(tagId).first()?.name == "标签一")
    }

    @Test
    fun uniqueIndexConflictsAreReportedEvenIfPrecheckMissesThem() = runBlocking {
        val categoryDao = database.categoryDao()
        val tagDao = database.tagDao()
        val categoryId = categoryDao.insert(Category(name = "分类一", isSystem = false))
        categoryDao.insert(Category(name = "分类二", isSystem = false))
        val tagId = tagDao.insert(Tag(name = "标签一"))
        tagDao.insert(Tag(name = "标签二"))
        val uncheckedRepository = CategoryRepository(
            object : CategoryDao by categoryDao {
                override suspend fun nameExistsExcludingId(name: String, excludedId: Long) = false
            },
            object : TagDao by tagDao {
                override suspend fun nameExistsExcludingId(name: String, excludedId: Long) = false
            },
        )

        assertEquals(
            SaveNameResult.AlreadyExists,
            uncheckedRepository.renameCategory(Category(categoryId, "分类一", false), "分类二"),
        )
        assertEquals(
            SaveNameResult.AlreadyExists,
            uncheckedRepository.renameTag(Tag(tagId, "标签一"), "标签二"),
        )
        assertEquals("分类一", categoryDao.observeById(categoryId).first()?.name)
        assertEquals("标签一", tagDao.observeById(tagId).first()?.name)
    }

    @Test
    fun unchangedNamesSucceedAndMissingRowsDoNotReportSuccess() = runBlocking {
        val categoryId = database.categoryDao().insert(Category(name = "分类", isSystem = false))
        val tagId = database.tagDao().insert(Tag(name = "标签"))
        assertEquals(
            SaveNameResult.Success,
            repository.renameCategory(Category(categoryId, "分类", false), " 分类 "),
        )
        assertEquals(SaveNameResult.Success, repository.renameTag(Tag(tagId, "标签"), " 标签 "))
        assertTrue(
            repository.renameCategory(Category(Long.MAX_VALUE, "不存在", false), "新分类")
                is SaveNameResult.Failure,
        )
        assertTrue(
            repository.renameTag(Tag(Long.MAX_VALUE, "不存在"), "新标签") is SaveNameResult.Failure,
        )
    }

    @Test
    fun renamedNamesAndRelationsRemainAfterDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Only this test-created database is removed; the application's database is never opened.
        val testDatabaseName = "rename-persistence-${UUID.randomUUID()}.db"
        var persistent = Room.databaseBuilder(context, AppDatabase::class.java, testDatabaseName)
            .build()
        try {
            val categoryId = persistent.categoryDao().insert(Category(name = "旧分类", isSystem = false))
            val tagId = persistent.tagDao().insert(Tag(name = "旧标签"))
            val movieId = persistent.movieDao().insert(
                Movie(
                    name = "持久化测试", type = "电影",
                    status = MovieStatus.WANT_TO_WATCH.value, createTime = 1L,
                ),
            )
            persistent.categoryDao().addMovieCategory(MovieCategory(movieId, categoryId))
            persistent.tagDao().addMovieTag(MovieTag(movieId, tagId))
            val persistentRepository = CategoryRepository(persistent.categoryDao(), persistent.tagDao())
            assertEquals(
                SaveNameResult.Success,
                persistentRepository.renameCategory(Category(categoryId, "旧分类", false), "新分类"),
            )
            assertEquals(
                SaveNameResult.Success,
                persistentRepository.renameTag(Tag(tagId, "旧标签"), "新标签"),
            )
            persistent.close()
            persistent = Room.databaseBuilder(context, AppDatabase::class.java, testDatabaseName).build()

            assertEquals("新分类", persistent.categoryDao().observeById(categoryId).first()?.name)
            assertEquals("新标签", persistent.tagDao().observeById(tagId).first()?.name)
            assertEquals(listOf(categoryId), persistent.categoryDao().getCategoryIdsForMovie(movieId))
            assertEquals(listOf(tagId), persistent.tagDao().getTagIdsForMovie(movieId))
            assertEquals(1L, persistent.categoryDao().observeMovieCounts().first().single().movieCount)
            assertEquals(1L, persistent.tagDao().observeMovieCounts().first().single().movieCount)
        } finally {
            persistent.close()
            context.deleteDatabase(testDatabaseName)
        }
    }
}
