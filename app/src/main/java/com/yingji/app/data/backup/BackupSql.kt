package com.yingji.app.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.repository.CategoryRepository
import org.json.JSONArray
import org.json.JSONObject

/** Names/columns are compiled allowlists. No SQL or column name from an archive is executed. */
internal object BackupSql {
    fun read(db: AppDatabase, preferences: BackupPreferences): BackupData {
        var rowCount = 0L
        BACKUP_TABLES.forEach { t ->
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM " + t.name).use { c ->
                check(c.moveToFirst()); val n = c.getLong(0); rowCount += n
                require(rowCount <= 100_000 && (t.name != "movies" || n <= 10_000)) { "备份记录过多" }
            }
        }
        val tables = BACKUP_TABLES.associate { t ->
            val rows = JSONArray()
            val query = "SELECT " + t.columns.joinToString(",") + " FROM " + t.name + " ORDER BY " + t.columns.take(t.keys).joinToString(",")
            db.openHelper.writableDatabase.query(query).use { c ->
                while (c.moveToNext()) rows.put(JSONArray().also { row -> repeat(c.columnCount) { i -> row.put(when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> c.getString(i)
                    else -> error("资料字段不支持备份")
                }) } })
            }
            if (t.name == "ai_profile_state" && rows.length() == 0) rows.put(JSONArray(listOf(1, 0, 0, 1)))
            t.name to rows
        }
        return BackupData(tables, preferences)
    }
    fun requirePristine(data: BackupData) {
        require(BACKUP_TABLES.filter { it.name !in setOf("categories", "ai_profile_state") }.all { data.rows(it.name).isEmpty() }) { "当前资料库不是空资料库；首版不支持合并或覆盖恢复" }
        require(data.rows("categories").all { it.getLong(2) == 1L && it.getString(1) in CategoryRepository.SYSTEM_CATEGORY_NAMES }) { "已有自定义或改名分类，不能恢复到此资料库" }
        val s = data.rows("ai_profile_state").single()
        require(s.getLong(1) == 0L && s.getLong(2) == 0L && s.getLong(3) == 1L) { "已有真实画像设置，不能恢复到此资料库" }
    }
    fun integrity(db: AppDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { c -> require(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) { "资料完整性核查失败" } }
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { c -> require(!c.moveToFirst()) { "资料关联核查失败" } }
    }
    fun posterReferenced(db: AppDatabase, path: String): Boolean = db.openHelper.writableDatabase.query("SELECT 1 FROM movies WHERE posterPath=? LIMIT 1", arrayOf(path)).use { it.moveToFirst() }
    /** One caller-owned Room transaction; no synthetic watch session or network side effect. */
    fun insert(db: AppDatabase, data: BackupData, posters: Map<String, String>) {
        val sql = db.openHelper.writableDatabase
        fun add(table: String, row: JSONArray, overrides: Map<String, Any?> = emptyMap(), generated: Boolean = false): Long {
            val spec = BACKUP_TABLES.single { it.name == table }; val values = ContentValues()
            spec.columns.forEachIndexed { i, name ->
                if (!(generated && name == "id")) {
                    val v = if (overrides.containsKey(name)) overrides[name] else row.get(i)
                    when (v) {
                        null, JSONObject.NULL -> values.putNull(name)
                        is String -> values.put(name, v)
                        is Int -> values.put(name, v)
                        is Long -> values.put(name, v)
                        is Double -> values.put(name, v)
                        else -> error("资料字段类型无效")
                    }
                }
            }
            return sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, values).also { require(it != -1L) { "恢复写入失败" } }
        }
        val categoryMap = mutableMapOf<Long, Long>()
        val existing = read(db, data.preferences).rows("categories").associateBy { it.getString(1) }
        data.rows("categories").forEach { row ->
            val old = existing[row.getString(1)]
            if (old != null) require(old.getLong(2) == row.getLong(2)) { "分类同名但属性冲突" }
            categoryMap[row.getLong(0)] = old?.getLong(0) ?: add("categories", row, generated = true)
        }
        val tagMap = data.rows("tags").associate { it.getLong(0) to add("tags", it, generated = true) }
        val movieMap = data.rows("movies").associate { row ->
            val poster = if (row.isNull(2)) null else posters.getValue(row.getString(2))
            row.getLong(0) to add("movies", row, mapOf("posterPath" to poster), generated = true)
        }
        data.rows("movie_categories").forEach { add("movie_categories", it, mapOf("movieId" to movieMap.getValue(it.getLong(0)), "categoryId" to categoryMap.getValue(it.getLong(1)))) }
        data.rows("movie_tags").forEach { add("movie_tags", it, mapOf("movieId" to movieMap.getValue(it.getLong(0)), "tagId" to tagMap.getValue(it.getLong(1)))) }
        data.rows("watch_history").forEach { add("watch_history", it, mapOf("movieId" to movieMap.getValue(it.getLong(1))), generated = true) }
        data.rows("ai_candidate_imports").forEach { add("ai_candidate_imports", it, mapOf("movieId" to movieMap.getValue(it.getLong(2)))) }
        // Historical profile features may outlive a removed category/tag. Keep them detached:
        // reserve an unused feature-only number rather than binding them to an unrelated new row.
        val detachedCategories = mutableMapOf<Long, Long>(); val detachedTags = mutableMapOf<Long, Long>()
        fun mapped(old: Long, live: Map<Long, Long>, detached: MutableMap<Long, Long>): Long = live[old] ?: detached.getOrPut(old) {
            val used = live.values.toSet() + detached.values + if (live === categoryMap) existing.values.map { it.getLong(0) } else emptyList()
            var candidate = Long.MAX_VALUE
            while (candidate in used) candidate--
            check(candidate > 0); candidate
        }
        fun feature(id: String): String = when {
            id.startsWith("category:") -> "category:" + mapped(id.substringAfter(':').toLong(), categoryMap, detachedCategories)
            id.startsWith("tag:") -> "tag:" + mapped(id.substringAfter(':').toLong(), tagMap, detachedTags)
            else -> id
        }
        listOf("ai_explicit_preferences", "ai_profile_corrections").forEach { table -> data.rows(table).forEach { add(table, it, mapOf("featureId" to feature(it.getString(0)))) } }
        data.rows("ai_profile_feedback").forEach { add("ai_profile_feedback", it, mapOf("featureId" to feature(it.getString(1)))) }

        val flags = data.rows("ai_profile_state").single()
        sql.execSQL("INSERT OR IGNORE INTO ai_profile_state (id,ratingsEnabled,behaviorEnabled,feedbackEnabled,profileVersion,sourceSettingsVersion,libraryFingerprint,updatedAt) VALUES (1,0,0,1,0,0,'',0)")
        val previousVersion = sql.query("SELECT sourceSettingsVersion FROM ai_profile_state WHERE id=1").use { c -> check(c.moveToFirst()); c.getLong(0) }
        val nextVersion = Math.addExact(previousVersion, 1L)
        sql.execSQL("UPDATE ai_profile_state SET ratingsEnabled=?,behaviorEnabled=?,feedbackEnabled=?,sourceSettingsVersion=? WHERE id=1", arrayOf(flags.getLong(1), flags.getLong(2), flags.getLong(3), nextVersion))
        integrity(db)
    }
}
