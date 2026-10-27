package com.yingji.app.presentation.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class WorkerByokIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = "byok-" + UUID.randomUUID().toString()
    private class Memory : AiSecureKeyStore {
        var value: String? = null; var time: Long? = null; var kind: AiKeyVerificationKind? = null; var revision = "initial"; var fail = false
        override suspend fun read() = AiKeyRecord(value?.let(AiSecret::fromInput), time, kind, revision)
        override suspend fun replace(key: AiSecret?, verifiedAt: Long?, kind: AiKeyVerificationKind?): String {
            if (fail) throw java.io.IOException("Injected failure")
            value = key?.useValue { it }; time = verifiedAt; this.kind = kind; revision = UUID.randomUUID().toString(); return revision
        }
    }
    private fun client(keys: AiKeyConfigurationRepository? = null) = WorkerHttpClient("http://127.0.0.1:8787",
        WorkerRequestSigner(AndroidKeystoreWorkerIdentity(context)), keys, true, session)
    private fun repo(store: Memory) = AiKeyConfigurationRepository(store, WorkerMockAiKeyVerifier(client()))
    private fun control(fault: String? = null, release: Boolean = false): JSONObject {
        val c = URL("http://127.0.0.1:8787/__test/state").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 10000; c.setRequestProperty("X-Yingji-Mock-Session", session)
            if (fault != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(JSONObject().put("fault", fault).put("release", release).toString().toByteArray()) } }
            assertEquals(200, c.responseCode); return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    private suspend fun rejected(problem: AiKeyProblem, block: suspend () -> Unit) {
        assertEquals(problem, (runCatching { block() }.exceptionOrNull() as AiKeyException).problem)
    }
    private fun request() = WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU), AiIntegrationContext(ProfileSnapshot(token = ProfileToken(1,1)), true))
    @Test fun networkVerificationCommitsOnlyAfterSuccessAndNeverConsumesRecommendationQuota() = runBlocking<Unit> {
        val store = Memory(); val keys = repo(store); keys.refresh()
        keys.verifyAndSave("TEST_ONLY_BYOK_old"); val before = keys.state.value
        assertEquals(AiKeyVerificationKind.MOCK, before.kind); assertFalse(before.canRequestLiveAi)
        rejected(AiKeyProblem.INVALID_KEY) { keys.verifyAndSave("TEST_ONLY_BYOK_INVALID") }
        assertEquals("TEST_ONLY_BYOK_old", store.value); assertEquals(before.revision, keys.state.value.revision)
        store.fail = true; rejected(AiKeyProblem.STORAGE_FAILED) { keys.verifyAndSave("TEST_ONLY_BYOK_new") }
        assertEquals("TEST_ONLY_BYOK_old", store.value); assertEquals(before.lastVerifiedAt, keys.state.value.lastVerifiedAt)
        store.fail = false; keys.verifyAndSave("TEST_ONLY_BYOK_new"); assertEquals("TEST_ONLY_BYOK_new", store.value)
        val recreated = repo(store); recreated.refresh(); assertEquals(keys.state.value, recreated.state.value)
        assertEquals(30, client(keys).quota().remaining); assertEquals(0, control().getInt("modelCalls")); assertEquals(4, control().getInt("verifications"))
    }
    @Test fun upstreamAccessBillingAndRateErrorsAreDistinctAndPreserveOldKey() = runBlocking<Unit> {
        val store = Memory(); val keys = repo(store); keys.verifyAndSave("TEST_ONLY_BYOK_old"); val before = keys.state.value.revision
        for ((fault,problem) in listOf("verify-auth" to AiKeyProblem.INVALID_KEY, "verify-forbidden" to AiKeyProblem.ACCESS_DENIED,
            "verify-billing" to AiKeyProblem.ACCOUNT_UNAVAILABLE, "verify-rate" to AiKeyProblem.RATE_LIMITED)) {
            control(fault); rejected(problem) { keys.verifyAndSave("TEST_ONLY_BYOK_new") }
            assertEquals("TEST_ONLY_BYOK_old", store.value); assertEquals(before, keys.state.value.revision)
        }
        assertEquals(0, control().getInt("modelCalls")); assertEquals(5, control().getInt("verifications"))
    }
    @Test fun genuineLookingKeyAndMissingConfigurationCannotMakeHttpRequests() = runBlocking<Unit> {
        val store = Memory(); val keys = repo(store); keys.refresh()
        rejected(AiKeyProblem.MOCK_ONLY) { keys.verifyAndSave("NOT_A_TEST_CREDENTIAL_never_sent") }
        assertEquals("KEY_REQUIRED", (runCatching { client(keys).hints(request()) }.exceptionOrNull() as WorkerProblem).code)
        val demo = MockAiHomeRepository(context, AiTestStore()); demo.saveSetup(AiDemoSetup())
        val home = WorkerDemoHomeRepository(demo, client(keys), MockCandidateCatalogRepository(), keys); home.selectIntegrationMode(true)
        assertEquals(AiQuotaStatus.UNAVAILABLE, home.readQuota().status)
        assertEquals(0, control().getInt("posts")); assertEquals(0, control().getInt("gets")); assertNull(store.value)
    }
    @Test fun duplicateVerificationHasOneLookupAndDeletionRejectsItsLateSuccess() = runBlocking<Unit> {
        val store = Memory(); val keys = repo(store); keys.verifyAndSave("TEST_ONLY_BYOK_old")
        control("verify-hold")
        val pending = async(Dispatchers.IO) { runCatching { keys.verifyAndSave("TEST_ONLY_BYOK_new") } }
        try {
            withTimeout(10000) { while (control().getInt("held") == 0) yield() }
            rejected(AiKeyProblem.BUSY) { keys.verifyAndSave("TEST_ONLY_BYOK_duplicate") }
            assertEquals("TEST_ONLY_BYOK_old", store.value); keys.delete(); val deletion = keys.state.value.revision
            control("normal", true)
            assertEquals(AiKeyProblem.STALE_OPERATION, (pending.await().exceptionOrNull() as AiKeyException).problem)
            assertNull(store.value); assertEquals(deletion, keys.state.value.revision); assertEquals(2, control().getInt("verifications"))
            assertEquals(0, control().getInt("modelCalls"))
        } finally { control("normal", true); pending.cancelAndJoin() }
    }
    @Test fun deletionDuringRecommendationRejectsLateBatchAndDoesNotWriteHistory() = runBlocking<Unit> {
        val store = Memory(); val keys = repo(store); keys.verifyAndSave("TEST_ONLY_BYOK_old")
        val demo = MockAiHomeRepository(context, AiTestStore()); demo.saveSetup(AiDemoSetup())
        val home = WorkerDemoHomeRepository(demo, client(keys), MockCandidateCatalogRepository(), keys); home.selectIntegrationMode(true)
        control("hold"); val pending = async(Dispatchers.IO) { runCatching { home.recommendWithMetadata(AiMode.FOR_YOU,"",MockScenario.WORKER) } }
        try {
            withTimeout(10000) { while (control().getInt("held") == 0) yield() }
            keys.delete(); control("normal", true)
            val problem = (pending.await().exceptionOrNull() as WorkerProblem).code
            assertTrue(problem in setOf("STALE_KEY", "STALE_PROFILE")); assertTrue(demo.readManagement().history.isEmpty())
            assertEquals(1, control().getInt("modelCalls"))
            assertEquals("KEY_REQUIRED", (runCatching { client(keys).hints(request()) }.exceptionOrNull() as WorkerProblem).code)
            assertEquals(1, control().getInt("modelCalls"))
        } finally { control("normal", true); pending.cancelAndJoin() }
    }
    @Test fun replacementUsesNewBusinessIdAndOriginalFiltersHistoryAndQuotaRemainEffective() = runBlocking<Unit> {
        val store = Memory(); val keys = repo(store); keys.verifyAndSave("TEST_ONLY_BYOK_first")
        val demo = MockAiHomeRepository(context, AiTestStore()); demo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE)))
        val home = WorkerDemoHomeRepository(demo, client(keys), MockCandidateCatalogRepository(), keys); home.selectIntegrationMode(true)
        val first = home.recommendWithMetadata(AiMode.FOR_YOU,"",MockScenario.WORKER)
        assertEquals(2, first.items.size); assertTrue(first.items.none { it.topic == "科幻" }); assertEquals(1, first.quota!!.used)
        keys.verifyAndSave("TEST_ONLY_BYOK_second")
        val second = home.recommendWithMetadata(AiMode.FOR_YOU,"",MockScenario.WORKER)
        assertEquals(2, second.items.size); assertEquals(2, second.quota!!.used); assertEquals(2, control().getInt("modelCalls"))
        assertEquals(2, demo.readManagement().history.size); keys.delete()
        assertEquals(2, demo.readManagement().history.size); assertEquals(2, client().quota().used)
    }
}
