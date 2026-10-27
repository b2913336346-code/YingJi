package com.yingji.app.domain.ai

import com.yingji.app.data.ai.WorkerLiveAiKeyVerifier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** All operations use this in-memory service. No network or real credentials. */
class WorkerLiveAiKeyVerifierTest {
    private class Service : AiWorkerKeyVerification {
        var calls=0; var error: WorkerProblem?=null; var gate: CompletableDeferred<Unit>?=null
        val started=CompletableDeferred<Unit>()
        override suspend fun verify(key: AiSecret) { calls++; started.complete(Unit); gate?.await(); error?.let { throw it } }
    }
    private class Store : AiSecureKeyStore {
        var value: String?=null; var at: Long?=null; var kind: AiKeyVerificationKind?=null; var revision="empty"; var fail=false
        override suspend fun read()=AiKeyRecord(value?.let(AiSecret::fromInput),at,kind,revision)
        override suspend fun replace(key: AiSecret?,verifiedAt: Long?,kind: AiKeyVerificationKind?): String {
            if(fail)throw java.io.IOException("Injected failure")
            value=key?.useValue { it };at=verifiedAt;this.kind=kind;revision=java.util.UUID.randomUUID().toString();return revision
        }
    }
    private val configuration=AiLiveWorkerConfiguration.from(true,"https://approved-test.workers.dev")
    private val fake="FAKE_RUNTIME_KEY_NEVER_SENT_123456"
    private suspend fun rejected(problem: AiKeyProblem, block: suspend ()->Unit) {
        assertEquals(problem,(runCatching { block() }.exceptionOrNull() as AiKeyException).problem)
    }
    @Test fun disabledMissingAndInvalidConfigNeverInvokesVerification()=runBlocking {
        val service=Service()
        for(config in listOf(AiLiveWorkerConfiguration.from(false,"https://approved-test.workers.dev"),AiLiveWorkerConfiguration.from(true,""),AiLiveWorkerConfiguration.from(true,"https://api.deepseek.com"))) {
            val verifier=WorkerLiveAiKeyVerifier(config,service);assertNull(verifier.kind)
            AiSecret.fromInput(fake).use { key->rejected(AiKeyProblem.UNAVAILABLE) { verifier.verify(key) } }
        }
        assertEquals(0,service.calls)
    }
    @Test fun syntheticMalformedAndNonAsciiKeysCannotStartARealVerification()=runBlocking {
        val service=Service();val verifier=WorkerLiveAiKeyVerifier(configuration,service)
        for(value in listOf("short","TEST_ONLY_BYOK_do_not_forward","含有非Ascii的无效Key字符串")) AiSecret.fromInput(value).use { key->rejected(AiKeyProblem.INVALID_KEY) { verifier.verify(key) } }
        assertEquals(0,service.calls)
    }
    @Test fun onlySuccessfulVerificationCommitsLiveMetadataWithoutEchoingCredential()=runBlocking {
        val service=Service();val store=Store();val repo=AiKeyConfigurationRepository(store,WorkerLiveAiKeyVerifier(configuration,service)) { 1234L }
        assertEquals(AiKeyVerificationKind.LIVE,repo.verificationKind);assertEquals(configuration.origin,repo.verificationWorkerOrigin)
        repo.verifyAndSave("  "+fake+"  ");assertEquals(fake,store.value);assertEquals(AiKeyVerificationKind.LIVE,store.kind)
        assertEquals(1234L,repo.state.value.lastVerifiedAt);assertTrue(repo.state.value.canRequestLiveAi);assertFalse(repo.state.value.toString().contains(fake));assertEquals(1,service.calls)
    }
    @Test fun upstreamErrorsAreDistinctAndNeverReplaceThePreviouslySavedKey()=runBlocking {
        val service=Service();val store=Store();val repo=AiKeyConfigurationRepository(store,WorkerLiveAiKeyVerifier(configuration,service));repo.verifyAndSave(fake)
        val before=repo.state.value
        for((code,problem)in listOf("UPSTREAM_AUTH" to AiKeyProblem.INVALID_KEY,"UPSTREAM_FORBIDDEN" to AiKeyProblem.ACCESS_DENIED,"UNAUTHORIZED" to AiKeyProblem.ACCESS_DENIED,"UPSTREAM_BILLING" to AiKeyProblem.ACCOUNT_UNAVAILABLE,"UPSTREAM_RATE_LIMIT" to AiKeyProblem.RATE_LIMITED,"TRANSPORT_TIMEOUT" to AiKeyProblem.NETWORK_FAILED,"MODEL_FORMAT" to AiKeyProblem.UNAVAILABLE,"TASK_PENDING" to AiKeyProblem.UNAVAILABLE)) {
            service.error=WorkerProblem(code);rejected(problem) { repo.verifyAndSave(fake+"_replacement") }
            assertEquals(fake,store.value);assertEquals(before.revision,repo.state.value.revision);assertEquals(before.lastVerifiedAt,repo.state.value.lastVerifiedAt)
        }
        assertEquals(9,service.calls)
    }
    @Test fun successfulRemoteValidationWithFailedDiskCommitRetainsOldKey()=runBlocking {
        val service=Service();val store=Store();val repo=AiKeyConfigurationRepository(store,WorkerLiveAiKeyVerifier(configuration,service));repo.verifyAndSave(fake);val before=repo.state.value
        store.fail=true;rejected(AiKeyProblem.STORAGE_FAILED) { repo.verifyAndSave(fake+"_replacement") }
        assertEquals(fake,store.value);assertEquals(before.revision,repo.state.value.revision);assertEquals(2,service.calls)
    }
    @Test fun duplicateClicksAndDeletionCannotSaveALateValidation()=runBlocking {
        val service=Service();val store=Store();val repo=AiKeyConfigurationRepository(store,WorkerLiveAiKeyVerifier(configuration,service));service.gate=CompletableDeferred()
        val pending=async { runCatching { repo.verifyAndSave(fake) } };service.started.await()
        rejected(AiKeyProblem.BUSY) { repo.verifyAndSave(fake+"_duplicate") };repo.delete();service.gate!!.complete(Unit)
        assertEquals(AiKeyProblem.STALE_OPERATION,(pending.await().exceptionOrNull() as AiKeyException).problem);assertNull(store.value);assertEquals(1,service.calls)
    }
    @Test fun cancelledVerificationNeverCommitsAndDoesNotRetry()=runBlocking {
        val service=Service();service.gate=CompletableDeferred();val store=Store();val repo=AiKeyConfigurationRepository(store,WorkerLiveAiKeyVerifier(configuration,service))
        val pending=launch { repo.verifyAndSave(fake) };service.started.await();pending.cancelAndJoin()
        assertNull(store.value);assertFalse(repo.state.value.verifying);assertEquals(1,service.calls)
    }
}
