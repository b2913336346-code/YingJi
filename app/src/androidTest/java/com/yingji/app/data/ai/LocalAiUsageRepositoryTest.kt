package com.yingji.app.data.ai

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.ai.AiTestStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LocalAiUsageRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private suspend fun problem(expected: LocalAiUsageProblem, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error is LocalAiUsageException); assertEquals(expected, (error as LocalAiUsageException).problem)
    }
    @Test fun thirtiethSuccessPersistsAndThirtyFirstDoesNotExecute() = runBlocking<Unit> {
        val store=AiTestStore(); val repo=LocalAiUsageRepository(context,store); var calls=0
        repeat(30) { repo.task { calls++ } }; assertEquals(30,calls)
        problem(LocalAiUsageProblem.LIMIT) { repo.task { calls++ } }
        val restarted=LocalAiUsageRepository(context,store).snapshot()
        assertEquals(30,calls); assertEquals(30L,restarted.succeeded); assertEquals(30L,restarted.started); assertEquals(0,restarted.remaining)
    }
    @Test fun failedTasksCountErrorsButOneInternalRetryRemainsOneBusinessTask() = runBlocking<Unit> {
        val repo=LocalAiUsageRepository(context,AiTestStore()); var http=0
        val error=runCatching { repo.task { http++; throw IllegalStateException("private prompt and Key") } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        repo.task { http++; http++ }; val s=repo.snapshot()
        assertEquals(3,http); assertEquals(2L,s.started); assertEquals(1L,s.succeeded); assertEquals(1L,s.failed); assertEquals(29,s.remaining)
    }
    @Test fun rapidConcurrentTasksAreBlockedBeforeDispatchAndReadsDoNotFinalizePendingWork() = runBlocking<Unit> {
        val repo=LocalAiUsageRepository(context,AiTestStore()); val started=CompletableDeferred<Unit>(); val gate=CompletableDeferred<Unit>(); var calls=0
        val pending=async { repo.task { calls++; started.complete(Unit); gate.await() } }; started.await()
        repeat(10) { problem(LocalAiUsageProblem.BUSY) { repo.task { calls++ } } }
        assertEquals(1L,repo.snapshot().unresolved); assertEquals(1,calls); gate.complete(Unit); pending.await()
        assertEquals(1L,repo.snapshot().succeeded); assertEquals(0L,repo.snapshot().failed)
    }
    @Test fun dateForwardBackwardAndYearBoundaryResetOnlyThisSeparateCounter() = runBlocking<Unit> {
        var date=LocalDate.of(2026,12,31); val store=AiTestStore(); val repo=LocalAiUsageRepository(context,store) { date }
        repo.task { }; store.edit { it[stringPreferencesKey("future_unknown")]="preserve" }
        for (next in listOf(LocalDate.of(2027,1,1),LocalDate.of(2026,12,30))) {
            date=next; val s=repo.snapshot(); assertEquals(next.toString(),s.day); assertEquals(0L,s.started); assertEquals(30,s.remaining); repo.task { }
        }
        assertEquals("preserve",store.data.value[stringPreferencesKey("future_unknown")])
    }
    @Test fun taskCrossingMidnightDoesNotConsumeNewDaysAllowance() = runBlocking<Unit> {
        var date=LocalDate.of(2026,10,22); val repo=LocalAiUsageRepository(context,AiTestStore()) { date }
        repo.task { date=date.plusDays(1); assertEquals(0L,repo.snapshot().started) }
        assertEquals(0L,repo.snapshot().succeeded); assertEquals(30,repo.snapshot().remaining)
    }
    @Test fun beginWriteFailurePreventsDispatchAndCanRetryWithoutDataLoss() = runBlocking<Unit> {
        val store=AiTestStore(); val repo=LocalAiUsageRepository(context,store); repo.task { }; val before=store.data.value; var calls=0
        store.failWrites=true; problem(LocalAiUsageProblem.STORAGE) { repo.task { calls++ } }; assertEquals(0,calls); assertEquals(before,store.data.value)
        store.failWrites=false; repo.task { calls++ }; assertEquals(1,calls); assertEquals(2L,repo.snapshot().succeeded)
    }
    @Test fun failedFinalWriteSurvivesRestartAsUnknownAndReservesOneAttempt() = runBlocking<Unit> {
        val store=AiTestStore(); val repo=LocalAiUsageRepository(context,store)
        problem(LocalAiUsageProblem.STORAGE) { repo.task { store.failWrites=true } }; store.failWrites=false
        val s=LocalAiUsageRepository(context,store).snapshot(); assertEquals(1L,s.started); assertEquals(0L,s.succeeded); assertEquals(0L,s.failed); assertEquals(1L,s.unresolved); assertEquals(29,s.remaining)
        assertEquals(setOf("day","started","succeeded","failed"),store.data.value.asMap().keys.map { it.name }.toSet())
    }
    @Test fun cancellationIsRecordedAndLockIsReleasedWithoutRetry() = runBlocking<Unit> {
        val repo=LocalAiUsageRepository(context,AiTestStore()); val started=CompletableDeferred<Unit>(); var calls=0
        val task=launch { repo.task { calls++; started.complete(Unit); awaitCancellation() } }; started.await(); task.cancelAndJoin()
        assertEquals(1,calls); assertEquals(1L,repo.snapshot().failed); assertEquals(0L,repo.snapshot().unresolved)
        repo.task { calls++ }; assertEquals(2,calls); assertEquals(1L,repo.snapshot().succeeded)
    }
    @Test fun invalidStoredCountersArePreservedAndNeverDispatch() = runBlocking<Unit> {
        val store=AiTestStore(); store.edit { it[stringPreferencesKey("day")]=LocalDate.now().toString(); it[longPreferencesKey("started")]=-1 }
        val before=store.data.value; val repo=LocalAiUsageRepository(context,store)
        problem(LocalAiUsageProblem.STORAGE) { repo.task { fail("must not dispatch") } }; assertEquals(before,store.data.value)
    }
    @Test fun unavailableUsageDoesNotBlockFreeProfileHistoryOrCleanup() = runBlocking<Unit> {
        val store=AiTestStore(); val usage=LocalAiUsageRepository(context,store); val demo=MockAiHomeRepository(context,AiTestStore(),localUsage=usage)
        demo.saveSetup(AiDemoSetup()); store.failWrites=true
        assertTrue(demo.readManagement().localUsageUnavailable)
        assertTrue(demo.setManagementPreference("科幻",PreferenceDirection.LIKE).localUsageUnavailable)
        assertEquals(PreferenceDirection.LIKE,demo.readSetup().preferences["科幻"])
        assertTrue(demo.clearManagement(AiClearAction.HISTORY).localUsageUnavailable)
        demo.recommend(AiMode.FOR_YOU,"",MockScenario.SUCCESS)
        assertEquals(1,demo.readManagement().history.size)
    }
    @Test fun mockRecommendationsHistoryFeedbackAndEveryClearActionNeverConsumeOrResetLocalUsage() = runBlocking<Unit> {
        val usage=LocalAiUsageRepository(context,AiTestStore()); usage.task { }; val before=usage.snapshot()
        val demo=MockAiHomeRepository(context,AiTestStore(),localUsage=usage); demo.saveSetup(AiDemoSetup())
        demo.recommend(AiMode.FOR_YOU,"",MockScenario.SUCCESS); demo.saveFeedback("mock-0",AiFeedback.LIKE)
        for (action in AiClearAction.entries) { demo.readManagement(); demo.setMockQuota(MockQuotaScenario.EXHAUSTED); demo.clearManagement(action); assertEquals(before,usage.snapshot()) }
        assertEquals(before,demo.readManagement().localUsage)
    }
}
