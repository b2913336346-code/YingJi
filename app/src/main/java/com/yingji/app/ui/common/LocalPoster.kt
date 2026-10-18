package com.yingji.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LocalPoster(
    path: String?,
    modifier: Modifier = Modifier,
    maxDecodeSizePx: Int = 1024,
) {
    val context = LocalContext.current
    var bitmap by remember(path, maxDecodeSizePx) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(path, maxDecodeSizePx) { mutableStateOf(!path.isNullOrBlank()) }
    LaunchedEffect(path, maxDecodeSizePx) {
        bitmap = null
        isLoading = !path.isNullOrBlank()
        if (!path.isNullOrBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    decodeSampledBitmap(
                        context = context,
                        path = path,
                        maxSizePx = maxDecodeSizePx.coerceAtLeast(1),
                    )?.asImageBitmap()
                }.getOrNull()
            }
        }
        isLoading = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap),
                contentDescription = "作品海报",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            val placeholder = when {
                path.isNullOrBlank() -> "无海报"
                isLoading -> "海报加载中"
                else -> "海报加载失败"
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = if (path.isNullOrBlank()) "暂无海报" else placeholder,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun decodeSampledBitmap(
    context: Context,
    path: String,
    maxSizePx: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeBitmap(context, path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxSizePx) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return decodeBitmap(context, path, options)
}

private fun decodeBitmap(
    context: Context,
    path: String,
    options: BitmapFactory.Options,
): Bitmap? {
    val uri = Uri.parse(path)
    return when (uri.scheme) {
        "content" -> context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        "file" -> BitmapFactory.decodeFile(uri.path, options)
        else -> BitmapFactory.decodeFile(File(path).absolutePath, options)
    }
}
