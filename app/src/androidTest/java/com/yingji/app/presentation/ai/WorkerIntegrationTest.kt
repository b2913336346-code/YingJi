package com.yingji.app.presentation.ai

import android.content.Context
import androidx.datastore.preferences.core.*
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

class WorkerIntegrationTest {
    private val session = "device-" + UUID.randomUUID().toString()
    private val token = "TEST_ONLY_ACCESS_0000000000000000000000000000"
    private fun client(access: String = token) = workerTestClient(session, access)
    private fun request(profile: Long = 1) = WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU), AiIntegrationContext(ProfileSnapshot(token = ProfileToken(profile, 1)), true))
    private fun control(fault: String? = null): JSONObject {
        val c = URL("http://127.0.0.1:8787/__test/state").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 10000; c.setRequestProperty("X-Yingji-Mock-Session", session)
            if (fault != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(JSONObject().put("fault", fault).toString().toByteArray()) } }
            assertEquals(200, c.responseCode); return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    private suspend fun problem(code: String, operation: suspend () -> Unit) {
        try { operation(); fail("Expected " + code) } catch (e: WorkerProblem) { assertEquals(code, e.code) }
    }
    @Test fun actualAndroidHttpRoundTripIsIdempotentAndQuotaComesFromWorker() = runBlocking<Unit> {
        val c = client(); assertEquals(30, c.quota().remaining)
        val req = request(); val first = c.hints(req)
        assertEquals(WorkerTaskStatus.SUCCESS, first.status); assertEquals(req.requestId, first.requestId)
        assertEquals(req.token, first.token); assertEquals(140L, first.usage.totalTokens); assertEquals(1, first.quota.used)
        assertEquals(listOf("科幻"), first.hints!!.preferredTopics); assertEquals(first, c.hints(req))
        assertEquals(1, control().getInt("modelCalls")); assertEquals(29, c.quota().remaining)
    }
    @Test fun authorizationAndServerStaleVersionsAreExplicitAndNeverRetryModels() = runBlocking<Unit> {
        assertEquals("UNAUTHORIZED", client("TEST_ONLY_INVALID_000000000000000000000000").hints(request()).error!!.code)
        assertEquals(0, control().getInt("modelCalls"))
        assertEquals(WorkerTaskStatus.SUCCESS, client().hints(request(2)).status)
        assertEquals("STALE_PROFILE", client().hints(request(1)).error!!.code)
        assertEquals(1, control().getInt("modelCalls"))
    }
    @Test fun malformedModelOutputIsRealWorkerFailureWithNoSuccessCharge() = runBlocking<Unit> {
        control("model-format"); val result = client().hints(request())
        assertEquals(WorkerTaskStatus.ERROR, result.status); assertEquals("MODEL_FORMAT", result.error!!.code)
        assertNull(result.hints); assertEquals(0, result.quota.used); assertEquals(1, control().getInt("modelCalls"))
    }
    @Test fun malformedJsonWrongVersionAndRedirectAreRejected() = runBlocking<Unit> {
        val c = client()
        for (fault in listOf("invalid-json", "wrong-version", "redirect")) {
            control(fault); problem("INVALID_RESPONSE") { c.hints(request()) }
        }
        assertEquals(2, control().getInt("modelCalls"))
    }
    @Test fun unsupportedEndpointsAndUnsafeCredentialsCannotBeUsed() = runBlocking<Unit> {
        for (url in listOf("https://api.deepseek.com", "http://example.com:8787", "http://127.0.0.1:8787/path", "https://user@x.workers.dev", "https://x.workers.dev?secret=yes")) {
            try { workerTestClient(session, token, url); fail(url) } catch (_: IllegalArgumentException) { }
        }
        problem("CONFIGURATION_ERROR") { client("TEST_ONLY_bad\nheader").quota() }
        assertEquals("WorkerHttpClient(redacted)", client().toString())
    }
    @Test fun wrapperUsesExistingCandidatePipelineAndManagementDoesNotMakeModelCalls() = runBlocking<Unit> {
        val demo = MockAiHomeRepository(ApplicationProvider.getApplicationContext<Context>(), AiTestStore())
        val repo = WorkerDemoHomeRepository(demo, client(), MockCandidateCatalogRepository())
        repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE)))
        repo.selectIntegrationMode(true)
        val result = repo.recommendWithMetadata(AiMode.FOR_YOU, "", MockScenario.WORKER)
        assertEquals(2, result.items.size); assertTrue(result.items.none { it.topic == "科幻" }); assertEquals(1, result.quota!!.used)
        val first = repo.readManagement(); assertEquals(1, first.history.size); assertFalse(first.quotaFixtureEditable)
        repo.readManagement(); repo.readQuota(); assertEquals(1, control().getInt("modelCalls"))
        problem("SERVER_QUOTA_READ_ONLY") { repo.setMockQuota(MockQuotaScenario.AVAILABLE) }
        val version = demo.readIntegrationProfile().token
        repo.clearManagement(AiClearAction.ALL); assertEquals(AiDemoSetup(), repo.readSetup())
        assertTrue(demo.readIntegrationProfile().token.profileVersion > version.profileVersion)
        assertEquals(1, repo.readQuota().used); assertEquals(1, control().getInt("modelCalls"))
    }
    @Test fun summaryVersionsAreMonotonicAtomicAndSurviveRepositoryRecreation() = runBlocking<Unit> {
        val store = AiTestStore(); val context = ApplicationProvider.getApplicationContext<Context>()
        val demo = MockAiHomeRepository(context, store); assertEquals(ProfileToken(0, 0), demo.readIntegrationProfile().token)
        demo.saveSetup(AiDemoSetup()); val first = demo.readIntegrationProfile().token
        demo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS); assertEquals(first, demo.readIntegrationProfile().token)
        demo.setManagementSources(ProfileSourceSettings(true, false, true)); val second = demo.readIntegrationProfile().token
        assertEquals(first.profileVersion + 1, second.profileVersion); assertEquals(1L, second.sourceSettingsVersion)
        store.failWrites = true
        try { demo.setManagementPreference("科幻", PreferenceDirection.LIKE); fail("Failed storage committed") } catch (_: java.io.IOException) { }
        assertEquals(second, demo.readIntegrationProfile().token); store.failWrites = false
        assertEquals(second, MockAiHomeRepository(context, store).readIntegrationProfile().token)
        demo.saveFeedback("mock-0", AiFeedback.LIKE); val feedbackVersion = demo.readIntegrationProfile().token
        assertEquals(second.profileVersion + 1, feedbackVersion.profileVersion)
        demo.saveFeedback("mock-0", AiFeedback.LIKE); assertEquals(feedbackVersion, demo.readIntegrationProfile().token)
        demo.clearManagement(AiClearAction.INFERRED); val cleared = demo.readIntegrationProfile().token
        assertEquals(feedbackVersion.profileVersion + 1, cleared.profileVersion)
        demo.saveFeedback("mock-0", AiFeedback.LIKE); assertEquals(cleared.profileVersion + 1, demo.readIntegrationProfile().token.profileVersion)
        demo.clearManagement(AiClearAction.ALL); val third = demo.readIntegrationProfile().token
        assertTrue(third.profileVersion > second.profileVersion); assertEquals(2L, third.sourceSettingsVersion)
        problem("STALE_PROFILE") { demo.recordIntegrationHistory(first, AiMode.FOR_YOU, listOf("old"), MockScenario.SUCCESS) }
        assertTrue(demo.readManagement().history.isEmpty())
    }
    @Test fun serviceFailureFallsBackLocallyButFreeTextHasNoUnverifiedInterpretation() = runBlocking<Unit> {
        val demo = MockAiHomeRepository(ApplicationProvider.getApplicationContext<Context>(), AiTestStore())
        val repo = WorkerDemoHomeRepository(demo, client(), MockCandidateCatalogRepository()); repo.saveSetup(AiDemoSetup()); repo.selectIntegrationMode(true)
        control("unavailable")
        val local = repo.recommendWithMetadata(AiMode.FOR_YOU, "", MockScenario.WORKER)
        assertEquals(3, local.items.size); assertTrue(local.notice!!.contains("本地可信"))
        assertTrue(repo.recommendWithMetadata(AiMode.QUERY, "今晚想看科幻", MockScenario.WORKER).items.isEmpty())
        assertEquals(0, control().getInt("posts")); assertEquals(0, control().getInt("modelCalls"))
    }

    @Test fun signatureOriginNormalizesDefaultHttpsPortAndHostCasingLikeWorkerUrl() {
        val identity = AndroidKeystoreWorkerIdentity(ApplicationProvider.getApplicationContext<Context>())
        val client = WorkerHttpClient("https://EXAMPLE.workers.dev:443", WorkerRequestSigner(identity))
        assertEquals("https://example.workers.dev/v1/ai/quota", client.signingTarget("/v1/ai/quota"))
        assertEquals("https://local.test/v1/ai/quota", workerTestClient(session).signingTarget("/v1/ai/quota"))
    }
}
