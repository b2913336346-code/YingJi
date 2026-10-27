package com.yingji.app.presentation.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingji.app.domain.ai.TmdbCredentialStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiCandidateSourceControls(tmdbSelected: Boolean, enabled: Boolean, select: (Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !tmdbSelected, onClick = { select(false) }, enabled = enabled,
            label = { Text("Mock 演示") }, modifier = Modifier.testTag("ai_source_mock"))
        FilterChip(selected = tmdbSelected, onClick = { select(true) }, enabled = enabled,
            label = { Text("TMDB 只读候选") }, modifier = Modifier.testTag("ai_source_tmdb"))
    }
}

@Composable
internal fun TmdbHomeCard(viewModel: TmdbCredentialViewModel, managementAvailable: Boolean, onManage: () -> Unit, sourceControls: @Composable () -> Unit, importAvailable: Boolean = false) {
    val credentials by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val ranking by viewModel.ranking.collectAsStateWithLifecycle()
    val personalConsent by viewModel.personalizedConsent.collectAsStateWithLifecycle()
    OutlinedCard(Modifier.fillMaxWidth().testTag("ai_home_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("影迹 AI · TMDB 只读候选", style = MaterialTheme.typography.titleMedium)
            sourceControls()
            Text(if (viewModel.personalizedAvailable) "读取候选仅访问 TMDB，每次最多 6 项，不发送画像。真实本地画像推荐须另外确认摘要；模型只给方向，不生成作品事实。" else "仅主动读取时访问 TMDB；每次最多 6 项。不调用 DeepSeek，不发送画像或作品库，不代表个性化推荐。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (importAvailable) "候选结果仅存内存，不写历史、不自动保存海报。点击“加入影迹”确认并填写表单后，最终保存才新增作品和来源 ID。" else "结果仅存内存；切换来源、离开首页或修改令牌后清除。不写历史、不导入作品、不自动保存海报。",
                style = MaterialTheme.typography.bodySmall)
            Text(when (credentials.status) {
                TmdbCredentialStatus.LOADING -> "正在读取 TMDB 本地配置"
                TmdbCredentialStatus.NOT_CONFIGURED -> "未配置 TMDB 令牌；请在 AI 管理 → TMDB 数据源配置中保存"
                TmdbCredentialStatus.SAVED_UNVERIFIED -> "TMDB 令牌已本地保存；读取状态以本次结果为准"
                TmdbCredentialStatus.RECOVERY_REQUIRED -> "TMDB 令牌无法解密，请在 AI 管理中重新配置"
                TmdbCredentialStatus.READ_FAILED -> "TMDB 配置读取失败，可重试读取配置"
            }, Modifier.testTag("tmdb_home_configuration"))
            if (credentials.status in setOf(TmdbCredentialStatus.READ_FAILED, TmdbCredentialStatus.RECOVERY_REQUIRED)) {
                TextButton(onClick = viewModel::refresh, enabled = !credentials.saving, modifier = Modifier.testTag("tmdb_home_config_retry")) { Text("重试读取配置") }
            }
            OutlinedButton(onClick = viewModel::readCandidates,
                enabled = viewModel.previewAvailable && !credentials.saving && credentials.status != TmdbCredentialStatus.LOADING && preview.status != TmdbPreviewStatus.LOADING,
                modifier = Modifier.testTag("tmdb_home_read")) { Text(if (preview.status == TmdbPreviewStatus.LOADING) "正在读取" else "读取候选") }
            TmdbPreviewStatusText(preview, "tmdb_home_status")
            if (!managementAvailable) Text("演示设置暂不可用，请切回 Mock 重试后再打开 AI 管理。", Modifier.testTag("tmdb_home_management_unavailable"))
            if (viewModel.rankingAvailable) {
                Text("Mock 推荐排序仅用演示偏好和本机方向，最多显示 3 项；不读取真实画像、不调用模型、不写历史或缓存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = viewModel::rankCandidates,
                    enabled = preview.status == TmdbPreviewStatus.SUCCESS && ranking.status != TmdbPreviewStatus.LOADING && personalConsent == null && !credentials.saving,
                    modifier = Modifier.testTag("tmdb_home_rank")) { Text(if (ranking.status == TmdbPreviewStatus.LOADING) "正在本地排序" else "Mock 推荐排序") }
                Text(when (if (ranking.personalized) TmdbPreviewStatus.IDLE else ranking.status) {
                    TmdbPreviewStatus.IDLE -> "读取候选后可主动排序，不增加联网请求"
                    TmdbPreviewStatus.LOADING -> "正在按演示偏好排序已核验候选"
                    TmdbPreviewStatus.SUCCESS -> "本机 Mock 排序：" + ranking.rows.size + " 项 · 非真实画像推荐"
                    TmdbPreviewStatus.EMPTY -> "没有符合演示条件的候选，不补充作品"
                    TmdbPreviewStatus.ERROR -> ranking.message.orEmpty()
                }, Modifier.testTag("tmdb_home_rank_status"))
                if (!ranking.personalized && ranking.status != TmdbPreviewStatus.ERROR) ranking.message?.let { Text(it, Modifier.testTag("tmdb_home_rank_message")) }
            }
            if (viewModel.personalizedAvailable) {
                OutlinedButton(onClick = viewModel::requestPersonalizedRecommendation,
                    enabled = preview.status == TmdbPreviewStatus.SUCCESS && ranking.status != TmdbPreviewStatus.LOADING && personalConsent == null && !credentials.saving,
                    modifier = Modifier.testTag("tmdb_personal_recommend")) { Text(if (viewModel.personalizedUsesMockKey) "真实画像推荐 · 协议 Mock" else "真实画像推荐 · DeepSeek") }
                Text(if (ranking.personalized) when (ranking.status) {
                    TmdbPreviewStatus.LOADING -> "正在准备或处理本次真实画像推荐"
                    TmdbPreviewStatus.EMPTY -> "没有符合真实画像条件的候选，不补充作品。" + ranking.message.orEmpty()
                    TmdbPreviewStatus.SUCCESS, TmdbPreviewStatus.ERROR -> ranking.message.orEmpty()
                    TmdbPreviewStatus.IDLE -> "请审阅本次画像摘要后确认"
                } else "读取候选后可使用真实本地画像；每次单独确认，取消不请求模型。", Modifier.testTag("tmdb_personal_status"))
            }
            TextButton(onClick = onManage, enabled = managementAvailable && preview.status != TmdbPreviewStatus.LOADING,
                modifier = Modifier.testTag("ai_manage_open")) { Text("AI 管理") }
        }
    }
}
