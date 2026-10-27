package com.yingji.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Keep feedback readable with the existing palette, including explicitly colored body typography. */
@Composable
fun YingJiSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val foreground = MaterialTheme.colorScheme.onSurface
        Snackbar(
            modifier = Modifier.testTag("yingji_snackbar"),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = foreground,
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = data::performAction, modifier = Modifier.testTag("yingji_snackbar_action")) {
                        Text(label, color = foreground)
                    }
                }
            },
            dismissAction = if (data.visuals.withDismissAction) {
                {
                    IconButton(onClick = data::dismiss, modifier = Modifier.testTag("yingji_snackbar_dismiss")) {
                        Icon(Icons.Default.Close, contentDescription = "关闭提示", tint = foreground)
                    }
                }
            } else null,
        ) {
            Text(data.visuals.message, color = foreground, modifier = Modifier.testTag("yingji_snackbar_message"))
        }
    }
}
