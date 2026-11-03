package com.yingji.app.data.backup

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class RestoreRecord(val phase: String, val work: String, val files: List<String>, val before: String = "", val after: String = "", val settings: BackupPreferences? = null)
internal class RestoreJournal(root: File) {
    private val atomic = AtomicFile(File(root, "restore-journal.json"))
    fun exists() = atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()
    fun read(): RestoreRecord? {
        if (!exists()) return null
        val o = atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
        require(o.getInt("version") == 1 && o.getString("phase") in setOf("PREPARING", "READY", "COMMITTED")) { "恢复记录无法识别，已保留现场" }
        val work = o.getString("work")
        require(Regex("check_[a-f0-9]{32}").matches(work)) { "恢复目录记录无效" }
        val names = o.getJSONArray("files").let { a -> (0 until a.length()).map { a.getString(it) } }
        require(names.size <= 10000 && names.distinct().size == names.size && names.all { Regex("restore_[a-f0-9]{32}\\.img").matches(it) }) { "恢复海报记录无效" }
        val before = o.getString("before"); val after = o.getString("after")
        require(listOf(before, after).all { it.isEmpty() || Regex("[a-f0-9]{64}").matches(it) }) { "恢复摘要记录无效" }
        return RestoreRecord(o.getString("phase"), work, names, before, after, if (o.isNull("settings")) null else BackupPreferences.from(o.getJSONObject("settings")))
    }
    fun write(r: RestoreRecord) {
        val o = JSONObject().put("version", 1).put("phase", r.phase).put("work", r.work).put("files", JSONArray(r.files))
            .put("before", r.before).put("after", r.after).put("settings", r.settings?.json() ?: JSONObject.NULL)
        val output = atomic.startWrite()
        try { output.write(o.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun clear() { atomic.delete(); check(!exists()) { "恢复完成记录未能清理" } }
}
