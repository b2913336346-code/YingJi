package com.yingji.app.presentation.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkerProcessRestartTest {
    @Test fun localVersionsAndServerQuotaSurviveThreeActualAndroidProcesses() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val id = requireNotNull(args.getString("a8Fixture")); val phase = requireNotNull(args.getString("a8Phase"))
        require(Regex("a8-restart-[a-f0-9-]{36}").matches(id)); require(phase in setOf("prepare", "verify", "verify-reset"))
        val context = ApplicationProvider.getApplicationContext<Context>(); val file = File(context.filesDir, id + ".preferences_pb")
        if (phase == "prepare") check(!file.exists()) else check(file.isFile)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val demo = MockAiHomeRepository(context, PreferenceDataStoreFactory.create(scope = scope) { file })
            val repo = WorkerDemoHomeRepository(demo, workerTestClient(id), MockCandidateCatalogRepository())
            repo.selectIntegrationMode(true)
            when (phase) {
                "prepare" -> {
                    repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE)))
                    repo.setManagementSources(ProfileSourceSettings(true, false, true)); repo.saveFeedback("mock-1", AiFeedback.LIKE)
                    val batch = repo.recommendWithMetadata(AiMode.FOR_YOU, "", MockScenario.WORKER)
                    assertEquals(2, batch.items.size); assertEquals(1, batch.quota!!.used)
                    assertEquals(ProfileToken(3, 1), demo.readIntegrationProfile().token)
                }
                "verify" -> {
                    assertEquals(ProfileToken(3, 1), demo.readIntegrationProfile().token)
                    val view = repo.readManagement(); assertEquals(1, view.history.size); assertEquals(1, view.quota.used)
                    assertEquals(AiFeedback.LIKE, view.setup.feedback["mock-1"])
                    repo.clearManagement(AiClearAction.ALL); assertEquals(ProfileToken(4, 2), demo.readIntegrationProfile().token)
                }
                else -> {
                    assertEquals(ProfileToken(4, 2), demo.readIntegrationProfile().token)
                    val view = repo.readManagement(); assertEquals(AiDemoSetup(), view.setup); assertTrue(view.history.isEmpty())
                    assertEquals(1, view.quota.used); assertEquals(29, view.quota.remaining); assertFalse(view.quotaFixtureEditable)
                }
            }
            Log.i("A8_RESTART", "phase=" + phase + " pid=" + Process.myPid())
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join() }
    }
}
