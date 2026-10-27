package com.yingji.app.data.repository

import androidx.room.withTransaction
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.database.dao.WatchHistoryDao
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.resolveCurrentEpisodeForStatusTransition
import com.yingji.app.utils.TimeUtils

class ActiveWatchException : IllegalStateException("请先结束本次观看，再改为想看")

/** All session checks and writes run under the shared write lock and one Room transaction. */
internal class WatchSessionStore(
    private val database: AppDatabase,
    private val histories: WatchHistoryDao = database.watchHistoryDao(),
) {
    private val movies = database.movieDao()

    suspend fun <T> write(action: suspend () -> T): T = DatabaseHealthMonitor.protectWrite {
        database.withTransaction { action() }
    }

    /** Called inside the caller's transaction; never backfills existing rows on startup. */
    suspend fun applyStatus(movie: Movie, previousStatus: String?, time: Long): Movie {
        val status = MovieStatus.entries.first { it.value == movie.status }
        val records = histories.getForMovie(movie.id)
        val active = records.filter { it.endTime == null }
        check(active.size <= 1) { "存在多条未结束观看记录" }
        when (status) {
            MovieStatus.WANT_TO_WATCH -> if (active.isNotEmpty()) throw ActiveWatchException()
            MovieStatus.WATCHING -> if (active.isEmpty()) {
                histories.insert(WatchHistory(movieId = movie.id, startTime = time,
                    watchNumber = (records.maxOfOrNull { it.watchNumber } ?: 0) + 1))
            }
            MovieStatus.FINISHED -> {
                val current = active.singleOrNull()
                if (current != null) {
                    require(time >= current.startTime) { "结束时间不能早于开始时间" }
                    histories.update(current.copy(endTime = time,
                        duration = TimeUtils.calculateDuration(current.startTime, time)))
                } else if (previousStatus != MovieStatus.FINISHED.value) {
                    // A directly recorded completion has no known elapsed watch duration.
                    histories.insert(WatchHistory(movieId = movie.id, startTime = time,
                        endTime = time, duration = 0,
                        watchNumber = (records.maxOfOrNull { it.watchNumber } ?: 0) + 1))
                }
            }
        }
        return movie.copy(currentEpisode = resolveCurrentEpisodeForStatusTransition(
            MovieStatus.entries.firstOrNull { it.value == previousStatus }, status,
            movie.currentEpisode, movie.totalEpisode))
    }

    suspend fun start(movieId: Long, startTime: Long): Long? = write {
        val movie = requireMovie(movieId)
        val records = histories.getForMovie(movieId)
        check(records.count { it.endTime == null } <= 1) { "存在多条未结束观看记录" }
        if (records.any { it.endTime == null }) return@write null
        val id = histories.insert(WatchHistory(movieId = movieId, startTime = startTime,
            watchNumber = (records.maxOfOrNull { it.watchNumber } ?: 0) + 1))
        setStatus(movie, MovieStatus.WATCHING)
        id
    }

    suspend fun end(historyId: Long, endTime: Long) = write {
        val history = requireHistory(historyId)
        if (history.endTime != null) return@write
        require(endTime >= history.startTime) { "结束时间不能早于开始时间" }
        histories.update(history.copy(endTime = endTime,
            duration = TimeUtils.calculateDuration(history.startTime, endTime)))
        synchronizeAfterClose(history.movieId)
    }

    suspend fun updateTimes(historyId: Long, startTime: Long, endTime: Long?) = write {
        val history = requireHistory(historyId)
        updateHistory(history.copy(startTime = startTime, endTime = endTime,
            duration = endTime?.let { TimeUtils.calculateDuration(startTime, it) } ?: 0L), history)
    }

    suspend fun insert(history: WatchHistory): Long = write {
        validateHistory(history)
        val movie = requireMovie(history.movieId)
        val id = histories.insert(history)
        if (history.endTime == null) setStatus(movie, MovieStatus.WATCHING)
        id
    }

    suspend fun update(history: WatchHistory) = write {
        val current = requireHistory(history.id)
        require(history.movieId == current.movieId) { "不能更改观看记录所属作品" }
        updateHistory(history, current)
    }

    private suspend fun updateHistory(history: WatchHistory, current: WatchHistory) {
        validateHistory(history)
        histories.update(history)
        when {
            history.endTime == null -> setStatus(requireMovie(history.movieId), MovieStatus.WATCHING)
            current.endTime == null -> synchronizeAfterClose(history.movieId)
        }
    }

    private suspend fun validateHistory(history: WatchHistory) {
        require(history.endTime == null || history.endTime >= history.startTime) { "结束时间不能早于开始时间" }
        if (history.endTime == null) {
            check(histories.getForMovie(history.movieId).none { it.id != history.id && it.endTime == null }) {
                "该作品已有未结束的观看记录"
            }
        }
    }

    private suspend fun synchronizeAfterClose(movieId: Long) {
        val status = if (histories.getForMovie(movieId).any { it.endTime == null }) MovieStatus.WATCHING else MovieStatus.FINISHED
        setStatus(requireMovie(movieId), status)
    }

    private suspend fun requireMovie(movieId: Long): Movie =
        checkNotNull(movies.getById(movieId)) { "作品不存在" }

    private suspend fun requireHistory(historyId: Long): WatchHistory =
        checkNotNull(histories.getById(historyId)) { "观看记录不存在" }

    private suspend fun setStatus(movie: Movie, status: MovieStatus) {
        movies.update(movie.copy(status = status.value,
            currentEpisode = resolveCurrentEpisodeForStatusTransition(
                MovieStatus.entries.firstOrNull { it.value == movie.status }, status,
                movie.currentEpisode, movie.totalEpisode)))
    }
}
