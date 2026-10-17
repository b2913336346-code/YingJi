package com.yingji.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val MinimalMotionDuration = 280

@Composable
fun MinimalEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    graphicSize: Dp = 56.dp,
) {
    val lineColor = MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Canvas(modifier = Modifier.size(graphicSize)) {
            val strokeWidth = 1.25.dp.toPx()
            val inset = strokeWidth
            drawRoundRect(
                color = lineColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius(5.dp.toPx()),
                style = Stroke(width = strokeWidth),
            )
            drawLine(
                color = lineColor,
                start = Offset(size.width * 0.24f, size.height * 0.36f),
                end = Offset(size.width * 0.76f, size.height * 0.36f),
                strokeWidth = strokeWidth,
            )
            drawLine(
                color = lineColor,
                start = Offset(size.width * 0.24f, size.height * 0.58f),
                end = Offset(size.width * 0.62f, size.height * 0.58f),
                strokeWidth = strokeWidth,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
