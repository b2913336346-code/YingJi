package com.yingji.app.presentation.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AiKeyActionState(val deleting: Boolean = false, val confirmDelete: Boolean = false, val message: String? = null)
class AiKeyConfigurationViewModel(private val repository: AiKeyConfigurationRepository) : ViewModel() {
    val state = repository.state
    val verificationDirectApi = repository.verificationDirectApi
    val verificationKind = repository.verificationKind
    val verificationWorkerOrigin = repository.verificationWorkerOrigin
    private val actions = MutableStateFlow(AiKeyActionState())
    val actionState = actions.asStateFlow()
    private var generation = 0L
    init { refresh() }
    fun refresh() { viewModelScope.launch { repository.refresh() } }
    fun verifyAndSave(input: String, onSaved: () -> Unit) {
        if (state.value.verifying || actions.value.deleting) return
        val current = ++generation; actions.value = actions.value.copy(message = null)
        viewModelScope.launch {
            try {
                repository.verifyAndSave(input)
                if (current == generation) { actions.value = actions.value.copy(message = when {
                    state.value.kind == AiKeyVerificationKind.LIVE && verificationDirectApi -> "已保存通过验证的 Key；仅代表上次验证成功，真实画像推荐须逐次确认摘要后发起"
                    state.value.kind == AiKeyVerificationKind.LIVE -> "已保存通过验证的 Key；仅代表上次验证成功，首页推荐仍为 Mock"
                    verificationDirectApi -> "已保存通过 Mock 验证的测试 Key；当前构建使用本机协议 Mock"
                    else -> "已保存通过 Mock 验证的测试 Key；真实服务尚未接入"
                }); onSaved() }
            } catch (e: CancellationException) { throw e
            } catch (e: AiKeyException) { if (current == generation) actions.value = actions.value.copy(message = keyProblemLabel(e.problem, verificationKind, verificationDirectApi)) }
        }
    }
    fun requestDelete() { if (!actions.value.deleting) actions.value = actions.value.copy(confirmDelete = true, message = null) }
    fun cancelDelete() { if (!actions.value.deleting) actions.value = actions.value.copy(confirmDelete = false) }
    fun delete() {
        if (actions.value.deleting || !actions.value.confirmDelete) return
        generation++; actions.value = actions.value.copy(deleting = true, message = null)
        viewModelScope.launch {
            try { repository.delete(); actions.value = AiKeyActionState(message = "本地 Key 已删除，新的模型调用已禁用") }
            catch (e: CancellationException) { throw e }
            catch (_: AiKeyException) { actions.value = actions.value.copy(deleting = false, message = "删除失败，未提示成功，请重试") }
        }
    }
    companion object {
        fun factory(repository: AiKeyConfigurationRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AiKeyConfigurationViewModel(repository) as T
        }
    }
}
fun keyProblemLabel(problem: AiKeyProblem, kind: AiKeyVerificationKind? = AiKeyVerificationKind.MOCK, directApi: Boolean = false) = when (problem) {
    AiKeyProblem.EMPTY_INPUT -> "请输入 Key，空白输入不会保存"
    AiKeyProblem.INVALID_INPUT -> "Key 格式无效，不会保存"
    AiKeyProblem.INVALID_KEY -> if (kind == AiKeyVerificationKind.MOCK) "Mock 验证失败，原有 Key 保留" else "验证失败，原有 Key 保留"
    AiKeyProblem.MOCK_ONLY -> "当前仅接受 TEST_ONLY_BYOK_ 开头的虚构测试 Key；真实 Key 不会发送或保存"
    AiKeyProblem.UNAVAILABLE -> "验证服务尚不可用，未保存或替换 Key"
    AiKeyProblem.ACCESS_DENIED -> if (directApi) "DeepSeek 权限或安全连接验证失败；原有 Key 保留" else "客户端或代理访问验证失败，请检查已批准的设备公钥；原有 Key 保留"
    AiKeyProblem.RATE_LIMITED -> "验证过于频繁，请稍后重试；原有 Key 保留"
    AiKeyProblem.ACCOUNT_UNAVAILABLE -> "账户额度不可用，原有 Key 保留"
    AiKeyProblem.NETWORK_FAILED -> if (directApi) "DeepSeek 连接暂不可用，请检查网络；原有 Key 保留" else if (kind == AiKeyVerificationKind.MOCK) "联调连接失败，请检查本地服务；原有 Key 保留" else "连接失败，请检查受控 Worker；原有 Key 保留"
    AiKeyProblem.STORAGE_FAILED -> "密钥保存失败，未替换原有 Key，请重试"
    AiKeyProblem.STALE_OPERATION -> "配置已变化，本次验证结果未保存"
    AiKeyProblem.BUSY -> "正在验证，请稍候"
}
