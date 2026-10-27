package com.yingji.app.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val Context.localAiUsageStore by preferencesDataStore(name = "ai_local_usage")

/** Separate, additive DataStore. No credentials, prompts, profile, request bodies or Room records. */
class LocalAiUsageRepository(
    context: Context,
    private val store: DataStore<Preferences> = context.localAiUsageStore,
    private val today: () -> LocalDate = { LocalDate.now() },
) : LocalAiUsageTracker {
    private val taskMutex = Mutex()
    override suspend fun snapshot(): LocalAiUsageSnapshot = update { it }

    override suspend fun <T> task(block: suspend () -> T): T {
        if (!taskMutex.tryLock()) throw LocalAiUsageException(LocalAiUsageProblem.BUSY)
        try {
            val begun = update {
                if (!it.canStart) throw LocalAiUsageException(LocalAiUsageProblem.LIMIT)
                if (it.started == Long.MAX_VALUE) throw LocalAiUsageException(LocalAiUsageProblem.STORAGE)
                it.copy(started = it.started + 1)
            }
            val result = try { block() } catch (error: Throwable) {
                // Cancellation must not erase an already dispatched attempt. Do not persist error text.
                withContext(NonCancellable) { finish(begun.day, success = false) }
                throw error
            }
            withContext(NonCancellable) { finish(begun.day, success = true) }
            return result
        } finally { taskMutex.unlock() }
    }

    private suspend fun finish(day: String, success: Boolean) {
        update { current ->
            // A task started yesterday does not consume today's reminder after a local date change.
            if (current.day != day) current else if (success) current.copy(succeeded = current.succeeded + 1)
            else current.copy(failed = current.failed + 1)
        }
    }

    private suspend fun update(transform: (LocalAiUsageSnapshot) -> LocalAiUsageSnapshot): LocalAiUsageSnapshot {
        try {
            var result: LocalAiUsageSnapshot? = null
            store.edit { values ->
                val date = today().toString()
                val saved = values[DAY]?.let { day ->
                    LocalDate.parse(day)
                    LocalAiUsageSnapshot(day, values[STARTED] ?: 0, values[SUCCEEDED] ?: 0, values[FAILED] ?: 0)
                } ?: run {
                    check(values[STARTED] == null && values[SUCCEEDED] == null && values[FAILED] == null)
                    LocalAiUsageSnapshot(date)
                }
                val current = if (saved.day == date) saved else LocalAiUsageSnapshot(date)
                val next = transform(current)
                values[DAY] = next.day; values[STARTED] = next.started
                values[SUCCEEDED] = next.succeeded; values[FAILED] = next.failed
                result = next
            }
            return checkNotNull(result)
        } catch (error: CancellationException) { throw error
        } catch (error: LocalAiUsageException) { throw error
        } catch (_: Exception) { throw LocalAiUsageException(LocalAiUsageProblem.STORAGE) }
    }
    private companion object {
        val DAY = stringPreferencesKey("day")
        val STARTED = longPreferencesKey("started")
        val SUCCEEDED = longPreferencesKey("succeeded")
        val FAILED = longPreferencesKey("failed")
    }
}
