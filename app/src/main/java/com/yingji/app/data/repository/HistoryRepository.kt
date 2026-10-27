package com.yingji.app.data.repository

import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.dao.WatchHistoryDao
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.WatchHistory
import kotlinx.coroutines.flow.Flow

class HistoryRepository(
    private val database: AppDatabase,
    private val watchHistoryDao: WatchHistoryDao = database.watchHistoryDao(),
) {
    private val sessions = WatchSessionStore(database, watchHistoryDao)

    fun observeAll(): Flow<List<WatchHistory>> =
        DatabaseHealthMonitor.protectRead(watchHistoryDao.observeAll())

    fun observeByMovieId(movieId: Long): Flow<List<WatchHistory>> =
        DatabaseHealthMonitor.protectRead(watchHistoryDao.observeByMovieId(movieId))

    suspend fun getById(historyId: Long): WatchHistory? = DatabaseHealthMonitor.protectQuery {
        watchHistoryDao.getById(historyId)
    }

    suspend fun insert(history: WatchHistory): Long = sessions.insert(history)

    suspend fun update(history: WatchHistory) = sessions.update(history)

    suspend fun startWatching(movieId: Long, startTime: Long): Long? = sessions.start(movieId, startTime)

    suspend fun endWatching(historyId: Long, endTime: Long) = sessions.end(historyId, endTime)

    suspend fun updateTimes(historyId: Long, startTime: Long, endTime: Long?) =
        sessions.updateTimes(historyId, startTime, endTime)

    suspend fun delete(history: WatchHistory) = DatabaseHealthMonitor.protectWrite {
        watchHistoryDao.delete(history)
    }
}
