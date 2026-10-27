package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeepSeekHttpClientTest {
    private val fake = "FAKE_DIRECT_CREDENTIAL_00000000"
    private class Store(var value:String? = "FAKE_DIRECT_CREDENTIAL_00000000",var kind:AiKeyVerificationKind?=AiKeyVerificationKind.LIVE):AiSecureKeyStore {
        var revision="initial";var writes=0
        override suspend fun read()=AiKeyRecord(value?.let(AiSecret::fromInput),if(value==null)null else 1L,kind,revision)
        override suspend fun replace(key:AiSecret?,verifiedAt:Long?,kind:AiKeyVerificationKind?):String {
            value=key?.useValue { it };this.kind=kind;revision=java.util.UUID.randomUUID().toString();writes++;return revision
        }
    }
    private class Reply(var status:Int=200,var bytes:ByteArray=byteArrayOf()):HttpURLConnection(URL("https://api.deepseek.com")) {
        var mime="application/json";var encoding:String?=null;var declared:Long=-1;var fail:IOException?=null
        var hold=false;var afterStatus:(()->Unit)?=null;val started=CountDownLatch(1);val released=CountDownLatch(1)
        val body=ByteArrayOutputStream();var disconnected=false;var seen:URL?=null
        override fun connect(){}
        override fun usingProxy()=false
        override fun disconnect(){disconnected=true;released.countDown()}
        override fun getOutputStream()=body
        override fun getResponseCode():Int {
            started.countDown();if(hold&&!released.await(5,TimeUnit.SECONDS))throw SocketTimeoutException("fixture timeout")
            fail?.let { throw it };afterStatus?.invoke();return status
        }
        override fun getInputStream()=ByteArrayInputStream(bytes)
        override fun getContentType()=mime
        override fun getContentEncoding()=encoding
        override fun getContentLengthLong()=declared
    }
    private class Harness(vararg initial:Reply) {
        val replies=java.util.ArrayDeque(initial.toList());val opened=mutableListOf<Reply>()
        val client=DeepSeekHttpClient { url -> synchronized(this) { check(replies.isNotEmpty());replies.removeFirst().also { it.seen=url;opened.add(it) } } }
    }
    private fun balance(available:Boolean=true) = JSONObject().put("is_available",available).put("balance_infos",JSONArray().put(
        JSONObject().put("currency","CNY").put("total_balance","1.00").put("granted_balance","0.00").put("topped_up_balance","1.00"))).toString()
    private fun hintContent()=JSONObject().put("preferredTopics",JSONArray().put("科幻")).put("conditions",JSONObject().put("requiredTopics",JSONArray()).put("excludedTopics",JSONArray())
        .put("minYear",JSONObject.NULL).put("maxYear",JSONObject.NULL).put("maxRuntimeMinutes",JSONObject.NULL))
    private fun completion(content:String=hintContent().toString()):JSONObject=JSONObject().put("id","fixture-completion").put("object","chat.completion").put("model","deepseek-flash")
        .put("choices",JSONArray().put(JSONObject().put("index",0).put("finish_reason","stop").put("message",JSONObject().put("role","assistant").put("content",content))))
        .put("usage",JSONObject().put("prompt_tokens",100).put("completion_tokens",20).put("total_tokens",120))
    private fun reply(text:String=completion().toString(),status:Int=200)=Reply(status,text.toByteArray())
    private fun request()=WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU),AiIntegrationContext(ProfileSnapshot(token=ProfileToken(1,1)),true))
    private suspend fun keys(client:DeepSeekHttpClient,store:Store=Store())=AiKeyConfigurationRepository(store,DeepSeekAiKeyVerifier(client)).also { it.refresh() }
    private suspend fun problem(expected:String,block:suspend()->Unit) {
        val error=runCatching { block() }.exceptionOrNull();assertTrue(error is WorkerProblem);assertEquals(expected,(error as WorkerProblem).code)
    }
    @Test fun verificationUsesOnlyOfficialHttpsBearerGetWithoutProfileOrGeneration()=runBlocking<Unit> {
        val r=reply(balance());val h=Harness(r)
        AiSecret.fromInput(fake).use { h.client.verify(it){true} }
        assertEquals("https://api.deepseek.com/user/balance",r.seen.toString());assertEquals("GET",r.requestMethod)
        assertEquals("Bearer "+fake,r.getRequestProperty("Authorization"));assertNull(r.getRequestProperty("X-DeepSeek-Key"))
        assertEquals(0,r.body.size());assertEquals(10000,r.connectTimeout);assertEquals(30000,r.readTimeout);assertFalse(r.instanceFollowRedirects);assertFalse(r.useCaches)
        assertTrue(r.disconnected);assertEquals(1,h.opened.size)
    }
    @Test fun recommendationSendsBoundedOfficialSchemaAndOnlyEnabledSummaryFields()=runBlocking<Unit> {
        val r=reply();val h=Harness(r);val req=request().copy(profileSummary=WorkerProfileSummary(ProfileSourceSettings(false,false,true),
            listOf(WorkerExplicit("喜剧",PreferenceDirection.DISLIKE)),listOf(WorkerInferred("秘密评分",PreferenceDirection.LIKE,ProfileSource.RATING),WorkerInferred("科幻",PreferenceDirection.LIKE,ProfileSource.FEEDBACK))),
            conditions=CandidateHardConditions(excludedIdentities=setOf(CandidateIdentity("private-source","private-id"))))
        val result=h.client.hints(req,keys(h.client));val body=JSONObject(r.body.toString("UTF-8"));val context=JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("https://api.deepseek.com/chat/completions",r.seen.toString());assertEquals("POST",r.requestMethod);assertEquals("Bearer "+fake,r.getRequestProperty("Authorization"))
        assertEquals("deepseek-flash",body.getString("model"));assertEquals(512,body.getInt("max_tokens"));assertFalse(body.getBoolean("stream"))
        assertEquals("disabled",body.getJSONObject("thinking").getString("type"));assertEquals("json_object",body.getJSONObject("response_format").getString("type"))
        assertEquals(1,context.getJSONArray("inferred").length());assertEquals("科幻",context.getJSONArray("inferred").getJSONObject(0).getString("topic"))
        for(value in listOf(fake,req.requestId,"profileVersion","sourceSettingsVersion","private-id","秘密评分","Room","poster"))assertFalse(context.toString().contains(value))
        assertEquals(listOf("科幻"),result.hints.preferredTopics);assertEquals(120L,result.usage.totalTokens);assertEquals(1,result.usage.httpRequests);assertTrue(result.usage.complete)
    }
    @Test fun missingAndMockOnlyKeysNeverOpenAConnection()=runBlocking<Unit> {
        val h=Harness()
        problem("KEY_REQUIRED"){h.client.hints(request(),keys(h.client,Store(null,null)))}
        problem("KEY_REQUIRED"){h.client.hints(request(),keys(h.client,Store("TEST_ONLY_BYOK_fixture",AiKeyVerificationKind.MOCK)))}
        assertTrue(h.opened.isEmpty())
    }
    @Test fun syntheticOrInvalidLiveCredentialsNeverReachHttp()=runBlocking<Unit> {
        val h=Harness()
        for(value in listOf("TEST_ONLY_BYOK_fixture","tiny","非ASCII".repeat(8)))AiSecret.fromInput(value).use { key->problem("KEY_INVALID"){h.client.verify(key){true}} }
        assertTrue(h.opened.isEmpty());assertEquals("DeepSeekHttpClient(redacted)",h.client.toString())
    }
    @Test fun authPermissionBillingRateAndRedirectFailuresNeverRetryOrExposeBodies()=runBlocking<Unit> {
        for((status,code)in mapOf(401 to "UPSTREAM_AUTH",403 to "UPSTREAM_FORBIDDEN",402 to "UPSTREAM_BILLING",429 to "UPSTREAM_RATE_LIMIT",302 to "INVALID_RESPONSE",400 to "UPSTREAM_REQUEST_REJECTED",422 to "UPSTREAM_REQUEST_REJECTED")) {
            val r=reply(fake,status);val h=Harness(r);problem(code){h.client.hints(request(),keys(h.client))};assertEquals(1,h.opened.size);assertTrue(r.disconnected)
        }
    }
    @Test fun transientServerFailureRetriesOnlyOnceAndDoesNotClaimCompleteBillingUsage()=runBlocking<Unit> {
        for(status in listOf(500,502,503,504)) {
            val h=Harness(reply(status=status),reply());val result=h.client.hints(request(),keys(h.client))
            assertEquals(2,h.opened.size);assertEquals(2,result.usage.httpRequests);assertEquals(1,result.usage.retries);assertFalse(result.usage.complete);assertNull(result.usage.totalTokens)
        }
    }
    @Test fun consecutiveTransientFailuresHaveExactlyTwoAttempts()=runBlocking<Unit> {
        val h=Harness(reply(status=503),reply(status=503),reply());problem("UPSTREAM_UNAVAILABLE"){h.client.hints(request(),keys(h.client))};assertEquals(2,h.opened.size)
    }
    @Test fun networkAndTimeoutRetryOnceButValidationNeverAutomaticallyRetries()=runBlocking<Unit> {
        for(error in listOf(IOException(fake),SocketTimeoutException(fake))) {
            val r=reply().also { it.fail=error };val h=Harness(r,reply());assertEquals(1,h.client.hints(request(),keys(h.client)).usage.retries);assertEquals(2,h.opened.size)
            val v=Harness(reply().also { it.fail=error },reply(balance()));AiSecret.fromInput(fake).use { key->problem(if(error is SocketTimeoutException)"TRANSPORT_TIMEOUT" else "OFFLINE"){v.client.verify(key){true}} };assertEquals(1,v.opened.size)
        }
    }
    @Test fun certificateFailureIsNotRetried()=runBlocking<Unit> {
        val h=Harness(reply().also { it.fail=javax.net.ssl.SSLHandshakeException(fake) },reply());problem("SECURE_CONNECTION_FAILED"){h.client.hints(request(),keys(h.client))};assertEquals(1,h.opened.size)
    }
    @Test fun unsafeContentMetadataOversizedStreamsAndMalformedUtf8AreRejected()=runBlocking<Unit> {
        val invalid=listOf(reply().also { it.mime="text/html" },reply().also { it.encoding="gzip" },reply().also { it.declared=32769 },Reply(200,ByteArray(32769){32}),Reply(200,byteArrayOf(0xc3.toByte(),0x28)))
        for(r in invalid){val h=Harness(r);problem("INVALID_RESPONSE"){h.client.hints(request(),keys(h.client))};assertEquals(1,h.opened.size)}
    }
    @Test fun malformedDuplicateTrailingAndInventedCandidateOutputsAreRejectedWithoutRetry()=runBlocking<Unit> {
        val invalid=listOf("{",completion().toString()+" {}",completion().toString().replace("\"object\":","\"object\":\"chat.completion\",\"object\":"),
            completion(hintContent().put("movies",JSONArray().put("虚构电影")).toString()).toString(),completion("{\"preferredTopics\":[],\"preferredTopics\":[]}").toString(),
            completion().also { it.getJSONArray("choices").getJSONObject(0).put("finish_reason","length") }.toString(),
            completion().also { it.getJSONObject("usage").put("total_tokens",121) }.toString(),
            completion().also { it.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls",JSONArray()) }.toString())
        for(text in invalid){val h=Harness(reply(text));problem("MODEL_FORMAT"){h.client.hints(request(),keys(h.client))};assertEquals(1,h.opened.size)}
    }
    @Test fun escapedCredentialEchoesInNestedContentAndUnknownEnvelopeFieldsAreRejected()=runBlocking<Unit> {
        val leaked=hintContent().put("preferredTopics",JSONArray().put(fake)).toString()
        val escaped=leaked.replace("F","\\u0046")
        for(text in listOf(completion(leaked).toString(),completion(escaped).toString(),completion().put("unexpected",fake).toString())){
            val h=Harness(reply(text));problem("MODEL_FORMAT"){h.client.hints(request(),keys(h.client))};assertEquals(1,h.opened.size)
        }
    }
    @Test fun balanceUnavailableMalformedOrOversizedNeverSavesOrOverwritesOldKey()=runBlocking<Unit> {
        for((text,expected)in listOf(balance(false) to AiKeyProblem.ACCOUNT_UNAVAILABLE,JSONObject(balance(false)).put("balance_infos",JSONArray()).toString() to AiKeyProblem.ACCOUNT_UNAVAILABLE,"{}" to AiKeyProblem.UNAVAILABLE," ".repeat(8193) to AiKeyProblem.UNAVAILABLE)){
            val h=Harness(reply(text));val store=Store();val repo=keys(h.client,store);val before=repo.state.value
            val error=runCatching { repo.verifyAndSave(fake+"_new") }.exceptionOrNull() as AiKeyException
            assertEquals(expected,error.problem);assertEquals(fake,store.value);assertEquals(before.revision,repo.state.value.revision);assertEquals(0,store.writes)
        }
    }
    @Test fun validationSuccessCommitsOnlyAfterTheAccountLookupCompletes()=runBlocking<Unit> {
        val r=reply(balance()).also { it.hold=true };val h=Harness(r);val store=Store();val repo=keys(h.client,store)
        val pending=async { repo.verifyAndSave(fake+"_new") };withContext(Dispatchers.IO){assertTrue(r.started.await(5,TimeUnit.SECONDS))}
        assertEquals(fake,store.value);assertEquals(0,store.writes);r.released.countDown();pending.await();assertEquals(fake+"_new",store.value);assertEquals(1,store.writes)
    }
    @Test fun duplicateTaskIdCannotCreateASecondConcurrentHttpRequest()=runBlocking<Unit> {
        val r=reply().also { it.hold=true };val h=Harness(r);val keys=keys(h.client);val req=request()
        val first=async { h.client.hints(req,keys) };withContext(Dispatchers.IO){assertTrue(r.started.await(5,TimeUnit.SECONDS))}
        problem("REQUEST_IN_PROGRESS"){h.client.hints(req,keys)};r.released.countDown();first.await();assertEquals(1,h.opened.size)
    }
    @Test fun deletingKeyDisconnectsPendingRecommendationAndPreventsRetryAndNewCalls()=runBlocking<Unit> {
        val r=reply(status=503).also { it.hold=true };val h=Harness(r,reply());val store=Store();val keys=keys(h.client,store)
        val pending=async { runCatching { h.client.hints(request(),keys) } };withContext(Dispatchers.IO){assertTrue(r.started.await(5,TimeUnit.SECONDS))}
        keys.delete();assertTrue(r.disconnected);assertNull(store.value);assertEquals("STALE_KEY",(pending.await().exceptionOrNull() as WorkerProblem).code)
        problem("KEY_REQUIRED"){h.client.hints(request(),keys)};assertEquals(1,h.opened.size)
    }
    @Test fun deletingKeyDisconnectsVerificationAndCannotRestoreItsLateSuccess()=runBlocking<Unit> {
        val r=reply(balance()).also { it.hold=true };val h=Harness(r);val store=Store();val repo=keys(h.client,store)
        val pending=async { runCatching { repo.verifyAndSave(fake+"_new") } };withContext(Dispatchers.IO){assertTrue(r.started.await(5,TimeUnit.SECONDS))}
        repo.delete();assertTrue(r.disconnected);assertEquals(AiKeyProblem.STALE_OPERATION,(pending.await().exceptionOrNull() as AiKeyException).problem);assertNull(store.value)
    }
    @Test fun coroutineCancellationDisconnectsHttpWithoutAutomaticRetry()=runBlocking<Unit> {
        val r=reply().also { it.hold=true };val h=Harness(r,reply());val keys=keys(h.client)
        val pending=launch { h.client.hints(request(),keys) };withContext(Dispatchers.IO){assertTrue(r.started.await(5,TimeUnit.SECONDS))}
        pending.cancelAndJoin();assertTrue(r.disconnected);assertEquals(1,h.opened.size)
    }
    @Test fun staleProfileGuardPreventsInitialDispatchAndAnyRetry()=runBlocking<Unit> {
        val empty=Harness();AiSecret.fromInput(fake).use { key->problem("STALE_KEY"){empty.client.verify(key){false}} };assertTrue(empty.opened.isEmpty())
        val current=AtomicBoolean(true);val r=reply(status=503).also { it.afterStatus={current.set(false)} };val h=Harness(r,reply())
        problem("STALE_KEY"){h.client.hints(request(),keys(h.client)){current.get()}};assertEquals(1,h.opened.size)
    }
}
