package com.yingji.app.presentation.movie

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.NoTmdbPosterException
import com.yingji.app.domain.ai.TmdbPoster
import com.yingji.app.domain.ai.TmdbProblem
import com.yingji.app.domain.ai.TmdbProblemCode
import com.yingji.app.domain.usecase.RemotePosterSaveResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RemotePosterViewModelTest {
    private val store = ViewModelStore()
    private val poster = TmdbPoster("/fictionalDetail.png")
    private val loads = AtomicInteger()
    private val saves = AtomicInteger()
    private var hasSource: suspend () -> Boolean = { true }
    private var lookup: suspend () -> TmdbPoster = { poster }
    private var save: suspend (TmdbPoster) -> RemotePosterSaveResult = { RemotePosterSaveResult("/private/fictional.png", false) }
    private lateinit var vm: RemotePosterViewModel
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun ready(): RemotePosterViewModel {
        main { vm = RemotePosterViewModel({ hasSource() }, { loads.incrementAndGet(); lookup() }, { saves.incrementAndGet(); save(it) }); store.put("poster", vm); vm.enter() }
        await { !it.checking }; return vm
    }
    private suspend fun await(predicate: (RemotePosterState) -> Boolean) = withTimeout(5000) { vm.uiState.first(predicate) }
    @After fun close() { main { store.clear() } }

    @Test fun enteringAnOrdinaryWorkHasNoEntryOrOnlineSideEffects() = runBlocking {
        hasSource = { false }; ready()
        main { vm.open(); vm.confirm() }
        assertFalse(vm.uiState.value.available); assertFalse(vm.uiState.value.dialogVisible)
        assertEquals(0, loads.get()); assertEquals(0, saves.get())
    }
    @Test fun localReadFailureIsReportedWithoutAnyNetworkOrSave() = runBlocking {
        hasSource = { throw IOException("synthetic database failure") }; ready()
        assertEquals("海报来源读取失败，请重新打开详情", vm.uiState.value.message)
        assertFalse(vm.uiState.value.available); assertEquals(0, loads.get()); assertEquals(0, saves.get())
    }
    @Test fun openingAndCancellingNeverSavesAndRapidOpenStartsOneLookup() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        lookup = { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        ready(); assertEquals(0, loads.get())
        main { vm.confirm(); vm.open(); vm.open() }
        withTimeout(5000) { started.await() }; assertTrue(vm.uiState.value.loading)
        main { vm.dismiss() }; withTimeout(5000) { cancelled.await() }
        assertFalse(vm.uiState.value.dialogVisible); assertEquals(1, loads.get()); assertEquals(0, saves.get())
    }
    @Test fun onlyConfirmationSavesAndRapidConfirmationCannotDuplicateTheWrite() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        save = { assertEquals(poster, it); started.complete(Unit); finish.await(); RemotePosterSaveResult("/private/saved.png", false) }
        ready(); main { vm.open() }; await { it.poster != null }
        assertEquals(0, saves.get())
        main { vm.confirm(); vm.confirm(); vm.dismiss() }
        withTimeout(5000) { started.await() }; assertTrue(vm.uiState.value.saving); assertEquals(1, saves.get())
        finish.complete(Unit); await { it.message == "海报已保存" }
        assertFalse(vm.uiState.value.dialogVisible); assertEquals(1, saves.get())
        main { vm.consumeMessage() }; assertNull(vm.uiState.value.message)
    }
    @Test fun missingImageAndCredentialErrorsHaveClearFeedbackAndCanBeRetried() = runBlocking {
        lookup = { throw NoTmdbPosterException() }; ready(); main { vm.open() }
        await { it.message != null }; assertEquals("该作品暂无可用海报", vm.uiState.value.message)
        lookup = { throw TmdbProblem(TmdbProblemCode.KEY_REQUIRED) }; main { vm.open() }
        await { it.message?.contains("令牌") == true }; assertFalse(vm.uiState.value.dialogVisible)
        lookup = { poster }; main { vm.open() }; await { it.poster != null }
        assertEquals(3, loads.get()); assertEquals(0, saves.get())
    }
    @Test fun failedSaveHasNoSuccessMessageAndRequiresAnotherSelectionAndConfirmation() = runBlocking {
        save = { throw IOException("synthetic failed write") }; ready(); main { vm.open() }; await { it.poster != null }
        main { vm.confirm() }; await { it.message != null }
        assertEquals("海报保存未完成，请重试或重新打开详情确认", vm.uiState.value.message)
        assertFalse(vm.uiState.value.saving); assertFalse(vm.uiState.value.dialogVisible)
        main { vm.confirm() }; assertEquals(1, saves.get())
        save = { RemotePosterSaveResult("/private/retry.png", true) }
        main { vm.open() }; await { it.poster != null }; main { vm.confirm() }
        await { it.message == "海报已保存" }; assertEquals(2, saves.get())
    }
    @Test fun leavingAndReenteringDoesNotRestoreOrAutomaticallySaveAnOnlineSelection() = runBlocking {
        ready(); main { vm.open() }; await { it.poster != null }
        main { vm.leave(); vm.enter() }; await { !it.checking }
        assertNull(vm.uiState.value.poster); assertFalse(vm.uiState.value.dialogVisible)
        assertEquals(1, loads.get()); assertEquals(0, saves.get())
    }
    @Test fun aLateNonCancellableSaveCannotPublishFeedbackIntoAReenteredPage() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val ended = CompletableDeferred<Unit>()
        save = { withContext(NonCancellable) { started.complete(Unit); finish.await(); ended.complete(Unit); RemotePosterSaveResult("/private/committed.png", false) } }
        ready(); main { vm.open() }; await { it.poster != null }; main { vm.confirm() }
        withTimeout(5000) { started.await() }; main { vm.leave(); vm.enter() }; await { !it.checking }
        finish.complete(Unit); withTimeout(5000) { ended.await() }; main { }
        assertNull(vm.uiState.value.message); assertFalse(vm.uiState.value.dialogVisible)
        assertEquals(1, loads.get()); assertEquals(1, saves.get())
    }
}
