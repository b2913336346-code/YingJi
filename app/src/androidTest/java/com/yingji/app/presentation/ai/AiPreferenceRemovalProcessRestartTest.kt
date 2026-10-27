package com.yingji.app.presentation.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.MockAiHomeRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AiPreferenceRemovalProcessRestartTest {
    @Test fun unconfirmedRemovalIsNotPersistedAndConfirmedRemovalSurvivesNextProcess()=runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments();val name=requireNotNull(args.getString("a7RemovalFixture"));val phase=requireNotNull(args.getString("a7RemovalPhase"))
        require(Regex("a7-removal-[a-f0-9-]+[.]preferences_pb").matches(name));require(phase in setOf("prepare","confirm","verify"))
        val context=ApplicationProvider.getApplicationContext<Context>();val file=File(context.filesDir,name)
        if(phase=="prepare")check(!file.exists()) else check(file.isFile)
        val scope=CoroutineScope(Dispatchers.IO+SupervisorJob());val models=ViewModelStore()
        fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        try {
            val repo=MockAiHomeRepository(context,PreferenceDataStoreFactory.create(scope=scope){file})
            if(phase=="prepare"){
                repo.saveSetup(AiDemoSetup(preferences=mapOf("科幻" to PreferenceDirection.LIKE)));repo.saveFeedback("mock-0",AiFeedback.LIKE)
                repo.recommend(AiMode.FOR_YOU,"",MockScenario.SUCCESS);repo.setMockQuota(MockQuotaScenario.EXHAUSTED)
            }
            lateinit var vm:AiHomeViewModel;main{vm=AiHomeViewModel(repo);models.put("vm",vm)}
            withTimeout(5000){vm.state.first{!it.initializing}};assertNull(vm.state.value.pendingPreferenceRemoval)
            main{vm.openManagement()};withTimeout(5000){vm.state.first{it.management!=null&&!it.managementLoading}}
            if(phase!="verify"){
                assertEquals(PreferenceDirection.LIKE,repo.readSetup().preferences["科幻"])
                main{vm.changeManagementPreference("科幻",PreferenceDirection.LIKE)};assertEquals("科幻",vm.state.value.pendingPreferenceRemoval)
                if(phase=="confirm"){
                    main{vm.confirmPreferenceRemoval()};withTimeout(5000){vm.state.first{it.pendingPreferenceRemoval==null&&!it.managementSaving}}
                    assertTrue(repo.readSetup().preferences.isEmpty())
                } else assertEquals(PreferenceDirection.LIKE,repo.readSetup().preferences["科幻"])
            } else {
                assertTrue(repo.readSetup().preferences.isEmpty());assertNull(vm.state.value.pendingPreferenceRemoval)
                val snapshot=repo.readManagement();assertEquals(1,snapshot.setup.feedback.size);assertEquals(1,snapshot.inferred.size);assertEquals(1,snapshot.history.size);assertEquals(AiQuotaStatus.EXHAUSTED,snapshot.quota.status)
            }
            Log.i("YingJiA7Removal","PHASE="+phase+"; PID="+Process.myPid())
        } finally {main{models.clear()};scope.cancel();scope.coroutineContext[Job]?.join();if(phase=="verify")check(file.delete())}
    }
}
