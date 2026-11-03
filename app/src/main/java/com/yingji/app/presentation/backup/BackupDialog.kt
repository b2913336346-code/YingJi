package com.yingji.app.presentation.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yingji.app.data.backup.BackupRecoveryState
import com.yingji.app.data.backup.PortableBackupCoordinator

@Composable
fun BackupHost(backup: PortableBackupCoordinator, onClose: () -> Unit) {
    val vm: BackupViewModel = viewModel(key = "portable-backup", factory = BackupViewModel.factory(backup))
    val state by vm.state.collectAsStateWithLifecycle()
    val recovery by vm.recovery.collectAsStateWithLifecycle()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), vm::write)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::fileSelected)
    LaunchedEffect(state.exportRequested) { if (state.exportRequested) { vm.exportLaunched(); create.launch("YingJi-backup-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().take(8) + ".yjb") } }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var applySettings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    fun secret(confirmPassword: Boolean): CharArray? {
        if (password.length !in 12..256 || confirmPassword && repeated != password) {
            formError = if (password.length !in 12..256) "备份密码需为 12–256 个字符" else "两次备份密码不一致"
            return null
        }
        val copy = password.toCharArray(); password = ""; repeated = ""; formError = null; return copy
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认恢复到空资料库？") },
        text = { Text("仅导入已检查的备份资料，不覆盖原数据库。保留现有默认分类 ID；密钥、授权及用量不迁移。显示设置" + if (applySettings) "将在资料成功后单独应用。" else "保留本机值。") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.restore(applySettings) }, modifier = Modifier.testTag("backup_restore_confirm")) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
    AlertDialog(onDismissRequest = { vm.close(onClose) }, title = { Text("备份与恢复") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).testTag("backup_scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("包含作品、分类/标签、观看记录、评分、本地海报、真实本地画像偏好及显示设置。密钥、授权、用量、推荐缓存/历史和 Mock 演示数据不备份。", style = MaterialTheme.typography.bodySmall)
                Text("备份文件默认加密；密码不是 API Key 或签名密码，不会替你保存。遗忘后可能无法找回资料。", style = MaterialTheme.typography.bodySmall)
                if (recovery.pendingSettings) {
                    Text("上次恢复资料已完成，显示设置尚未应用。不要再次导入作品。", Modifier.testTag("backup_pending_settings"))
                    OutlinedButton(onClick = vm::retrySettings, enabled = !state.busy && !state.selecting) { Text("仅重试显示设置") }
                    OutlinedButton(onClick = vm::keepSettings, enabled = !state.busy && !state.selecting) { Text("保留本机显示设置") }
                }
                OutlinedTextField(password, { password = it }, label = { Text("备份文件密码") }, singleLine = true, enabled = !state.busy && !state.selecting,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("backup_password"))
                OutlinedTextField(repeated, { repeated = it }, label = { Text("再次输入密码（创建备份时）") }, singleLine = true, enabled = !state.busy && !state.selecting,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("backup_password_repeat"))
                formError?.let { Text(it, Modifier.testTag("backup_password_error")) }
                OutlinedButton(onClick = { secret(true)?.let(vm::create) }, enabled = !state.busy && !state.selecting && !recovery.pendingSettings, modifier = Modifier.testTag("backup_create")) { Text("创建加密备份") }
                OutlinedButton(onClick = { secret(false)?.let { if (vm.selectFile(it)) open.launch(arrayOf("*/*")) } }, enabled = !state.busy && !state.selecting && !recovery.pendingSettings, modifier = Modifier.testTag("backup_check")) { Text("选择并检查备份") }
                state.preview?.let { preview ->
                    Text("文件：" + preview.fileLabel + "；校验标识：" + preview.archiveFingerprint, Modifier.testTag("backup_file"))
                    Text("将复用 " + preview.reusedCategories + " 个分类，新增 " + (preview.categories - preview.reusedCategories) + " 个分类。", style = MaterialTheme.typography.bodySmall)
                    Text("已检查：" + preview.movies + " 条作品，" + preview.categories + " 个分类，" + preview.tags + " 个标签，" + preview.posters + " 张海报", Modifier.testTag("backup_preview"))
                    Text("本机可能额外保留 " + preview.extraDefaultCategories + " 个未使用的默认分类。检查通过不代表已经恢复。", style = MaterialTheme.typography.bodySmall)
                    if (!preview.canRestore) Text(preview.reason.orEmpty(), Modifier.testTag("backup_target_blocked"))
                    Row { Checkbox(applySettings, { applySettings = it }, enabled = !state.busy && !state.selecting, modifier = Modifier.testTag("backup_apply_settings")); Text("资料成功后应用备份显示设置（默认保留本机）") }
                    OutlinedButton(onClick = { confirm = true }, enabled = preview.canRestore && !state.busy && !state.selecting, modifier = Modifier.testTag("backup_restore")) { Text("恢复到空资料库") }
                }
                if (state.busy) { CircularProgressIndicator(); Text(if (state.committing) "正在核验并完成恢复，请稍候" else "正在处理备份文件，请稍候") }
                state.message?.let { Text(it, Modifier.testTag("backup_message")) }
                if (state.busy && !state.committing) TextButton(onClick = vm::cancel) { Text("取消操作") }
                Text("首版不支持非空资料库合并或覆盖恢复，不要通过清库或卸载绕过限制。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.close(onClose) }, enabled = !state.busy && !state.selecting, modifier = Modifier.testTag("backup_close")) { Text("关闭") } })
}

@Composable
fun BackupRecoveryHost(state: BackupRecoveryState, retry: () -> Unit) {
    if (state.error == null) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
    else AlertDialog(onDismissRequest = {}, title = { Text("恢复状态待核验") }, text = { Text(state.error) },
        confirmButton = { TextButton(onClick = retry, modifier = Modifier.testTag("backup_recovery_retry")) { Text("重新核验") } })
}
