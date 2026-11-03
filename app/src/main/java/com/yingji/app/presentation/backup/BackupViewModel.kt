package com.yingji.app.presentation.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.backup.BackupPreview
import com.yingji.app.data.backup.PortableBackupCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BackupUiState(val busy: Boolean = false, val committing: Boolean = false, val message: String? = null,
    val preview: BackupPreview? = null, val exportRequested: Boolean = false, val selecting: Boolean = false)

internal class BackupViewModel(private val backup: PortableBackupCoordinator) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()
    val recovery = backup.recovery
    private var operation: Job? = null
    private var pendingPassword: CharArray? = null
    private fun launch(committing: Boolean = false, action: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.selecting || mutable.value.exportRequested) return
        mutable.value = mutable.value.copy(busy = true, committing = committing, message = null)
        operation = viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { mutable.value = mutable.value.copy(message = "操作已取消；请核对当前状态，不要重复导入") }
            catch (e: Exception) { mutable.value = mutable.value.copy(message = safeFailure(e)) }
            finally { mutable.value = mutable.value.copy(busy = false, committing = false) }
        }
    }
    fun create(password: CharArray) {
        if (mutable.value.busy || mutable.value.selecting || mutable.value.exportRequested) { password.fill('\u0000'); return }
        mutable.value = mutable.value.copy(preview = null)
        launch { try { val count = backup.prepareExport(password); mutable.value = mutable.value.copy(exportRequested = true, message = "已生成并核验加密包（" + count + " 条作品），请选择新的保存位置") } finally { password.fill('\u0000') } }
    }
    fun exportLaunched() { mutable.value = mutable.value.copy(exportRequested = false, selecting = true) }
    fun write(uri: Uri?) {
        mutable.value = mutable.value.copy(selecting = false)
        launch {
        if (uri == null) { backup.discard(); mutable.value = mutable.value.copy(message = "已取消文件保存") }
        else { backup.writeExport(uri); mutable.value = mutable.value.copy(message = "备份已保存，目标文件读回核验通过") }
    }
    }
    fun selectFile(password: CharArray): Boolean {
        if (mutable.value.busy) { password.fill('\u0000'); return false }
        pendingPassword?.fill('\u0000'); pendingPassword = password; mutable.value = mutable.value.copy(selecting = true); return true
    }
    fun fileSelected(uri: Uri?) {
        mutable.value = mutable.value.copy(selecting = false)
        val secret = pendingPassword; pendingPassword = null
        if (uri == null) { secret?.fill('\u0000'); return }
        if (secret == null) { mutable.value = mutable.value.copy(message = "文件选择期间操作已中断，请重新输入备份密码并选择文件"); return }
        mutable.value = mutable.value.copy(preview = null)
        launch { try { val preview = backup.checkDocument(uri, secret); mutable.value = mutable.value.copy(preview = preview, message = "备份密码、完整性及独立验证库检查通过；尚未恢复") } finally { secret.fill('\u0000') } }
    }
    fun restore(settings: Boolean) {
        val preview = mutable.value.preview ?: return
        if (!preview.canRestore) return
        mutable.value = mutable.value.copy(preview = null)
        launch(committing = true) { mutable.value = mutable.value.copy(message = backup.restore(settings)) }
    }
    fun retrySettings() = launch { backup.retrySettings(); mutable.value = mutable.value.copy(message = "显示设置已应用") }
    fun keepSettings() = launch { backup.keepSettings(); mutable.value = mutable.value.copy(message = "资料已恢复，已选择保留本机显示设置") }
    fun cancel() { if (!mutable.value.committing) operation?.cancel() }
    fun close(done: () -> Unit) {
        if (mutable.value.busy) return
        pendingPassword?.fill('\u0000'); pendingPassword = null
        launch { backup.discard(); mutable.value = BackupUiState(); done() }
    }
    override fun onCleared() { pendingPassword?.fill('\u0000') }
    companion object {
        fun factory(backup: PortableBackupCoordinator) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(backup) as T
        }
    }
}

private fun safeFailure(e: Exception): String {
    if (e is javax.crypto.AEADBadTagException || generateSequence<Throwable>(e) { it.cause }.any { it is javax.crypto.AEADBadTagException }) return "密码错误或备份损坏；未执行新的资料导入"
    val allowed = setOf("当前资料库不是空资料库；首版不支持合并或覆盖恢复", "已有自定义或改名分类，不能恢复到此资料库", "已有真实画像设置，不能恢复到此资料库", "请先处理上次恢复", "请先完成上次恢复的核验或显示设置处理", "备份密码需为 12–256 个字符", "不支持的备份格式或加密参数", "不支持此备份版本", "本地海报缺失或不在托管目录，未生成完整备份", "备份文件超过 128 MiB 限制", "备份海报总量过大", "海报文件过大或为空", "海报无法解码或像素过大", "目标文件读回校验未通过", "目标文件可能不完整，文件提供方未确认删除；请勿使用该文件", "目标文件不是空的新文档，已保留原文件；请重新创建并选择新位置", "可用空间不足，未完成备份或恢复", "画像特征引用已不存在的分类或标签，请先检查本地画像")
    return e.message?.takeIf { it in allowed } ?: "操作未完成。原资料未被清空；请检查文件、密码、可用空间和当前恢复状态后再操作，勿重复导入"
}
