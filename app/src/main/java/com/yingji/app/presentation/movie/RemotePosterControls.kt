package com.yingji.app.presentation.movie

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun RemotePosterControls(state: RemotePosterState, onOpen: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (!state.available || state.checking) return
    OutlinedButton(onClick = onOpen, enabled = !state.dialogVisible,
        modifier = Modifier.fillMaxWidth().testTag("detail_remote_poster_open")) {
        Text(if (state.saving) "海报保存中" else if (state.loading) "正在读取海报" else "使用此海报")
    }
    if (state.dialogVisible) {
        AlertDialog(onDismissRequest = { if (!state.saving) onDismiss() },
            modifier = Modifier.testTag("detail_remote_poster_dialog"),
            title = { Text("使用此海报") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (state.loading) "正在读取对应作品的海报…" else if (state.saving) "正在保存海报…" else
                        "确认后联网读取并保存到本机；已有海报会在保存成功后替换。取消不会发起网络请求或修改数据。", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = state.confirmationPending && !state.saving && !state.loading,
                    modifier = Modifier.testTag("detail_remote_poster_confirm")) { Text("确认使用") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !state.saving,
                    modifier = Modifier.testTag("detail_remote_poster_cancel")) { Text("取消") }
            })
    }
}
