package com.yingji.app.data.ai

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.BuildConfig
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.ai.AiKeyConfigurationViewModel
import com.yingji.app.presentation.ai.AiTestStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Actual build-selected factories, in-memory records and synthetic candidates; no provider request. */
class DirectDevelopmentModeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var local: LocalProfileTestFixture
    private lateinit var usage: LocalAiUsageRepository
    private val live get() = BuildConfig.AI_DIRECT_VERIFICATION_ENABLED
    private val kind get() = if (live) AiKeyVerificationKind.LIVE else AiKeyVerificationKind.MOCK
    private val grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "Synthetic development-mode test permission", true, true, false)
    @Before fun setup() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting()
        local = LocalProfileTestFixture(); local.seed()
        usage = LocalAiUsageRepository(context, AiTestStore())
    }
    @After fun close() { local.close() }
    private fun repository(store: DirectTestKeyStore) = AiKeyConfigurationRepository(store, AiHomeRepositoryFactory.verifier(context))
    private fun recommender(keys: AiKeyConfigurationRepository) = AiHomeRepositoryFactory.personalized(local.profiles, keys, { grant }, usage, local.manager)
    private fun catalog() = CandidateCatalog(listOf(CandidateSource("tmdb", CandidateSourceKind.VERIFIED_CATALOG, "App-authored typed fixture",
        CandidateLicense(grant.state, grant.evidence, true, false, false))), listOf(
        CatalogCandidate(CandidateIdentity("tmdb", "movie:7"), "虚构开发模式候选", setOf("科幻"), 2024, 90, verifiedAt = System.currentTimeMillis())))
    private suspend fun rejected(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull() ?: throw AssertionError("Expected rejection")
    @Test fun verifierDemoAndRealProfileShareTheBuildModeAndMissingKeyNeverStartsAChargedTask() = runBlocking<Unit> {
        val keys = repository(DirectTestKeyStore()); keys.refresh()
        val demo = MockAiHomeRepository(context, AiTestStore())
        val home = AiHomeRepositoryFactory.create(demo, MockCandidateCatalogRepository(), context, keys, usage)
        val personal = recommender(keys)
        assertTrue(keys.verificationDirectApi); assertEquals(kind, keys.verificationKind)
        assertSame(demo, home); assertTrue(home.directDemoAvailable); assertFalse(home.workerDemoAvailable)
        assertEquals(!live, home.directUsesMockKey); assertEquals(!live, personal.usesMockKey)
        assertEquals("KEY_REQUIRED", (rejected { personal.prepare(catalog()) } as WorkerProblem).code)
        assertEquals(0L, usage.snapshot().started)
    }
    @Test fun testOnlyInputCanOnlyBeSavedByTheMockVerifier() = runBlocking<Unit> {
        val store = DirectTestKeyStore(); val keys = repository(store); keys.refresh()
        if (live) {
            assertEquals(AiKeyProblem.INVALID_KEY, (rejected { keys.verifyAndSave("TEST_ONLY_BYOK_development") } as AiKeyException).problem)
            assertNull(store.value); assertNull(store.kind); assertFalse(keys.state.value.canRequestLiveAi)
        } else {
            keys.verifyAndSave("TEST_ONLY_BYOK_development")
            assertEquals(AiKeyVerificationKind.MOCK, store.kind)
            assertEquals(AiKeyStorageStatus.CONFIGURED, keys.state.value.storage)
            assertFalse(keys.state.value.canRequestLiveAi)
        }
        assertEquals(0L, usage.snapshot().started)
    }
    @Test fun credentialsFromTheOtherBuildModeArePreservedButCannotAuthorizeRecommendation() = runBlocking<Unit> {
        val oldKind = if (live) AiKeyVerificationKind.MOCK else AiKeyVerificationKind.LIVE
        val store = DirectTestKeyStore("FAKE_OTHER_MODE_KEY_NO_NETWORK", oldKind)
        val keys = repository(store); keys.refresh()
        val before = local.tables()
        assertEquals("KEY_REQUIRED", (rejected { recommender(keys).prepare(catalog()) } as WorkerProblem).code)
        assertEquals("FAKE_OTHER_MODE_KEY_NO_NETWORK", store.value); assertEquals(oldKind, store.kind)
        assertEquals(before, local.tables()); assertEquals(0L, usage.snapshot().started)
    }
    @Test fun consentPreviewAndDeletingKeyBeforeConfirmationNeverStartModelUsageOrWriteLibrary() = runBlocking<Unit> {
        val store = DirectTestKeyStore(if (live) "FAKE_LIVE_KEY_NEVER_DISPATCHED" else "TEST_ONLY_BYOK_development", kind)
        val keys = repository(store); keys.refresh(); val personal = recommender(keys)
        val before = local.tables(); val catalog = catalog(); val consent = personal.prepare(catalog)
        assertEquals(keys.state.value.revision, consent.credentialRevision)
        assertTrue(consent.summary.explicit.isNotEmpty()); assertEquals(0L, usage.snapshot().started)
        keys.delete()
        assertEquals("KEY_REQUIRED", (rejected { personal.recommend(catalog, consent) { true } } as WorkerProblem).code)
        assertNull(store.value); assertEquals(before, local.tables()); assertEquals(0L, usage.snapshot().started)
    }
    @Test fun successfulDirectLiveSaveReportsPerRequestConsentInsteadOfMockOnlyBehavior() = runBlocking<Unit> {
        var calls = 0
        val service = object : DeepSeekKeyValidation {
            override suspend fun verify(key: AiSecret, isCurrent: () -> Boolean) { assertTrue(isCurrent()); calls++ }
        }
        val store = DirectTestKeyStore(); val keys = AiKeyConfigurationRepository(store, DeepSeekAiKeyVerifier(service))
        val models = ViewModelStore(); lateinit var model: AiKeyConfigurationViewModel
        val saved = CompletableDeferred<Unit>()
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        try {
            main { model = AiKeyConfigurationViewModel(keys); models.put("configuration", model) }
            withTimeout(5000) { keys.state.first { it.storage != AiKeyStorageStatus.LOADING } }
            main { model.verifyAndSave("FAKE_LIVE_VALIDATION_FIXTURE") { saved.complete(Unit) } }
            withTimeout(5000) { saved.await() }
            assertEquals(1, calls); assertEquals(AiKeyVerificationKind.LIVE, store.kind)
            val text = model.actionState.value.message.orEmpty()
            assertTrue(text.contains("逐次确认摘要")); assertTrue(text.contains("上次验证成功"))
            assertFalse(text.contains("首页推荐仍为 Mock"))
        } finally { main { models.clear() } }
    }
}
