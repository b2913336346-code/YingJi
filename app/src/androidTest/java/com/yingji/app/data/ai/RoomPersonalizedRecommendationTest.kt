package com.yingji.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.*
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.ai.AiTestStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RoomPersonalizedRecommendationTest {
 private val context=ApplicationProvider.getApplicationContext<Context>()
 private lateinit var db:AppDatabase
 private lateinit var movies:MovieRepository
 private lateinit var profiles:ProfileRepository
 private lateinit var manager:RoomLocalProfileManagementRepository
 private lateinit var keys:AiKeyConfigurationRepository
 private lateinit var usage:LocalAiUsageRepository
 private var grant=TmdbSourcePermission(CandidateLicenseState.ACTIVE,"Synthetic test permission only",true,true,true)
 private val content="""{"preferredTopics":["喜剧","科幻"],"conditions":{"requiredTopics":[],"excludedTopics":[],"minYear":null,"maxYear":null,"maxRuntimeMinutes":null}}"""
 private fun envelope()=JSONObject().put("id","fixture").put("object","chat.completion").put("model","deepseek-flash")
  .put("choices",org.json.JSONArray().put(JSONObject().put("index",0).put("finish_reason","stop").put("message",JSONObject().put("role","assistant").put("content",content))))
  .put("usage",JSONObject().put("prompt_tokens",1).put("completion_tokens",1).put("total_tokens",2)).toString()
 private inner class Reply(val code:Int=200,val held:Boolean=false):HttpURLConnection(URL("https://api.deepseek.com")){
  val body=ByteArrayOutputStream();val started=CountDownLatch(1);val release=CountDownLatch(1);@Volatile var disconnected=false
  override fun connect(){};override fun usingProxy()=false;override fun disconnect(){disconnected=true;release.countDown()}
  override fun getOutputStream()=body
  override fun getResponseCode():Int{started.countDown();if(held)check(release.await(8,TimeUnit.SECONDS));return code}
  override fun getContentType()="application/json";override fun getInputStream()=ByteArrayInputStream(envelope().toByteArray())
 }
 private lateinit var reply:Reply
 private var calls=0
 private lateinit var ranker:RoomPersonalizedRecommendation
 private fun catalog()=CandidateCatalog(listOf(CandidateSource("tmdb",CandidateSourceKind.VERIFIED_CATALOG,"App-authored typed identity fixtures only",
  CandidateLicense(grant.state,grant.evidence,grant.displayAllowed,grant.importAllowed,false,grant.validUntil))),listOf(
   CatalogCandidate(CandidateIdentity("tmdb","movie:7"),"虚构科幻样本",setOf("科幻"),2020,90,verifiedAt=100),
   CatalogCandidate(CandidateIdentity("tmdb","tv:8"),"虚构喜剧样本",setOf("喜剧"),2021,null,verifiedAt=100)))
 @Before fun setup()=runBlocking<Unit>{
  DatabaseHealthMonitor.resetForTesting();db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();movies=MovieRepository(db)
  db.categoryDao().insert(Category(7,"分类",false));db.tagDao().insert(Tag(7,"科幻"));db.tagDao().insert(Tag(8,"喜剧"))
  movies.insertWithRelations(Movie(41,"RAW_PRIVATE_TITLE","/private/local-poster.png","分类","已看完",createTime=123,rating=8.5),7,setOf(7))
  profiles=ProfileRepository(db,ObserveLibraryRepository(movies)){100}
  manager=RoomLocalProfileManagementRepository(profiles,CategoryRepository(db.categoryDao(),db.tagDao()))
  profiles.setSources(ProfileSourceSettings(true,true,true));profiles.setExplicitPreference(ProfileFeature.tag(8,"喜剧"),PreferenceDirection.DISLIKE)
  keys=AiKeyConfigurationRepository(DirectTestKeyStore("FAKE_PERSONALIZED_TEST_KEY",AiKeyVerificationKind.LIVE),DisabledAiKeyVerifier());keys.refresh()
  usage=LocalAiUsageRepository(context,AiTestStore());reply=Reply();calls=0
  val client=DeepSeekHttpClient(usage){url->assertEquals("https://api.deepseek.com/chat/completions",url.toString());calls++;reply}
  ranker=RoomPersonalizedRecommendation(profiles,keys,{grant},false,{current->DirectAiService(keys,usage,client,current)},client::cancelPending,{100},manager)
 }
 @After fun close(){db.close();DatabaseHealthMonitor.resetForTesting()}
 private suspend fun failure(block:suspend()->Unit):Throwable=runCatching{block()}.exceptionOrNull()?:throw AssertionError("Expected rejection")
 @Test fun realRoomProfileVerifiedFactsAndInjectedDirectServiceUseMinimalSummaryAndNoHistoryOrCache()=runBlocking<Unit>{
  val before=movies.observeAll().first();val consent=ranker.prepare(catalog());assertEquals(0,calls);assertEquals(0L,usage.snapshot().started)
  val result=ranker.recommend(catalog(),consent){true};assertEquals(1,calls);assertEquals(listOf("movie:7"),result.items.map{it.candidate.identity.externalId})
  assertEquals(AiRecommendationOrigin.WORKER,result.origin);assertEquals(before,movies.observeAll().first());assertEquals(0,db.candidateImportDao().let{db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ai_candidate_imports").use{it.moveToFirst();it.getInt(0)}})
  val payload=JSONObject(reply.body.toString("UTF-8"));val summary=payload.getJSONArray("messages").getJSONObject(1).getString("content")
  for(value in listOf("RAW_PRIVATE_TITLE","category:","tag:","8.5","/private/","profileVersion","sourceSettingsVersion","FAKE_PERSONALIZED","verifiedAt","movie:7"))assertFalse(value,summary.contains(value))
  assertTrue(summary.contains("科幻"));assertTrue(summary.contains("喜剧"));assertEquals(1L,usage.snapshot().succeeded)
  val system=payload.getJSONArray("messages").getJSONObject(0).getString("content")
  val example=JSONObject(system.substringAfter("Example JSON output: ").substringBefore(". The example"))
  assertEquals(setOf("preferredTopics","conditions"),example.keys().asSequence().toSet());assertTrue(example.getJSONObject("conditions").isNull("maxYear"))
  ranker.recommend(catalog(),ranker.prepare(catalog())){true};assertEquals(2,calls) // no retained metadata cache
 }
 @Test fun profileOrSessionChangeAfterConsentRejectsBeforeAnyModelRequest()=runBlocking<Unit>{
  val consent=ranker.prepare(catalog());profiles.setSources(ProfileSourceSettings(false,false,false))
  assertTrue(failure{ranker.recommend(catalog(),consent){true}} is WorkerProblem);assertEquals(0,calls)
  val next=ranker.prepare(catalog());assertTrue(failure{ranker.recommend(catalog(),next){false}} is WorkerProblem);assertEquals(0,calls)
 }
 @Test fun expiredRevokedUnknownAndAiDisallowedPermissionsNeverReachTheModel()=runBlocking<Unit>{
  val initial=grant
  for(value in listOf(initial.copy(state=CandidateLicenseState.REVOKED),initial.copy(state=CandidateLicenseState.UNKNOWN),initial.copy(aiRecommendationAllowed=false),initial.copy(validUntil=100))){grant=value;assertTrue(failure{ranker.prepare(catalog())} is TmdbProblem);assertEquals(0,calls)}
 }
 @Test fun renamedTagUsesCurrentLabelAndInvalidatesPreviouslyReviewedSummary()=runBlocking<Unit>{
  val consent=ranker.prepare(catalog());CategoryRepository(db.categoryDao(),db.tagDao()).renameTag(Tag(8,"喜剧"),"纪录片")
  assertTrue(failure{ranker.recommend(catalog(),consent){true}} is WorkerProblem);assertEquals(0,calls)
  val next=ranker.prepare(catalog());assertTrue(next.summary.explicit.any{it.topic=="纪录片"&&it.direction==PreferenceDirection.DISLIKE})
 }
 @Test fun authenticationFailureFallsBackOnlyToHardFilteredVerifiedFacts()=runBlocking<Unit>{
  reply=Reply(401);val result=ranker.recommend(catalog(),ranker.prepare(catalog())){true}
  assertEquals("UPSTREAM_AUTH",result.problem);assertEquals(listOf("movie:7"),result.items.map{it.candidate.identity.externalId});assertEquals(1,calls)
 }
 @Test fun profileChangeDuringRequestDisconnectsAndRejectsLateResult()=runBlocking<Unit>{
  reply=Reply(held=true);val consent=ranker.prepare(catalog());val task=async(Dispatchers.Default){runCatching{ranker.recommend(catalog(),consent){true}}}
  assertTrue(withContext(Dispatchers.IO){reply.started.await(5,TimeUnit.SECONDS)});profiles.setSources(ProfileSourceSettings(false,false,false))
  val result=withTimeout(5000){task.await()};assertTrue(result.isFailure);assertTrue(reply.disconnected);assertEquals(1,calls)
 }
 @Test fun keyDeletionDuringRequestDisconnectsAndRejectsLateResult()=runBlocking<Unit>{
  reply=Reply(held=true);val consent=ranker.prepare(catalog());val task=async(Dispatchers.Default){runCatching{ranker.recommend(catalog(),consent){true}}}
  assertTrue(withContext(Dispatchers.IO){reply.started.await(5,TimeUnit.SECONDS)});keys.delete()
  assertTrue(withTimeout(5000){task.await()}.isFailure);assertTrue(reply.disconnected);assertEquals(1,calls)
 }
 @Test fun rapidSubmissionIsRejectedAndSharedUsagePreventsAnotherChargedTask()=runBlocking<Unit>{
  reply=Reply(held=true);val consent=ranker.prepare(catalog());val task=async(Dispatchers.Default){ranker.recommend(catalog(),consent){true}}
  assertTrue(withContext(Dispatchers.IO){reply.started.await(5,TimeUnit.SECONDS)})
  assertEquals("REQUEST_IN_PROGRESS",(failure{ranker.recommend(catalog(),consent){true}} as WorkerProblem).code)
  assertEquals(LocalAiUsageProblem.BUSY,(failure{usage.task{}} as LocalAiUsageException).problem)
  reply.release.countDown();assertTrue(task.await().items.isNotEmpty());assertEquals(1,calls)
 }
 @Test fun labelChangeBetweenConsentRecheckAndSnapshotNeverSendsAnUnconfirmedSummary()=runBlocking<Unit>{
  var reads=0
  val changing=object:LocalProfileManagementRepository by manager{
   override fun observe()=flow{
    val snapshot=manager.observe().first();reads++
    emit(if(reads>=3)snapshot.copy(features=snapshot.features.map{if(it.id=="tag:8")it.copy(label="纪录片")else it})else snapshot)
   }
  }
  val guarded=RoomPersonalizedRecommendation(profiles,keys,{grant},false,{error("Unconfirmed summary reached service")},clock={100},localProfiles=changing)
  val consent=guarded.prepare(catalog())
  val rejected=failure{guarded.recommend(catalog(),consent){true}}
  assertEquals("STALE_PROFILE",(rejected as WorkerProblem).code);assertEquals(0,calls)
 }

}
