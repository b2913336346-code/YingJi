package com.yingji.app.presentation.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yingji.app.domain.ai.*

internal fun localFeatureLabel(feature: ProfileFeature): String = when (feature.id.substringBefore(':')) {
    "category" -> "分类 · " + feature.label
    "tag" -> "标签 · " + feature.label
    else -> "主题 · " + feature.label
}

@Composable
internal fun LocalProfileConfirmation(state: AiHomeState, viewModel: AiHomeViewModel): Boolean {
    val local = state.localProfile
    if (state.managementScope != AiManagementScope.LOCAL_PROFILE || (local.pendingRemoval == null && !local.pendingReset)) return false
    AlertDialog(onDismissRequest = viewModel::cancelLocalConfirmation,
        title = { Text(if (local.pendingReset) "确认重置真实本地画像？" else "确认移除真实本地偏好？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (local.pendingReset) "清除真实 AI 的明确偏好、反馈、推断及纠错；来源恢复评分关闭、观看行为关闭、AI 反馈开启。"
                else "仅移除“" + localFeatureLabel(checkNotNull(local.pendingRemoval)) + "”的明确偏好；保留原始证据和纠错，启用的推断仍可能生效。")
            Text("不会删除作品、评分、观看记录、分类、标签或海报；不会修改 Mock 演示、推荐历史、Key 或独立用量。仅在本机操作，不发送数据。")
            local.error?.let { Text(it, Modifier.testTag("real_profile_confirmation_error")) }
            if (local.saving) Text("正在保存，请稍候")
        } },
        confirmButton = { TextButton(onClick = if (local.pendingReset) viewModel::confirmLocalReset else viewModel::confirmLocalRemoval,
            enabled = !local.saving, modifier = Modifier.testTag("real_profile_confirm")) { Text("确认") } },
        dismissButton = { TextButton(onClick = viewModel::cancelLocalConfirmation, enabled = !local.saving,
            modifier = Modifier.testTag("real_profile_cancel")) { Text("取消") } })
    return true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalProfileManagementContent(local: LocalProfileManagementState, viewModel: AiHomeViewModel) {
    if (local.loading) { CircularProgressIndicator(Modifier.size(24.dp)); Text("正在读取真实本地画像") }
    local.error?.let { Text(it, Modifier.testTag("real_profile_error")) }
    local.message?.let { Text(it, Modifier.testTag("real_profile_message")) }
    if (local.saving) Text("正在保存，请稍候")
    val value = local.snapshot
    if (value == null) {
        if (!local.loading) TextButton(onClick = viewModel::loadLocalProfile, modifier = Modifier.testTag("real_profile_retry")) { Text("重试读取") }
        return
    }
    val enabled = !local.loading && !local.saving
    val profile = value.profile
    val explicit = profile.explicitPreferences.associate { it.feature.id to it.direction }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("real_profile_scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("明确偏好 · 真实本地", style = MaterialTheme.typography.titleSmall) }
        item { Text("再次点击已选偏好将请求确认移除。分类与标签使用独立标识；明确偏好优先于推断。", style = MaterialTheme.typography.bodySmall) }
        items(value.features, key = { "explicit:" + it.id }) { feature ->
            Column {
                Text(localFeatureLabel(feature))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreferenceDirection.entries.forEach { direction ->
                        FilterChip(selected = explicit[feature.id] == direction, onClick = { viewModel.changeLocalPreference(feature, direction) }, enabled = enabled,
                            label = { Text(if (direction == PreferenceDirection.LIKE) "喜欢" else "排除") },
                            modifier = Modifier.testTag("real_profile_pref_" + feature.id + "_" + direction.name))
                    }
                }
            }
        }
        item { Text("画像来源 · 仅在本机使用", style = MaterialTheme.typography.titleSmall) }
        item { Text("评分 8–10 分为正向、0–4 分为负向；其他评分中性。观看行为只记录完成/重复观看，不直接判为喜欢。来源关闭后不再参与本地推断；开关不授权外传。", style = MaterialTheme.typography.bodySmall) }
        item { LocalSource("个人评分", "ratings", profile.settings.ratingsEnabled, enabled) { viewModel.changeLocalSources(profile.settings.copy(ratingsEnabled = it)) } }
        item { LocalSource("观看行为", "behavior", profile.settings.behaviorEnabled, enabled) { viewModel.changeLocalSources(profile.settings.copy(behaviorEnabled = it)) } }
        item { LocalSource("AI 推荐反馈", "feedback", profile.settings.feedbackEnabled, enabled) { viewModel.changeLocalSources(profile.settings.copy(feedbackEnabled = it)) } }
        item { Text("推断偏好 · 真实本地", style = MaterialTheme.typography.titleSmall) }
        if (profile.inferredPreferences.isEmpty()) item { Text("暂无真实本地推断", Modifier.testTag("real_profile_inferred_empty")) }
        items(profile.inferredPreferences, key = { "inferred:" + it.feature.id + ":" + it.source.name }) { row ->
            Column(Modifier.testTag("real_profile_inferred_" + row.feature.id + "_" + row.source.name)) {
                Text(localFeatureLabel(row.feature) + " · " + when (row.direction) { PreferenceDirection.LIKE -> "偏向喜欢"; PreferenceDirection.DISLIKE -> "偏向排除"; null -> "中性证据" })
                Text("来源：" + when (row.source) { ProfileSource.RATING -> "个人评分"; ProfileSource.BEHAVIOR -> "观看行为"; ProfileSource.FEEDBACK -> "AI 反馈" } +
                    "；证据 " + row.evidenceCount + " 条；完成 " + row.completedWatches + " 次，重复 " + row.repeatWatches + " 次。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewModel.changeLocalCorrection(row.feature, true) }, enabled = enabled,
                    modifier = Modifier.testTag("real_profile_correct_" + row.feature.id + "_" + row.source.name)) { Text("纠错：不据此推断") }
            }
        }
        if (profile.suppressedFeatureIds.isNotEmpty()) item { Text("已纠错的推断", style = MaterialTheme.typography.titleSmall) }
        items(profile.suppressedFeatureIds.sorted(), key = { "correction:" + it }) { id ->
            val feature = value.features.first { it.id == id }
            TextButton(onClick = { viewModel.changeLocalCorrection(feature, false) }, enabled = enabled,
                modifier = Modifier.testTag("real_profile_undo_" + id)) { Text("撤销纠错：" + localFeatureLabel(feature)) }
        }
        item { Text("重置范围", style = MaterialTheme.typography.titleSmall) }
        item { Text("仅清除真实 AI 偏好、反馈、推断及纠错，恢复来源默认值；保留所有作品、评分、观看记录、分类、标签、海报、Mock、Key、推荐历史和用量。") }
        item { OutlinedButton(onClick = viewModel::requestLocalReset, enabled = enabled,
            modifier = Modifier.testTag("real_profile_reset")) { Text("重置真实本地画像") } }
    }
}

@Composable
private fun LocalSource(label: String, id: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag("real_profile_source_" + id))
    }
}
