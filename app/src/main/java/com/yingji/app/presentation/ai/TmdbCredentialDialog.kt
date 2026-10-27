package com.yingji.app.presentation.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingji.app.domain.ai.*

@Composable
fun TmdbCredentialDialog(viewModel: TmdbCredentialViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    DisposableEffect(viewModel) { onDispose { viewModel.clearPreview() } }
    // Deliberately excluded from SavedState and ViewModel: closing/recreating discards the draft.
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.cancelDelete(); viewModel.refresh() }
    val close = { if (!state.saving) { input = ""; visible = false; onClose() } }
    if (actions.confirmDelete) {
        AlertDialog(onDismissRequest = viewModel::cancelDelete,
            title = { Text("删除 TMDB 本地令牌？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅删除此设备的 TMDB 令牌；保留 DeepSeek Key、作品、画像、推荐历史、海报和 Mock 数据。")
                actions.message?.let { Text(it, Modifier.testTag("tmdb_delete_error")) }
            } },
            confirmButton = { TextButton(onClick = { viewModel.delete { input = ""; visible = false } }, enabled = !state.saving,
                modifier = Modifier.testTag("tmdb_delete_confirm")) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete, enabled = !state.saving,
                modifier = Modifier.testTag("tmdb_delete_cancel")) { Text("取消") } })
        return
    }
    AlertDialog(onDismissRequest = close, title = { Text("TMDB 数据源配置") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("tmdb_config_scroll"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("仅限非商业开发。保存令牌不联网；首页默认使用 Mock，选择 TMDB 后主动读取。候选预览、入库校验及详情页海报操作会联网。") }
                item { Text(when (state.status) {
                    TmdbCredentialStatus.LOADING -> "正在读取配置"
                    TmdbCredentialStatus.NOT_CONFIGURED -> "未配置"
                    TmdbCredentialStatus.SAVED_UNVERIFIED -> if (preview.status in setOf(TmdbPreviewStatus.SUCCESS, TmdbPreviewStatus.EMPTY)) "已本地保存 · 本次读取成功（仅此处预览）" else "已本地保存 · 尚未联网验证"
                    TmdbCredentialStatus.RECOVERY_REQUIRED -> "令牌无法解密，请重新配置"
                    TmdbCredentialStatus.READ_FAILED -> "配置读取失败，请重试"
                }, Modifier.testTag("tmdb_config_status")) }
                item { Text("使用 TMDB API 读访问令牌，不是 DeepSeek Key。已保存的令牌不回显；替换成功后才移除旧配置。", style = MaterialTheme.typography.bodySmall) }
                item {
                    OutlinedTextField(input, { input = it }, label = { Text("TMDB API 读访问令牌") }, singleLine = true,
                        enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("tmdb_token_input"),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        trailingIcon = { TextButton(onClick = { visible = !visible }, enabled = !state.saving,
                            modifier = Modifier.testTag("tmdb_token_visibility")) { Text(if (visible) "隐藏" else "显示") } })
                }
                item { OutlinedButton(onClick = { viewModel.save(input) { input = ""; visible = false } },
                    enabled = !state.saving && state.status != TmdbCredentialStatus.LOADING,
                    modifier = Modifier.testTag("tmdb_token_save")) { Text(if (state.status == TmdbCredentialStatus.SAVED_UNVERIFIED) "替换本地令牌" else "保存到本机") } }
                if (state.status !in setOf(TmdbCredentialStatus.LOADING, TmdbCredentialStatus.NOT_CONFIGURED)) item {
                    TextButton(onClick = viewModel::requestDelete, enabled = !state.saving,
                        modifier = Modifier.testTag("tmdb_token_delete")) { Text("删除 TMDB 本地令牌") }
                }
                if (state.status in setOf(TmdbCredentialStatus.READ_FAILED, TmdbCredentialStatus.RECOVERY_REQUIRED)) item {
                    TextButton(onClick = viewModel::refresh, enabled = !state.saving,
                        modifier = Modifier.testTag("tmdb_config_retry")) { Text("重试读取") }
                }
                actions.message?.let { message -> item { Text(message, Modifier.testTag("tmdb_config_message")) } }
                item { HorizontalDivider() }
                item { Text("TMDB 候选只读预览", style = MaterialTheme.typography.titleSmall) }
                item { Text("每次最多读取 6 项；先验证外部 ID 与详情。结果仅存本次弹窗内存，关闭后清除；不写历史、不导入作品、不调用 DeepSeek。每项瞬时网络错误至多重试一次。", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedButton(onClick = viewModel::readCandidates,
                    enabled = viewModel.previewAvailable && !state.saving && !actions.confirmDelete &&
                        state.status != TmdbCredentialStatus.LOADING && preview.status != TmdbPreviewStatus.LOADING,
                    modifier = Modifier.testTag("tmdb_preview_read")) { Text(if (preview.status == TmdbPreviewStatus.LOADING) "正在读取" else "读取候选") } }
                item { TmdbPreviewStatusText(preview) }
                preview.rows.forEach { row -> item(key = "preview_" + row.identity.externalId) { TmdbCandidateCard(row) } }
                item { TmdbAttribution(uriHandler = uriHandler) }
                item { Text("与 DeepSeek 分别加密存储于本机非备份区域。候选仅存内存；在线预览不保存海报，详情页确认后才保存到本机。切换来源不自动联网。", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = close, enabled = !state.saving,
            modifier = Modifier.testTag("tmdb_config_close")) { Text("关闭") } })
}
