package com.yingji.app.presentation.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yingji.app.data.ai.MemoryOnlyTmdbPosterLoader
import com.yingji.app.data.ai.TmdbPosterPreviewLoader
import com.yingji.app.domain.ai.TmdbPoster
import kotlinx.coroutines.CancellationException

internal val LocalTmdbPosterPreviewLoader = staticCompositionLocalOf<TmdbPosterPreviewLoader> { MemoryOnlyTmdbPosterLoader() }

@Composable
internal fun TmdbPosterPreview(poster: TmdbPoster, tag: String) {
    val loader = LocalTmdbPosterPreviewLoader.current
    var image by remember(poster, loader) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(poster, loader) { mutableStateOf(false) }
    LaunchedEffect(poster, loader) {
        try { image = loader.load(poster).asImageBitmap() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Box(Modifier.size(96.dp, 144.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).testTag(tag),
        contentAlignment = Alignment.Center) {
        val loaded = image
        if (loaded != null) Image(loaded, contentDescription = "在线海报预览", contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp, 144.dp).testTag(tag + "_loaded"))
        else Text(if (failed) "海报预览失败" else "海报加载中", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(tag + if (failed) "_error" else "_loading"))
    }
}
