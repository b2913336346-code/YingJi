package com.yingji.app.presentation.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yingji.app.domain.ai.*

@Composable
internal fun PersonalizedConsentDialog(consent: PersonalizedConsent, mock: Boolean, cancel: () -> Unit, confirm: () -> Unit) {
    val summary = consent.summary
    fun direction(value: PreferenceDirection) = if (value == PreferenceDirection.LIKE) "喜欢" else "排除"
    AlertDialog(onDismissRequest = cancel,
        title = { Text("确认本次真实画像推荐？") },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("tmdb_personal_consent_scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(if (mock) "当前为本机协议 Mock：使用真实本地画像，接受 TEST_ONLY_ 测试 Key；不调用模型、不增加真实用量。"
                else "确认后将下列最小画像摘要通过 Android HTTPS 直连 DeepSeek。你使用自己的 Key 并承担费用；瞬时错误至多重试一次，重试可能额外收费。本地软额度不能保证账户不超支。") }
            item { Text("只处理主题名称与方向；分类/标签只按名称投影，不发送本地 ID、作品名、评分原值、观看日期、次数或海报。观看行为不作为喜欢信号，也不外传。来源开关不等于外传授权。") }
            item { Text("明确偏好：" + summary.explicit.joinToString("；") { it.topic + " · " + direction(it.direction) }.ifEmpty { "无" }, Modifier.testTag("tmdb_personal_explicit")) }
            item { Text("已启用推断：" + summary.inferred.joinToString("；") { it.topic + " · " + direction(it.direction) + " · " + if (it.source == ProfileSource.RATING) "评分方向" else "AI 反馈方向" }.ifEmpty { "无" }, Modifier.testTag("tmdb_personal_inferred")) }
            item { Text("授权仅适用于本次操作；画像、来源、模型 Key 或候选变化后需重新确认。作品事实只来自已核验 TMDB 候选，模型不能补造作品。不保存候选缓存、历史或自动下载海报；最终入库仍需确认并填写现有表单。") }
        } },
        confirmButton = { TextButton(onClick = confirm, modifier = Modifier.testTag("tmdb_personal_consent_confirm")) { Text(if (mock) "确认并本机验证" else "确认并请求") } },
        dismissButton = { TextButton(onClick = cancel, modifier = Modifier.testTag("tmdb_personal_consent_cancel")) { Text("取消") } })
}
