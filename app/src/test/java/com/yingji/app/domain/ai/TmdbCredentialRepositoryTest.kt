package com.yingji.app.domain.ai

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class TmdbCredentialRepositoryTest {
    private class Memory : TmdbSecureCredentialStore {
        var value: String? = null; var time: Long? = null; var revision = "empty"
        var writes = 0; var failWrite = false; var readError: Exception? = null
        var entered: CompletableDeferred<Unit>? = null; var release: CompletableDeferred<Unit>? = null
        override suspend fun read(): TmdbCredentialRecord? {
            readError?.let { throw it }
            return TmdbCredentialRecord(value?.let(TmdbSecret::fromInput), time, revision)
        }
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String {
            entered?.complete(Unit); release?.await()
            if (failWrite) throw IOException("Injected failure")
            writes++; value = key?.useValue { it }; time = savedAt; revision = UUID.randomUUID().toString(); return revision
        }
    }
    private suspend fun problem(expected: TmdbCredentialProblem, block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (e: TmdbCredentialException) { assertEquals(expected, e.problem) }
    }
    @Test fun emptyAndInvalidInputsNeverWriteOrExposeInput() = runBlocking {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory); repo.refresh()
        problem(TmdbCredentialProblem.EMPTY_INPUT) { repo.save(" \t ") }
        for (value in listOf("TEST_ONLY_TMDB_\nunsafe", "two words", "x".repeat(4097)))
            problem(TmdbCredentialProblem.INVALID_INPUT) { repo.save(value) }
        assertEquals(0, memory.writes); assertEquals(TmdbCredentialStatus.NOT_CONFIGURED, repo.state.value.status)
    }
    @Test fun bearerTokenBoundariesAndRedactedObjects() {
        val value = "TEST_ONLY_TMDB_" + "x".repeat(4081)
        TmdbSecret.fromInput(value).use { secret ->
            assertEquals(4096, secret.useValue { it.length }); assertFalse(secret.toString().contains(value))
            assertFalse(TmdbCredentialRecord(secret, 1, "revision").toString().contains(value))
        }
        TmdbSecret.fromInput("  TEST_ONLY_TMDB_trimmed  ").use { assertEquals("TEST_ONLY_TMDB_trimmed", it.useValue { value -> value }) }
    }
    @Test fun saveReplaceRefreshAndDeleteRemainUnverified() = runBlocking {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory) { 123 }; repo.refresh()
        repo.save("TEST_ONLY_TMDB_first"); val first = repo.state.value.revision
        assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status); assertEquals(123L, repo.state.value.savedAt)
        repo.save("TEST_ONLY_TMDB_second"); assertNotEquals(first, repo.state.value.revision)
        assertFalse(repo.state.value.toString().contains("TEST_ONLY_TMDB"))
        val reopened = TmdbCredentialRepository(memory); reopened.refresh()
        assertEquals(repo.state.value, reopened.state.value)
        reopened.delete(); val deletedRevision = reopened.state.value.revision
        val afterRestart = TmdbCredentialRepository(memory); afterRestart.refresh()
        assertEquals(TmdbCredentialStatus.NOT_CONFIGURED, afterRestart.state.value.status)
        assertEquals(deletedRevision, afterRestart.state.value.revision); assertNull(memory.value); assertNull(afterRestart.state.value.savedAt)
    }
    @Test fun failedReplacementAndDeleteKeepOldStateAndCanRetry() = runBlocking {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory) { 123 }; repo.save("TEST_ONLY_TMDB_old")
        val revision = repo.state.value.revision; memory.failWrite = true
        problem(TmdbCredentialProblem.STORAGE_FAILED) { repo.save("TEST_ONLY_TMDB_new") }
        assertEquals("TEST_ONLY_TMDB_old", memory.value); assertEquals(revision, repo.state.value.revision)
        problem(TmdbCredentialProblem.STORAGE_FAILED) { repo.delete() }
        assertEquals("TEST_ONLY_TMDB_old", memory.value); assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status)
        assertFalse(repo.state.value.saving); memory.failWrite = false; repo.delete(); assertNull(memory.value)
    }
    @Test fun duplicateSaveAndDeleteAreRejectedWhileWriteIsPending() = runBlocking {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory)
        memory.entered = CompletableDeferred(); memory.release = CompletableDeferred()
        val first = async(start = CoroutineStart.UNDISPATCHED) { repo.save("TEST_ONLY_TMDB_first") }
        memory.entered!!.await(); assertTrue(repo.state.value.saving)
        problem(TmdbCredentialProblem.BUSY) { repo.save("TEST_ONLY_TMDB_second") }
        problem(TmdbCredentialProblem.BUSY) { repo.delete() }
        memory.release!!.complete(Unit); first.await()
        assertEquals(1, memory.writes); assertEquals("TEST_ONLY_TMDB_first", memory.value); assertFalse(repo.state.value.saving)
    }
    @Test fun cancelledWriteDoesNotReplacePreviousCredential() = runBlocking {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory); repo.save("TEST_ONLY_TMDB_old")
        memory.entered = CompletableDeferred(); memory.release = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.save("TEST_ONLY_TMDB_cancelled") }
        memory.entered!!.await(); job.cancelAndJoin()
        assertEquals("TEST_ONLY_TMDB_old", memory.value); assertEquals(1, memory.writes); assertFalse(repo.state.value.saving)
    }
    @Test fun recoveryAndReadFailureKeepCiphertextStoreUntouchedAndAllowRefresh() = runBlocking {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory)
        memory.readError = TmdbCredentialRecoveryRequired(); repo.refresh()
        assertEquals(TmdbCredentialStatus.RECOVERY_REQUIRED, repo.state.value.status)
        memory.readError = IOException("TEST_ONLY_TMDB_must_not_leak"); repo.refresh()
        assertEquals(TmdbCredentialStatus.READ_FAILED, repo.state.value.status)
        assertFalse(repo.state.value.toString().contains("must_not_leak")); assertEquals(0, memory.writes)
        memory.readError = null; repo.refresh(); assertEquals(TmdbCredentialStatus.NOT_CONFIGURED, repo.state.value.status)
    }
}
