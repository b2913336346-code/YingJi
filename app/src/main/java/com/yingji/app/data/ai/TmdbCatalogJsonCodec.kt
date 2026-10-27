package com.yingji.app.data.ai

import android.util.JsonReader
import android.util.JsonToken
import com.yingji.app.domain.ai.*
import java.io.StringReader
import java.math.BigDecimal
import java.time.LocalDate

/** Bounded strict parser. No model facts, source license or local category from remote JSON. Poster paths are optional and strictly bounded. */
internal class TmdbCatalogJsonCodec {
    private fun bad(): Nothing = throw TmdbProblem(TmdbProblemCode.INVALID_RESPONSE)
    private fun root(text: String): Map<String, Any?> {
        var nodes = 0
        fun read(reader: JsonReader, depth: Int): Any? {
            if (depth > 16 || ++nodes > 12000) bad()
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                    reader.beginObject()
                    while (reader.hasNext()) { val key = reader.nextName(); if (containsKey(key)) bad(); put(key, read(reader, depth + 1)) }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> mutableListOf<Any?>().apply { reader.beginArray(); while (reader.hasNext()) add(read(reader, depth + 1)); reader.endArray() }
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> BigDecimal(reader.nextString())
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> bad()
            }
        }
        try {
            if (text.toByteArray(Charsets.UTF_8).size > 131072) bad()
            return JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val parsed = read(reader, 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) bad()
                obj(parsed)
            }
        } catch (e: TmdbProblem) { throw e } catch (_: Exception) { bad() }
    }
    @Suppress("UNCHECKED_CAST") private fun obj(value: Any?) = value as? Map<String, Any?> ?: bad()
    private fun integer(value: Any?, minimum: Int = 1): Int {
        val number = try { (value as? BigDecimal)?.intValueExact() } catch (_: ArithmeticException) { null }
        return number?.takeIf { it >= minimum } ?: bad()
    }
    private fun text(value: Any?, max: Int): String = (value as? String)?.trim()?.takeIf {
        it.isNotBlank() && it.length <= max && it.none { c -> c.code < 32 || c.code == 127 }
    } ?: bad()
    private fun adult(row: Map<String, Any?>): Boolean = when (row["adult"]) { null, false -> false; true -> true; else -> bad() }
    fun ids(body: String): List<Int> {
        val row = root(body); if (integer(row["page"]) != 1) bad()
        val results = row["results"] as? List<*> ?: bad(); if (results.size > 20) bad()
        return results.mapNotNull { value -> obj(value).let { if (adult(it)) null else integer(it["id"]) } }.distinct().take(3)
    }
    fun detail(body: String, request: TmdbCatalogRequest, source: String, now: Long): CatalogCandidate? {
        val row = root(body); val id = integer(row["id"]); if (id != request.id || now < 0) bad()
        if (adult(row)) return null
        val title = text(row[if (request.kind == TmdbMediaKind.MOVIE) "title" else "name"], 500)
        val rawDate = row[if (request.kind == TmdbMediaKind.MOVIE) "release_date" else "first_air_date"]
        val year = if (rawDate == null || rawDate == "") null else try {
            LocalDate.parse(text(rawDate, 10)).year.also { if (it !in 1800..3000) bad() }
        } catch (_: Exception) { bad() }
        val runtime = if (request.kind == TmdbMediaKind.TV || row["runtime"] == null) null else integer(row["runtime"], 0).let {
            if (it > 10000) bad(); it.takeIf { n -> n > 0 }
        }
        val genres = row["genres"]?.let { it as? List<*> ?: bad() }.orEmpty(); if (genres.size > 32) bad()
        val topics = genres.map { value -> val genre = obj(value); val genreId = integer(genre["id"])
            val name = text(genre["name"], 200)
            when (genreId) { 878 -> "科幻"; 35 -> "喜剧"; 99 -> "纪录片"; 18 -> "剧情"; 16 -> "动画"; 10765 -> "科幻与奇幻"; else -> name }
        }.toSet()
        val overview = row["overview"]?.let { value ->
            val content = value as? String ?: bad()
            if (content.length > 4000 || content.any { it.code < 32 && it !in "\n\r\t" }) bad()
            content.trim().takeIf { it.isNotEmpty() }
        }
        val poster = (row["poster_path"] as? String)?.let { path -> runCatching { TmdbPoster(path) }.getOrNull() }
        return CatalogCandidate(CandidateIdentity(source, request.kind.path + ":" + id), title, topics, year, runtime, overview, now, poster = poster)
    }
}
