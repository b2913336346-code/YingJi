package com.yingji.app.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.yingji.app.ui.common.YingJiSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.utils.TimeUtils
import com.yingji.app.ui.common.MinimalEmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    movie: Movie,
    histories: List<WatchHistory>,
    onBack: () -> Unit,
    onStartWatching: () -> Unit,
    onEndWatching: (WatchHistory) -> Unit,
    onUpdateTimes: (WatchHistory, Long, Long?) -> Unit,
    actionState: HistoryActionState = HistoryActionState(),
    onActionMessageConsumed: () -> Unit = {},
) {
    val activeHistory = histories.firstOrNull { it.endTime == null }
    val completedWatchNumber = histories
        .filter { it.endTime != null }
        .maxOfOrNull { it.watchNumber }
    var editingHistory by remember { mutableStateOf<WatchHistory?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionState.message) {
        actionState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            onActionMessageConsumed()
        }
    }

    Scaffold(
        snackbarHost = { YingJiSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = "观看历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = movie.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            item {
                Text(
                    text = completedWatchNumber?.let {
                        "当前刷次：${TimeUtils.watchLabel(it)}"
                    } ?: "当前刷次：尚未完成一刷",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                Button(
                    onClick = {
                        if (activeHistory == null) {
                            onStartWatching()
                        } else {
                            onEndWatching(activeHistory)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !actionState.isSaving,
                ) {
                    Text(text = if (activeHistory == null) "开始观看" else "结束观看")
                }
            }
            if (histories.isEmpty()) {
                item {
                    MinimalEmptyState(
                        message = "还没有观看记录",
                        modifier = Modifier.fillMaxWidth(),
                        graphicSize = 44.dp,
                    )
                }
            } else {
                items(histories, key = { it.id }) { history ->
                    HistoryItem(
                        history = history,
                        enabled = !actionState.isSaving,
                        onEdit = { editingHistory = history },
                    )
                }
            }
            item { Text(text = "", modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }

    editingHistory?.let { history ->
        EditHistoryDialog(
            history = history,
            onDismiss = { editingHistory = null },
            onSave = { startTime, endTime ->
                onUpdateTimes(history, startTime, endTime)
                editingHistory = null
            },
            isSaving = actionState.isSaving,
        )
    }
}

@Composable
private fun HistoryItem(
    history: WatchHistory,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (history.endTime == null) {
                        "第${history.watchNumber}次观看中"
                    } else {
                        TimeUtils.watchLabel(history.watchNumber)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = "开始：${TimeUtils.format(history.startTime)}")
                Text(text = "结束：${history.endTime?.let(TimeUtils::format) ?: "观看中"}")
                Text(
                    text = if (history.endTime == null) {
                        "时长：观看中"
                    } else {
                        "时长：${TimeUtils.formatDuration(history.duration)}"
                    },
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Filled.Edit, contentDescription = "修改时间")
            }
        }
    }
}

@Composable
private fun EditHistoryDialog(
    history: WatchHistory,
    onDismiss: () -> Unit,
    onSave: (Long, Long?) -> Unit,
    isSaving: Boolean,
) {
    var startText by remember(history.id) { mutableStateOf(TimeUtils.format(history.startTime)) }
    var endText by remember(history.id) {
        mutableStateOf(history.endTime?.let(TimeUtils::format).orEmpty())
    }
    var errorText by remember(history.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "修改观看时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text(text = "开始时间") },
                    supportingText = { Text(text = "格式：yyyy-MM-dd HH:mm") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text(text = "结束时间") },
                    supportingText = { Text(text = "观看中可留空") },
                    singleLine = true,
                )
                errorText?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val startTime = TimeUtils.parse(startText)
                    val endTime = if (endText.isBlank()) null else TimeUtils.parse(endText)
                    errorText = when {
                        startTime == null -> "开始时间格式不正确"
                        endText.isNotBlank() && endTime == null -> "结束时间格式不正确"
                        endTime != null && endTime < startTime -> "结束时间不能早于开始时间"
                        else -> null
                    }
                    if (errorText == null && startTime != null) {
                        onSave(startTime, endTime)
                    }
                },
                enabled = !isSaving,
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
    )
}
