package com.yingji.app.data.database

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DatabaseProtectionUiState(
    val initializing: Boolean = true,
    val issue: DatabaseIssue? = null,
    val backup: DatabaseBackupRecord? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val persistenceWarning: String? = null,
)

class DatabaseProtectionCoordinator(
    private val marker: File,
    private val backups: DatabaseBackupStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(DatabaseProtectionUiState())
    val state = mutableState.asStateFlow()
    private val initializeLock = Mutex()
    private val exportLock = Mutex()
    @Volatile private var incidentHandled = false

    suspend fun initialize() = initializeLock.withLock {
        if (!mutableState.value.initializing) return@withLock
        withContext(Dispatchers.IO) {
            DatabaseHealthMonitor.bindProtection(::preserve)
            try {
                check(marker.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建保护标记目录" }
                if (!marker.exists()) writeMarker(0)
                val code = RandomAccessFile(marker, "r").use { file ->
                    check(file.length() == 1L) { "保护标记异常" }
                    file.readUnsignedByte()
                }
                if (code != 0) {
                    val issue = DatabaseIssue.entries.getOrNull(code - 1) ?: DatabaseIssue.UNKNOWN
                    incidentHandled = true
                    DatabaseHealthMonitor.restoreProtection(issue)
                    mutableState.value = DatabaseProtectionUiState(false, issue, backups.latest(),
                        message = "上次异常保护仍然有效；未重新打开原数据库")
                } else {
                    mutableState.value = DatabaseProtectionUiState(initializing = false)
                }
            } catch (error: Exception) {
                incidentHandled = true
                DatabaseHealthMonitor.restoreProtection(DatabaseIssue.ACCESS)
                mutableState.value = DatabaseProtectionUiState(false, DatabaseIssue.ACCESS,
                    message = "无法读取或持久化保护状态，已暂停数据库访问：${error.message}")
            }
        }
    }

    @Synchronized
    private fun preserve(issue: DatabaseIssue) {
        if (incidentHandled) return
        incidentHandled = true
        val markerError = runCatching { writeMarker(issue.ordinal + 1) }.exceptionOrNull()
        mutableState.value = DatabaseProtectionUiState(false, issue, busy = true,
            persistenceWarning = markerError?.let { "保护标记写入失败；无法保证跨重启保留此状态：${it.message}" })
        val record = backups.capture()
        mutableState.update { it.copy(backup = record, busy = false) }
        if (record.complete) scope.launch {
            val checked = backups.verifyWorkingCopy(record)
            mutableState.update { it.copy(backup = checked) }
        }
    }

    suspend fun export(resolver: ContentResolver, uri: Uri) {
        if (!exportLock.tryLock()) return
        try {
            mutableState.update { it.copy(busy = true, message = null) }
            withContext(Dispatchers.IO) {
                val record = checkNotNull(mutableState.value.backup) { "没有保全记录" }
                val output = checkNotNull(resolver.openOutputStream(uri, "w")) { "无法打开导出位置" }
                output.use { backups.export(record, it) }
            }
            mutableState.update { it.copy(message = "保全材料已写入所选位置；未进行目标文件回读校验") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(message = "导出失败：${error.message}；原文件及保全材料未清除") }
        } finally {
            mutableState.update { it.copy(busy = false) }
            exportLock.unlock()
        }
    }

    fun cancelExport() {
        mutableState.update { it.copy(message = "已取消导出；原文件及保全材料保持不变") }
    }

    private fun writeMarker(code: Int) {
        require(marker.canonicalFile == File(checkNotNull(marker.absoluteFile.parentFile).canonicalFile, marker.name)) {
            "保护标记路径异常"
        }
        RandomAccessFile(marker, "rw").use { file ->
            file.seek(0)
            file.writeByte(code)
            file.fd.sync()
        }
    }
}
