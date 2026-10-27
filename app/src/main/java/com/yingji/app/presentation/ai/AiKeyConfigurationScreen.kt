package com.yingji.app.presentation.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingji.app.domain.ai.*
import com.yingji.app.ui.common.YingJiTopBar
import java.text.DateFormat
import java.util.Date

@Composable
fun AiKeyConfigurationScreen(viewModel: AiKeyConfigurationViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val action by viewModel.actionState.collectAsStateWithLifecycle()
    // Input is intentionally neither rememberSaveable nor part of ViewModel/SavedStateHandle.
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val busy = state.verifying || action.deleting
    BackHandler(busy) { }
    Scaffold(topBar = { YingJiTopBar("AI 模型配置", navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("ai_key_scroll"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("DeepSeek", style = MaterialTheme.typography.titleMedium) }
            item { Text(if (viewModel.verificationDirectApi) when (viewModel.verificationKind) {
                AiKeyVerificationKind.MOCK -> "直连协议 Mock 验证：仅接受虚构测试 Key，全部在本机执行，不发送网络请求、不产生模型费用；真实验证尚未启用。个性化 Mock 可使用经逐次确认的本地画像摘要，仍不向外发送。"
                AiKeyVerificationKind.LIVE -> "仅在主动点击验证后，通过 HTTPS 将 Key 直接发送给 DeepSeek 官方 api.deepseek.com，查询账户可用状态；验证不发送画像或生成推荐，也不保证后续模型请求一定成功。实网个性化推荐须另行逐次确认后，才发送最小画像摘要；API 费用由你的 DeepSeek 账户承担，重试可能额外收费。"
                null -> "正式验证未启用；直连网络层已准备，启用并主动验证前不会发送或保存新的 Key。本机 Mock 可独立使用。"
            } else when (viewModel.verificationKind) {
                AiKeyVerificationKind.MOCK -> "Mock 配置验证：仅验证虚构测试 Key，不产生模型费用。通过本地 Worker 验证，需先在测试服务批准设备公钥；真实验证尚未接入。"
                AiKeyVerificationKind.LIVE -> "点击验证会把所输入的 Key 临时发送至受控 Worker：" + viewModel.verificationWorkerOrigin.orEmpty() + "。请确认你信任该部署方。此操作仅验证账户鉴权，不发送画像或生成推荐；首页推荐仍为 Mock。"
                null -> "正式验证未启用；配置受控 Worker 并确认启用前，不会发送或保存新的 Key。"
            }) }
            item { Text(when {
                state.verifying -> "验证中"
                state.storage == AiKeyStorageStatus.CONFIGURED && viewModel.verificationKind != null && state.kind != viewModel.verificationKind -> "已保存的 Key 属于其他验证模式，请在当前模式重新验证并保存"
                state.error != null -> "验证或保存失败；已有配置是否可用以最近保存状态为准"
                state.storage == AiKeyStorageStatus.CONFIGURED -> if (state.kind == AiKeyVerificationKind.MOCK) "已验证（仅 Mock）" else "已验证（上次验证成功）"
                state.storage == AiKeyStorageStatus.RECOVERY_REQUIRED -> "密钥无法解密，请重新配置"
                state.storage == AiKeyStorageStatus.READ_FAILED -> "密钥读取失败，请重试"
                state.storage == AiKeyStorageStatus.LOADING -> "正在读取配置"
                else -> "未配置"
            }, Modifier.testTag("ai_key_status")) }
            item { Text("最近验证：" + (state.lastVerifiedAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "暂无"), Modifier.testTag("ai_key_verified_at")) }
            item { Text("已保存的 Key 不回显；更换时输入新 Key，验证成功并保存后才替换旧值。", style = MaterialTheme.typography.bodySmall) }
            item {
                OutlinedTextField(input, { input = it }, label = { Text(if (state.storage == AiKeyStorageStatus.CONFIGURED) "新的 DeepSeek Key" else "DeepSeek Key") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("ai_key_input"),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = { TextButton(onClick = { visible = !visible }, enabled = !busy, modifier = Modifier.testTag("ai_key_visibility")) { Text(if (visible) "隐藏" else "显示") } })
            }
            item {
                OutlinedButton(onClick = { viewModel.verifyAndSave(input) { input = ""; visible = false } },
                    enabled = !busy && state.storage != AiKeyStorageStatus.LOADING && viewModel.verificationKind != null,
                    modifier = Modifier.testTag("ai_key_verify_save")) { Text((if (state.storage == AiKeyStorageStatus.CONFIGURED) "验证并更换" else "验证并保存") + if (viewModel.verificationKind == AiKeyVerificationKind.MOCK) "（Mock）" else "") }
            }
            if (state.storage == AiKeyStorageStatus.CONFIGURED || state.storage == AiKeyStorageStatus.RECOVERY_REQUIRED || state.storage == AiKeyStorageStatus.READ_FAILED) item {
                TextButton(onClick = viewModel::requestDelete, enabled = !action.deleting, modifier = Modifier.testTag("ai_key_delete")) { Text("删除本地 Key") }
            }
            if (state.storage == AiKeyStorageStatus.READ_FAILED) item { TextButton(onClick = viewModel::refresh, enabled = !busy, modifier = Modifier.testTag("ai_key_retry")) { Text("重试读取") } }
            action.message?.let { message -> item { Text(message, Modifier.testTag("ai_key_message")) } }
            item { Text(if (viewModel.verificationDirectApi) "费用与直连 API" else "费用与可信代理", style = MaterialTheme.typography.titleMedium) }
            item { Text(if (viewModel.verificationDirectApi) "DeepSeek API 费用由你的 DeepSeek 账户承担；重试可能产生额外费用。本地次数提醒不是供应商强制额度或账户费用上限，不能保证不超支。" else "接入真实服务后，DeepSeek API 费用由你的 DeepSeek 账户承担；Worker 平台费用由部署账户承担。主动验证或推荐可能产生模型费用。Worker 任务额度不是 DeepSeek 账户余额或官方费用上限。") }
            item { Text(if (viewModel.verificationDirectApi) "调用链为 Android → HTTPS → DeepSeek，不需要 Worker 或设备公钥批准。仅访问固定官方主机，不支持自定义服务器地址。Mock 不联网；实网个性化推荐仅在逐次确认后发送最小画像摘要，来源开关不等于外传授权。" else "调用链为 Android → 受控 Worker → DeepSeek。Worker 在转发时能接触原始 Key，需要信任代理部署方；此方案不属于端到端保密。") }
            item { Text("Key 使用 Android Keystore 支持的加密机制保存在本机非备份区域；加密降低静态泄露风险，不能保证运行时绝对不可提取。备份恢复无法解密时需重新配置；普通作品、海报和其他 AI 数据保留。") }
        }
    }
    if (action.confirmDelete) AlertDialog(onDismissRequest = viewModel::cancelDelete,
        title = { Text("删除本地 Key？") }, text = { Text("删除后禁止新的模型调用；作品、画像、推荐历史和缓存均保留。") },
        confirmButton = { TextButton(onClick = { viewModel.delete(); input = ""; visible = false }, enabled = !action.deleting, modifier = Modifier.testTag("ai_key_delete_confirm")) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = viewModel::cancelDelete, enabled = !action.deleting, modifier = Modifier.testTag("ai_key_delete_cancel")) { Text("取消") } })
}
