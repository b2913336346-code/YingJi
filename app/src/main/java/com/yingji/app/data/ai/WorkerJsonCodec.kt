package com.yingji.app.data.ai

import android.util.JsonReader
import android.util.JsonToken
import com.yingji.app.domain.ai.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal
import java.time.Instant

/** Strict JSON and exact v1 keys. Duplicate fields, coercion and trailing data are rejected. */
class WorkerJsonCodec(private val mockService: Boolean = false) {
    private fun bad(): Nothing = throw WorkerProblem("INVALID_RESPONSE")
    private fun root(text: String): Map<String, Any?> {
        var nodes = 0
        fun read(reader: JsonReader, depth: Int): Any? {
            if (depth > 16 || ++nodes > 4096) bad()
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                    reader.beginObject()
                    while (reader.hasNext()) { val name = reader.nextName(); if (containsKey(name)) bad(); put(name, read(reader, depth + 1)) }
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
            if (text.toByteArray(Charsets.UTF_8).size > 32768) bad()
            return JsonReader(StringReader(text)).use { reader ->
                reader.setLenient(false)
                val value = read(reader, 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) bad()
                obj(value)
            }
        } catch (e: WorkerProblem) { throw e } catch (_: Exception) { bad() }
    }
    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = (value as? Map<String, Any?>) ?: bad()
    private fun exact(value: Any?, vararg keys: String): Map<String, Any?> = obj(value).also { if (it.keys != keys.toSet()) bad() }
    private fun text(value: Any?, max: Int = 100): String = (value as? String)?.takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= max && it.none { c -> c.code < 32 } } ?: bad()
    private fun bool(value: Any?): Boolean = value as? Boolean ?: bad()
    private fun number(value: Any?, max: Long = WorkerRequestBuilder.MAX_SAFE_INTEGER): Long {
        val result = try { (value as? BigDecimal)?.longValueExact() } catch (_: ArithmeticException) { null }
        return result?.takeIf { it in 0..max } ?: bad()
    }
    private fun nullableNumber(value: Any?) = if (value == null) null else number(value)
    private fun money(value: Any?, nullable: Boolean = false) { if (nullable && value == null) return; if (value !is String || !Regex("0|[1-9][0-9]{0,30}").matches(value)) bad() }
    private fun topics(value: Any?): List<String> {
        val rows = value as? List<*> ?: bad(); if (rows.size > 12) bad()
        return rows.map { WorkerRequestBuilder.topic(text(it, 60)) }.also { if (it.distinct().size != it.size) bad() }
    }
    private fun conditions(value: Any?): CandidateHardConditions {
        val row = exact(value, "requiredTopics", "excludedTopics", "minYear", "maxYear", "maxRuntimeMinutes")
        fun bound(name: String, min: Int, max: Int): Int? = row[name]?.let { number(it, max.toLong()).toInt().also { value -> if (value < min) bad() } }
        val result = CandidateHardConditions(topics(row["requiredTopics"]).toSet(), topics(row["excludedTopics"]).toSet(), bound("minYear", 1800, 3000), bound("maxYear", 1800, 3000), bound("maxRuntimeMinutes", 1, 10000))
        if (result.requiredTopics.any { it in result.excludedTopics } || (result.minYear != null && result.maxYear != null && result.minYear > result.maxYear)) bad()
        return result
    }
    private fun quota(value: Any?): AiQuotaSnapshot {
        val row = obj(value)
        if (row.keys == setOf("status") && row["status"] == "UNAVAILABLE") return AiQuotaSnapshot(AiQuotaStatus.UNAVAILABLE, mockService)
        exact(row, "status", "day", "timeZone", "limit", "used", "reserved", "remaining", "resetsAt", "budget")
        val status = try { AiQuotaStatus.valueOf(text(row["status"])) } catch (_: Exception) { bad() }
        if (status == AiQuotaStatus.UNAVAILABLE || !Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text(row["day"]))) bad()
        if (text(row["timeZone"]) !in setOf("Asia/Shanghai", "UTC")) bad()
        val reset = text(row["resetsAt"], 64); try { Instant.parse(reset) } catch (_: Exception) { bad() }
        val budget = exact(row["budget"], "currency", "status", "estimatedMicros", "reservedMicros", "dailyLimitMicros", "totalEstimatedMicros", "totalReservedMicros", "totalLimitMicros", "actualCostVerified")
        if (budget["currency"] != "CNY" || budget["status"] !in setOf("CONFIGURED", "UNCONFIGURED", "REVIEW_REQUIRED") || budget["actualCostVerified"] != false) bad()
        for (key in listOf("estimatedMicros", "reservedMicros", "totalEstimatedMicros", "totalReservedMicros")) money(budget[key])
        money(budget["dailyLimitMicros"], true); money(budget["totalLimitMicros"], true)
        val limit = number(row["limit"], Int.MAX_VALUE.toLong()).toInt(); val used = number(row["used"], Int.MAX_VALUE.toLong()).toInt()
        number(row["reserved"], Int.MAX_VALUE.toLong())
        val remaining = number(row["remaining"], Int.MAX_VALUE.toLong()).toInt()
        return AiQuotaSnapshot(status, mockService, limit, used, remaining, reset)
    }
    private fun usage(value: Any?): WorkerUsage {
        val row = exact(value, "complete", "httpRequests", "retries", "promptTokens", "completionTokens", "totalTokens")
        val prompt = nullableNumber(row["promptTokens"]); val completion = nullableNumber(row["completionTokens"]); val total = nullableNumber(row["totalTokens"])
        if (prompt != null && completion != null && total != null && prompt + completion != total) bad()
        return WorkerUsage(bool(row["complete"]), number(row["httpRequests"], Int.MAX_VALUE.toLong()).toInt(), number(row["retries"], Int.MAX_VALUE.toLong()).toInt(), prompt, completion, total)
    }
    private fun task(row: Map<String, Any?>, httpStatus: Int, request: WorkerHintRequest?): WorkerTaskResponse {
        exact(row, "schemaVersion", "requestId", "profileVersion", "sourceSettingsVersion", "status", "recommendationHints", "usage", "quota", "error")
        if (number(row["schemaVersion"]) != 1L) bad()
        val status = when (row["status"]) { "success" -> WorkerTaskStatus.SUCCESS; "pending" -> WorkerTaskStatus.PENDING; "error" -> WorkerTaskStatus.ERROR; else -> bad() }
        if ((status == WorkerTaskStatus.SUCCESS && httpStatus != 200) || (status == WorkerTaskStatus.PENDING && httpStatus != 202) || (status == WorkerTaskStatus.ERROR && httpStatus !in 400..599)) bad()
        val id = row["requestId"]?.let { text(it, 36).also { value -> if (!Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-4[a-fA-F0-9]{3}-[89abAB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}").matches(value)) bad() } }
        val p = nullableNumber(row["profileVersion"]); val s = nullableNumber(row["sourceSettingsVersion"])
        if ((p == null) != (s == null) || (id == null) != (p == null) || (status != WorkerTaskStatus.ERROR && id == null)) bad()
        val token = p?.let { ProfileToken(it, s!!) }
        if (request != null && ((id != null && id != request.requestId) || (token != null && token != request.token))) bad()
        val error = row["error"]?.let { value -> val e = exact(value, "code", "retryable"); WorkerProblem(text(e["code"], 64), bool(e["retryable"])) }
        if ((status == WorkerTaskStatus.ERROR) != (error != null)) bad()
        val hints = row["recommendationHints"]?.let { value -> val h = exact(value, "preferredTopics", "conditions"); WorkerHints(topics(h["preferredTopics"]), conditions(h["conditions"])) }
        if ((status == WorkerTaskStatus.SUCCESS) != (hints != null)) bad()
        return WorkerTaskResponse(id, token, status, hints, quota(row["quota"]), usage(row["usage"]), error)
    }
    fun decodeTask(text: String, status: Int, request: WorkerHintRequest) = task(root(text), status, request)
    fun decodeQuota(text: String, status: Int): AiQuotaSnapshot {
        val row = root(text)
        if (row["status"] == "error") throw (task(row, status, null).error ?: WorkerProblem("SERVICE_UNAVAILABLE"))
        exact(row, "schemaVersion", "status", "quota", "daily", "total")
        if (status != 200 || number(row["schemaVersion"]) != 1L || row["status"] != "success") bad()
        for (name in listOf("daily", "total")) {
            val stats = exact(row[name], "successes", "reservedTasks", "failedTasks", "httpRequests", "retries", "knownPromptTokens", "knownCompletionTokens", "unknownUsageAttempts", "committedMicros", "reservedMicros")
            for ((key, value) in stats) if (key.endsWith("Micros")) money(value) else number(value)
        }
        return quota(row["quota"])
    }
    fun rejectSecret(text: String, secret: String) {
        fun scan(value: Any?) {
            when (value) {
                is String -> if (secret.isNotEmpty() && value.contains(secret, ignoreCase = true)) bad()
                is Map<*, *> -> value.forEach { (key, item) -> scan(key); scan(item) }
                is List<*> -> value.forEach(::scan)
            }
        }
        scan(root(text))
    }
    fun decodeVerification(text: String, status: Int, requestId: String) {
        val row = root(text)
        exact(row, "schemaVersion", "requestId", "status", "verified", "verifiedAt", "usage", "quota", "error")
        if (number(row["schemaVersion"]) != 1L || (row["requestId"] != null && row["requestId"] != requestId)) bad()
        val used = usage(row["usage"]); quota(row["quota"])
        if (row["status"] == "error") {
            if (status !in 400..599 || bool(row["verified"]) || row["verifiedAt"] != null) bad()
            val error = exact(row["error"], "code", "retryable")
            val code = text(error["code"], 64)
            if (!Regex("[A-Z][A-Z0-9_]{0,63}").matches(code)) bad()
            throw WorkerProblem(code, bool(error["retryable"]))
        }
        if (row["status"] == "pending") {
            if (status != 202 || row["requestId"] != requestId || bool(row["verified"]) || row["verifiedAt"] != null || row["error"] != null) bad()
            throw WorkerProblem("TASK_PENDING")
        }
        if (status != 200 || row["status"] != "success" || row["requestId"] != requestId || !bool(row["verified"]) || row["error"] != null || number(row["verifiedAt"]) <= 0 || !used.complete || used.httpRequests != 1 || used.retries != 0 || used.promptTokens != 0L || used.completionTokens != 0L || used.totalTokens != 0L) bad()
    }
    fun encode(request: WorkerHintRequest): String {
        fun conditions(value: CandidateHardConditions) = JSONObject().put("requiredTopics", JSONArray(value.requiredTopics.sorted())).put("excludedTopics", JSONArray(value.excludedTopics.sorted()))
            .put("minYear", value.minYear ?: JSONObject.NULL).put("maxYear", value.maxYear ?: JSONObject.NULL).put("maxRuntimeMinutes", value.maxRuntimeMinutes ?: JSONObject.NULL)
        val profile = request.profileSummary
        val summary = JSONObject().put("enabledSources", JSONObject().put("ratings", profile.enabledSources.ratingsEnabled).put("behavior", profile.enabledSources.behaviorEnabled).put("feedback", profile.enabledSources.feedbackEnabled))
            .put("explicit", JSONArray().apply { profile.explicit.forEach { put(JSONObject().put("topic", it.topic).put("direction", it.direction.name)) } })
            .put("inferred", JSONArray().apply { profile.inferred.forEach { put(JSONObject().put("topic", it.topic).put("direction", it.direction.name).put("source", it.source.name)) } })
        return JSONObject().put("schemaVersion", 1).put("requestId", request.requestId).put("mode", request.mode.name).put("userQuery", request.userQuery)
            .put("externalProcessingConsent", true).put("profileVersion", request.token.profileVersion).put("sourceSettingsVersion", request.token.sourceSettingsVersion)
            .put("profileSummary", summary).put("hardConditions", conditions(request.conditions))
            .put("excludeExternalIds", JSONArray().apply { request.conditions.excludedIdentities.sortedWith(compareBy<CandidateIdentity> { it.sourceId }.thenBy { it.externalId }).forEach { put(JSONObject().put("sourceId", it.sourceId).put("externalId", it.externalId)) } })
            .toString().also { if (it.toByteArray(Charsets.UTF_8).size > 16384) throw WorkerProblem("PAYLOAD_TOO_LARGE") }
    }
}
