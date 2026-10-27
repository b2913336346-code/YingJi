package com.yingji.app.presentation.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LocalProfileManagementProcessRestartTest {
    @Test fun realPreferencesSourcesCorrectionsAndConfirmedResetSurviveThreeProcesses() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments(); val name = requireNotNull(args.getString("localProfileFixture")); val phase = requireNotNull(args.getString("localProfilePhase"))
        require(Regex("local-profile-[a-f0-9-]+[.]db").matches(name)); require(phase in setOf("prepare", "verify", "verify-reset"))
        val context = ApplicationProvider.getApplicationContext<Context>(); val hashFile = File(context.filesDir, name + ".sha")
        if (phase == "prepare") check(!context.getDatabasePath(name).exists() && !hashFile.exists()) else check(context.getDatabasePath(name).isFile && hashFile.isFile)
        DatabaseHealthMonitor.resetForTesting(); val f = LocalProfileTestFixture(name); f.keepPoster = phase != "verify-reset"
        val models = ViewModelStore()
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun rawHash() = MessageDigest.getInstance("SHA-256").digest(f.tables().toString().toByteArray()).joinToString("") { "%02x".format(it) }
        try {
            if (phase == "prepare") { f.seed(); f.manager.correct(f.tag, true); hashFile.writeText(rawHash()) }
            lateinit var vm: AiHomeViewModel
            main { vm = AiHomeViewModel(AiTestRepository(), localProfiles = f.manager); models.put("vm", vm) }
            withTimeout(5000) { vm.state.first { !it.initializing } }; assertEquals(AiManagementScope.MOCK, vm.state.value.managementScope); assertNull(vm.state.value.localProfile.pendingRemoval)
            main { vm.openManagement() }; withTimeout(5000) { vm.state.first { !it.managementLoading } }
            main { vm.selectManagementScope(AiManagementScope.LOCAL_PROFILE) }; withTimeout(5000) { vm.state.first { it.localProfile.snapshot != null && !it.localProfile.loading } }
            if (phase != "verify-reset") {
                val value = vm.state.value.localProfile.snapshot!!.profile
                assertEquals(ProfileSourceSettings(true, true, true), value.settings); assertEquals(2, value.explicitPreferences.size); assertTrue(value.suppressedFeatureIds.contains(f.tag.id))
                main { vm.changeLocalPreference(f.category, PreferenceDirection.LIKE) }; assertEquals(f.category, vm.state.value.localProfile.pendingRemoval)
                if (phase == "verify") {
                    main { vm.confirmLocalRemoval() }; withTimeout(5000) { vm.state.first { it.localProfile.pendingRemoval == null && !it.localProfile.saving } }
                    assertEquals(listOf(f.tag.id), f.profiles.getProfile().explicitPreferences.map { it.feature.id })
                    main { vm.requestLocalReset(); vm.confirmLocalReset() }; withTimeout(5000) { vm.state.first { !it.localProfile.pendingReset && !it.localProfile.saving } }
                    assertTrue(f.profiles.getProfile().explicitPreferences.isEmpty())
                } else assertEquals(2, f.profiles.getProfile().explicitPreferences.size)
            } else {
                val value = f.profiles.getProfile(); assertEquals(ProfileSourceSettings(), value.settings); assertTrue(value.explicitPreferences.isEmpty()); assertTrue(value.inferredPreferences.isEmpty()); assertTrue(value.suppressedFeatureIds.isEmpty()); assertTrue(f.db.aiProfileDao().feedback().isEmpty())
            }
            assertEquals(hashFile.readText(), rawHash())
            val movie = f.movies.observeAll().first().single(); assertEquals(8.5, movie.rating!!, 0.0); assertEquals(listOf(7L), f.db.categoryDao().getCategoryIdsForMovie(41)); assertEquals(listOf(7L), f.db.tagDao().getTagIdsForMovie(41)); assertEquals(1, f.db.watchHistoryDao().getForMovie(41).size)
            val poster = File(requireNotNull(movie.posterPath)); assertEquals(context.cacheDir.canonicalFile, poster.parentFile!!.canonicalFile); require(poster.name.startsWith("profile-fixture-")); assertArrayEquals(byteArrayOf(1, 2, 3, 4), poster.readBytes()); f.poster = poster
            Log.i("LocalProfileRestart", "PHASE=" + phase + "; PID=" + Process.myPid())
        } finally {
            main { models.clear() }; f.close()
            if (phase == "verify-reset") { check(context.deleteDatabase(name)); check(hashFile.delete()) }
        }
    }
}
