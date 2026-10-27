package com.yingji.app.presentation.ai

import com.yingji.app.data.ai.WorkerJsonCodec
import com.yingji.app.domain.ai.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkerJsonCodecTest {
    private val codec = WorkerJsonCodec(true)
    private val request = WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU), AiIntegrationContext(ProfileSnapshot(token = ProfileToken(1, 2)), true))
    private fun response() = JSONObject("""{"schemaVersion":1,"requestId":"REPLACE","profileVersion":1,"sourceSettingsVersion":2,"status":"success","recommendationHints":{"preferredTopics":["科幻"],"conditions":{"requiredTopics":[],"excludedTopics":[],"minYear":null,"maxYear":null,"maxRuntimeMinutes":null}},"usage":{"complete":true,"httpRequests":1,"retries":0,"promptTokens":100,"completionTokens":40,"totalTokens":140},"quota":{"status":"AVAILABLE","day":"2026-10-19","timeZone":"Asia/Shanghai","limit":30,"used":1,"reserved":0,"remaining":29,"resetsAt":"2026-10-19T16:00:00.000Z","budget":{"currency":"CNY","status":"CONFIGURED","estimatedMicros":"180","reservedMicros":"0","dailyLimitMicros":"100000000","totalEstimatedMicros":"180","totalReservedMicros":"0","totalLimitMicros":"1000000000","actualCostVerified":false}},"error":null}""").put("requestId", request.requestId)
    private fun reject(text: String, status: Int = 200) {
        try { codec.decodeTask(text, status, request); fail("Invalid JSON accepted") } catch (e: WorkerProblem) { assertEquals("INVALID_RESPONSE", e.code) }
    }
    @Test fun structuredSuccessPendingAndErrorHaveExactTypesAndMatchingIdentity() {
        val good = codec.decodeTask(response().toString(), 200, request)
        assertEquals(WorkerTaskStatus.SUCCESS, good.status); assertEquals(request.token, good.token); assertEquals(140L, good.usage.totalTokens)
        val pending = response().put("status", "pending").put("recommendationHints", JSONObject.NULL)
        pending.getJSONObject("usage").put("complete", false).put("promptTokens", JSONObject.NULL).put("completionTokens", JSONObject.NULL).put("totalTokens", JSONObject.NULL)
        assertEquals(WorkerTaskStatus.PENDING, codec.decodeTask(pending.toString(), 202, request).status)
        val error = pending.put("status", "error").put("error", JSONObject().put("code", "QUOTA_EXHAUSTED").put("retryable", false))
        assertEquals("QUOTA_EXHAUSTED", codec.decodeTask(error.toString(), 429, request).error!!.code)
        reject(error.toString(), 200)
    }
    @Test fun missingUnknownDuplicateTrailingAndCoercedFieldsAreRejected() {
        reject(response().apply { remove("quota") }.toString())
        reject(response().put("extra", true).toString())
        reject(response().toString().replace("{", "{\"schemaVersion\":1,", true))
        reject(response().toString() + " {}")
        reject(response().put("profileVersion", "1").toString())
        reject(response().put("profileVersion", 1.5).toString())
        reject(response().put("profileVersion", 9007199254740992L).toString())
    }
    @Test fun lateVersionsIdsHintsAndContradictoryUsageAreRejected() {
        reject(response().put("profileVersion", 2).toString()); reject(response().put("sourceSettingsVersion", 3).toString())
        reject(response().put("requestId", java.util.UUID.randomUUID().toString()).toString())
        reject(response().apply { getJSONObject("usage").put("totalTokens", 141) }.toString())
        reject(response().apply { getJSONObject("recommendationHints").put("title", "untrusted") }.toString())
        reject(response().apply { getJSONObject("recommendationHints").getJSONObject("conditions").put("minYear", 3001) }.toString())
        reject(response().put("recommendationHints", JSONObject.NULL).toString())
    }
    @Test fun budgetMoneyIsNeverCoercedAndMalformedQuotaIsFailClosed() {
        reject(response().apply { getJSONObject("quota").getJSONObject("budget").put("estimatedMicros", 180) }.toString())
        reject(response().apply { getJSONObject("quota").getJSONObject("budget").put("actualCostVerified", true) }.toString())
        reject(response().apply { getJSONObject("quota").put("remaining", -1) }.toString())
        reject(response().apply { getJSONObject("quota").put("resetsAt", "tomorrow") }.toString())
        reject(response().toString() + " ".repeat(32768))
    }
    @Test fun encodedPayloadHasOnlyContractKeysAndNoRoomEvidence() {
        val p = ProfileSnapshot(token = ProfileToken(3, 4), explicitPreferences = listOf(
            ExplicitPreference(ProfileFeature.category(123, "private category"), PreferenceDirection.LIKE),
            ExplicitPreference(ProfileFeature.topic("科幻"), PreferenceDirection.DISLIKE)))
        val value = JSONObject(codec.encode(WorkerRequestBuilder.build(AiHintInput(AiMode.TONIGHT, "private ignored query"), AiIntegrationContext(p, true))))
        assertEquals(setOf("schemaVersion", "requestId", "mode", "userQuery", "externalProcessingConsent", "profileVersion", "sourceSettingsVersion", "profileSummary", "excludeExternalIds", "hardConditions"), value.keys().asSequence().toSet())
        assertEquals("", value.getString("userQuery")); assertTrue(value.getBoolean("externalProcessingConsent"))
        val text = value.getJSONObject("profileSummary").toString(); assertFalse(text.contains("private")); assertFalse(text.contains("123")); assertFalse(text.contains("movieId"))
        assertEquals("科幻", value.getJSONObject("profileSummary").getJSONArray("explicit").getJSONObject(0).getString("topic"))
    }

    private fun verification() = JSONObject().put("schemaVersion",1).put("requestId",request.requestId).put("status","success")
        .put("verified",true).put("verifiedAt",1792400000000L).put("usage",JSONObject().put("complete",true).put("httpRequests",1).put("retries",0).put("promptTokens",0).put("completionTokens",0).put("totalTokens",0))
        .put("quota",JSONObject().put("status","UNAVAILABLE")).put("error",JSONObject.NULL)
    private fun rejectVerification(value: String, status: Int = 200) {
        assertEquals("INVALID_RESPONSE", (runCatching { codec.decodeVerification(value,status,request.requestId) }.exceptionOrNull() as WorkerProblem).code)
    }
    @Test fun verificationRequiresExactSuccessIdentityNoCoercionAndZeroGenerationTokens() {
        codec.decodeVerification(verification().toString(),200,request.requestId)
        rejectVerification(verification().put("verified","true").toString())
        rejectVerification(verification().put("requestId",java.util.UUID.randomUUID().toString()).toString())
        rejectVerification(verification().put("verifiedAt",JSONObject.NULL).toString())
        rejectVerification(verification().put("balance",10).toString())
        rejectVerification(verification().apply { getJSONObject("usage").put("retries",1) }.toString())
        rejectVerification(verification().apply { getJSONObject("usage").put("promptTokens",1).put("totalTokens",1) }.toString())
        rejectVerification(verification().toString(),202)
        rejectVerification(verification().toString().replace("{","{\"verified\":true,",true))
    }
    @Test fun failedOrPendingVerificationCanNeverBeTreatedAsVerified() {
        val error = verification().put("status","error").put("verified",false).put("verifiedAt",JSONObject.NULL)
            .put("error",JSONObject().put("code","UPSTREAM_AUTH").put("retryable",false))
        assertEquals("UPSTREAM_AUTH", (runCatching { codec.decodeVerification(error.toString(),502,request.requestId) }.exceptionOrNull() as WorkerProblem).code)
        rejectVerification(error.put("verified",true).toString(),502)
        val pending = verification().put("status","pending").put("verified",false).put("verifiedAt",JSONObject.NULL)
        assertEquals("TASK_PENDING",(runCatching { codec.decodeVerification(pending.toString(),202,request.requestId) }.exceptionOrNull() as WorkerProblem).code)
        rejectVerification(pending.put("verifiedAt",123).toString(),202)
    }
    @Test fun credentialEchoCannotEscapeResponseGuardUsingJsonEscapesOrPropertyNames() {
        val key="TEST_ONLY_BYOK_secret"
        for (value in listOf(JSONObject().put("message",key).toString(),JSONObject().put(key,"value").toString(),"{\"message\":\"\\u0054EST_ONLY_BYOK_secret\"}")) {
            assertEquals("INVALID_RESPONSE",(runCatching { codec.rejectSecret(value,key) }.exceptionOrNull() as WorkerProblem).code)
        }
        codec.rejectSecret("{\"status\":\"success\"}",key)
    }
}
