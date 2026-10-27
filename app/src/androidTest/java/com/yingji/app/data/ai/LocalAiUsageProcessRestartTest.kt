package com.yingji.app.data.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class LocalAiUsageProcessRestartTest {
    @Test fun statisticsAndUnknownAttemptsSurviveARealProcessRestart()=runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments(); val name=requireNotNull(args.getString("a6Fixture")); val phase=requireNotNull(args.getString("a6Phase"))
        require(Regex("a6-restart-[a-f0-9-]+[.]preferences_pb").matches(name)); require(phase in setOf("prepare","verify"))
        val context=ApplicationProvider.getApplicationContext<Context>(); val file=File(context.filesDir,name)
        if(phase=="prepare")check(!file.exists()) else check(file.isFile)
        val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
        try {
            val store=PreferenceDataStoreFactory.create(scope=scope){file}; var failWrites=false
            val faultStore=object:androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                override val data=store.data
                override suspend fun updateData(transform:suspend(androidx.datastore.preferences.core.Preferences)->androidx.datastore.preferences.core.Preferences):androidx.datastore.preferences.core.Preferences {
                    if(failWrites)throw java.io.IOException("fixture write failure")
                    return store.updateData(transform)
                }
            }
            val repo=LocalAiUsageRepository(context,faultStore){LocalDate.of(2026,10,22)}
            if(phase=="prepare") {
                repo.task { }; assertTrue(runCatching { repo.task { throw IllegalStateException("fixture error") } }.isFailure)
                assertEquals(LocalAiUsageProblem.STORAGE,(runCatching { repo.task { failWrites=true } }.exceptionOrNull() as LocalAiUsageException).problem)
            } else {
                assertEquals(LocalAiUsageSnapshot("2026-10-22",3,1,1),repo.snapshot()); assertEquals(1L,repo.snapshot().unresolved); repo.task { }
                assertEquals(LocalAiUsageSnapshot("2026-10-22",4,2,1),repo.snapshot())
            }
            Log.i("YingJiA6Restart","PHASE="+phase+"; PID="+Process.myPid())
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); if(phase=="verify")check(file.delete()) }
    }
}
