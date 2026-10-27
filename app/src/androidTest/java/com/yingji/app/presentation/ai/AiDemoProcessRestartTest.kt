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

class AiDemoProcessRestartTest {
    @Test fun completedOnboardingAndSelectionsSurviveRealProcessRestart() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val name = requireNotNull(args.getString("a3Fixture"))
        require(Regex("a3-restart-[a-f0-9-]+[.]preferences_pb").matches(name))
        val phase = requireNotNull(args.getString("a3Phase"))
        require(phase == "prepare" || phase == "verify")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, name)
        if (phase == "prepare") check(!file.exists()) else check(file.isFile)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repo = MockAiHomeRepository(context, store)
            if (phase == "prepare") {
                assertFalse(repo.readSetup().completed)
                repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE), sources = ProfileSourceSettings(true, false, false)))
                repo.saveFeedback("mock-1", AiFeedback.UNINTERESTED)
            } else {
                val restored = repo.readSetup()
                assertTrue(restored.completed)
                assertEquals(mapOf("科幻" to PreferenceDirection.DISLIKE), restored.preferences)
                assertEquals(ProfileSourceSettings(true, false, false), restored.sources)
                assertEquals(mapOf("mock-1" to AiFeedback.UNINTERESTED), restored.feedback)
                assertFalse(repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS).any { it.topic == "科幻" })
            }
            Log.i("YingJiA3Restart", "PHASE=" + phase + "; PID=" + Process.myPid())
        } finally {
            scope.cancel(); scope.coroutineContext[Job]?.join()
            if (phase == "verify") check(file.delete()) // Only this UUID fixture.
        }
    }
}
