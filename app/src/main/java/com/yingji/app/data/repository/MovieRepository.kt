package com.yingji.app.data.repository

import androidx.room.withTransaction
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.database.dao.MovieDao
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.relation.MovieWithHistory
import com.yingji.app.data.relation.MovieWithTags
import kotlinx.coroutines.flow.Flow

class MovieRepository(
    private val database: AppDatabase,
) {
    private val movieDao = database.movieDao()
    private val categoryDao = database.categoryDao()
    private val tagDao = database.tagDao()
    fun observeAll(): Flow<List<Movie>> = DatabaseHealthMonitor.protectRead(movieDao.observeAll())

    fun observeById(movieId: Long): Flow<Movie?> =
        DatabaseHealthMonitor.protectRead(movieDao.observeById(movieId))

    fun observeWithHistory(movieId: Long): Flow<MovieWithHistory?> =
        DatabaseHealthMonitor.protectRead(movieDao.observeWithHistory(movieId))

    fun observeWithTags(movieId: Long): Flow<MovieWithTags?> =
        DatabaseHealthMonitor.protectRead(movieDao.observeWithTags(movieId))

    suspend fun insert(movie: Movie): Long = DatabaseHealthMonitor.protectWrite {
        movieDao.insert(movie)
    }

    suspend fun insertWithRelations(
        movie: Movie,
        categoryId: Long,
        tagIds: Set<Long>,
    ): Long = DatabaseHealthMonitor.protectWrite {
        database.withTransaction {
            val movieId = movieDao.insert(movie)
            categoryDao.addMovieCategory(MovieCategory(movieId, categoryId))
            tagIds.forEach { tagId ->
                tagDao.addMovieTag(MovieTag(movieId, tagId))
            }
            movieId
        }
    }

    suspend fun update(movie: Movie) = DatabaseHealthMonitor.protectWrite {
        movieDao.update(movie)
    }

    suspend fun updateWithRelations(
        movie: Movie,
        categoryId: Long,
        tagIds: Set<Long>,
    ) = DatabaseHealthMonitor.protectWrite {
        database.withTransaction {
            movieDao.update(movie)

            val currentCategoryIds = categoryDao.getCategoryIdsForMovie(movie.id)
            if (currentCategoryIds.size != 1 || currentCategoryIds.singleOrNull() != categoryId) {
                categoryDao.replaceMovieCategory(movie.id, categoryId)
            }

            val currentTagIds = tagDao.getTagIdsForMovie(movie.id).toSet()
            (currentTagIds - tagIds).forEach { tagId ->
                tagDao.removeMovieTag(movie.id, tagId)
            }
            (tagIds - currentTagIds).forEach { tagId ->
                tagDao.addMovieTag(MovieTag(movie.id, tagId))
            }
        }
    }

    suspend fun delete(movie: Movie) = DatabaseHealthMonitor.protectWrite {
        movieDao.delete(movie)
    }
}
