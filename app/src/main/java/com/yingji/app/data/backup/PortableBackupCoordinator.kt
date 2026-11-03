package com.yingji.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.room.withTransaction
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.repository.ThemeRepository
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

public data class BackupRecoveryState(val ready: Boolean = false, val error: String? = null, val pendingSettings: Boolean = false)
public data class BackupPreview(val movies: Int, val categories: Int, val tags: Int, val posters: Int, val canRestore: Boolean, val reason: String?, val extraDefaultCategories: Int, val fileLabel: String = "", val archiveFingerprint: String = "", val reusedCategories: Int = 0)

/** App-private staging and additive transactions only. Never replaces, clears or rebuilds the user's DB. */
class PortableBackupCoordinator(
    private val context: Context,
    private val db: AppDatabase,
    private val themes: ThemeRepository,
    private val rebuildProfile: suspend () -> Unit,
    private val fault: (String) -> Unit = {},
    private val availableBytes: () -> Long = { context.noBackupFilesDir.usableSpace },
) {
    private val root = File(context.noBackupFilesDir, "portable-backup")
    private val posters = File(context.filesDir, "posters")
    private val journal = RestoreJournal(root)
    private val lock = Mutex()
    private val initialization = Mutex()
    private val recoveryState = MutableStateFlow(BackupRecoveryState())
    val recovery = recoveryState.asStateFlow()
    private var exportWork: File? = null
    private var checked: Pair<File, BackupData>? = null
    private suspend fun <T> liveTransaction(block: suspend () -> T): T = DatabaseHealthMonitor.protectQuery {
        db.withTransaction {
            DatabaseHealthMonitor.ensureAccessible()
            try { block() } catch (e: Exception) {
                if (e.message in setOf("资料完整性核查失败", "资料关联核查失败")) DatabaseHealthMonitor.reportIntegrityCheckFailure()
                throw e
            }
        }
    }
    private suspend fun <T> single(block: suspend () -> T): T {
        check(lock.tryLock()) { "正在执行备份或恢复，请稍后" }
        return try { withContext(Dispatchers.IO) { block() } } finally { lock.unlock() }
    }
    private fun work(prefix: String): File = File(root, prefix + "_" + UUID.randomUUID().toString().replace("-", "")).also {
        check(it.mkdir()); File(it, "owner").writeText(it.name)
    }
    private fun clean(dir: File) {
        require(dir.canonicalFile.parentFile == root.canonicalFile && Regex("(export|check)_[a-f0-9]{32}").matches(dir.name))
        if (!dir.exists()) return
        check(File(dir, "owner").readText() == dir.name) { "临时目录归属无法核实" }
        val allowed = Regex("owner|payload\\.zip|archive\\.yjb|received\\.yjb|data\\.json|manifest\\.json|image_[a-f0-9]{64}|copy_[a-f0-9]{32}|validation\\.db(?:-wal|-shm|-journal)?")
        val files = dir.listFiles() ?: error("临时目录不可读")
        check(files.all { it.canonicalFile.parentFile == dir.canonicalFile && it.isFile && allowed.matches(it.name) }) { "临时目录含未知材料，已保留" }
        files.filter { it.name != "owner" }.forEach { check(it.delete()) { "临时材料清理失败" } }
        check(File(dir, "owner").delete() && dir.delete()) { "临时目录清理失败" }
    }
    private suspend fun recoverLocked() {
        val r = journal.read()
        if (r == null) { recoveryState.value = BackupRecoveryState(ready = true); return }
        if (r.phase == "COMMITTED") { rebuildProfile(); finishCommitted(r); return }
        val prefs = themes.backupPreferences()
        val fingerprint = liveTransaction { BackupSql.read(db, prefs).fingerprint() }
        if (r.phase == "READY" && fingerprint == r.after) {
            journal.write(r.copy(phase = "COMMITTED"))
            rebuildProfile()
            finishCommitted(r.copy(phase = "COMMITTED"))
        } else if (r.phase == "PREPARING" || r.phase == "READY" && fingerprint == r.before) {
            liveTransaction {
                r.files.forEach { name -> val f = File(posters, name); check(!BackupSql.posterReferenced(db, f.absolutePath)) { "未完成恢复含已引用文件，已保留现场" }; if (f.exists()) check(f.canonicalFile.parentFile == posters.canonicalFile && f.isFile && f.delete()) }
            }
            journal.clear(); clean(File(root, r.work)); checked = null
            recoveryState.value = BackupRecoveryState(ready = true)
        } else error("上次恢复的提交状态无法核实，原资料和现场均已保留；请勿清除数据")
    }
    private fun finishCommitted(r: RestoreRecord) {
        val dir = File(root, r.work)
        if (dir.exists()) clean(dir)
        checked = null
        if (r.settings == null) journal.clear()
        recoveryState.value = BackupRecoveryState(true, pendingSettings = r.settings != null)
    }
    suspend fun initialize() {
        if (recoveryState.value.ready) return
        initialization.withLock {
            if (!recoveryState.value.ready) lock.withLock { withContext(Dispatchers.IO) {
                recoveryState.value = BackupRecoveryState()
                try {
                    check(root.isDirectory || root.mkdirs()) { "备份暂存目录不可用" }
                    recoverLocked()
                    if (journal.read() == null) root.listFiles().orEmpty().filter { it.isDirectory && Regex("(export|check)_[a-f0-9]{32}").matches(it.name) && it != exportWork && it != checked?.first }.forEach { clean(it) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { recoveryState.value = BackupRecoveryState(false, "备份恢复状态尚未核验，请检查可用空间或文件访问后重试；原资料已保留，不要清除数据") }
            } }
        }
    }
    private suspend fun snapshot(dir: File, cancel: () -> Unit): BackupData {
        val prefs = themes.backupPreferences()
        return liveTransaction {
            BackupSql.integrity(db)
            val data = BackupSql.read(db, prefs); data.validate(false)
            val sourceBytes = data.rows("movies").filter { !it.isNull(2) }.map { it.getString(2) }.distinct().sumOf {
                val f = File(it).canonicalFile
                require(f.parentFile == posters.canonicalFile && f.isFile) { "本地海报缺失或不在托管目录，未生成完整备份" }
                f.length()
            }
            val dataBytes = data.json().toString().toByteArray(Charsets.UTF_8).size.toLong()
            require(dataBytes <= 16L * 1024 * 1024 && sourceBytes <= BackupCipher.MAX_BYTES) { "备份资料过大" }
            require(availableBytes() > (sourceBytes + dataBytes + 1024 * 1024) * 5 + 4 * 1024 * 1024) { "可用空间不足，未完成备份或恢复" }
            fault("snapshot-start")

            val references = mutableMapOf<String, String>(); var imageBytes = 0L
            data.rows("movies").forEach { row ->
                cancel()
                if (!row.isNull(2)) {
                    val original = row.getString(2)
                    val id = references.getOrPut(original) {
                        val source = File(original).canonicalFile
                        require(source.parentFile == posters.canonicalFile && source.isFile) { "本地海报缺失或不在托管目录，未生成完整备份" }
                        val beforeLength = source.length(); val modified = source.lastModified()
                        val copied = File(dir, "copy_" + UUID.randomUUID().toString().replace("-", ""))
                        val hash = source.inputStream().use { BackupArchive.copyBounded(it, copied, BackupArchive.MAX_IMAGE, cancel) }
                        require(source.isFile && source.length() == beforeLength && source.lastModified() == modified && copied.length() == beforeLength) { "海报在备份期间变化，请重试" }
                        BackupArchive.image(copied)
                        imageBytes += copied.length(); require(imageBytes <= BackupCipher.MAX_BYTES - 16 * 1024 * 1024) { "备份海报总量过大" }
                        val target = File(dir, "image_" + hash)
                        if (target.exists()) check(copied.delete()) else Files.move(copied.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        hash
                    }
                    row.put(2, id)
                }
            }
            check(themes.backupPreferences() == prefs) { "显示设置在备份期间变化，请重试" }
            data.validate(true); data
        }
    }
    suspend fun prepareExport(password: CharArray): Int = try { single {
        check(recoveryState.value.ready) { "恢复状态尚未核验" }; DatabaseHealthMonitor.ensureAccessible()
        check(!journal.exists()) { "请先完成上次恢复的核验或显示设置处理" }
        discardLocked()
        val dir = work("export"); val job = currentCoroutineContext(); val cancel = { job.ensureActive() }
        try {
            val data = snapshot(dir, cancel)
            BackupArchive.pack(data, data.rows("movies").filter { !it.isNull(2) }.map { it.getString(2) }.distinct().associateWith { File(dir, "image_" + it) }, File(dir, "payload.zip"), cancel)
            FileOutputStream(File(dir, "archive.yjb")).use { output -> File(dir, "payload.zip").inputStream().use { BackupCipher.encrypt(it, output, password, cancel) }; output.fd.sync() }
            val verification = work("check")
            try { val copy = decode(File(dir, "archive.yjb"), verification, password, cancel); check(copy.fingerprint() == data.fingerprint() && copy.preferences == data.preferences) { "备份回读核验失败" } } finally { clean(verification) }
            exportWork = dir; data.movieCount
        } catch (e: Exception) { clean(dir); throw e } finally { password.fill('\u0000') }
    } } finally { password.fill(0.toChar()) }
    private fun decode(file: File, dir: File, password: CharArray, cancel: () -> Unit): BackupData {
        FileOutputStream(File(dir, "payload.zip")).use { out -> file.inputStream().use { BackupCipher.decrypt(it, out, password, cancel) }; out.fd.sync() }
        return BackupArchive.unpack(File(dir, "payload.zip"), dir, cancel)
    }
    suspend fun writeExport(uri: Uri) = single {
        val dir = exportWork ?: run { runCatching { if (documentEmpty(uri)) DocumentsContract.deleteDocument(context.contentResolver, uri) }; error("备份操作已中断，请重新创建") }
        require(documentEmpty(uri)) { "目标文件不是空的新文档，已保留原文件；请重新创建并选择新位置" }
        val file = File(dir, "archive.yjb"); val job = currentCoroutineContext()
        try {
            checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output -> file.inputStream().use { source -> val b = ByteArray(65536); while (true) { job.ensureActive(); val n = source.read(b); if (n < 0) break; output.write(b, 0, n) } }; output.flush() }
            val expected = file.inputStream().use { digestStream(it, BackupCipher.MAX_BYTES + 1024 * 1024, job) }
            val actual = checkNotNull(context.contentResolver.openInputStream(uri)).use { digestStream(it, BackupCipher.MAX_BYTES + 1024 * 1024, job) }
            check(expected == actual) { "目标文件读回校验未通过" }
        } catch (e: Exception) {
            val deleted = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            if (!deleted) throw IOException("目标文件可能不完整，文件提供方未确认删除；请勿使用该文件", e)
            throw e
        }
        finally { clean(dir); exportWork = null }
    }
    private fun documentEmpty(uri: Uri): Boolean {
        if (uri.scheme == "file") {
            val f = File(requireNotNull(uri.path))
            return !f.exists() || f.isFile && f.length() == 0L
        }
        return checkNotNull(context.contentResolver.openInputStream(uri)).use { it.read() == -1 }
    }
    suspend fun checkDocument(uri: Uri, password: CharArray): BackupPreview = try { single {
        check(recoveryState.value.ready) { "恢复状态尚未核验" }; DatabaseHealthMonitor.ensureAccessible()
        check(!journal.exists()) { "请先处理上次恢复" }; discardLocked()
        val dir = work("check"); val job = currentCoroutineContext(); val cancel = { job.ensureActive() }
        try {
            val archiveHash = checkNotNull(context.contentResolver.openInputStream(uri)).use { BackupArchive.copyBounded(it, File(dir, "received.yjb"), BackupCipher.MAX_BYTES + 1024 * 1024, cancel) }
            val data = decode(File(dir, "received.yjb"), dir, password, cancel)
            validateInScratch(dir, data)
            val existing = liveTransaction { BackupSql.read(db, themes.backupPreferences()) }
            val error = runCatching { BackupSql.requirePristine(existing); data.rows("categories").forEach { row -> existing.rows("categories").find { it.getString(1) == row.getString(1) }?.let { check(it.getLong(2) == row.getLong(2)) { "分类属性冲突" } } } }.exceptionOrNull()?.message
            checked = dir to data
            BackupPreview(data.movieCount, data.rows("categories").size, data.rows("tags").size, data.rows("movies").filter { !it.isNull(2) }.map { it.getString(2) }.distinct().size,
                error == null, error, existing.rows("categories").count { row -> data.rows("categories").none { it.getString(1) == row.getString(1) } }, documentLabel(uri), archiveHash.take(12), data.rows("categories").count { row -> existing.rows("categories").any { it.getString(1) == row.getString(1) && it.getLong(2) == row.getLong(2) } })
        } catch (e: Exception) { clean(dir); throw e } finally { password.fill('\u0000') }
    } } finally { password.fill(0.toChar()) }
    private fun documentLabel(uri: Uri): String {
        val label = runCatching {
            if (uri.scheme == "file") File(requireNotNull(uri.path)).name
            else context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull().orEmpty().filterNot { it.isISOControl() }.take(200)
        return label.ifBlank { "已选择的备份文件" }
    }
    private suspend fun validateInScratch(dir: File, data: BackupData) {
        val scratch = Room.databaseBuilder(context, AppDatabase::class.java, File(dir, "validation.db").absolutePath).build()
        try { scratch.withTransaction { BackupSql.insert(scratch, data, data.rows("movies").filter { !it.isNull(2) }.map { it.getString(2) }.distinct().associateWith { File(dir, "image_" + it).absolutePath }) } } finally { scratch.close() }
    }
    suspend fun restore(applyPreferences: Boolean): String = single {
        check(recoveryState.value.ready) { "恢复状态尚未核验" }; DatabaseHealthMonitor.ensureAccessible()
        val (dir, data) = checked ?: error("请先重新选择并检查备份")
        check(!journal.exists()) { "上次恢复尚未处理" }
        val requiredBytes = data.rows("movies").filter { !it.isNull(2) }.map { it.getString(2) }.distinct().sumOf { File(dir, "image_" + it).length() } + File(dir, "data.json").length() * 4 + 16L * 1024 * 1024
        require(availableBytes() > requiredBytes) { "可用空间不足，未完成备份或恢复" }
        val ids = data.rows("movies").filter { !it.isNull(2) }.map { it.getString(2) }.distinct()
        val names = ids.associateWith { "restore_" + UUID.randomUUID().toString().replace("-", "") + ".img" }
        var r = RestoreRecord("PREPARING", dir.name, names.values.toList(), settings = if (applyPreferences) data.preferences else null)
        check(names.values.none { File(posters, it).exists() }) { "恢复目标已存在" }
        journal.write(r)
        try {
            val job = currentCoroutineContext()
            check(posters.isDirectory || posters.mkdirs())
            require(posters.canonicalFile.parentFile == context.filesDir.canonicalFile) { "海报目录路径无效" }
            ids.forEach { id -> job.ensureActive(); val file = File(dir, "image_" + id); BackupArchive.image(file); check(file.inputStream().use { digestStream(it, BackupArchive.MAX_IMAGE, job) } == id); Files.copy(file.toPath(), File(posters, names.getValue(id)).toPath()) }
            fault("files-published")
            withContext(NonCancellable) {
                liveTransaction {
                    val prefs = themes.backupPreferences(); val before = BackupSql.read(db, prefs)
                    BackupSql.requirePristine(before)
                    BackupSql.insert(db, data, names.mapValues { File(posters, it.value).absolutePath })
                    r = r.copy(phase = "READY", before = before.fingerprint(), after = BackupSql.read(db, prefs).fingerprint())
                    journal.write(r); fault("before-commit")
                }
                fault("after-commit")
                r = r.copy(phase = "COMMITTED"); journal.write(r); fault("committed-recorded"); rebuildProfile(); finishCommitted(r)
            }
        } catch (e: Exception) {
            try { recoverLocked() } catch (_: Exception) { recoveryState.value = BackupRecoveryState(false, "恢复提交状态待核验，原资料与现场已保留") }
            val committed = recoveryState.value.ready && r.after.isNotEmpty() && runCatching {
                liveTransaction { BackupSql.read(db, themes.backupPreferences()).fingerprint() == r.after }
            }.getOrDefault(false)
            if (!committed) throw e
        }
        if (applyPreferences) {
            try { applySettingsLocked(); "资料和显示设置已恢复" } catch (_: Exception) { "资料已恢复；显示设置未应用，请单独重试，不要再次导入" }
        } else "资料已恢复；保留本机显示设置"
    }
    private suspend fun applySettingsLocked() {
        val r = requireNotNull(journal.read()); check(r.phase == "COMMITTED")
        themes.applyBackupPreferences(requireNotNull(r.settings)); journal.clear()
        recoveryState.value = BackupRecoveryState(ready = true)
    }
    suspend fun retrySettings() = single { applySettingsLocked() }
    suspend fun keepSettings() = single { check(journal.read()?.phase == "COMMITTED"); journal.clear(); recoveryState.value = BackupRecoveryState(ready = true) }
    private fun discardLocked() {
        exportWork?.let { clean(it) }; exportWork = null
        checked?.let { clean(it.first) }; checked = null
    }
    suspend fun discard() = single { if (!journal.exists()) discardLocked() }
    private fun digestStream(input: InputStream, max: Long, job: kotlin.coroutines.CoroutineContext): String {
        val d = java.security.MessageDigest.getInstance("SHA-256"); val b = ByteArray(65536); var count = 0L
        while (true) { job.ensureActive(); val n = input.read(b); if (n < 0) break; check(n > 0); count += n; require(count <= max) { "文件超过大小限制" }; d.update(b, 0, n) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
