package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.data.repository.HistoryRepository

class RecordWatchUseCase(
    private val historyRepository: HistoryRepository,
) {
    suspend fun start(movieId: Long, startTime: Long = System.currentTimeMillis()): Long? =
        historyRepository.startWatching(movieId, startTime)

    suspend fun end(history: WatchHistory, endTime: Long = System.currentTimeMillis()) {
        historyRepository.endWatching(history.id, endTime)
    }

    suspend fun updateTimes(history: WatchHistory, startTime: Long, endTime: Long?) {
        historyRepository.updateTimes(history.id, startTime, endTime)
    }
}
