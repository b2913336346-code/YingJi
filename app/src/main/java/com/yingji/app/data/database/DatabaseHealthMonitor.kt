package com.yingji.app.data.database

import android.database.sqlite.SQLiteDatabaseCorruptException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

sealed interface DatabaseHealthState {
    data object Available : DatabaseHealthState

    data class Unavailable(
        val message: String = "本地数据无法正常读取，已停止相关操作以避免数据进一步受损。",
    ) : DatabaseHealthState
}

object DatabaseHealthMonitor {
    private val _state = MutableStateFlow<DatabaseHealthState>(DatabaseHealthState.Available)
    val state: StateFlow<DatabaseHealthState> = _state.asStateFlow()

    suspend fun <T> protectWrite(block: suspend () -> T): T {
        ensureWritable()
        return try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportIfCorruption(exception)
            throw exception
        }
    }

    fun <T> protectRead(source: Flow<T>): Flow<T> = source.catch { exception ->
        reportIfCorruption(exception)
        throw exception
    }

    fun reportIfCorruption(exception: Throwable): Boolean {
        val isCorruption = generateSequence(exception as Throwable?) { it.cause }
            .any { it is SQLiteDatabaseCorruptException }
        if (isCorruption) markUnavailable()
        return isCorruption
    }

    fun reportIntegrityCheckFailure() {
        markUnavailable()
    }

    internal fun resetForTesting() {
        _state.value = DatabaseHealthState.Available
    }

    private fun ensureWritable() {
        if (_state.value is DatabaseHealthState.Unavailable) {
            throw DatabaseUnavailableException()
        }
    }

    private fun markUnavailable() {
        _state.compareAndSet(
            expect = DatabaseHealthState.Available,
            update = DatabaseHealthState.Unavailable(),
        )
    }
}

class DatabaseUnavailableException : IllegalStateException("Database access is disabled")
