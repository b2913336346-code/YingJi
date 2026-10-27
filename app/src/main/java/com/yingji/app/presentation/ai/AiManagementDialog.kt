package com.yingji.app.presentation.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yingji.app.domain.ai.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiManagementDialog(state: AiHomeState, viewModel: AiHomeViewModel) {
    if (!state.managementOpen) return
    var tmdbOpen by remember { mutableStateOf(false) }
    val tmdb = viewModel.tmdbCredentials
    if (tmdbOpen && tmdb != null) {
        val configuration: TmdbCredentialViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
            key = "tmdb-configuration", factory = TmdbCredentialViewModel.factory(tmdb, viewModel.tmdbPreview))
        TmdbCredentialDialog(configuration) { tmdbOpen = false }
        return
    }
    if (LocalProfileConfirmation(state, viewModel)) return
    val removal = state.pendingPreferenceRemoval
    if (removal != null) {
        AlertDialog(onDismissRequest = viewModel::cancelPreferenceRemoval,
            title = { Text("确认移除明确偏好？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅移除本机 Mock 的“" + removal + "”明确偏好；保留演示反馈、推断、纠错、来源开关、历史和独立用量。不会删除真实画像、作品、评分、观看记录、分类、标签或海报。")
                state.managementError?.let { Text(it, Modifier.testTag("ai_manage_remove_error")) }
                if (state.managementSaving) Text("正在保存，请稍候")
            } },
            confirmButton = { TextButton(onClick = viewModel::confirmPreferenceRemoval, enabled = !state.managementSaving,
                modifier = Modifier.testTag("ai_manage_remove_confirm")) { Text("确认移除") } },
            dismissButton = { TextButton(onClick = viewModel::cancelPreferenceRemoval, enabled = !state.managementSaving,
                modifier = Modifier.testTag("ai_manage_remove_cancel")) { Text("取消") } })
        return
    }
    val clear = state.pendingClear
    if (clear != null) {
        AlertDialog(onDismissRequest = viewModel::cancelClear,
            title = { Text("确认" + clear.label + "？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(clear.scope)
                Text("仅作用于本机 Mock 演示数据。不会删除真实画像、作品、评分、观看记录、分类、标签或海报，也不会重置独立用量统计。")
                state.managementError?.let { Text(it, Modifier.testTag("ai_manage_clear_error")) }
                if (state.managementSaving) Text("正在保存，请稍候")
            } },
            confirmButton = { TextButton(onClick = viewModel::confirmClear, enabled = !state.managementSaving,
                modifier = Modifier.testTag("ai_manage_clear_confirm")) { Text("确认清理") } },
            dismissButton = { TextButton(onClick = viewModel::cancelClear, enabled = !state.managementSaving,
                modifier = Modifier.testTag("ai_manage_clear_cancel")) { Text("取消") } })
        return
    }
    AlertDialog(onDismissRequest = viewModel::closeManagement,
        title = { Text(if (state.managementScope == AiManagementScope.LOCAL_PROFILE) "AI 管理 · 真实本地画像" else "AI 管理 · Mock") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.localProfileAvailable) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiManagementScope.entries.forEach { scope ->
                    FilterChip(selected = state.managementScope == scope, onClick = { viewModel.selectManagementScope(scope) },
                        enabled = !state.managementSaving && !state.managementLoading && !state.localProfile.saving,
                        label = { Text(if (scope == AiManagementScope.MOCK) "Mock 演示" else "真实本地画像") },
                        modifier = Modifier.testTag("ai_manage_scope_" + scope.name))
                }
            }
            if (state.managementScope == AiManagementScope.LOCAL_PROFILE) {
                Text("仅管理本机真实画像；不会发送真实画像，也不会改变首页 Mock 或直连演示摘要。", style = MaterialTheme.typography.bodySmall)
                LocalProfileManagementContent(state.localProfile, viewModel)
            } else {
            Text("画像与历史为本机 Mock 演示；用量独立统计，管理操作不发送模型请求。", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiManagementTab.entries.forEach { tab ->
                    FilterChip(selected = state.managementTab == tab, onClick = { viewModel.selectManagementTab(tab) },
                        enabled = !state.managementSaving, label = { Text(tab.label) }, modifier = Modifier.testTag("ai_manage_tab_" + tab.name))
                }
            }
            if (state.managementLoading) { CircularProgressIndicator(Modifier.size(24.dp)); Text("正在读取 AI 管理数据") }
            state.managementError?.let { Text(it, Modifier.testTag("ai_manage_error")) }
            state.managementMessage?.let { Text(it, Modifier.testTag("ai_manage_message")) }
            if (state.managementSaving) Text("正在保存，请稍候")
            val snapshot = state.management
            if (!state.managementLoading && snapshot == null) {
                TextButton(onClick = viewModel::loadManagement, modifier = Modifier.testTag("ai_manage_retry")) { Text("重试读取") }
            } else if (snapshot != null) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("ai_manage_scroll"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state.managementTab) {
                        AiManagementTab.PROFILE -> profileItems(snapshot, viewModel, !state.managementSaving)
                        AiManagementTab.HISTORY -> {
                            item { Text("Mock 推荐历史 · 最近 20 次，最长 30 天；查看不发起推荐请求。") }
                            if (snapshot.history.isEmpty()) item { Text("暂无演示推荐历史", Modifier.testTag("ai_manage_history_empty")) }
                            items(snapshot.history, key = { it.id }) { row ->
                                OutlinedCard(Modifier.fillMaxWidth().testTag("ai_manage_history_" + row.id)) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Mock · " + row.mode.label + " · " + row.outcome.label)
                                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(row.createdAt)), style = MaterialTheme.typography.bodySmall)
                                        if (row.titles.isEmpty()) Text("本次没有推荐条目") else row.titles.forEach { Text(it) }
                                    }
                                }
                            }
                        }
                        AiManagementTab.CLEANUP -> {
                            item { Text("清理前逐项确认范围；取消不会修改数据。独立额度状态始终保留。") }
                            items(AiClearAction.entries, key = { it.name }) { action ->
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(action.scope, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { viewModel.requestClear(action) }, enabled = !state.managementSaving,
                                            modifier = Modifier.testTag("ai_manage_clear_" + action.name)) { Text(action.label) }
                                    }
                                }
                            }
                        }
                        AiManagementTab.QUOTA -> {
                            snapshot.localUsage?.let { localUsageItems(it) }
                            if (snapshot.localUsageUnavailable) item { Text("本地用量暂不可用，新在线任务暂停；本地推荐、画像和历史仍可用。", Modifier.testTag("ai_local_usage_error")) }
                            item { Text(if (snapshot.directMode) if (snapshot.quota.isMock) "直连协议 Mock 不联网、不计真实用量；此状态仅用于本机演示。" else "设备本地软额度，按本地日期切换重置；不是 DeepSeek 强制限额，不能保证不超支。" else if (snapshot.quotaFixtureEditable) "Mock 额度场景仅用于演示，不代表 DeepSeek 额度或账户费用；本地软额度可以被绕过。" else "本地联调额度由测试服务返回，客户端不能重置；不代表真实费用。") }
                            item { Text("状态：" + quotaLabel(snapshot.quota.status), Modifier.testTag("ai_manage_quota_status")) }
                            item { Text((if (snapshot.directMode) "本地可用状态：" else if (snapshot.quotaFixtureEditable) "演示上限：" else "测试上限：") + (snapshot.quota.limit?.toString() ?: "未知") + (if (snapshot.directMode) "；已占用：" else "；已成功：") + (snapshot.quota.used?.toString() ?: "未知") + "；剩余：" + (snapshot.quota.remaining?.toString() ?: "未知")) }
                            item { Text("重置时间：" + (snapshot.quota.resetsAt ?: if (snapshot.directMode) "按设备本地日期切换；Mock 状态不代表真实额度" else if (snapshot.quota.isMock) "演示场景不提供真实重置时间" else "测试服务未提供")) }
                            item { Text("额度耗尽或不可用时，仅禁用演示的新 AI 请求；画像管理、历史、反馈和清理仍可用。") }
                            item { TextButton(onClick = viewModel::refreshQuota, enabled = !state.managementSaving,
                                modifier = Modifier.testTag("ai_manage_quota_refresh")) { Text("刷新额度") } }
                            if (snapshot.quota.isMock && snapshot.quotaFixtureEditable) items(MockQuotaScenario.entries, key = { it.name }) { scenario ->
                                OutlinedButton(onClick = { viewModel.chooseMockQuota(scenario) }, enabled = !state.managementSaving,
                                    modifier = Modifier.testTag("ai_manage_quota_" + scenario.name)) { Text("Mock 场景：" + scenario.label) }
                            }
                        }
                    }
                }
            }
            }
        } },
        dismissButton = { if (tmdb != null) TextButton(onClick = { tmdbOpen = true },
            enabled = !state.managementLoading && !state.managementSaving && !state.localProfile.saving,
            modifier = Modifier.testTag("ai_manage_tmdb_config")) { Text("TMDB 数据源配置") } },
        confirmButton = { TextButton(onClick = viewModel::closeManagement, enabled = !state.managementSaving && !state.localProfile.saving,
            modifier = Modifier.testTag("ai_manage_close")) { Text("关闭") } })
}

