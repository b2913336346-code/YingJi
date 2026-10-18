package com.yingji.app.data.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ExperimentalCoroutinesApi

sealed interface DatabaseHealthState {
    data object Available : DatabaseHealthState

    data class Unavailable(
        val message: String = "本地数据无法正常读取，已停止相关操作以避免数据进一步受损。",
        val issue: DatabaseIssue = DatabaseIssue.CORRUPTION,
    ) : DatabaseHealthState
}

object DatabaseHealthMonitor {
    private val _state = MutableStateFlow<DatabaseHealthState>(DatabaseHealthState.Available)
    val state: StateFlow<DatabaseHealthState> = _state.asStateFlow()
    private val writes = Mutex()
    @Volatile private var onProtection: ((DatabaseIssue) -> Unit)? = null

    internal fun bindProtection(listener: (DatabaseIssue) -> Unit) {
        onProtection = listener
    }

    suspend fun <T> protectWrite(block: suspend () -> T): T = writes.withLock {
        protectQuery(block)
    }

    suspend fun <T> protectQuery(block: suspend () -> T): T {
        ensureAccessible()
        return try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportOperationFailure(exception)
            throw exception
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> protectRead(source: Flow<T>): Flow<T> = state.flatMapLatest { health ->
        if (health is DatabaseHealthState.Unavailable) emptyFlow()
        else source.catch { exception ->
            if (exception is CancellationException) throw exception
            reportOperationFailure(exception)
            throw exception
        }
    }

    fun <T> protectUiRead(source: Flow<T>): Flow<T> = source.catch { exception ->
        if (exception is CancellationException) throw exception
        stop(classifyDatabaseIssue(exception))
    }

    fun reportIfCorruption(exception: Throwable): Boolean {
        val isCorruption = classifyDatabaseIssue(exception) == DatabaseIssue.CORRUPTION
        if (isCorruption) stop(DatabaseIssue.CORRUPTION)
        return isCorruption
    }

    fun reportIntegrityCheckFailure() {
        stop(DatabaseIssue.CORRUPTION)
    }

    internal fun resetForTesting() {
        onProtection = null
        _state.value = DatabaseHealthState.Available
    }

    fun ensureAccessible() {
        if (_state.value is DatabaseHealthState.Unavailable) {
            throw DatabaseUnavailableException()
        }
    }

    fun stop(issue: DatabaseIssue) {
        val changed = _state.compareAndSet(
            expect = DatabaseHealthState.Available,
            update = DatabaseHealthState.Unavailable(issue = issue),
        )
        if (changed) onProtection?.invoke(issue)
    }

    internal fun restoreProtection(issue: DatabaseIssue) {
        _state.value = DatabaseHealthState.Unavailable(issue = issue)
    }

    private fun reportOperationFailure(error: Throwable) {
        val issue = classifyDatabaseIssue(error)
        if (issue in setOf(DatabaseIssue.CORRUPTION, DatabaseIssue.DISK_FULL, DatabaseIssue.ACCESS)) stop(issue)
    }
}

class DatabaseUnavailableException : IllegalStateException("Database access is disabled")
