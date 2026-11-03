package com.yingji.app.data.backup

import com.yingji.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class BackupTable(val name: String, val columns: List<String>, val types: String, val keys: Int = 1)
internal val BACKUP_TABLES = listOf(
    BackupTable("categories", listOf("id", "name", "isSystem"), "LSL"),
    BackupTable("tags", listOf("id", "name"), "LS"),
    BackupTable("movies", listOf("id", "name", "posterPath", "type", "status", "currentEpisode", "totalEpisode", "createTime", "rating"), "LSSSSLLLN"),
    BackupTable("movie_categories", listOf("movieId", "categoryId"), "LL", 2),
    BackupTable("movie_tags", listOf("movieId", "tagId"), "LL", 2),
    BackupTable("watch_history", listOf("id", "movieId", "startTime", "endTime", "duration", "watchNumber"), "LLLLLL"),
    BackupTable("ai_candidate_imports", listOf("sourceId", "externalId", "movieId"), "SSL", 2),
    BackupTable("ai_profile_state", listOf("id", "ratingsEnabled", "behaviorEnabled", "feedbackEnabled"), "LLLL"),
    BackupTable("ai_explicit_preferences", listOf("featureId", "label", "direction", "updatedAt"), "SSSL"),
    BackupTable("ai_profile_corrections", listOf("featureId", "updatedAt"), "SL"),
    BackupTable("ai_profile_feedback", listOf("eventId", "featureId", "label", "direction", "updatedAt"), "SSSSL"),
)

/** Only display preferences; credentials, authorization, usage and transient AI state never enter this DTO. */
data class BackupPreferences(val theme: String, val display: String, val sort: String) {
    fun validate() {
        require(ThemeMode.entries.any { it.name == theme } && MovieDisplayMode.entries.any { it.name == display } && MovieSortOption.entries.any { it.name == sort }) { "备份显示设置无效" }
    }
    internal fun json() = JSONObject().put("theme", theme).put("display", display).put("sort", sort)
    companion object {
        internal fun from(value: JSONObject): BackupPreferences {
            require(value.keys().asSequence().toSet() == setOf("theme", "display", "sort")) { "备份设置结构无效" }
            return BackupPreferences(value.getString("theme"), value.getString("display"), value.getString("sort")).also { it.validate() }
        }
    }
}

