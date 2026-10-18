package com.yingji.app.data.database

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PreservedFile(val name: String, val size: Long, val sha256: String)

data class DatabaseBackupRecord(
    val directory: File,
    val complete: Boolean,
    val files: List<PreservedFile>,
    val message: String,
    val validation: String = "副本完整性尚未检查",
    val expectedFiles: List<String> = emptyList(),
) {
    val exportable: Boolean get() = files.isNotEmpty() && File(directory, "manifest.json").isFile
}

/** Raw evidence only. No method opens, modifies, closes or deletes the source database. */
class DatabaseBackupStore(
    private val source: File,
    private val root: File,
    private val copyFile: (File, File) -> Unit = ::copyNewFile,
    private val availableBytes: () -> Long = { root.usableSpace },
) {
    private val suffixes = listOf("", "-wal", "-shm", "-journal")

    companion object {
        /** Validate a disposable copy before SQLite can change the source's journal header. */
        fun preflightExisting(source: File, scratchRoot: File) {
            val suffixes = listOf("", "-wal", "-shm", "-journal")
            val originals = suffixes.map { File(source.path + it) }.filter { it.exists() }
            if (originals.isEmpty()) return // A genuinely new installation may create its first database.
            if (!source.isFile) throw android.database.sqlite.SQLiteCantOpenDatabaseException("数据库主文件缺失但附件仍在，禁止创建空库")
            val parent = checkNotNull(source.absoluteFile.parentFile).canonicalFile
            val fingerprints = originals.associate { file ->
                require(file.canonicalFile == File(parent, file.name)) { "数据库附件路径异常" }
                file.name to fingerprint(file)
            }
            check(scratchRoot.isDirectory || scratchRoot.mkdirs()) { "无法创建打开前核查目录" }
            val working = File(scratchRoot, UUID.randomUUID().toString())
            check(working.mkdir()) { "无法创建独立核查副本" }
            try {
                val size = fingerprints.values.sumOf { it.size }
                if (size > Long.MAX_VALUE - 65_536 || working.usableSpace <= size + 65_536) {
                    throw android.database.sqlite.SQLiteFullException("打开前核查空间不足")
                }
                originals.forEach { file ->
                    val target = File(working, file.name)
                    copyNewFile(file, target)
                    check(fingerprint(target) == fingerprints.getValue(file.name)) { "核查副本复制校验失败" }
                }
                check(suffixes.map { File(source.path + it) }.filter { it.exists() }.map { it.name }.toSet() == fingerprints.keys &&
                    originals.all { fingerprint(it) == fingerprints.getValue(it.name) }) { "原数据库正在变化，无法确认打开安全性" }
                inspectDatabase(File(working, source.name))
            } finally {
                // Only this newly-created disposable directory is eligible for cleanup.
                // Never close, checkpoint, rename, overwrite or delete the source/evidence.
                working.listFiles().orEmpty().forEach { file ->
                    if (file.canonicalFile.parentFile == working.canonicalFile && file.name in suffixes.map { source.name + it }) file.delete()
                }
                working.delete() // Removes an empty owned scratch directory only.
            }
        }

        private fun inspectDatabase(file: File): String {
            val header = ByteArray(16)
            val count = file.inputStream().use { it.read(header) }
            if (count != 16 || !header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) {
                throw android.database.sqlite.SQLiteDatabaseCorruptException("数据库文件头异常")
            }
            return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY,
                DatabaseErrorHandler { }).use { database ->
                database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    if (!(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext())) {
                        throw android.database.sqlite.SQLiteDatabaseCorruptException("副本完整性检查未通过")
                    }
                }
                database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    if (cursor.moveToFirst()) throw android.database.sqlite.SQLiteDatabaseCorruptException("副本存在外键关联异常")
                }
                try {
                    check(database.version in 1..2) { "副本版本不受支持" }
                    val identity = database.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null)
                        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    val expected = if (database.version == 1) "9a1ffc9cdec304a54dcb435d8287bb72" else "9d6975ae6c0dfcefb475018b1bf35ac8"
                    check(identity == expected) { "副本 Room Schema 标识不匹配" }
                    val counts = listOf("movies", "watch_history", "categories", "tags", "movie_categories", "movie_tags")
                        .sumOf { table -> database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                            check(cursor.moveToFirst()); cursor.getLong(0)
                        } }
                    "副本检查通过（Schema ${database.version}，$counts 条表记录）；事务时间点一致性仍未确认"
                } catch (error: Exception) {
                    if (classifyDatabaseIssue(error) == DatabaseIssue.CORRUPTION) throw error
                    throw DatabaseMigrationException(error)
                }
            }
        }
    }

    @Synchronized
    fun capture(): DatabaseBackupRecord {
        val directory = File(root, UUID.randomUUID().toString())
        val copied = mutableListOf<PreservedFile>()
        var expectedFiles = emptyList<String>()
        return try {
            val sourceParent = checkNotNull(source.absoluteFile.parentFile).canonicalFile
            require(source.canonicalFile == File(sourceParent, source.name)) { "数据库路径不允许使用符号链接" }
            require(root.canonicalFile != sourceParent) { "保全目录不能是数据库目录" }
            check(root.isDirectory || root.mkdirs()) { "无法创建保全目录" }
            check(directory.mkdir()) { "无法创建独立保全目录" }
            check(source.isFile) { "原数据库文件缺失，禁止创建空库替代" }
            val originals = suffixes.map { File(source.path + it) }.filter { it.exists() }
            expectedFiles = originals.map { it.name }
            val before = originals.associate { file ->
                require(file.isFile && file.canonicalFile == File(sourceParent, file.name)) { "异常数据库附件路径" }
                file.name to fingerprint(file)
            }
            val size = before.values.sumOf { it.size }
            check(size <= (Long.MAX_VALUE - 65_536) / 2 && availableBytes() >= size * 2 + 65_536) {
                "保全空间不足，原文件保持不变"
            }
            originals.forEach { original ->
                val target = File(directory, original.name)
                copyFile(original, target)
                val result = fingerprint(target)
                check(result == before.getValue(original.name)) { "复制校验失败：${original.name}" }
                copied.add(result)
            }
            val afterNames = suffixes.map { File(source.path + it) }.filter { it.exists() }.map { it.name }.toSet()
            check(afterNames == before.keys && originals.all { fingerprint(it) == before.getValue(it.name) }) {
                "保全过程中原文件发生变化，一致性未确认"
            }
            DatabaseBackupRecord(directory, true, copied.toList(),
                "文件保全完成；事务时间点一致性未确认，不代表数据库可恢复", expectedFiles = expectedFiles)
                .also(::saveManifest)
        } catch (error: Exception) {
            DatabaseBackupRecord(directory, false, copied.toList(),
                "保全未完成：${error.message ?: "文件操作失败"}；未删除或覆盖原文件", expectedFiles = expectedFiles)
                .also { record -> runCatching { saveManifest(record) } }
        }
    }

    fun verifyWorkingCopy(record: DatabaseBackupRecord): DatabaseBackupRecord {
        if (!record.complete) return record
        val checked = try {
            val working = File(root, "validation-${UUID.randomUUID()}")
            check(working.mkdir()) { "无法创建副本核查目录" }
            record.files.forEach { entry ->
                val evidence = evidenceFile(record, entry)
                check(fingerprint(evidence) == entry) { "保全材料校验失败" }
                copyNewFile(evidence, File(working, entry.name))
            }
            record.copy(validation = inspectDatabase(File(working, source.name)))
        } catch (error: Exception) {
            record.copy(validation = "副本检查未通过或无法完成：${error.message ?: "未知错误"}；保留原始材料，不代表可恢复备份")
        }
        return try {
            saveManifest(checked)
            checked
        } catch (_: Exception) {
            record.copy(validation = "检查结果无法持久化，仍按未验证材料处理")
        }
    }

    fun latest(): DatabaseBackupRecord? = root.listFiles().orEmpty()
        .filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
        .sortedByDescending { it.lastModified() }
        .firstOrNull()?.let { directory ->
            runCatching {
                require(directory.canonicalFile.parentFile == root.canonicalFile)
                val json = JSONObject(AtomicFile(File(directory, "manifest.json")).openRead().bufferedReader().use { it.readText() })
                check(json.getString("database") == source.name)
                val entries = json.getJSONArray("files")
                DatabaseBackupRecord(directory, json.getBoolean("complete"),
                    (0 until entries.length()).map { index -> entries.getJSONObject(index).let {
                        PreservedFile(it.getString("name"), it.getLong("size"), it.getString("sha256"))
                    } }, json.getString("message"), json.getString("validation"),
                    json.getJSONArray("expectedFiles").let { expected -> (0 until expected.length()).map { expected.getString(it) } })
            }.getOrNull()
        }

    fun export(record: DatabaseBackupRecord, output: OutputStream) {
        check(record.exportable) { "没有可导出的保全材料" }
        require(record.directory.canonicalFile.parentFile == root.canonicalFile)
        record.files.forEach { check(fingerprint(evidenceFile(record, it)) == it) { "保全材料已变化，禁止虚报导出成功" } }
        ZipOutputStream(output).use { zip ->
            (listOf("manifest.json") + record.files.map { it.name }).forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                File(record.directory, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun evidenceFile(record: DatabaseBackupRecord, entry: PreservedFile): File {
        require(entry.name in suffixes.map { source.name + it }) { "非法保全文件名" }
        val file = File(record.directory, entry.name)
        require(file.canonicalFile.parentFile == record.directory.canonicalFile) { "非法保全文件路径" }
        return file
    }

    private fun saveManifest(record: DatabaseBackupRecord) {
        check(record.directory.canonicalFile.parentFile == root.canonicalFile)
        val json = JSONObject().put("database", source.name).put("complete", record.complete)
            .put("consistency", "UNCONFIRMED").put("message", record.message).put("validation", record.validation)
            .put("expectedFiles", JSONArray(record.expectedFiles))
            .put("absentAtCapture", JSONArray(suffixes.map { source.name + it }.filter { it !in record.expectedFiles }))
            .put("notCopied", JSONArray(record.expectedFiles.filter { name -> record.files.none { it.name == name } }))
            .put("files", JSONArray(record.files.map { entry -> JSONObject()
                .put("name", entry.name).put("size", entry.size).put("sha256", entry.sha256) }))
        val atomic = AtomicFile(File(record.directory, "manifest.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}

private fun fingerprint(file: File): PreservedFile {
    val hash = MessageDigest.getInstance("SHA-256")
    var size = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
            size += count
        }
    }
    return PreservedFile(file.name, size, hash.digest().joinToString("") { "%02x".format(it) })
}

private fun copyNewFile(source: File, target: File) {
    if (!target.createNewFile()) throw IOException("拒绝覆盖已有保全文件")
    source.inputStream().use { input -> FileOutputStream(target).use { output ->
        input.copyTo(output)
        output.fd.sync()
    } }
}
