package com.yingji.app.data.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseHealthMonitorTest {
    @Before
    fun setUp() {
        DatabaseHealthMonitor.resetForTesting()
    }

    @After
    fun tearDown() {
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test
    fun ordinaryConstraintFailureDoesNotTriggerCorruptionProtection() {
        val detected = DatabaseHealthMonitor.reportIfCorruption(
            SQLiteConstraintException("duplicate"),
        )

        assertFalse(detected)
        assertTrue(DatabaseHealthMonitor.state.value is DatabaseHealthState.Available)
    }

    @Test
    fun explicitCorruptionBlocksLaterWritesWithoutRetryingProtection() = runBlocking {
        assertTrue(
            DatabaseHealthMonitor.reportIfCorruption(
                IllegalStateException("wrapped", SQLiteDatabaseCorruptException("corrupt")),
            ),
        )
        assertTrue(DatabaseHealthMonitor.state.value is DatabaseHealthState.Unavailable)

        val blocked = runCatching {
            DatabaseHealthMonitor.protectWrite { error("must not execute") }
        }
        assertTrue(blocked.exceptionOrNull() is DatabaseUnavailableException)

        DatabaseHealthMonitor.reportIfCorruption(SQLiteDatabaseCorruptException("again"))
        assertTrue(DatabaseHealthMonitor.state.value is DatabaseHealthState.Unavailable)
    }

    @Test
    fun integrityCheckFailureBlocksWrites() = runBlocking {
        DatabaseHealthMonitor.reportIntegrityCheckFailure()

        val blocked = runCatching {
            DatabaseHealthMonitor.protectWrite { Unit }
        }
        assertTrue(blocked.exceptionOrNull() is DatabaseUnavailableException)
    }
}
