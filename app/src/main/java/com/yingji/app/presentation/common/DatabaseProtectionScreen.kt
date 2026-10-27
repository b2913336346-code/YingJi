package com.yingji.app.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yingji.app.data.database.DatabaseProtectionUiState
import com.yingji.app.data.database.DatabaseProtectionCoordinator
import com.yingji.app.ui.common.YingJiTopBar

@Composable
fun DatabaseProtectionHost(coordinator: DatabaseProtectionCoordinator, state: DatabaseProtectionUiState) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(object : ActivityResultContracts.CreateDocument("application/zip") {
        override fun createIntent(context: android.content.Context, input: String): android.content.Intent =
            super.createIntent(context, input).putExtra(
                android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:" + android.os.Environment.DIRECTORY_DOWNLOADS,
                ),
            )
    }) { uri ->
        if (uri == null) coordinator.cancelExport()
        else scope.launch { coordinator.export(resolver, uri) }
    }
    DatabaseProtectionScreen(state) { exportLauncher.launch("yingji-preservation.zip") }
}

@Composable
fun DatabaseProtectionScreen(state: DatabaseProtectionUiState, onExport: () -> Unit) {
    var showDetails by rememberSaveable { mutableStateOf(true) }
    Scaffold(topBar = { YingJiTopBar(title = "影迹") }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("数据库保护中", style = MaterialTheme.typography.titleLarge)
            Text(state.issue?.label ?: "数据库状态尚未确认")
            Text("已暂停数据库访问。不会自动恢复、清库或重建数据库；关闭提示不会解除保护。")
            Text(if (state.busy) "正在处理，请勿重复操作" else state.backup?.message ?: "尚无可用保全记录，原文件未被应用删除或覆盖")
            state.backup?.let { Text(it.validation) }
            state.message?.let { Text(it) }
            state.persistenceWarning?.let { Text(it) }
            Button(onClick = onExport, enabled = !state.busy && state.backup?.exportable == true) {
                Text("导出保全材料")
            }
            OutlinedButton(onClick = { showDetails = true }) { Text("查看保护状态") }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("数据库保护中") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.issue?.label ?: "数据库状态尚未确认")
                    Text(state.backup?.message ?: "尚无可用保全记录；不会创建空库替代原库")
                    state.backup?.let { Text(it.validation) }
                    state.message?.let { Text(it) }
                    state.persistenceWarning?.let { Text(it) }
                    Text("保全材料可能仍包含损坏数据。关闭后仍保持保护，不会自动重试打开数据库。")
                }
            },
            confirmButton = {
                TextButton(onClick = onExport, enabled = !state.busy && state.backup?.exportable == true) {
                    Text("导出保全材料")
                }
            },
            dismissButton = { TextButton(onClick = { showDetails = false }) { Text("关闭提示") } },
        )
    }
}
