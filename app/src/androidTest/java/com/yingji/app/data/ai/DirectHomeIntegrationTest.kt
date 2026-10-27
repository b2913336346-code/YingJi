package com.yingji.app.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.ai.AiTestStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class DirectTestKeyStore(var value: String? = null, var kind: AiKeyVerificationKind? = null) : AiSecureKeyStore {
    var revision = "fixture-initial"
    override suspend fun read() = AiKeyRecord(value?.let(AiSecret::fromInput), if (value == null) null else 1L, kind, revision)
    override suspend fun replace(key: AiSecret?, verifiedAt: Long?, kind: AiKeyVerificationKind?): String {
        value = key?.useValue { it }; this.kind = kind; revision = java.util.UUID.randomUUID().toString(); return revision
    }
}
class DirectHomeIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val completion = """{"id":"fixture","object":"chat.completion","model":"deepseek-flash","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{\"preferredTopics\":[],\"conditions\":{\"requiredTopics\":[],\"excludedTopics\":[],\"minYear\":null,\"maxYear\":null,\"maxRuntimeMinutes\":null}}"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    private class Reply(val status: Int = 200, val text: String, val timeout: Boolean = false, val held: Boolean = false) : HttpURLConnection(URL("https://api.deepseek.com")) {
        val body = ByteArrayOutputStream(); val started = CountDownLatch(1); val release = CountDownLatch(1); var disconnected = false
        override fun connect() {}; override fun usingProxy() = false
        override fun disconnect() { disconnected = true; release.countDown() }
        override fun getOutputStream() = body
        override fun getResponseCode(): Int { started.countDown(); if (held && !release.await(5, TimeUnit.SECONDS)) throw SocketTimeoutException(); if (timeout) throw SocketTimeoutException(); return status }
        override fun getContentType() = "application/json"
        override fun getInputStream() = ByteArrayInputStream(text.toByteArray())
    }
    private inner class Fixture(vararg replies: Reply) {
        val store = AiTestStore(); val usageStore = AiTestStore(); var day = java.time.LocalDate.of(2026, 10, 22)
        val usage = LocalAiUsageRepository(context, usageStore, today = { day })
        val catalog = object : CandidateCatalogRepository {
            override suspend fun load(): CandidateCatalog {
                val data = MockCandidateCatalogRepository().load()
                return data.copy(sources = data.sources.map { it.copy(license = it.license.copy(cacheAllowed = true,
                    evidence = "Explicit cache permission for this isolated test fixture", maxCacheAgeMillis = 60000)) })
            }
        }
        val demo = MockAiHomeRepository(context, store, localUsage = usage)
        val keys = AiKeyConfigurationRepository(DirectTestKeyStore("FAKE_DIRECT_CREDENTIAL_00000000", AiKeyVerificationKind.LIVE), DisabledAiKeyVerifier())
        val opened = mutableListOf<Reply>(); val urls = mutableListOf<String>(); val queue = java.util.ArrayDeque(replies.toList())
        val client = DeepSeekHttpClient(usage) { url -> synchronized(this) { urls.add(url.toString()); queue.removeFirst().also { opened.add(it) } } }
        init { demo.attachDirect(DirectHomeIntegration(demo, keys, catalog, false,
            { current -> DirectAiService(keys, usage, client, current) }, client::cancelPending)) }
        suspend fun ready() { keys.refresh(); demo.saveSetup(AiDemoSetup()); demo.selectDirectMode(true) }
        suspend fun call(mode: AiMode = AiMode.FOR_YOU) = demo.recommendWithMetadata(mode, if (mode == AiMode.QUERY) "科幻" else "", MockScenario.DIRECT)
    }
    private suspend fun problem(code: String, block: suspend () -> Unit) { assertEquals(code, (runCatching { block() }.exceptionOrNull() as WorkerProblem).code) }
    @Test fun actualDirectServiceUsesBearerAndMinimalSchemaThenCacheAndHistoryAreFree() = runBlocking<Unit> {
        val r = Reply(text = completion); val f = Fixture(r); f.ready()
        f.demo.saveSetup(AiDemoSetup(preferences = mapOf("喜剧" to PreferenceDirection.DISLIKE)))
        val first = f.call(); assertTrue(first.items.isNotEmpty()); assertTrue(first.items.all { it.topic != "喜剧" && it.candidateIdentity != null })
        assertEquals(listOf("https://api.deepseek.com/chat/completions"), f.urls)
        assertEquals("Bearer FAKE_DIRECT_CREDENTIAL_00000000", r.getRequestProperty("Authorization"))
        val body = org.json.JSONObject(r.body.toString("UTF-8")); assertEquals("deepseek-flash", body.getString("model"))
        val summary = body.getJSONArray("messages").getJSONObject(1).getString("content")
        for (privateValue in listOf("FAKE_DIRECT", "profileVersion", "sourceSettingsVersion", "mock-0", "poster", "rating")) assertFalse(summary.contains(privateValue))
        assertTrue(f.call().notice!!.contains("缓存")); assertEquals(1, f.opened.size)
        val before = f.usage.snapshot(); val management = f.demo.readManagement()
        assertEquals(2, management.history.size); assertFalse(management.quotaFixtureEditable); assertTrue(management.directMode)
        assertEquals(before, f.usage.snapshot()); assertEquals(1L, before.succeeded)
    }
    @Test fun twoTimeoutsHaveExactlyTwoHttpAttemptsAndOneTaskWithoutOuterRetry() = runBlocking<Unit> {
        val f = Fixture(Reply(text = "", timeout = true), Reply(text = "", timeout = true)); f.ready()
        assertTrue(f.call().notice!!.contains("至多重试一次")); assertEquals(2, f.opened.size)
        assertEquals(1L, f.usage.snapshot().started); assertEquals(1L, f.usage.snapshot().failed)
    }
    @Test fun transientRetrySuccessCountsOneTask() = runBlocking<Unit> {
        val f = Fixture(Reply(503, ""), Reply(text = completion)); f.ready(); assertTrue(f.call().items.isNotEmpty())
        assertEquals(2, f.opened.size); assertEquals(1L, f.usage.snapshot().succeeded)
    }
    @Test fun authenticationBillingRateAndSchemaFailuresAreMappedWithoutRetryOrInventedQueryWorks() = runBlocking<Unit> {
        for ((status, message) in listOf(401 to "Key", 403 to "权限", 402 to "计费", 429 to "受限", 200 to "格式无效")) {
            val f = Fixture(Reply(status, "{}")); f.ready(); val result = f.call(AiMode.QUERY)
            assertTrue(result.items.isEmpty()); assertTrue(result.notice!!.contains(message)); assertEquals(1, f.opened.size)
            assertEquals(0L, f.usage.snapshot().succeeded); assertEquals(1L, f.usage.snapshot().failed)
        }
    }
    @Test fun missingAndDeletedKeysBlockEvenPreviouslyCachedResultsWithoutHttp() = runBlocking<Unit> {
        val f = Fixture(Reply(text = completion)); f.ready(); f.call(); val history = f.demo.readManagement().history
        f.keys.delete(); problem("KEY_REQUIRED") { f.call() }; assertEquals(1, f.opened.size); assertEquals(history, f.demo.readManagement().history)
    }
    @Test fun dailyQuotaRefreshBlocksUncachedTasksButAllowsCacheAndNextLocalDay() = runBlocking<Unit> {
        val f = Fixture(Reply(text = completion), Reply(text = completion)); f.ready(); f.call(); repeat(29) { f.usage.task {} }
        assertTrue(f.call().notice!!.contains("缓存")); assertTrue(f.call(AiMode.TONIGHT).notice!!.contains("软额度不可用")); assertEquals(1, f.opened.size)
        f.day = f.day.plusDays(1); f.call(AiMode.TONIGHT); assertEquals(2, f.opened.size); assertEquals(1L, f.usage.snapshot().succeeded)
    }
    @Test fun usageStorageFailureStopsNewHttpButLeavesFreeManagementAvailable() = runBlocking<Unit> {
        val f = Fixture(); f.ready(); f.usageStore.failWrites = true
        assertTrue(f.call().notice!!.contains("软额度不可用")); assertTrue(f.opened.isEmpty())
        assertTrue(f.demo.readManagement().localUsageUnavailable); f.demo.setManagementPreference("科幻", PreferenceDirection.LIKE)
        assertEquals(PreferenceDirection.LIKE, f.demo.readSetup().preferences["科幻"])
    }
    @Test fun concurrentSubmissionIsRejectedAndDeletionDisconnectsPendingWithoutWritingHistory() = runBlocking<Unit> {
        val r = Reply(text = completion, held = true); val f = Fixture(r); f.ready()
        val first = async(Dispatchers.Default) { runCatching { f.call() } }
        assertTrue(withContext(Dispatchers.IO) { r.started.await(5, TimeUnit.SECONDS) })
        problem("REQUEST_IN_PROGRESS") { f.call() }; f.keys.delete(); assertTrue(first.await().isFailure)
        assertTrue(r.disconnected); assertTrue(f.demo.readManagement().history.isEmpty()); assertEquals(1, f.opened.size)
    }
    @Test fun profileChangeDisconnectsPendingAndRejectsLateHistory() = runBlocking<Unit> {
        val r = Reply(text = completion, held = true); val f = Fixture(r); f.ready()
        val first = async(Dispatchers.Default) { runCatching { f.call() } }; assertTrue(withContext(Dispatchers.IO) { r.started.await(5, TimeUnit.SECONDS) })
        f.demo.setManagementSources(ProfileSourceSettings(false, false, false)); assertTrue(first.await().isFailure)
        assertTrue(r.disconnected); assertTrue(f.demo.readManagement().history.isEmpty())
    }
    @Test fun debugFactoryKeepsIdentityUsesEncryptedMockKeyAndNeverIncrementsActualUsage() = runBlocking<Unit> {
        val file = File(context.noBackupFilesDir, "a8-direct-" + java.util.UUID.randomUUID() + ".enc")
        val store = AndroidKeystoreAiKeyStore(file); val keys = AiKeyConfigurationRepository(store, AiHomeRepositoryFactory.verifier(context))
        try {
            keys.refresh(); keys.verifyAndSave("TEST_ONLY_BYOK_a8")
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("TEST_ONLY_BYOK_a8"))
            val usage = LocalAiUsageRepository(context, AiTestStore()); val demo = MockAiHomeRepository(context, AiTestStore(), localUsage = usage)
            val home = AiHomeRepositoryFactory.create(demo, MockCandidateCatalogRepository(), context, keys)
            assertSame(demo, home); assertTrue(home.directDemoAvailable); assertFalse(home.workerDemoAvailable)
            home.saveSetup(AiDemoSetup()); home.selectDirectMode(true); assertTrue(home.recommendWithMetadata(AiMode.FOR_YOU, "", MockScenario.DIRECT).notice!!.contains("不联网"))
            assertEquals(0L, usage.snapshot().started); keys.delete(); problem("KEY_REQUIRED") { home.recommendWithMetadata(AiMode.FOR_YOU, "", MockScenario.DIRECT) }
        } finally { keys.delete(); file.delete() }
    }
    @Test fun resetRevokesSessionConsentAndKeepsUsageAndKey() = runBlocking<Unit> {
        val f = Fixture(Reply(text = completion)); f.ready(); f.call(); val usage = f.usage.snapshot(); val key = f.keys.state.value
        f.demo.clearManagement(AiClearAction.ALL)
        assertEquals(AiDemoSetup(), f.demo.readSetup()); assertEquals(usage, f.usage.snapshot()); assertEquals(key, f.keys.state.value)
        assertTrue(runCatching { f.call() }.isFailure); assertTrue(f.demo.readManagement().history.isEmpty())
    }
}
