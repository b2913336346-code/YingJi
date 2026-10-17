package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.utils.TimeUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecordWatchUseCase(
    private val historyRepository: HistoryRepository,
) {
    private val startMutex = Mutex()

    suspend fun start(
        movieId: Long,
        startTime: Long = System.currentTimeMillis(),
    ): Long? = startMutex.withLock {
        val histories = historyRepository.observeByMovieId(movieId).first()
        if (histories.any { it.endTime == null }) return@withLock null

        val watchNumber = (histories.maxOfOrNull { it.watchNumber } ?: 0) + 1
        historyRepository.insert(
            WatchHistory(
                movieId = movieId,
                startTime = startTime,
                watchNumber = watchNumber,
            ),
        )
    }

    suspend fun end(
        history: WatchHistory,
        endTime: Long = System.currentTimeMillis(),
    ) {
        if (history.endTime != null) return
        require(endTime >= history.startTime)
        historyRepository.update(
            history.copy(
                endTime = endTime,
                duration = TimeUtils.calculateDuration(history.startTime, endTime),
            ),
        )
    }

    suspend fun updateTimes(
        history: WatchHistory,
        startTime: Long,
        endTime: Long?,
    ) {
        require(endTime == null || endTime >= startTime)
        historyRepository.update(
            history.copy(
                startTime = startTime,
                endTime = endTime,
                duration = endTime?.let { TimeUtils.calculateDuration(startTime, it) } ?: 0L,
            ),
        )
    }
}
