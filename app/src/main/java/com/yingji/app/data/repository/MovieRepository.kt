package com.yingji.app.data.repository

import androidx.room.withTransaction
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.relation.MovieWithHistory
import com.yingji.app.data.relation.MovieWithTags
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.isValidMovieRating
import kotlinx.coroutines.flow.Flow

class MovieRepository(
    private val database: AppDatabase,
) {
    private val movieDao = database.movieDao()
    private val categoryDao = database.categoryDao()
    private val tagDao = database.tagDao()
    private val sessions = WatchSessionStore(database)
    fun observeAll(): Flow<List<Movie>> = DatabaseHealthMonitor.protectRead(movieDao.observeAll())

    fun observeById(movieId: Long): Flow<Movie?> =
        DatabaseHealthMonitor.protectRead(movieDao.observeById(movieId))

    fun observeWithHistory(movieId: Long): Flow<MovieWithHistory?> =
        DatabaseHealthMonitor.protectRead(movieDao.observeWithHistory(movieId))

    fun observeWithTags(movieId: Long): Flow<MovieWithTags?> =
        DatabaseHealthMonitor.protectRead(movieDao.observeWithTags(movieId))

    suspend fun readRatingFeatures(): List<com.yingji.app.data.database.model.RatingFeatureRow> =
        DatabaseHealthMonitor.protectQuery { movieDao.readRatingFeatures() }

    suspend fun readBehaviorFeatures(): List<com.yingji.app.data.database.model.BehaviorFeatureRow> =
        DatabaseHealthMonitor.protectQuery { movieDao.readBehaviorFeatures() }

    fun observeProfileInputs(includeBehavior: Boolean): Flow<Long> = DatabaseHealthMonitor.protectRead(
        if (includeBehavior) movieDao.observeBehaviorProfileInputs() else movieDao.observeRatingProfileInputs(),
    )

    suspend fun readWorkNames(): List<String> = DatabaseHealthMonitor.protectQuery { movieDao.readNames() }

    suspend fun isPosterReferenced(path: String): Boolean = DatabaseHealthMonitor.protectQuery {
        movieDao.isPosterReferenced(path)
    }


    suspend fun hasRemotePosterSource(movieId: Long): Boolean = DatabaseHealthMonitor.protectQuery {
        val source = database.candidateImportDao().tmdbSourcesForMovie(movieId).singleOrNull()
        source != null && Regex("(movie|tv):[1-9][0-9]*").matches(source.externalId)
    }

    data class RemotePosterTarget(val movieId: Long, val posterPath: String?, val externalId: String)

    suspend fun remotePosterTarget(movieId: Long): RemotePosterTarget = DatabaseHealthMonitor.protectQuery {
        database.withTransaction {
            val movie = requireNotNull(movieDao.getById(movieId)) { "作品不存在" }
            val source = database.candidateImportDao().tmdbSourcesForMovie(movieId).singleOrNull()
            require(source != null && Regex("(movie|tv):[1-9][0-9]*").matches(source.externalId)) { "作品没有可用的 TMDB 来源" }
            RemotePosterTarget(movie.id, movie.posterPath, source.externalId)
        }
    }

    /** Compare only the poster column; concurrent rating, status and relation edits stay intact. */
    suspend fun replaceRemotePoster(target: RemotePosterTarget, newPath: String, validateBeforeWrite: () -> Unit = {}) = DatabaseHealthMonitor.protectWrite {
        require(newPath.startsWith("/") && !newPath.contains("://")) { "海报必须是本地文件" }
        database.withTransaction {
            val source = database.candidateImportDao().tmdbSourcesForMovie(target.movieId).singleOrNull()
            check(source?.externalId == target.externalId) { "作品来源已变化，请重新确认海报" }
            validateBeforeWrite()
            check(movieDao.replacePosterPath(target.movieId, target.posterPath, newPath) == 1) { "作品或海报已变化，请重新打开详情" }
        }
    }

    /** Keep the reference check and unlink inside the protected write/Room boundary. */
    suspend fun removePosterFileIfUnused(path: String, remove: suspend () -> Unit) = DatabaseHealthMonitor.protectWrite {
        database.withTransaction {
            if (!movieDao.isPosterReferenced(path)) remove()
        }
    }

    suspend fun insert(movie: Movie): Long = DatabaseHealthMonitor.protectWrite {
        database.withTransaction { insertMovieAndSession(movie) }
    }

    suspend fun insertWithRelations(
        movie: Movie,
        categoryId: Long,
        tagIds: Set<Long>,
        beforeInsert: suspend () -> Unit = {},
        afterInsert: suspend (Long) -> Unit = {},
    ): Long = DatabaseHealthMonitor.protectWrite {
        database.withTransaction {
            beforeInsert()
            val movieId = insertMovieAndSession(movie)
            categoryDao.addMovieCategory(MovieCategory(movieId, categoryId))
            tagIds.forEach { tagId ->
                tagDao.addMovieTag(MovieTag(movieId, tagId))
            }
            afterInsert(movieId)
            movieId
        }
    }

    suspend fun update(movie: Movie) = DatabaseHealthMonitor.protectWrite {
        database.withTransaction { updateMovieAndSession(movie) }
    }

    suspend fun updateStatus(movieId: Long, status: MovieStatus) =
        DatabaseHealthMonitor.protectWrite {
            database.withTransaction {
                val current = checkNotNull(movieDao.getById(movieId)) { "作品不存在" }
                updateMovieAndSession(current.copy(status = status.value))
            }
        }

    private suspend fun insertMovieAndSession(movie: Movie): Long {
        require(movie.name.isNotBlank()) { "作品名称不能为空" }
        require(isValidMovieRating(movie.rating)) { "评分须为 0–10 分，按 0.5 分递增" }
        val id = movieDao.insert(movie.copy(name = movie.name.trim()))
        val stored = movie.copy(id = id, name = movie.name.trim())
        val normalized = sessions.applyStatus(stored, null, System.currentTimeMillis())
        if (normalized != stored) movieDao.update(normalized)
        return id
    }

    private suspend fun updateMovieAndSession(movie: Movie) {
        require(movie.name.isNotBlank()) { "作品名称不能为空" }
        require(isValidMovieRating(movie.rating)) { "评分须为 0–10 分，按 0.5 分递增" }
        val current = checkNotNull(movieDao.getById(movie.id)) { "作品不存在" }
        movieDao.update(sessions.applyStatus(movie.copy(name = movie.name.trim()), current.status, System.currentTimeMillis()))
    }

    suspend fun updateWithRelations(
        movie: Movie,
        categoryId: Long,
        tagIds: Set<Long>,
    ) = DatabaseHealthMonitor.protectWrite {
        database.withTransaction {
            updateMovieAndSession(movie)

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
