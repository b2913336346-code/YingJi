package com.yingji.app.presentation.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.domain.usecase.RecordWatchUseCase
import com.yingji.app.presentation.common.runDatabaseWriteAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryActionState(
    val isSaving: Boolean = false,
    val message: String? = null,
)

class HistoryViewModel(
    private val historyRepository: HistoryRepository,
) : ViewModel() {
    private val recordWatch = RecordWatchUseCase(historyRepository)
    private val _actionState = MutableStateFlow(HistoryActionState())
    val actionState: StateFlow<HistoryActionState> = _actionState.asStateFlow()

    fun observeByMovieId(movieId: Long): Flow<List<WatchHistory>> =
        historyRepository.observeByMovieId(movieId)

    fun startWatching(movieId: Long) {
        runHistoryAction {
            recordWatch.start(movieId)
        }
    }

    fun endWatching(history: WatchHistory) {
        runHistoryAction {
            recordWatch.end(history)
        }
    }

    fun updateTimes(
        history: WatchHistory,
        startTime: Long,
        endTime: Long?,
    ) {
        runHistoryAction {
            recordWatch.updateTimes(history, startTime, endTime)
        }
    }

    fun consumeActionMessage() {
        _actionState.value = HistoryActionState()
    }

    private fun runHistoryAction(action: suspend () -> Unit) {
        if (_actionState.value.isSaving) return
        _actionState.value = HistoryActionState(isSaving = true)
        viewModelScope.launch {
            try {
                val succeeded = runDatabaseWriteAction(
                    action = action,
                    onFailure = { exception ->
                        Log.e(TAG, "Failed to save watch history", exception)
                        _actionState.value = HistoryActionState(
                            message = "观看记录保存失败，请重试",
                        )
                    },
                )
                if (succeeded) _actionState.value = HistoryActionState()
            } catch (exception: CancellationException) {
                _actionState.value = HistoryActionState()
                throw exception
            }
        }
    }

    companion object {
        private const val TAG = "HistoryViewModel"

        fun factory(historyRepository: HistoryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(historyRepository) as T
            }
    }
}
