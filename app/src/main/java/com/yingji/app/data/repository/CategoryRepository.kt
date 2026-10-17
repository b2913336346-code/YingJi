package com.yingji.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.yingji.app.data.database.dao.CategoryDao
import com.yingji.app.data.database.dao.TagDao
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.database.model.RelationMovieCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

sealed interface SaveNameResult {
    data object Success : SaveNameResult
    data object InvalidName : SaveNameResult
    data object AlreadyExists : SaveNameResult
    data object NotEditable : SaveNameResult
    data class Failure(val exception: Exception) : SaveNameResult
}

class CategoryRepository(
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
) {
    fun observeCategories(): Flow<List<Category>> =
        DatabaseHealthMonitor.protectRead(categoryDao.observeAll())

    fun observeTags(): Flow<List<Tag>> = DatabaseHealthMonitor.protectRead(tagDao.observeAll())

    fun observeCategory(categoryId: Long): Flow<Category?> =
        DatabaseHealthMonitor.protectRead(categoryDao.observeById(categoryId))

    fun observeTag(tagId: Long): Flow<Tag?> =
        DatabaseHealthMonitor.protectRead(tagDao.observeById(tagId))

    fun observeCategoryMovieCounts(): Flow<List<RelationMovieCount>> =
        DatabaseHealthMonitor.protectRead(categoryDao.observeMovieCounts())

    fun observeTagMovieCounts(): Flow<List<RelationMovieCount>> =
        DatabaseHealthMonitor.protectRead(tagDao.observeMovieCounts())

    fun observeMoviesForCategory(categoryId: Long): Flow<List<Movie>> =
        DatabaseHealthMonitor.protectRead(categoryDao.observeMovies(categoryId))

    fun observeMoviesForTag(tagId: Long): Flow<List<Movie>> =
        DatabaseHealthMonitor.protectRead(tagDao.observeMovies(tagId))

    fun observeCategoriesForMovie(movieId: Long): Flow<List<Category>> =
        DatabaseHealthMonitor.protectRead(categoryDao.observeForMovie(movieId))

    fun observeTagsForMovie(movieId: Long): Flow<List<Tag>> =
        DatabaseHealthMonitor.protectRead(tagDao.observeForMovie(movieId))

    fun observeMovieCategories(): Flow<List<MovieCategory>> =
        DatabaseHealthMonitor.protectRead(categoryDao.observeMovieCategories())

    fun observeMovieTags(): Flow<List<MovieTag>> =
        DatabaseHealthMonitor.protectRead(tagDao.observeMovieTags())

    suspend fun ensureSystemCategories() {
        DatabaseHealthMonitor.protectWrite {
            SYSTEM_CATEGORY_NAMES.forEach { name ->
                categoryDao.insert(Category(name = name, isSystem = true))
            }
        }
    }

    suspend fun insertCategory(category: Category): Long = DatabaseHealthMonitor.protectWrite {
        categoryDao.insert(category)
    }

    suspend fun addCustomCategory(name: String): SaveNameResult {
        val normalized = normalizeManagedName(name) ?: return SaveNameResult.InvalidName
        return insertName {
            categoryDao.insert(Category(name = normalized, isSystem = false))
        }
    }

    suspend fun renameCategory(category: Category, name: String): SaveNameResult {
        if (category.isSystem) return SaveNameResult.NotEditable
        val normalized = normalizeManagedName(name) ?: return SaveNameResult.InvalidName
        return updateName(
            exists = { categoryDao.nameExistsExcludingId(normalized, category.id) },
            update = { categoryDao.update(category.copy(name = normalized)) },
        )
    }

    suspend fun deleteCategory(category: Category) {
        DatabaseHealthMonitor.protectWrite {
            if (!category.isSystem) categoryDao.delete(category)
        }
    }

    suspend fun insertTag(tag: Tag): Long = DatabaseHealthMonitor.protectWrite {
        tagDao.insert(tag)
    }

    suspend fun addTag(name: String): SaveNameResult {
        val normalized = normalizeManagedName(name) ?: return SaveNameResult.InvalidName
        return insertName { tagDao.insert(Tag(name = normalized)) }
    }

    suspend fun renameTag(tag: Tag, name: String): SaveNameResult {
        val normalized = normalizeManagedName(name) ?: return SaveNameResult.InvalidName
        return updateName(
            exists = { tagDao.nameExistsExcludingId(normalized, tag.id) },
            update = { tagDao.update(tag.copy(name = normalized)) },
        )
    }

    suspend fun deleteTag(tag: Tag) = DatabaseHealthMonitor.protectWrite {
        tagDao.delete(tag)
    }

    suspend fun addCategoryToMovie(movieId: Long, categoryId: Long) =
        DatabaseHealthMonitor.protectWrite {
            categoryDao.replaceMovieCategory(movieId, categoryId)
        }

    suspend fun removeCategoryFromMovie(movieId: Long, categoryId: Long) =
        DatabaseHealthMonitor.protectWrite {
            categoryDao.removeMovieCategory(movieId, categoryId)
        }

    suspend fun addTagToMovie(movieId: Long, tagId: Long) =
        DatabaseHealthMonitor.protectWrite {
            tagDao.addMovieTag(MovieTag(movieId, tagId))
        }

    suspend fun removeTagFromMovie(movieId: Long, tagId: Long) =
        DatabaseHealthMonitor.protectWrite {
            tagDao.removeMovieTag(movieId, tagId)
        }

    private suspend fun insertName(insert: suspend () -> Long): SaveNameResult = try {
        DatabaseHealthMonitor.protectWrite {
            if (insert() == -1L) SaveNameResult.AlreadyExists else SaveNameResult.Success
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        SaveNameResult.Failure(exception)
    }

    private suspend fun updateName(
        exists: suspend () -> Boolean,
        update: suspend () -> Int,
    ): SaveNameResult = try {
        DatabaseHealthMonitor.protectWrite {
            when {
                exists() -> SaveNameResult.AlreadyExists
                update() == 0 -> SaveNameResult.Failure(
                    IllegalStateException("The category or tag no longer exists"),
                )
                else -> SaveNameResult.Success
            }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: SQLiteConstraintException) {
        SaveNameResult.AlreadyExists
    } catch (exception: Exception) {
        SaveNameResult.Failure(exception)
    }

    companion object {
        val SYSTEM_CATEGORY_NAMES = listOf("电影", "电视剧", "动漫", "综艺", "纪录片")
    }
}

internal fun normalizeManagedName(name: String): String? =
    name.trim().takeIf(String::isNotBlank)
