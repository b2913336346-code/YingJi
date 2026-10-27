package com.yingji.app.domain.ai

import com.yingji.app.data.ai.DeepSeekAiKeyVerifier
import com.yingji.app.data.ai.DeepSeekKeyValidation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekAiKeyVerifierTest {
    private val fake = "FAKE_DIRECT_CREDENTIAL_00000000"
    private class Store : AiSecureKeyStore {
        var value: String? = null; var kind: AiKeyVerificationKind? = null; var time: Long? = null; var revision = "initial"; var fail = false
        override suspend fun read() = AiKeyRecord(value?.let(AiSecret::fromInput),time,kind,revision)
        override suspend fun replace(key: AiSecret?, verifiedAt: Long?, kind: AiKeyVerificationKind?): String {
            if (fail) throw IllegalStateException("injected")
            value=key?.useValue { it }; time=verifiedAt;this.kind=kind;revision=java.util.UUID.randomUUID().toString();return revision
        }
    }
    private class Service : DeepSeekKeyValidation {
        var calls=0;var cancelled=0;var problem:Exception?=null
        var started=CompletableDeferred<Unit>();var gate:CompletableDeferred<Unit>?=null
        override suspend fun verify(key:AiSecret,isCurrent:()->Boolean) {
            calls++;started.complete(Unit);gate?.await();if(!isCurrent())throw WorkerProblem("STALE_KEY");problem?.let { throw it }
        }
        override fun cancelPending(){cancelled++}
    }
    private suspend fun rejected(problem:AiKeyProblem,block:suspend()->Unit) {
        val error=runCatching { block() }.exceptionOrNull();assertTrue(error is AiKeyException);assertEquals(problem,(error as AiKeyException).problem)
    }
    @Test fun disabledDirectVerifierDoesNotDispatchOrSave()=runBlocking {
        val service=Service();val store=Store();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service,enabled=false))
        assertTrue(repo.verificationDirectApi);assertNull(repo.verificationKind)
        rejected(AiKeyProblem.UNAVAILABLE){repo.verifyAndSave(fake)};assertEquals(0,service.calls);assertNull(store.value)
    }
    @Test fun onlySyntheticInputsAreAcceptedInMockAndNeverBecomeLiveMetadata()=runBlocking {
        val service=Service();val store=Store();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service,mock=true))
        rejected(AiKeyProblem.MOCK_ONLY){repo.verifyAndSave(fake)};assertEquals(0,service.calls)
        repo.verifyAndSave("TEST_ONLY_BYOK_local");assertEquals(AiKeyVerificationKind.MOCK,store.kind);assertFalse(repo.state.value.canRequestLiveAi)
    }
    @Test fun liveInputRejectsSyntheticNonAsciiAndControlCharactersBeforeDispatch()=runBlocking {
        val service=Service();val repo=AiKeyConfigurationRepository(Store(),DeepSeekAiKeyVerifier(service))
        for (value in listOf("TEST_ONLY_BYOK_unsupported","tiny","非ASCII".repeat(8))) rejected(AiKeyProblem.INVALID_KEY){repo.verifyAndSave(value)}
        rejected(AiKeyProblem.INVALID_INPUT){repo.verifyAndSave(fake+"\ncontrol")};assertEquals(0,service.calls)
    }
    @Test fun verificationReadsTrimmedRuntimeInputAndAtomicallyPersistsMetadata()=runBlocking {
        val store=Store();val service=Service();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service)){123L}
        repo.verifyAndSave("  "+fake+"  ");assertEquals(fake,store.value);assertEquals(123L,store.time);assertEquals(AiKeyVerificationKind.LIVE,store.kind)
        assertTrue(repo.state.value.canRequestLiveAi);assertFalse(repo.state.value.toString().contains(fake));assertEquals(1,service.calls)
    }
    @Test fun allSafeErrorsLeavePreviousCredentialAndVerificationMetadataUnchanged()=runBlocking {
        val store=Store();val service=Service();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service));repo.verifyAndSave(fake);val before=repo.state.value
        for ((code,problem) in mapOf("UPSTREAM_AUTH" to AiKeyProblem.INVALID_KEY,"UPSTREAM_FORBIDDEN" to AiKeyProblem.ACCESS_DENIED,
            "UPSTREAM_BILLING" to AiKeyProblem.ACCOUNT_UNAVAILABLE,"UPSTREAM_RATE_LIMIT" to AiKeyProblem.RATE_LIMITED,
            "TRANSPORT_TIMEOUT" to AiKeyProblem.NETWORK_FAILED,"MODEL_FORMAT" to AiKeyProblem.UNAVAILABLE,"STALE_KEY" to AiKeyProblem.STALE_OPERATION)) {
            service.problem=WorkerProblem(code);rejected(problem){repo.verifyAndSave(fake+"_new")}
            assertEquals(fake,store.value);assertEquals(before.revision,repo.state.value.revision);assertEquals(before.lastVerifiedAt,repo.state.value.lastVerifiedAt)
        }
        assertEquals(8,service.calls)
    }
    @Test fun unexpectedProviderFailureIsNotMisreportedAsDiskFailureOrLeaked()=runBlocking {
        val store=Store();val service=Service();service.problem=IllegalStateException(fake)
        val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service));rejected(AiKeyProblem.UNAVAILABLE){repo.verifyAndSave(fake)}
        assertNull(store.value);assertFalse(repo.state.value.toString().contains(fake))
    }
    @Test fun duplicateValidationHasOneCallAndDeleteRevokesPendingSave()=runBlocking {
        val store=Store();val service=Service();service.gate=CompletableDeferred();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service))
        val pending=async { runCatching { repo.verifyAndSave(fake) } };service.started.await()
        rejected(AiKeyProblem.BUSY){repo.verifyAndSave(fake+"_duplicate")};repo.delete();service.gate!!.complete(Unit)
        assertEquals(AiKeyProblem.STALE_OPERATION,(pending.await().exceptionOrNull() as AiKeyException).problem)
        assertNull(store.value);assertEquals(1,service.calls);assertTrue(service.cancelled>0)
    }
    @Test fun deletionBetweenVerificationEntryAndRequestGuardPreventsDispatch()=runBlocking {
        var valid=false;val service=Service();val verifier=DeepSeekAiKeyVerifier(service)
        AiSecret.fromInput(fake).use { key->rejected(AiKeyProblem.STALE_OPERATION){verifier.verifyCurrent(key){valid}} };assertEquals(0,service.calls)
    }
    @Test fun cancellationPropagatesWithoutCommitOrAutomaticRetry()=runBlocking {
        val store=Store();val service=Service();service.gate=CompletableDeferred();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(service))
        val task=launch { repo.verifyAndSave(fake) };service.started.await();task.cancelAndJoin()
        assertNull(store.value);assertFalse(repo.state.value.verifying);assertEquals(1,service.calls)
    }
    @Test fun diskFailureAfterValidationPreservesOldRecord()=runBlocking {
        val store=Store();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(Service()));repo.verifyAndSave(fake);val old=store.revision
        store.fail=true;rejected(AiKeyProblem.STORAGE_FAILED){repo.verifyAndSave(fake+"_new")};assertEquals(fake,store.value);assertEquals(old,store.revision)
    }
    @Test fun deleteInvokesOnlyRegisteredRequestRevocationAndNewRequestsAreBlocked()=runBlocking {
        val store=Store();val repo=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(Service()));repo.verifyAndSave(fake)
        val started=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>();var revoked=0
        val pending=async { runCatching { repo.withConfiguredKey(false,{revoked++}) { started.complete(Unit);gate.await() } } }
        started.await();repo.delete();gate.complete(Unit)
        assertEquals(1,revoked);assertEquals("STALE_KEY",(pending.await().exceptionOrNull() as WorkerProblem).code)
        assertEquals("KEY_REQUIRED",(runCatching { repo.withConfiguredKey(false){fail("must not call")} }.exceptionOrNull() as WorkerProblem).code)
    }
}
