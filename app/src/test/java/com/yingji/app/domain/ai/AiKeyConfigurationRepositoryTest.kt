package com.yingji.app.domain.ai

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiKeyConfigurationRepositoryTest {
    private class Store : AiSecureKeyStore {
        var value: String? = null; var time: Long? = null; var mode: AiKeyVerificationKind? = null; var version = "initial"
        var failWrite = false; var recovery = false; var writes = 0
        override suspend fun read(): AiKeyRecord? {
            if (recovery) throw AiKeyRecoveryRequired()
            return AiKeyRecord(value?.let(AiSecret::fromInput), time, mode, version)
        }
        override suspend fun replace(key: AiSecret?, verifiedAt: Long?, kind: AiKeyVerificationKind?): String {
            if (failWrite) throw IOException("Injected storage failure")
            value = key?.useValue { it }; time = verifiedAt; mode = kind; writes++; version = "revision-" + writes; recovery = false
            return version
        }
    }
    private class Verifier : AiKeyVerifier {
        override val kind = AiKeyVerificationKind.MOCK
        var calls = 0; var fail = false; var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun verify(key: AiSecret): AiKeyVerificationKind {
            calls++; started.complete(Unit); gate?.await()
            if (fail) throw AiKeyException(AiKeyProblem.INVALID_KEY)
            return kind
        }
    }
    private suspend fun rejected(problem: AiKeyProblem, block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: AiKeyException) { assertEquals(problem, e.problem) }
    }
    @Test fun blankAndControlInputsCannotVerifyOrWrite() = runBlocking {
        val store = Store(); val verifier = Verifier(); val repo = AiKeyConfigurationRepository(store, verifier)
        rejected(AiKeyProblem.EMPTY_INPUT) { repo.verifyAndSave("  ") }
        rejected(AiKeyProblem.INVALID_INPUT) { repo.verifyAndSave("TEST_ONLY\nembedded") }
        rejected(AiKeyProblem.INVALID_INPUT) { repo.verifyAndSave("x".repeat(513)) }
        assertEquals(0, verifier.calls); assertEquals(0, store.writes)
    }
    @Test fun successfulValidationTrimsAndCommitsMetadataButNeverGrantsLivePermission() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, Verifier()) { 1234L }
        repo.verifyAndSave("  TEST_ONLY_BYOK_alpha  ")
        assertEquals("TEST_ONLY_BYOK_alpha", store.value); assertEquals(1234L, repo.state.value.lastVerifiedAt)
        assertEquals(AiKeyStorageStatus.CONFIGURED, repo.state.value.storage)
        assertEquals(AiKeyVerificationKind.MOCK, repo.state.value.kind); assertFalse(repo.state.value.canRequestLiveAi)
        assertFalse(repo.state.value.toString().contains("TEST_ONLY_BYOK_alpha"))
    }
    @Test fun invalidReplacementPreservesPreviousKeyTimeAndRevision() = runBlocking {
        val store = Store(); val verifier = Verifier(); val repo = AiKeyConfigurationRepository(store, verifier) { 10 }
        repo.verifyAndSave("TEST_ONLY_BYOK_old"); val before = repo.state.value; verifier.fail = true
        rejected(AiKeyProblem.INVALID_KEY) { repo.verifyAndSave("TEST_ONLY_BYOK_new") }
        assertEquals("TEST_ONLY_BYOK_old", store.value); assertEquals(before.revision, repo.state.value.revision)
        assertEquals(before.lastVerifiedAt, repo.state.value.lastVerifiedAt); assertEquals(1, store.writes)
    }
    @Test fun failedCommitNeverClaimsNewKeyWasSaved() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, Verifier()); repo.verifyAndSave("old")
        val before = repo.state.value; store.failWrite = true
        rejected(AiKeyProblem.STORAGE_FAILED) { repo.verifyAndSave("replacement") }
        assertEquals("old", store.value); assertEquals(before.revision, repo.state.value.revision)
        assertEquals(AiKeyProblem.STORAGE_FAILED, repo.state.value.error); assertFalse(repo.state.value.verifying)
    }
    @Test fun newKeyDoesNotReplaceOldKeyUntilVerificationFinishes() = runBlocking {
        val store = Store(); val verifier = Verifier(); val repo = AiKeyConfigurationRepository(store, verifier)
        repo.verifyAndSave("old"); verifier.gate = CompletableDeferred()
        val saving = async { repo.verifyAndSave("new") }; yield()
        assertEquals("old", store.value); assertTrue(repo.state.value.verifying)
        verifier.gate!!.complete(Unit); saving.await(); assertEquals("new", store.value); assertEquals(2, store.writes)
    }
    @Test fun rapidDuplicateValidationIsRejectedWithoutASecondVerifierCall() = runBlocking {
        val store = Store(); val verifier = Verifier(); verifier.gate = CompletableDeferred()
        val repo = AiKeyConfigurationRepository(store, verifier)
        val first = async { repo.verifyAndSave("first") }; verifier.started.await()
        rejected(AiKeyProblem.BUSY) { repo.verifyAndSave("second") }; assertEquals(1, verifier.calls)
        verifier.gate!!.complete(Unit); first.await(); assertEquals("first", store.value)
    }
    @Test fun deletionRevokesValidationInFlightAndOldResultCannotRestoreKey() = runBlocking {
        val store = Store(); val verifier = Verifier(); val repo = AiKeyConfigurationRepository(store, verifier)
        repo.verifyAndSave("old"); verifier.gate = CompletableDeferred()
        val pending = async { runCatching { repo.verifyAndSave("new") } }; yield()
        repo.delete(); val deletionVersion = repo.state.value.revision; verifier.gate!!.complete(Unit)
        assertEquals(AiKeyProblem.STALE_OPERATION, (pending.await().exceptionOrNull() as AiKeyException).problem)
        assertNull(store.value); assertEquals(AiKeyStorageStatus.NOT_CONFIGURED, repo.state.value.storage)
        assertEquals(deletionVersion, repo.state.value.revision); assertFalse(repo.state.value.verifying)
    }
    @Test fun failedDeletionRetainsConfiguredStateAndReportsFailure() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, Verifier()); repo.verifyAndSave("old")
        store.failWrite = true; rejected(AiKeyProblem.STORAGE_FAILED) { repo.delete() }
        assertEquals("old", store.value); assertEquals(AiKeyStorageStatus.CONFIGURED, repo.state.value.storage)
    }
    @Test fun recoveryFailureDoesNotEraseRecordAndExplicitNewVerificationCanReconfigure() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, Verifier()); repo.verifyAndSave("old")
        store.recovery = true; repo.refresh(); assertEquals(AiKeyStorageStatus.RECOVERY_REQUIRED, repo.state.value.storage)
        assertEquals("old", store.value); assertFalse(repo.state.value.canRequestLiveAi)
        repo.verifyAndSave("new"); assertEquals("new", store.value); assertEquals(AiKeyStorageStatus.CONFIGURED, repo.state.value.storage)
    }
    @Test fun reopeningRepositoryRestoresMetadataAndDeletionTombstone() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, Verifier()) { 100 }
        repo.verifyAndSave("saved"); val reopened = AiKeyConfigurationRepository(store, Verifier()); reopened.refresh()
        assertEquals(repo.state.value, reopened.state.value); reopened.delete()
        val afterDelete = AiKeyConfigurationRepository(store, Verifier()); afterDelete.refresh()
        assertEquals(AiKeyStorageStatus.NOT_CONFIGURED, afterDelete.state.value.storage); assertEquals(reopened.state.value.revision, afterDelete.state.value.revision)
    }
    @Test fun cancellationBeforeValidationCompletesNeverWrites() = runBlocking {
        val store = Store(); val verifier = Verifier(); verifier.gate = CompletableDeferred()
        val repo = AiKeyConfigurationRepository(store, verifier); val task = launch { repo.verifyAndSave("cancelled") }
        verifier.started.await(); task.cancelAndJoin(); assertEquals(0, store.writes); assertFalse(repo.state.value.verifying)
    }
    @Test fun mockRejectsNonSyntheticAndInvalidCredentialsWithoutPersistence() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, LocalMockAiKeyVerifier())
        rejected(AiKeyProblem.MOCK_ONLY) { repo.verifyAndSave("FAKE_INPUT_NOT_A_REAL_KEY") }
        rejected(AiKeyProblem.INVALID_KEY) { repo.verifyAndSave("TEST_ONLY_BYOK_INVALID") }
        assertEquals(0, store.writes); repo.verifyAndSave("TEST_ONLY_BYOK_valid"); assertEquals(1, store.writes)
    }
    @Test fun disabledVerifierNeverSavesOrEnablesLiveCalls() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, DisabledAiKeyVerifier())
        rejected(AiKeyProblem.UNAVAILABLE) { repo.verifyAndSave("TEST_ONLY_BYOK_disabled") }; assertEquals(0, store.writes); assertFalse(repo.state.value.canRequestLiveAi)
    }
    @Test fun secretAndRecordAreRedactedInDiagnostics() {
        val secret = AiSecret.fromInput("TEST_ONLY_BYOK_sensitive"); val record = AiKeyRecord(secret, 1, AiKeyVerificationKind.MOCK)
        assertEquals("AiSecret([REDACTED])", secret.toString()); assertEquals("AiKeyRecord([REDACTED])", record.toString()); record.close()
        assertFalse(AiKeyException(AiKeyProblem.INVALID_KEY).toString().contains("sensitive"))
    }

    @Test fun missingOrWrongKindKeyCannotStartARequest() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, Verifier()); var called = false
        for (live in listOf(false, true)) assertEquals("KEY_REQUIRED", (runCatching { repo.withConfiguredKey(live) { called = true } }.exceptionOrNull() as WorkerProblem).code)
        repo.verifyAndSave("TEST_ONLY_BYOK_saved")
        assertEquals("KEY_REQUIRED", (runCatching { repo.withConfiguredKey(false) { called = true } }.exceptionOrNull() as WorkerProblem).code)
        assertFalse(called)
        repo.withConfiguredKey(true) { assertEquals("TEST_ONLY_BYOK_saved", it.useValue { value -> value }) }
    }
    @Test fun deletionOrReplacementFencesAnInFlightRequestAndClosesItsSecret() = runBlocking {
        for (delete in listOf(true, false)) {
            val store = Store(); val repo = AiKeyConfigurationRepository(store, Verifier()); repo.verifyAndSave("old")
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var retained: AiSecret? = null
            val task = async { runCatching { repo.withConfiguredKey(true) { retained = it; started.complete(Unit); release.await(); "late" } } }
            started.await(); if (delete) repo.delete() else repo.verifyAndSave("new"); release.complete(Unit)
            assertEquals("STALE_KEY", (task.await().exceptionOrNull() as WorkerProblem).code)
            assertTrue(retained!!.useValue { value -> value.all { it.code == 0 } })
        }
    }
    @Test fun failedRequestClosesSecretAndPendingConfigurationBlocksNewCalls() = runBlocking {
        val store = Store(); val verifier = Verifier(); val repo = AiKeyConfigurationRepository(store, verifier); repo.verifyAndSave("old")
        var retained: AiSecret? = null
        runCatching { repo.withConfiguredKey(true) { retained = it; throw WorkerProblem("OFFLINE") } }
        assertTrue(retained!!.useValue { value -> value.all { it.code == 0 } })
        verifier.gate = CompletableDeferred(); val pending = async { repo.verifyAndSave("new") }; yield()
        var called = false
        assertEquals("KEY_CONFIGURATION_BUSY", (runCatching { repo.withConfiguredKey(true) { called = true } }.exceptionOrNull() as WorkerProblem).code)
        assertFalse(called); verifier.gate!!.complete(Unit); pending.await()
    }
    @Test fun metadataFenceCannotWriteLateHistoryAfterKeyDeletion() = runBlocking {
        val store = Store(); val repo = AiKeyConfigurationRepository(store, Verifier()); repo.verifyAndSave("old"); val revision = repo.state.value.revision
        repo.delete(); var written = false
        assertEquals("STALE_KEY", (runCatching { repo.withCurrentConfiguration(revision) { written = true } }.exceptionOrNull() as WorkerProblem).code)
        assertFalse(written)
    }
}