private fun LazyListScope.localUsageItems(usage: LocalAiUsageSnapshot) {
    item { Text("本地用量提醒", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("ai_local_usage_title")) }
    item { Text("本地日期：" + usage.day + "；每日建议 30 次成功任务。", Modifier.testTag("ai_local_usage_day")) }
    item { Text("已发起：" + usage.started + "；已成功：" + usage.succeeded + "；错误/取消：" + usage.failed + "；进行中或结果未知：" + usage.unresolved,
        Modifier.testTag("ai_local_usage_counts")) }
    item { Text("本地可发起：" + usage.remaining + if (usage.canStart) "" else "（已达软额度或有待核查任务）", Modifier.testTag("ai_local_usage_remaining")) }
    item { Text("按设备本地日期切换重置；清除数据、修改日期或客户端可绕过。这不是 DeepSeek 强制限额，不能保证不超支。") }
    item { Text("费用由你的 DeepSeek 账户承担；失败、超时和重试也可能产生费用，重试最多一次。本地推荐、Mock、画像、历史、缓存和密钥验证不计入成功任务次数。") }
    item { Text("未完成写入的任务按结果未知保留并占用提醒次数；不保存 Key、完整提示词或错误正文。") }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.profileItems(snapshot: AiManagementSnapshot, viewModel: AiHomeViewModel, enabled: Boolean) {
    item { Text("明确偏好（演示）", style = MaterialTheme.typography.titleSmall) }
    items(AiDemoRules.topics, key = { "explicit:" + it }) { topic ->
        Column {
            Text(topic)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreferenceDirection.entries.forEach { direction ->
                    FilterChip(selected = snapshot.setup.preferences[topic] == direction,
                        onClick = { viewModel.changeManagementPreference(topic, direction) }, enabled = enabled,
                        label = { Text(if (direction == PreferenceDirection.LIKE) "喜欢" else "排除") },
                        modifier = Modifier.testTag("ai_manage_pref_" + topic + "_" + direction.name))
                }
            }
        }
    }
    item { Text("画像来源（演示）", style = MaterialTheme.typography.titleSmall) }
    item { Text("评分和观看行为开关只保存演示选择，不读取原始作品数据；推断仅来自演示反馈。", style = MaterialTheme.typography.bodySmall) }
    item { ManageSource("个人评分", "ratings", snapshot.setup.sources.ratingsEnabled, enabled) { viewModel.changeManagementSources(snapshot.setup.sources.copy(ratingsEnabled = it)) } }
    item { ManageSource("观看行为", "behavior", snapshot.setup.sources.behaviorEnabled, enabled) { viewModel.changeManagementSources(snapshot.setup.sources.copy(behaviorEnabled = it)) } }
    item { ManageSource("AI 推荐反馈", "feedback", snapshot.setup.sources.feedbackEnabled, enabled) { viewModel.changeManagementSources(snapshot.setup.sources.copy(feedbackEnabled = it)) } }
    item { Text("推断偏好（演示）", style = MaterialTheme.typography.titleSmall) }
    if (snapshot.inferred.isEmpty()) item { Text("暂无演示推断偏好", Modifier.testTag("ai_manage_inferred_empty")) }
    items(snapshot.inferred, key = { "inferred:" + it.feature.id }) { row ->
        Column(Modifier.testTag("ai_manage_inferred_" + row.feature.label)) {
            Text(row.feature.label + " · " + if (row.direction == PreferenceDirection.LIKE) "偏向喜欢" else "偏向排除")
            Text("来源：演示反馈；证据 " + row.evidenceCount + " 条。明确偏好优先。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { viewModel.changeCorrection(row.feature.label, true) }, enabled = enabled,
                modifier = Modifier.testTag("ai_manage_correct_" + row.feature.label)) { Text("纠错：不据此推断") }
        }
    }
    if (snapshot.corrections.isNotEmpty()) item { Text("已纠错的推断", style = MaterialTheme.typography.titleSmall) }
    items(snapshot.corrections.sorted(), key = { "correction:" + it }) { topic ->
        TextButton(onClick = { viewModel.changeCorrection(topic, false) }, enabled = enabled,
            modifier = Modifier.testTag("ai_manage_undo_" + topic)) { Text("撤销纠错：" + topic) }
    }
}

@Composable
private fun ManageSource(label: String, id: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag("ai_manage_source_" + id))
    }
}
private fun quotaLabel(status: AiQuotaStatus) = when (status) {
    AiQuotaStatus.AVAILABLE -> "可用"; AiQuotaStatus.EXHAUSTED -> "已耗尽"; AiQuotaStatus.BUDGET_BLOCKED -> "预算不足"
    AiQuotaStatus.BILLING_UNCONFIGURED -> "计费未配置"; AiQuotaStatus.REVIEW_REQUIRED -> "需要核查"; AiQuotaStatus.UNAVAILABLE -> "暂不可用"
}
