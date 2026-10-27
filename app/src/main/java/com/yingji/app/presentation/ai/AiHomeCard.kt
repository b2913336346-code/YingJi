package com.yingji.app.presentation.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yingji.app.domain.ai.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiHomeCard(state: AiHomeState, viewModel: AiHomeViewModel, sourceControls: (@Composable () -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("ai_home_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("影迹 AI · Mock 演示", style = MaterialTheme.typography.titleMedium)
            sourceControls?.invoke()
            Text(if (state.scenario == MockScenario.DIRECT) if (state.directUsesMockKey) "本机直连协议 Mock；仅处理演示摘要，不联网、不计真实用量。" else "仅向 DeepSeek 发送演示摘要；费用由你的账户承担，真实画像与作品库不外传。"
                else if (state.scenario == MockScenario.WORKER) "仅向本机测试服务发送演示摘要；不读取真实作品库、不调用真实模型。"
                else "虚构演示内容，无真实推荐；演示设置仅保存在本机，不读取作品库、不发送数据。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.keyConfiguration != null) {
                Text(if (state.keyConfiguration.storage == AiKeyStorageStatus.CONFIGURED) if (state.keyConfiguration.kind == AiKeyVerificationKind.LIVE) "模型 Key 上次验证成功；当前首页仍为 Mock 演示，不发起真实推荐。" else if (state.workerDemoAvailable) "模型配置已保存（当前仅 Mock）；可在 Mock 场景中选择本地 Worker 联调。" else if (state.directDemoAvailable) "模型配置已保存（当前仅 Mock）；可在 Mock 场景选择直连协议联调。" else "模型配置已保存（当前仅 Mock）；BYOK 联调尚未接入。" else "尚未配置可用模型 Key；本地 Mock 演示仍可使用。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai_key_home_status"))
                TextButton(onClick = viewModel::requestKeyConfiguration, modifier = Modifier.testTag("ai_key_configure")) { Text("AI 模型配置") }
            }
            when {
                state.initializing -> { CircularProgressIndicator(Modifier.size(24.dp)); Text("正在读取演示设置") }
                state.readFailed -> { Text(state.message.orEmpty()); TextButton(onClick = viewModel::load) { Text("重试读取") } }
                !state.expanded -> Button(onClick = viewModel::enter, modifier = Modifier.testTag("ai_enter"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)) { Text("开始体验") }
                else -> {
                    val busy = state.result == AiResultState.LOADING
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AiMode.FOR_YOU, AiMode.TONIGHT, AiMode.EXPLORE).forEach { mode ->
                            OutlinedButton(onClick = { viewModel.submit(mode) }, enabled = !busy && (state.quota.canRequestNewTask || state.scenario in setOf(MockScenario.WORKER, MockScenario.DIRECT)),
                                modifier = Modifier.testTag("ai_mode_" + mode.name)) { Text(mode.label) }
                        }
                    }
                    OutlinedTextField(state.input, viewModel::setInput, label = { Text("想看什么？") },
                        modifier = Modifier.fillMaxWidth().testTag("ai_input"), maxLines = 3,
                        supportingText = { Text("最多 500 字；仅演示接收输入") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = viewModel::editSetup, enabled = !busy) { Text("偏好与来源") }
                        Button(onClick = { viewModel.submit(AiMode.QUERY) }, enabled = !busy && (state.quota.canRequestNewTask || state.scenario in setOf(MockScenario.WORKER, MockScenario.DIRECT)),
                            modifier = Modifier.testTag("ai_submit"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)) { Text("提交") }
                    }
                    if (!state.quota.canRequestNewTask) Text(if (state.scenario == MockScenario.DIRECT) "本地软额度不可用；可继续使用可靠缓存、本地候选和 AI 管理。" else if (state.scenario == MockScenario.WORKER) "服务端额度不可用；仅可使用可靠缓存或本地演示候选，不发起新 AI 任务。" else "Mock 额度耗尽或暂不可用；可在 AI 管理中查看历史、修改画像或刷新额度。", modifier = Modifier.testTag("ai_home_quota_blocked"))
                    when (state.result) {
                        AiResultState.IDLE -> Text("选择快捷模式或输入后点击提交")
                        AiResultState.LOADING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp)); Text("正在加载 Mock 推荐")
                        }
                        AiResultState.SUCCESS -> Text("Mock 结果：" + state.recommendations.size + " 项，继续向下查看")
                        AiResultState.EMPTY -> Text("暂无符合条件的演示结果，不补充虚构的真实作品")
                        AiResultState.ERROR -> Text("Mock 请求失败，请重新提交")
                        AiResultState.OFFLINE -> Text("Mock 离线：没有可用的可靠推荐，请稍后重试")
                        AiResultState.QUOTA -> Text("Mock 额度已耗尽：本次仅演示提示，不涉及真实额度或费用")
                    }
                    state.message?.let { Text(it, modifier = Modifier.testTag("ai_message")) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("ai_scenario")) {
                            Text("Mock 场景：" + state.scenario.label)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MockScenario.entries.filter { (it != MockScenario.WORKER || state.workerDemoAvailable) && (it != MockScenario.DIRECT || state.directDemoAvailable) }.forEach { scenario ->
                                DropdownMenuItem(text = { Text(scenario.label) }, onClick = { viewModel.setScenario(scenario); menu = false },
                                    modifier = Modifier.testTag("ai_scenario_" + scenario.name))
                            }
                        }
                    }
                }
            }
            TextButton(onClick = viewModel::openManagement,
                enabled = !state.initializing && !state.readFailed && state.result != AiResultState.LOADING && !state.saving && state.feedbackSaving == null,
                modifier = Modifier.testTag("ai_manage_open")) { Text("AI 管理") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiRecommendationCard(item: MockRecommendation, state: AiHomeState, viewModel: AiHomeViewModel) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("ai_result_" + item.id)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text("Mock · " + item.topic, style = MaterialTheme.typography.bodySmall)
            Text(item.description)
            Text("演示理由：" + item.reason)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiFeedback.entries.forEach { feedback ->
                    FilterChip(selected = state.setup.feedback[item.id] == feedback,
                        onClick = { viewModel.feedback(item, feedback) }, label = { Text(feedback.label) },
                        enabled = state.feedbackSaving == null,
                        modifier = Modifier.testTag("ai_feedback_" + item.id + "_" + feedback.name))
                }
            }
            TextButton(onClick = { viewModel.requestAdd(item) }, modifier = Modifier.testTag("ai_add_" + item.id)) { Text("加入影迹") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiHomeDialogs(state: AiHomeState, viewModel: AiHomeViewModel, onAddMovie: (MockRecommendation) -> Unit) {
    AiManagementDialog(state, viewModel)
    if (state.directConsentPending) {
        AlertDialog(onDismissRequest = viewModel::cancelDirectConsent,
            title = { Text("确认直连协议联调范围") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本次仅处理演示偏好、启用的演示反馈、输入和筛选条件；不读取真实画像、作品、评分或观看记录。来源开关不等于外传授权。")
                Text(if (state.directUsesMockKey) "当前为本机协议 Mock：只接受 TEST_ONLY_ 测试 Key，不联网、不调用付费 API、不增加真实用量。"
                    else "确认后，你主动提交时将使用自己的 Key 直连 DeepSeek；费用由你的 DeepSeek 账户承担。失败、超时或至多一次重试仍可能收费；本地每日 30 次软额度可被绕过，不能保证不超支。")
                Text("确认仅在本次应用会话有效；切换场景、关闭应用或重启后需重新确认。作品来自可验证的演示候选，入库仍需确认并保存。")
            } },
            confirmButton = { TextButton(onClick = viewModel::confirmDirectConsent,
                modifier = Modifier.testTag("ai_direct_consent_confirm")) { Text("确认并选择") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDirectConsent,
                modifier = Modifier.testTag("ai_direct_consent_cancel")) { Text("取消") } })
    }
    state.step?.let { step ->
        AlertDialog(
            onDismissRequest = viewModel::closeSetup,
            title = { Text(if (step == 1) "1/2 · 选择偏好" else "2/2 · 画像来源") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("ai_setup_scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mock 本地演示设置；不会修改真实画像或授权外部传输。")
                    if (step == 1) {
                        Text("可不选；再次点击可撤销。排除项不会出现在演示结果中。")
                        AiDemoRules.topics.forEach { topic ->
                            Text(topic)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PreferenceDirection.entries.forEach { direction ->
                                    FilterChip(selected = state.draft.preferences[topic] == direction,
                                        onClick = { viewModel.setPreference(topic, direction) }, enabled = !state.saving,
                                        label = { Text(if (direction == PreferenceDirection.LIKE) "喜欢" else "排除") },
                                        modifier = Modifier.testTag("ai_pref_" + topic + "_" + direction.name))
                                }
                            }
                        }
                    } else {
                        val sources = state.draft.sources
                        Text("开关仅演示本地画像来源；不代表允许向外部服务发送摘要。")
                        AiSourceSwitch("个人评分", "ratings", sources.ratingsEnabled, !state.saving) { viewModel.setSources(sources.copy(ratingsEnabled = it)) }
                        AiSourceSwitch("观看行为", "behavior", sources.behaviorEnabled, !state.saving) { viewModel.setSources(sources.copy(behaviorEnabled = it)) }
                        AiSourceSwitch("AI 推荐反馈", "feedback", sources.feedbackEnabled, !state.saving) { viewModel.setSources(sources.copy(feedbackEnabled = it)) }
                        Text(if (state.setup.completed) "跳过将保留已保存的演示设置，不会提交推荐。"
                            else "跳过将保存默认值：评分关闭、观看行为关闭、AI 反馈开启，不会提交推荐。")
                    }
                    state.message?.let { Text(it, modifier = Modifier.testTag("ai_setup_error")) }
                    if (state.saving) Text("正在保存，请稍候")
                }
            },
            confirmButton = {
                TextButton(onClick = { if (step == 1) viewModel.setStep(2) else viewModel.saveSetup() },
                    enabled = !state.saving, modifier = Modifier.testTag("ai_setup_next")) { Text(if (step == 1) "下一步" else "保存并完成") }
            },
            dismissButton = {
                Row {
                    if (step == 2) TextButton(onClick = { viewModel.setStep(1) }, enabled = !state.saving) { Text("上一步") }
                    TextButton(onClick = { viewModel.saveSetup(skip = true) }, enabled = !state.saving,
                        modifier = Modifier.testTag("ai_setup_skip")) { Text("跳过") }
                }
            },
        )
    }
    state.pendingAdd?.let { item ->
        AlertDialog(onDismissRequest = viewModel::cancelAdd, title = { Text("加入 Mock 演示作品？") },
            text = { Text(item.title + "是虚构演示作品。确认后进入现有新增表单，选择分类并保存后才会写入作品库。") },
            confirmButton = { TextButton(onClick = { viewModel.confirmAdd()?.let { onAddMovie(it) } },
                modifier = Modifier.testTag("ai_add_confirm")) { Text("确认并填写") } },
            dismissButton = { TextButton(onClick = viewModel::cancelAdd) { Text("取消") } })
    }
}

@Composable
private fun AiSourceSwitch(label: String, id: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag("ai_source_" + id))
    }
}
