package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import org.json.JSONArray
import org.json.JSONObject

/** In-process protocol fixture only. No URL, sockets, provider calls or actual-usage accounting. */
internal class DirectMockService(private val keys: AiKeyConfigurationRepository, private val current: () -> Boolean) : AiWorkerService {
    private val codec = DeepSeekJsonCodec()
    override suspend fun quota() = AiQuotaSnapshot.mock()
    override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse = keys.withConfiguredKey(mock = true) { secret ->
        if (!current()) throw WorkerProblem("STALE_KEY")
        codec.request(request) // The exact production request validator also applies to this fixture.
        val content = JSONObject().put("preferredTopics", JSONArray(listOf("科幻", "喜剧", "纪录片")))
            .put("conditions", JSONObject().put("requiredTopics", JSONArray()).put("excludedTopics", JSONArray())
                .put("minYear", JSONObject.NULL).put("maxYear", JSONObject.NULL).put("maxRuntimeMinutes", JSONObject.NULL))
        val response = JSONObject().put("id", "local-protocol-mock").put("object", "chat.completion").put("model", "deepseek-flash")
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", content.toString()))))
            .put("usage", JSONObject().put("prompt_tokens", 0).put("completion_tokens", 0).put("total_tokens", 0))
        val result = secret.useValue { codec.hints(response.toString(), it) }
        if (!current()) throw WorkerProblem("STALE_KEY")
        WorkerTaskResponse(request.requestId, request.token, WorkerTaskStatus.SUCCESS, result.hints, quota(),
            WorkerUsage(true, 0, 0, 0, 0, 0), null)
    }
}
