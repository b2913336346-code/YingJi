package com.yingji.app.presentation.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.ai.NoTmdbPosterException
import com.yingji.app.domain.ai.TmdbPoster
import com.yingji.app.domain.ai.TmdbProblem
import com.yingji.app.domain.ai.TmdbProblemCode
import com.yingji.app.domain.usecase.RemotePosterSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** All online selection state is memory-only and belongs to a single detail back-stack entry. */
data class RemotePosterState(
    val available: Boolean = false,
    val checking: Boolean = true,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val poster: TmdbPoster? = null,
    val message: String? = null,
) {
    val dialogVisible: Boolean get() = loading || poster != null || saving
}

class RemotePosterViewModel(
    private val hasSource: suspend () -> Boolean,
    private val lookup: suspend () -> TmdbPoster,
    private val save: suspend (TmdbPoster) -> RemotePosterSaveResult,
) : ViewModel() {
    private val state = MutableStateFlow(RemotePosterState())
    val uiState = state.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L

    fun enter() {
        leave()
        val epoch = generation
        state.value = RemotePosterState()
        operation = viewModelScope.launch {
            try {
                val available = hasSource()
                if (epoch == generation) state.value = RemotePosterState(available = available, checking = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == generation) state.value = RemotePosterState(checking = false, message = "海报来源读取失败，请重新打开详情")
            }
        }
    }

    fun open() {
        val current = state.value
        if (!current.available || current.checking || current.dialogVisible) return
        val epoch = generation
        state.value = current.copy(loading = true, message = null)
        operation = viewModelScope.launch {
            try {
                val poster = lookup()
                if (epoch == generation) state.value = state.value.copy(loading = false, poster = poster)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (epoch == generation) state.value = state.value.copy(loading = false, poster = null, message = lookupFailure(error))
            }
        }
    }

    fun confirm() {
        val current = state.value
        val poster = current.poster ?: return
        if (current.saving || current.loading) return
        val epoch = generation
        state.value = current.copy(saving = true, message = null)
        operation = viewModelScope.launch {
            try {
                save(poster)
                if (epoch == generation) state.value = state.value.copy(saving = false, poster = null, message = "海报已保存")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == generation) state.value = state.value.copy(saving = false, poster = null,
                    message = "海报保存未完成，请重试或重新打开详情确认")
            }
        }
    }

    fun dismiss() {
        if (state.value.saving) return
        leave()
    }

    fun leave() {
        generation++
        operation?.cancel()
        operation = null
        state.value = state.value.copy(loading = false, saving = false, poster = null, message = null)
    }

    fun consumeMessage() { state.value = state.value.copy(message = null) }

    private fun lookupFailure(error: Exception): String = when {
        error is NoTmdbPosterException -> "该作品暂无可用海报"
        error is TmdbProblem -> when (error.code) {
            TmdbProblemCode.KEY_REQUIRED, TmdbProblemCode.KEY_UNAVAILABLE, TmdbProblemCode.STALE_KEY, TmdbProblemCode.BUSY -> "TMDB 令牌不可用或已变化，请在首页重新配置后重试"
            TmdbProblemCode.UNAUTHORIZED, TmdbProblemCode.FORBIDDEN -> "TMDB 令牌或权限无效，请在首页检查配置"
            TmdbProblemCode.LICENSE_REQUIRED -> "TMDB 来源许可不可用，暂时无法读取海报"
            TmdbProblemCode.NOT_FOUND -> "该作品的 TMDB 详情已不可用"
            TmdbProblemCode.RATE_LIMITED -> "TMDB 请求受限，请稍后手动重试"
            else -> "海报读取失败，请检查网络后重试"
        }
        else -> "海报读取失败，请检查网络后重试"
    }

    companion object {
        fun factory(hasSource: suspend () -> Boolean, lookup: suspend () -> TmdbPoster,
            save: suspend (TmdbPoster) -> RemotePosterSaveResult): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = RemotePosterViewModel(hasSource, lookup, save) as T
            }
    }
}
