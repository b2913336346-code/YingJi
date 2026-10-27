package com.yingji.app.presentation.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.MockAiHomeRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AiManagementProcessRestartTest {
    @Test fun managementAndResetPersistAcrossThreeRealProcesses() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments(); val name = requireNotNull(args.getString("a7Fixture")); val phase = requireNotNull(args.getString("a7Phase"))
        require(Regex("a7-restart-[a-f0-9-]+[.]preferences_pb").matches(name)); require(phase in setOf("prepare", "verify", "verify-reset"))
        val context = ApplicationProvider.getApplicationContext<Context>(); val file = File(context.filesDir, name)
        if (phase == "prepare") check(!file.exists()) else check(file.isFile)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = MockAiHomeRepository(context, PreferenceDataStoreFactory.create(scope = scope) { file })
            if (phase == "prepare") {
                repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE)))
                repo.saveFeedback("mock-0", AiFeedback.LIKE); repo.saveFeedback("mock-1", AiFeedback.DISLIKE)
                repo.correctInference("科幻", true); repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS)
                repo.clearManagement(AiClearAction.INFERRED); repo.setMockQuota(MockQuotaScenario.EXHAUSTED)
            } else if (phase == "verify") {
                val value = repo.readManagement(); assertTrue(value.setup.completed); assertEquals(mapOf("科幻" to PreferenceDirection.DISLIKE), value.setup.preferences)
                assertEquals(ProfileSourceSettings(), value.setup.sources); assertEquals(2, value.setup.feedback.size)
                assertTrue(value.inferred.isEmpty()); assertEquals(setOf("科幻"), value.corrections); assertEquals(1, value.history.size)
                assertEquals(AiQuotaStatus.EXHAUSTED, value.quota.status); repo.clearManagement(AiClearAction.ALL)
            } else {
                val value = repo.readManagement(); assertEquals(AiDemoSetup(), value.setup); assertTrue(value.history.isEmpty()); assertTrue(value.inferred.isEmpty()); assertTrue(value.corrections.isEmpty())
                assertEquals(AiQuotaStatus.EXHAUSTED, value.quota.status)
            }
            Log.i("YingJiA7Restart", "PHASE=" + phase + "; PID=" + Process.myPid())
        } finally {
            scope.cancel(); scope.coroutineContext[Job]?.join()
            if (phase == "verify-reset") check(file.delete()) // Only the validated UUID test fixture.
        }
    }
}
