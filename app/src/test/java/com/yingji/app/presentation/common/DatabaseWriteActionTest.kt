package com.yingji.app.presentation.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseWriteActionTest {
    @Test
    fun failureIsReportedWithoutReturningSuccessAndCanBeRetried() = runBlocking {
        var attempts = 0
        var failure: Exception? = null
        val action: suspend () -> Unit = {
            attempts += 1
            if (attempts == 1) error("Simulated write failure")
        }

        val firstSucceeded = runDatabaseWriteAction(action) { failure = it }

        assertFalse(firstSucceeded)
        assertEquals("Simulated write failure", failure?.message)

        failure = null
        val retrySucceeded = runDatabaseWriteAction(action) { failure = it }

        assertTrue(retrySucceeded)
        assertNull(failure)
        assertEquals(2, attempts)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() = runBlocking {
        runDatabaseWriteAction(
            action = { throw CancellationException("cancelled") },
            onFailure = { error("Cancellation must not be reported as a database failure") },
        )
        Unit
    }
}
