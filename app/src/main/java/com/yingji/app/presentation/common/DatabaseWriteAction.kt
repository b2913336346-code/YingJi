package com.yingji.app.presentation.common

import kotlinx.coroutines.CancellationException

internal suspend fun runDatabaseWriteAction(
    action: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
): Boolean = try {
    action()
    true
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Exception) {
    onFailure(exception)
    false
}