internal class BackupData(val tables: Map<String, JSONArray>, val preferences: BackupPreferences) {
    val movieCount get() = tables.getValue("movies").length()
    fun rows(name: String): List<JSONArray> = tables.getValue(name).let { a -> (0 until a.length()).map { a.getJSONArray(it) } }
    fun json() = JSONObject().put("format", 1).put("schema", 5).put("tables", tablesJson()).put("preferences", preferences.json())
    fun fingerprint(): String = sha256(tablesJson().toString().toByteArray(Charsets.UTF_8))
    private fun tablesJson() = JSONObject().also { o -> BACKUP_TABLES.forEach { o.put(it.name, tables.getValue(it.name)) } }
    fun validate(portable: Boolean) {
        preferences.validate()
        require(tables.keys == BACKUP_TABLES.map { it.name }.toSet()) { "备份表集合无效" }
        require(movieCount <= 10_000 && tables.values.sumOf { it.length().toLong() } <= 100_000) { "备份记录过多" }
        BACKUP_TABLES.forEach { t ->
            val seen = mutableSetOf<String>()
            rows(t.name).forEach { row ->
                require(row.length() == t.columns.size) { "备份行结构无效" }
                t.columns.indices.forEach { i ->
                    val v = row.get(i)
                    val nullable = t.name == "movies" && i in setOf(2, 6, 8) || t.name == "watch_history" && i == 3
                    if (v == JSONObject.NULL) require(nullable) { "备份缺少必需字段" }
                    else when (t.types[i]) {
                        'L' -> require(v is Int || v is Long) { "备份整数无效" }
                        'S' -> require(v is String && v.length <= 1000 && (v.isNotBlank() || t.name == "movies" && i == 3)) { "备份文字无效" }
                        'N' -> require(v is Number && v.toDouble().isFinite()) { "备份数值无效" }
                    }
                }
                val key = JSONArray().also { k -> repeat(t.keys) { k.put(row.get(it)) } }.toString()
                require(seen.add(key)) { "备份存在重复主键" }
                if (t.types[0] == 'L') require(row.getLong(0) > 0) { "备份标识无效" }
            }
        }
        val movies = rows("movies").associateBy { it.getLong(0) }
        val categories = rows("categories").associateBy { it.getLong(0) }
        val tags = rows("tags").associateBy { it.getLong(0) }
        require(categories.values.map { it.getString(1) }.distinct().size == categories.size && tags.values.map { it.getString(1) }.distinct().size == tags.size) { "备份分类或标签重名" }
        categories.values.forEach { require(it.getLong(2) in 0L..1L) { "分类属性无效" } }
        movies.values.forEach { m ->
            require(MovieStatus.entries.any { it.value == m.getString(4) } && m.getLong(5) in 0..Int.MAX_VALUE.toLong()) { "作品状态或进度无效" }
            if (!m.isNull(6)) require(m.getLong(6) in 0..Int.MAX_VALUE.toLong() && (m.getLong(6) == 0L || m.getLong(5) <= m.getLong(6))) { "总进度无效" }
            if (!m.isNull(8)) require(m.getDouble(8) in 0.0..10.0 && m.getDouble(8) * 2 % 1 == 0.0) { "评分无效" }
            if (portable && !m.isNull(2)) require(Regex("[a-f0-9]{64}").matches(m.getString(2))) { "海报引用无效" }
        }
        rows("movie_categories").forEach { require(it.getLong(0) in movies && it.getLong(1) in categories) { "分类关联无效" } }
        val categorized = rows("movie_categories").map { it.getLong(0) }.toSet()
        require(movies.keys.all { it in categorized }) { "作品缺少分类" }
        rows("movie_tags").forEach { require(it.getLong(0) in movies && it.getLong(1) in tags) { "标签关联无效" } }
        val watches = mutableSetOf<Pair<Long, Long>>()
        var totalDuration = 0L
        rows("watch_history").forEach {
            totalDuration = Math.addExact(totalDuration, it.getLong(4))
            require(it.getLong(1) in movies && it.getLong(4) >= 0 && it.getLong(5) in 1..Int.MAX_VALUE.toLong() && watches.add(it.getLong(1) to it.getLong(5))) { "观看记录无效" }
            require(it.isNull(3) || it.getLong(3) >= it.getLong(2)) { "观看日期区间无效" }
        }
        val open = rows("watch_history").filter { it.isNull(3) }.groupBy { it.getLong(1) }
        require(open.values.all { it.size == 1 } && open.keys.all { movies.getValue(it).getString(4) == MovieStatus.WATCHING.value }) { "未完成观看记录无效" }
        rows("ai_candidate_imports").forEach { require(it.getLong(2) in movies && Regex("[a-zA-Z0-9._:-]{1,100}").matches(it.getString(0)) && it.getString(1).length <= 200 && (it.getString(0) != "tmdb" || Regex("(movie|tv):[1-9][0-9]{0,17}").matches(it.getString(1)))) { "来源绑定无效" } }
        fun feature(id: String, label: String) {
            com.yingji.app.domain.ai.ProfileFeature(id, label)
        }
        rows("ai_explicit_preferences").forEach { feature(it.getString(0), it.getString(1)) }
        rows("ai_profile_corrections").forEach { feature(it.getString(0), "已纠错的特征") }
        rows("ai_profile_feedback").forEach { require(it.getString(0).length <= 200) { "反馈标识过长" }; feature(it.getString(1), it.getString(2)) }
        val states = rows("ai_profile_state")
        require(states.size == 1 && states[0].getLong(0) == 1L && (1..3).all { states[0].getLong(it) in 0L..1L }) { "画像来源设置无效" }
        (rows("ai_explicit_preferences") + rows("ai_profile_feedback")).forEach { require(it.getString(it.length() - 2) in setOf("LIKE", "DISLIKE")) { "画像方向无效" } }
    }
    companion object {
        fun parse(bytes: ByteArray): BackupData {
            require(bytes.size <= 16 * 1024 * 1024) { "备份资料过大" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val o = JSONObject(text)
            require(o.keys().asSequence().toSet() == setOf("format", "schema", "tables", "preferences") && isExactInteger(o, "format", 1) && isExactInteger(o, "schema", 5)) { "不支持此备份版本" }
            val ts = o.getJSONObject("tables")
            require(ts.keys().asSequence().toSet() == BACKUP_TABLES.map { it.name }.toSet()) { "备份表集合无效" }
            return BackupData(BACKUP_TABLES.associate { it.name to ts.getJSONArray(it.name) }, BackupPreferences.from(o.getJSONObject("preferences"))).also { it.validate(true) }
        }
    }
}
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun isExactInteger(o: JSONObject, key: String, expected: Long): Boolean = (o.get(key) is Int || o.get(key) is Long) && o.getLong(key) == expected
