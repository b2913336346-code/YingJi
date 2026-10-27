package com.yingji.app.data.database

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.data.database.dao.CategoryDao
import com.yingji.app.data.database.dao.TagDao
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.PosterRepository
import com.yingji.app.data.repository.MovieTypeRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.SaveNameResult
import com.yingji.app.domain.usecase.RecordWatchUseCase
import com.yingji.app.domain.usecase.SearchMoviesUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertingMultipleWatchRecordsKeepsEveryHistoryEntry() = runBlocking {
        val movieId = database.movieDao().insert(
            Movie(
                name = "测试作品",
                type = "电影",
                status = "观看中",
                createTime = 1L,
            ),
        )

        val firstHistoryId = database.watchHistoryDao().insert(
            WatchHistory(
                movieId = movieId,
                startTime = 10L,
                endTime = 20L,
                duration = 10L,
                watchNumber = 1,
            ),
        )
        val secondHistoryId = database.watchHistoryDao().insert(
            WatchHistory(
                movieId = movieId,
                startTime = 30L,
                endTime = 50L,
                duration = 20L,
                watchNumber = 2,
            ),
        )

        val history = database.watchHistoryDao().observeByMovieId(movieId).first()

        assertNotEquals(firstHistoryId, secondHistoryId)
        assertEquals(2, history.size)
        assertEquals(listOf(2, 1), history.map { it.watchNumber })
    }

    @Test
    fun movieCrudPersistsAcrossDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "phase3-persistence-test.db"
        context.deleteDatabase(databaseName)

        try {
            val firstDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName,
            ).build()
            val movieId = firstDatabase.movieDao().insert(
                Movie(
                    name = "待编辑作品",
                    type = "电影",
                    status = "想看",
                    createTime = 100L,
                ),
            )
            firstDatabase.close()

            val secondDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName,
            ).build()
            val insertedMovie = secondDatabase.movieDao().observeById(movieId).first()
            requireNotNull(insertedMovie)
            secondDatabase.movieDao().update(
                insertedMovie.copy(
                    name = "已编辑作品",
                    status = "已看完",
                ),
            )
            secondDatabase.close()

            val thirdDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName,
            ).build()
            val updatedMovie = thirdDatabase.movieDao().observeById(movieId).first()
            requireNotNull(updatedMovie)
            assertEquals("已编辑作品", updatedMovie.name)
            assertEquals("已看完", updatedMovie.status)
            thirdDatabase.movieDao().delete(updatedMovie)
            thirdDatabase.close()

            val fourthDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName,
            ).build()
            assertNull(fourthDatabase.movieDao().observeById(movieId).first())
            fourthDatabase.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun recordWatchCreatesNewRowsAndOnlyUpdatesSelectedHistory() = runBlocking {
        val movieId = database.movieDao().insert(
            Movie(
                name = "多刷测试作品",
                type = "电影",
                status = "观看中",
                createTime = 1L,
            ),
        )
        val repository = HistoryRepository(database)
        val recordWatch = RecordWatchUseCase(repository)

        val firstId = requireNotNull(recordWatch.start(movieId, startTime = 1_000L))
        val first = requireNotNull(repository.getById(firstId))
        assertNull(first.endTime)
        assertEquals(0, repository.observeByMovieId(movieId).first().count { it.endTime != null })
        recordWatch.end(first, endTime = 4_000L)
        assertEquals(1, repository.observeByMovieId(movieId).first().count { it.endTime != null })

        val secondId = requireNotNull(recordWatch.start(movieId, startTime = 10_000L))
        val second = requireNotNull(repository.getById(secondId))
        assertEquals(1, repository.observeByMovieId(movieId).first().count { it.endTime != null })
        recordWatch.end(second, endTime = 15_000L)
        assertEquals(2, repository.observeByMovieId(movieId).first().count { it.endTime != null })

        val thirdId = requireNotNull(recordWatch.start(movieId, startTime = 20_000L))
        val third = requireNotNull(repository.getById(thirdId))
        recordWatch.end(third, endTime = 29_000L)

        assertNotEquals(firstId, secondId)
        assertNotEquals(secondId, thirdId)
        assertEquals(3, repository.observeByMovieId(movieId).first().size)
        assertEquals(
            listOf(3, 2, 1),
            repository.observeByMovieId(movieId).first().map { it.watchNumber },
        )

        recordWatch.updateTimes(
            history = requireNotNull(repository.getById(secondId)),
            startTime = 11_000L,
            endTime = 18_000L,
        )

        assertEquals(3_000L, requireNotNull(repository.getById(firstId)).duration)
        assertEquals(7_000L, requireNotNull(repository.getById(secondId)).duration)
        assertEquals(9_000L, requireNotNull(repository.getById(thirdId)).duration)
    }

    @Test
    fun watchHistoryPersistsAcrossDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "phase4-history-persistence-test.db"
        context.deleteDatabase(databaseName)

        try {
            val firstDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName,
            ).build()
            val movieId = firstDatabase.movieDao().insert(
                Movie(
                    name = "历史持久化作品",
                    type = "电影",
                    status = "观看中",
                    createTime = 1L,
                ),
            )
            val firstRepository = HistoryRepository(firstDatabase)
            val firstUseCase = RecordWatchUseCase(firstRepository)
            val historyId = requireNotNull(firstUseCase.start(movieId, startTime = 5_000L))
            firstUseCase.end(
                history = requireNotNull(firstRepository.getById(historyId)),
                endTime = 12_000L,
            )
            firstDatabase.close()

            val secondDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName,
            ).build()
            val persisted = secondDatabase.watchHistoryDao().observeByMovieId(movieId).first()
            assertEquals(1, persisted.size)
            assertEquals(historyId, persisted.single().id)
            assertEquals(1, persisted.single().watchNumber)
            assertEquals(7_000L, persisted.single().duration)
            secondDatabase.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun searchSupportsNameFuzzyPinyinAndInitials() {
        val movies = listOf(
            Movie(
                id = 1L,
                name = "流浪地球",
                type = "电影",
                status = "已看完",
                createTime = 1L,
            ),
            Movie(
                id = 2L,
                name = "星际穿越",
                type = "电影",
                status = "想看",
                createTime = 2L,
            ),
            Movie(
                id = 3L,
                name = "重庆森林",
                type = "电影",
                status = "想看",
                createTime = 3L,
            ),
        )
        val search = SearchMoviesUseCase()

        assertEquals(listOf(1L), search(movies, "流浪").map { it.id })
        assertEquals(listOf(1L), search(movies, "浪地").map { it.id })
        assertEquals(listOf(1L), search(movies, "liulangdiqiu").map { it.id })
        assertEquals(listOf(1L), search(movies, "lldq").map { it.id })
        assertEquals(listOf(3L), search(movies, "chongqingsenlin").map { it.id })
        assertEquals(listOf(3L), search(movies, "cqsl").map { it.id })
    }

    @Test
    fun categoriesAndTagsKeepManyToManyRelations() = runBlocking {
        val repository = CategoryRepository(
            categoryDao = database.categoryDao(),
            tagDao = database.tagDao(),
        )
        repository.ensureSystemCategories()
        repository.ensureSystemCategories()
        val systemCategories = repository.observeCategories().first().filter { it.isSystem }
        assertEquals(
            CategoryRepository.SYSTEM_CATEGORY_NAMES.toSet(),
            systemCategories.map { it.name }.toSet(),
        )

        val movieId = database.movieDao().insert(
            Movie(
                name = "分类测试作品",
                type = "电影",
                status = "想看",
                createTime = 1L,
            ),
        )
        val categoryId = repository.insertCategory(Category(name = "收藏", isSystem = false))
        val tagId = repository.insertTag(Tag(name = "经典"))
        repository.addCategoryToMovie(movieId, categoryId)
        repository.addTagToMovie(movieId, tagId)

        assertEquals(listOf("收藏"), repository.observeCategoriesForMovie(movieId).first().filterNot { it.isSystem }.map { it.name })
        assertEquals(listOf("经典"), repository.observeTagsForMovie(movieId).first().map { it.name })

        repository.deleteCategory(systemCategories.first())
        assertTrue(repository.observeCategories().first().any { it.id == systemCategories.first().id })
    }

    @Test
    fun categoryAndTagCountsAndCollectionsTrackUniqueMoviesOnly() = runBlocking {
        val repository = CategoryRepository(database.categoryDao(), database.tagDao())
        val animationId = repository.insertCategory(Category(name = "动漫测试", isSystem = false))
        val movieId = repository.insertCategory(Category(name = "电影测试", isSystem = false))
        val campusId = repository.insertTag(Tag(name = "校园测试"))
        val dailyId = repository.insertTag(Tag(name = "日常测试"))

        suspend fun categoryCount(id: Long): Long =
            repository.observeCategoryMovieCounts().first()
                .firstOrNull { it.relationId == id }?.movieCount ?: 0L
        suspend fun tagCount(id: Long): Long =
            repository.observeTagMovieCounts().first()
                .firstOrNull { it.relationId == id }?.movieCount ?: 0L

        assertEquals(0L, categoryCount(animationId))
        assertEquals(0L, tagCount(campusId))

        val workId = database.movieDao().insert(
            Movie(
                name = "唯一作品",
                type = "动漫",
                status = "想看",
                createTime = 10L,
            ),
        )
        repository.addCategoryToMovie(workId, animationId)
        repository.addCategoryToMovie(workId, animationId)
        repository.addTagToMovie(workId, campusId)
        repository.addTagToMovie(workId, campusId)
        repository.addTagToMovie(workId, dailyId)

        repeat(3) { index ->
            database.watchHistoryDao().insert(
                WatchHistory(
                    movieId = workId,
                    startTime = index * 100L,
                    endTime = index * 100L + 50L,
                    duration = 50L,
                    watchNumber = index + 1,
                ),
            )
        }

        assertEquals(1L, categoryCount(animationId))
        assertEquals(1L, tagCount(campusId))
        assertEquals(1L, tagCount(dailyId))
        assertEquals(listOf(workId), repository.observeMoviesForCategory(animationId).first().map { it.id })
        assertEquals(listOf(workId), repository.observeMoviesForTag(campusId).first().map { it.id })

        val work = requireNotNull(database.movieDao().observeById(workId).first())
        database.movieDao().update(work.copy(status = "已看完"))
        assertEquals(1L, categoryCount(animationId))

        repository.removeCategoryFromMovie(workId, animationId)
        repository.addCategoryToMovie(workId, movieId)
        assertEquals(0L, categoryCount(animationId))
        assertEquals(1L, categoryCount(movieId))

        repository.removeTagFromMovie(workId, campusId)
        assertEquals(0L, tagCount(campusId))
        assertEquals(1L, tagCount(dailyId))

        database.movieDao().delete(requireNotNull(database.movieDao().observeById(workId).first()))
        assertEquals(0L, categoryCount(movieId))
        assertEquals(0L, tagCount(dailyId))
        assertTrue(repository.observeMoviesForCategory(Long.MAX_VALUE).first().isEmpty())
        assertTrue(repository.observeMoviesForTag(Long.MAX_VALUE).first().isEmpty())
    }

    @Test
    fun movieCreateAndEditKeepRelationsAtomicAndPreserveHistory() = runBlocking {
        val categoryRepository = CategoryRepository(database.categoryDao(), database.tagDao())
        val movieRepository = MovieRepository(database)
        val addMovie = com.yingji.app.domain.usecase.AddMovieUseCase(movieRepository)
        val animationId = categoryRepository.insertCategory(
            Category(name = "事务动漫", isSystem = false),
        )
        val movieCategoryId = categoryRepository.insertCategory(
            Category(name = "事务电影", isSystem = false),
        )
        val hotBloodId = categoryRepository.insertTag(Tag(name = "事务热血"))
        val battleId = categoryRepository.insertTag(Tag(name = "事务战斗"))

        val noTagMovieId = addMovie(
            name = "无标签作品",
            categoryName = "事务动漫",
            status = com.yingji.app.domain.model.MovieStatus.WANT_TO_WATCH,
            currentEpisode = 0,
            totalEpisode = null,
            posterPath = null,
            categoryId = animationId,
            tagIds = emptySet(),
        )
        assertEquals(
            listOf(animationId),
            database.categoryDao().getCategoryIdsForMovie(noTagMovieId),
        )
        assertTrue(database.tagDao().getTagIdsForMovie(noTagMovieId).isEmpty())

        val movieId = addMovie(
            name = "关联作品",
            categoryName = "事务电影",
            status = com.yingji.app.domain.model.MovieStatus.WATCHING,
            currentEpisode = 3,
            totalEpisode = 12,
            posterPath = null,
            categoryId = movieCategoryId,
            tagIds = setOf(hotBloodId, battleId, hotBloodId),
        )
        // Creation in WATCHING now records the first session atomically; complete that
        // real session instead of seeding a second history row in this relation test.
        val initialSession = database.watchHistoryDao().observeByMovieId(movieId).first().single()
        HistoryRepository(database).endWatching(initialSession.id, initialSession.startTime + 100L)

        assertEquals(listOf(movieCategoryId), database.categoryDao().getCategoryIdsForMovie(movieId))
        assertEquals(
            setOf(hotBloodId, battleId),
            database.tagDao().getTagIdsForMovie(movieId).toSet(),
        )

        val original = requireNotNull(database.movieDao().observeById(movieId).first())
        movieRepository.updateWithRelations(
            movie = original.copy(name = "纠正后的作品"),
            categoryId = animationId,
            tagIds = setOf(battleId),
        )

        val edited = requireNotNull(database.movieDao().observeById(movieId).first())
        assertEquals(movieId, edited.id)
        assertEquals("纠正后的作品", edited.name)
        assertEquals(original.status, edited.status)
        assertEquals(original.currentEpisode, edited.currentEpisode)
        assertEquals(listOf(animationId), database.categoryDao().getCategoryIdsForMovie(movieId))
        assertEquals(listOf(battleId), database.tagDao().getTagIdsForMovie(movieId))
        assertEquals(1, database.watchHistoryDao().observeByMovieId(movieId).first().size)

        movieRepository.updateWithRelations(
            movie = edited,
            categoryId = animationId,
            tagIds = emptySet(),
        )
        assertEquals(listOf(animationId), database.categoryDao().getCategoryIdsForMovie(movieId))
        assertTrue(database.tagDao().getTagIdsForMovie(movieId).isEmpty())

        val failedEdit = runCatching {
            movieRepository.updateWithRelations(
                movie = edited.copy(name = "不应保存"),
                categoryId = animationId,
                tagIds = setOf(Long.MAX_VALUE),
            )
        }
        assertTrue(failedEdit.isFailure)
        assertEquals("纠正后的作品", database.movieDao().observeById(movieId).first()?.name)
        assertEquals(listOf(animationId), database.categoryDao().getCategoryIdsForMovie(movieId))
        assertTrue(database.tagDao().getTagIdsForMovie(movieId).isEmpty())

        val movieCountBeforeFailedInsert = database.movieDao().observeAll().first().size
        val failedInsert = runCatching {
            addMovie(
                name = "不完整作品",
                categoryName = "不存在分类",
                status = com.yingji.app.domain.model.MovieStatus.WANT_TO_WATCH,
                currentEpisode = 0,
                totalEpisode = null,
                posterPath = null,
                categoryId = Long.MAX_VALUE,
                tagIds = emptySet(),
            )
        }
        assertTrue(failedInsert.isFailure)
        assertEquals(movieCountBeforeFailedInsert, database.movieDao().observeAll().first().size)
    }

    @Test
    fun addingCategoriesTrimsRejectsBlankAndDoesNotDuplicate() = runBlocking {
        val repository = CategoryRepository(database.categoryDao(), database.tagDao())

        assertEquals(SaveNameResult.InvalidName, repository.addCustomCategory("   "))
        assertEquals(SaveNameResult.Success, repository.addCustomCategory("  收藏  "))
        assertEquals(SaveNameResult.AlreadyExists, repository.addCustomCategory("收藏"))

        val customCategories = repository.observeCategories().first().filterNot { it.isSystem }
        assertEquals(listOf("收藏"), customCategories.map { it.name })
    }

    @Test
    fun addingTagsTrimsRejectsBlankAndDoesNotDuplicate() = runBlocking {
        val repository = CategoryRepository(database.categoryDao(), database.tagDao())

        assertEquals(SaveNameResult.InvalidName, repository.addTag("   "))
        assertEquals(SaveNameResult.Success, repository.addTag("  经典  "))
        assertEquals(SaveNameResult.AlreadyExists, repository.addTag("经典"))

        assertEquals(listOf("经典"), repository.observeTags().first().map { it.name })
    }

    @Test
    fun concurrentCategoryAndTagSubmissionsCreateOnlyOneRowEach() = runBlocking {
        val repository = CategoryRepository(database.categoryDao(), database.tagDao())

        val categoryResults = coroutineScope {
            List(8) { async { repository.addCustomCategory("连续分类") } }.awaitAll()
        }
        val tagResults = coroutineScope {
            List(8) { async { repository.addTag("连续标签") } }.awaitAll()
        }

        assertEquals(1, categoryResults.count { it == SaveNameResult.Success })
        assertEquals(7, categoryResults.count { it == SaveNameResult.AlreadyExists })
        assertEquals(1, tagResults.count { it == SaveNameResult.Success })
        assertEquals(7, tagResults.count { it == SaveNameResult.AlreadyExists })
        assertEquals(1, repository.observeCategories().first().count { it.name == "连续分类" })
        assertEquals(1, repository.observeTags().first().count { it.name == "连续标签" })
    }

    @Test
    fun databaseInsertFailureReturnsFailureInsteadOfThrowingFromNameSave() = runBlocking {
        val categoryDao = throwingDao(CategoryDao::class.java)
        val tagDao = throwingDao(TagDao::class.java)
        val repository = CategoryRepository(
            categoryDao,
            tagDao,
        )

        assertTrue(repository.addCustomCategory("分类") is SaveNameResult.Failure)
        assertTrue(repository.addTag("标签") is SaveNameResult.Failure)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> throwingDao(type: Class<T>): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, _, _ ->
        throw IllegalStateException("Simulated database failure")
    } as T

    @Test
    fun movieTypesIncludeDefaultsAndDoNotDuplicateCustomValues() = runBlocking {
        val repository = MovieTypeRepository(database.movieTypeDao())
        repository.ensureSystemTypes()
        repository.ensureSystemTypes()

        assertEquals("OVA", repository.saveType("  OVA  "))
        assertEquals("OVA", repository.saveType("ova"))

        val types = repository.observeAll().first()
        assertEquals(MovieTypeRepository.SYSTEM_TYPE_NAMES, types.filter { it.isSystem }.map { it.name })
        assertEquals(listOf("OVA"), types.filterNot { it.isSystem }.map { it.name })
    }

    @Test
    fun migrationFromVersionOnePreservesExistingMovieTypes() {
        val databaseName = "movie-type-migration-test.db"
        migrationHelper.createDatabase(databaseName, 1).apply {
            execSQL(
                """
                INSERT INTO movies
                (name, posterPath, type, status, currentEpisode, totalEpisode, createTime)
                VALUES ('旧作品', NULL, '  OVA  ', '想看', 0, NULL, 1)
                """.trimIndent(),
            )
            close()
        }

        val migratedDatabase = migrationHelper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            DatabaseModule.MIGRATION_1_2,
        )
        val cursor = migratedDatabase.query(
            "SELECT name, isSystem FROM movie_types ORDER BY isSystem DESC, sortOrder ASC, name ASC",
        )
        val names = buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
        cursor.close()
        migratedDatabase.close()

        assertEquals(MovieTypeRepository.SYSTEM_TYPE_NAMES + "OVA", names)
    }

    @Test
    fun databaseSchemaUsesCurrentVersion() {
        assertEquals(5, database.openHelper.readableDatabase.version)
    }

    @Test
    fun databaseRejectsOrphanHistoryAndPassesIntegrityCheck() = runBlocking {
        val orphanInsert = runCatching {
            database.watchHistoryDao().insert(
                WatchHistory(
                    movieId = Long.MAX_VALUE,
                    startTime = 1L,
                    watchNumber = 1,
                ),
            )
        }

        assertTrue(orphanInsert.isFailure)
        database.openHelper.readableDatabase.query("PRAGMA quick_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
    }

    @Test
    fun invalidWatchEndTimeDoesNotOverwriteHistory() = runBlocking {
        val movieId = database.movieDao().insert(
            Movie(
                name = "异常时间测试",
                type = "电影",
                status = "观看中",
                createTime = 1L,
            ),
        )
        val repository = HistoryRepository(database)
        val recordWatch = RecordWatchUseCase(repository)
        val historyId = requireNotNull(recordWatch.start(movieId, startTime = 5_000L))
        val history = requireNotNull(repository.getById(historyId))

        val invalidUpdate = runCatching {
            recordWatch.end(history, endTime = 4_999L)
        }
        val persisted = requireNotNull(repository.getById(historyId))

        assertTrue(invalidUpdate.exceptionOrNull() is IllegalArgumentException)
        assertNull(persisted.endTime)
        assertEquals(0L, persisted.duration)
    }

    @Test
    fun posterIsCopiedToPrivateStorageAndCanBeDeleted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "phase6-poster-source.jpg")
        source.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val repository = PosterRepository(context)

        val persistedPath = requireNotNull(repository.persist(Uri.fromFile(source).toString()))
        val persisted = File(persistedPath)

        assertTrue(persisted.exists())
        assertTrue(persisted.canonicalPath.startsWith(File(context.filesDir, "posters").canonicalPath))
        assertEquals(source.readBytes().toList(), persisted.readBytes().toList())

        repository.delete(persistedPath)
        assertTrue(!persisted.exists())
        source.delete()
        Unit
    }
}
