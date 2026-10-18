package com.yingji.app.data.repository

import com.yingji.app.data.database.dao.WatchHistoryDao
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.WatchHistory
import kotlinx.coroutines.flow.Flow

class HistoryRepository(
    private val watchHistoryDao: WatchHistoryDao,
) {
    fun observeAll(): Flow<List<WatchHistory>> =
        DatabaseHealthMonitor.protectRead(watchHistoryDao.observeAll())

    fun observeByMovieId(movieId: Long): Flow<List<WatchHistory>> =
        DatabaseHealthMonitor.protectRead(watchHistoryDao.observeByMovieId(movieId))

    suspend fun getById(historyId: Long): WatchHistory? = DatabaseHealthMonitor.protectQuery {
        watchHistoryDao.getById(historyId)
    }

    suspend fun insert(history: WatchHistory): Long = DatabaseHealthMonitor.protectWrite {
        watchHistoryDao.insert(history)
    }

    suspend fun update(history: WatchHistory) = DatabaseHealthMonitor.protectWrite {
        watchHistoryDao.update(history)
    }

    suspend fun delete(history: WatchHistory) = DatabaseHealthMonitor.protectWrite {
        watchHistoryDao.delete(history)
    }
}
