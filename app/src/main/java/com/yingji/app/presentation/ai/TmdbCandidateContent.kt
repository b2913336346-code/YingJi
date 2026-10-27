package com.yingji.app.presentation.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yingji.app.R
import com.yingji.app.domain.ai.CatalogCandidate

@Composable
internal fun TmdbPreviewStatusText(preview: TmdbPreviewState, tag: String = "tmdb_preview_status") {
    Text(when (preview.status) {
        TmdbPreviewStatus.IDLE -> "尚未读取候选"
        TmdbPreviewStatus.LOADING -> "正在校验 TMDB 候选"
        TmdbPreviewStatus.SUCCESS -> "已校验 " + preview.rows.size + " 项 · 不代表模型推荐"
        TmdbPreviewStatus.EMPTY -> "没有可验证的候选，不补充虚构作品"
        TmdbPreviewStatus.ERROR -> preview.message.orEmpty()
    }, Modifier.testTag(tag))
}

@Composable
internal fun TmdbCandidateCard(row: CatalogCandidate, prefix: String = "tmdb_preview_", onAdd: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(prefix + row.identity.externalId)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.identity.sourceId == "tmdb") row.poster?.let { poster ->
                TmdbPosterPreview(poster, prefix + row.identity.externalId + "_poster")
            }
            Text(row.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
            Text("TMDB · " + row.identity.externalId + " · " + (row.year?.toString() ?: "年份未知"), style = MaterialTheme.typography.bodySmall)
            Text(if (row.identity.externalId.startsWith("tv:")) "剧集总片长未知" else row.runtimeMinutes?.let { "片长 " + it + " 分钟" } ?: "片长未知", style = MaterialTheme.typography.bodySmall)
            Text("详情核验时间：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(row.verifiedAt)), style = MaterialTheme.typography.bodySmall)
            if (onAdd != null) TextButton(onClick = onAdd, modifier = Modifier.testTag("tmdb_add_" + row.identity.externalId)) { Text("加入影迹") }
        }
    }
}

@Composable
internal fun TmdbAttribution(prefix: String = "tmdb_credits", uriHandler: UriHandler = LocalUriHandler.current) {
    var attributionError by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("数据来源与致谢", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(prefix + "_title"))
        Image(painterResource(R.drawable.tmdb_logo), contentDescription = "TMDB 官方标识",
            modifier = Modifier.width(144.dp).aspectRatio(489.04f / 35.4f).testTag(prefix + "_logo"),
            colorFilter = ColorFilter.tint(if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color.White else Color.Black))
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(prefix + "_notice"))
        Text("本产品使用 TMDB API，但未获 TMDB 背书或认证。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            attributionError = false
            try { uriHandler.openUri("https://www.themoviedb.org") } catch (_: Exception) { attributionError = true }
        }, modifier = Modifier.testTag(prefix + "_link")) { Text("访问 TMDB") }
        if (attributionError) Text("无法打开浏览器，请稍后重试", Modifier.testTag(prefix + "_error"))
    }
}
