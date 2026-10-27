package com.yingji.app.presentation.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.ai.MockAiHomeRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

internal class AiTestStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    var failWrites = false
    var writes = 0
    private val mutex = Mutex()
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
        if (failWrites) throw IOException("Injected storage failure")
        transform(data.value).also { data.value = it; writes++ }
    }
}
internal class AiTestRepository(val store: AiTestStore = AiTestStore()) : AiHomeRepository {
    val real = MockAiHomeRepository(ApplicationProvider.getApplicationContext<Context>(), store)
    var failReads = false
    var failQuotaReads = false
    var managementClears = 0
    var managementGate: CompletableDeferred<Unit>? = null
    var requests = 0
    var lastMode: AiMode? = null
    var lastInput: String? = null
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun readSetup(): AiDemoSetup {
        if (failReads) throw IOException("Injected read failure")
        return real.readSetup()
    }
    override suspend fun readQuota(): AiQuotaSnapshot {
        if (failQuotaReads) throw IOException("Injected quota failure")
        return real.readQuota()
    }
    override suspend fun readManagement(): AiManagementSnapshot {
        if (failReads) throw IOException("Injected management read failure")
        return real.readManagement()
    }
    override suspend fun setManagementPreference(topic: String, direction: PreferenceDirection?) = real.setManagementPreference(topic, direction)
    override suspend fun setManagementSources(settings: ProfileSourceSettings) = real.setManagementSources(settings)
    override suspend fun correctInference(topic: String, suppressed: Boolean) = real.correctInference(topic, suppressed)
    override suspend fun clearManagement(action: AiClearAction): AiManagementSnapshot {
        managementClears++; managementGate?.await()
        return real.clearManagement(action)
    }
    override suspend fun setMockQuota(scenario: MockQuotaScenario) = real.setMockQuota(scenario)
    override suspend fun saveSetup(setup: AiDemoSetup) = real.saveSetup(setup)
    override suspend fun saveFeedback(id: String, feedback: AiFeedback?) = real.saveFeedback(id, feedback)
    override suspend fun recommend(mode: AiMode, input: String, scenario: MockScenario): List<MockRecommendation> {
        requests++; lastMode = mode; lastInput = input
        gate?.await()
        return real.recommend(mode, input, scenario)
    }
}

/** Real device Keystore signature, synthetic in-memory model Key. Host approves the exported public pin. */
internal fun workerTestClient(session: String? = null, access: String? = null, baseUrl: String = "http://127.0.0.1:8787"): com.yingji.app.data.ai.WorkerHttpClient {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val identity = com.yingji.app.data.ai.AndroidKeystoreWorkerIdentity(context)
    val signer = com.yingji.app.domain.ai.WorkerRequestSigner(object : com.yingji.app.domain.ai.WorkerClientIdentity {
        override val clientId get() = if (access == null || access == "TEST_ONLY_ACCESS_0000000000000000000000000000") identity.clientId else access
        override fun sign(message: ByteArray) = identity.sign(message)
    })
    val keys = com.yingji.app.domain.ai.AiKeyConfigurationRepository(object : com.yingji.app.domain.ai.AiSecureKeyStore {
        override suspend fun read() = com.yingji.app.domain.ai.AiKeyRecord(com.yingji.app.domain.ai.AiSecret.fromInput("TEST_ONLY_BYOK_android_fixture"), 1, com.yingji.app.domain.ai.AiKeyVerificationKind.MOCK, "fixture")
        override suspend fun replace(key: com.yingji.app.domain.ai.AiSecret?, verifiedAt: Long?, kind: com.yingji.app.domain.ai.AiKeyVerificationKind?) = error("Read-only test fixture")
    }, com.yingji.app.domain.ai.LocalMockAiKeyVerifier())
    return com.yingji.app.data.ai.WorkerHttpClient(baseUrl, signer, keys, localDemo = true, demoSession = session)
}
