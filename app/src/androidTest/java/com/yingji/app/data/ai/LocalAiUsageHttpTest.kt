package com.yingji.app.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.ai.AiTestStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class LocalAiUsageHttpTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val completion="""{"id":"fixture-completion","object":"chat.completion","model":"deepseek-flash","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{\"preferredTopics\":[],\"conditions\":{\"requiredTopics\":[],\"excludedTopics\":[],\"minYear\":null,\"maxYear\":null,\"maxRuntimeMinutes\":null}}"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    private class Reply(private val status:Int,private val text:String):HttpURLConnection(URL("https://api.deepseek.com")) {
        override fun connect(){}; override fun disconnect(){}; override fun usingProxy()=false
        override fun getOutputStream()=ByteArrayOutputStream()
        override fun getResponseCode()=status
        override fun getContentType()="application/json"
        override fun getInputStream()=ByteArrayInputStream(text.toByteArray())
    }
    private fun request()=WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU),AiIntegrationContext(ProfileSnapshot(token=ProfileToken(1,1)),true))
    private suspend fun keys(client:DeepSeekHttpClient)=AiKeyConfigurationRepository(object:AiSecureKeyStore {
        override suspend fun read()=AiKeyRecord(AiSecret.fromInput("FAKE_DIRECT_CREDENTIAL_00000000"),1,AiKeyVerificationKind.LIVE,"fixture")
        override suspend fun replace(key:AiSecret?,verifiedAt:Long?,kind:AiKeyVerificationKind?)=error("read only fixture")
    },DeepSeekAiKeyVerifier(client)).also { it.refresh() }
    @Test fun retrySuccessUsesTwoHttpCallsButPersistsOneSuccessfulTask()=runBlocking<Unit> {
        val usage=LocalAiUsageRepository(context,AiTestStore()); var http=0
        val client=DeepSeekHttpClient(usage) { http++; Reply(if(http==1)503 else 200,completion) }
        assertEquals(1,client.hints(request(),keys(client)).usage.retries)
        val s=usage.snapshot(); assertEquals(2,http); assertEquals(1L,s.started); assertEquals(1L,s.succeeded); assertEquals(0L,s.failed)
    }
    @Test fun twoFailedAttemptsPersistOneErrorAndNoSuccess()=runBlocking<Unit> {
        val usage=LocalAiUsageRepository(context,AiTestStore()); var http=0
        val client=DeepSeekHttpClient(usage) { http++; Reply(503,"private body") }
        assertEquals("UPSTREAM_UNAVAILABLE",(runCatching { client.hints(request(),keys(client)) }.exceptionOrNull() as WorkerProblem).code)
        assertEquals(2,http); assertEquals(1L,usage.snapshot().failed); assertEquals(0L,usage.snapshot().succeeded)
    }
    @Test fun softLimitAndStorageFailureStopBeforeOpeningHttp()=runBlocking<Unit> {
        val store=AiTestStore(); val usage=LocalAiUsageRepository(context,store); repeat(30){usage.task { }}; var http=0
        val client=DeepSeekHttpClient(usage) { http++; Reply(200,completion) }; val keys=keys(client)
        assertEquals(LocalAiUsageProblem.LIMIT,(runCatching { client.hints(request(),keys) }.exceptionOrNull() as LocalAiUsageException).problem)
        store.failWrites=true
        assertEquals(LocalAiUsageProblem.STORAGE,(runCatching { client.hints(request(),keys) }.exceptionOrNull() as LocalAiUsageException).problem); assertEquals(0,http)
    }
    @Test fun balanceVerificationDoesNotConsumeTaskAllowanceEvenWhenLocalLimitIsReached()=runBlocking<Unit> {
        val usage=LocalAiUsageRepository(context,AiTestStore()); repeat(30){usage.task { }}; val before=usage.snapshot(); var http=0
        val client=DeepSeekHttpClient(usage) { http++; Reply(200,"""{"is_available":true,"balance_infos":[]}""") }
        AiSecret.fromInput("FAKE_DIRECT_CREDENTIAL_00000000").use { client.verify(it){true} }
        assertEquals(1,http); assertEquals(before,usage.snapshot())
    }
}
