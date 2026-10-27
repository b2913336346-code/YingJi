package com.yingji.app.presentation.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DirectHomeProcessRestartTest {
    @Test fun encryptedKeyAndHistorySurviveButDirectConsentDoesNotSurviveProcessRestart() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments(); val name = requireNotNull(args.getString("a8DirectFixture")); val phase = requireNotNull(args.getString("a8DirectPhase"))
        require(Regex("a8-direct-[a-f0-9-]+[.]preferences_pb").matches(name)); require(phase in setOf("prepare", "verify"))
        val context = ApplicationProvider.getApplicationContext<Context>(); val data = File(context.filesDir, name); val encrypted = File(context.noBackupFilesDir, name + ".enc")
        if (phase == "prepare") check(!data.exists() && !encrypted.exists()) else check(data.isFile && encrypted.isFile)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()); val models = ViewModelStore()
        val keys = AiKeyConfigurationRepository(AndroidKeystoreAiKeyStore(encrypted), AiHomeRepositoryFactory.verifier(context))
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        try {
            val demo = MockAiHomeRepository(context, PreferenceDataStoreFactory.create(scope = scope) { data })
            AiHomeRepositoryFactory.create(demo, MockCandidateCatalogRepository(), context, keys); keys.refresh()
            if (phase == "prepare") { keys.verifyAndSave("TEST_ONLY_BYOK_a8_restart"); demo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.LIKE))) }
            lateinit var vm: AiHomeViewModel; main { vm = AiHomeViewModel(demo, keys); models.put("vm", vm) }
            withTimeout(5000) { vm.state.first { !it.initializing } }
            assertEquals(MockScenario.SUCCESS, vm.state.value.scenario); assertFalse(vm.state.value.directConsentPending)
            if (phase == "prepare") {
                main { vm.setScenario(MockScenario.DIRECT) }; assertTrue(vm.state.value.directConsentPending)
                assertTrue(demo.readManagement().history.isEmpty()); main { vm.confirmDirectConsent(); vm.submit(AiMode.FOR_YOU) }
                withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }; assertEquals(1, demo.readManagement().history.size)
            } else {
                assertEquals(AiKeyStorageStatus.CONFIGURED, keys.state.value.storage); assertEquals(AiKeyVerificationKind.MOCK, keys.state.value.kind)
                assertEquals(1, demo.readManagement().history.size); assertEquals(PreferenceDirection.LIKE, demo.readSetup().preferences["科幻"])
                assertTrue(runCatching { demo.recommendWithMetadata(AiMode.FOR_YOU, "", MockScenario.DIRECT) }.isFailure)
                main { vm.setScenario(MockScenario.DIRECT) }; assertTrue(vm.state.value.directConsentPending); main { vm.cancelDirectConsent() }
                assertEquals(MockScenario.SUCCESS, vm.state.value.scenario); assertEquals(1, demo.readManagement().history.size)
            }
            assertFalse(encrypted.readBytes().toString(Charsets.ISO_8859_1).contains("TEST_ONLY_BYOK_a8_restart"))
            Log.i("YingJiA8Direct", "PHASE=" + phase + "; PID=" + Process.myPid())
        } finally {
            main { models.clear() }; scope.cancel(); scope.coroutineContext[Job]?.join()
            if (phase == "verify") { keys.delete(); check(data.delete()); check(encrypted.delete()) }
        }
    }
}
