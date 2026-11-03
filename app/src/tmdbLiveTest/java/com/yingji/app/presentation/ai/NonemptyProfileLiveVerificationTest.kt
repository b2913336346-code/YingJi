package com.yingji.app.presentation.ai

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.BuildConfig
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.entity.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.usecase.AddMovieUseCase
import com.yingji.app.presentation.movie.MovieFormScreen
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Explicit manual source set only. Real credentials stay in-app; all work/profile writes use an owned database. */
class NonemptyProfileLiveVerificationTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun oneConfirmedRealTaskAndTaggedImportUseOnlyOwnedRoomData() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("profileLiveApproved") == "one-model-task-isolated-room-tag-import")
        val nonce = requireNotNull(args.getString("profileLiveNonce"))
        require(Regex("[a-f0-9]{32}").matches(nonce))
        val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
        require(app.packageName == "com.yingji.app" && BuildConfig.AI_DIRECT_VERIFICATION_ENABLED)
        val dbName = "live-profile-" + nonce + ".db"
        require(!app.getDatabasePath(dbName).exists())
        val consumed = File(app.noBackupFilesDir, "live-profile-consumed-" + nonce)
        check(consumed.createNewFile()) // A retry of this nonce can never send another paid task.
        val local = LocalProfileTestFixture(dbName)
        val modelCalls = AtomicInteger()
        val tmdbCalls = AtomicInteger()
        val output = JSONObject().put("passed", false).put("mainBusinessDatabaseWritten", false)
        fun digest(file: File): String? = file.takeIf { it.isFile }?.let {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
        }
        val keyFile = File(app.noBackupFilesDir, "ai-byok/config.enc")
        val tokenFile = File(app.noBackupFilesDir, "tmdb-credentials/config.enc")
        val keyBefore = digest(keyFile)
        val tokenBefore = digest(tokenFile)
        var passed = false
        var responseUsage: WorkerUsage? = null
        try {
            app.aiKeyConfigurationRepository.refresh()
            app.tmdbCredentialRepository.refresh()
            assertEquals(AiKeyVerificationKind.LIVE, app.aiKeyConfigurationRepository.state.value.kind)
            assertEquals(AiKeyStorageStatus.CONFIGURED, app.aiKeyConfigurationRepository.state.value.storage)
            assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, app.tmdbCredentialRepository.state.value.status)
            val beforeUsage = app.localAiUsageRepository.snapshot()
            local.db.categoryDao().insert(Category(101, "实网分类", false))
            local.db.tagDao().insert(Tag(101, "实网标签"))
            local.movies.insertWithRelations(Movie(41, "隔离评分样本", null, "实网分类", "已看完", createTime = 100, rating = 9.0), 101, setOf(101))
            local.profiles.setExplicitPreference(ProfileFeature.topic("科幻"), PreferenceDirection.LIKE)
            local.profiles.setExplicitPreference(ProfileFeature.topic("喜剧"), PreferenceDirection.DISLIKE)
            local.profiles.setSources(ProfileSourceSettings(true, false, true))
            val permission = TmdbSourcePermission(CandidateLicenseState.ACTIVE,
                "User-attested TMDB development permission; scope recorded in 2026-10-23-tmdb-authorization-scope.json",
                aiRecommendationAllowed = true, displayAllowed = true)
            val tmdb = TmdbHttpClient(true) { url: URL ->
                check(url.protocol == "https" && url.host == "api.themoviedb.org")
                check(tmdbCalls.incrementAndGet() <= 18)
                url.openConnection() as HttpsURLConnection
            }
            val transport = TmdbAuthenticatedTransport(tmdb, app.tmdbCredentialRepository)
            val catalog = TmdbCandidateCatalogRepository(transport, permission = { permission }).load()
            assertTrue(catalog.candidates.isNotEmpty())
            val model = DeepSeekHttpClient(app.localAiUsageRepository) { url: URL ->
                check(url.toString() == "https://api.deepseek.com/chat/completions")
                check(modelCalls.incrementAndGet() <= 2)
                url.openConnection() as HttpsURLConnection
            }
            val recommender = RoomPersonalizedRecommendation(local.profiles, app.aiKeyConfigurationRepository,
                { permission }, false, serviceFactory = { current ->
                    val delegate = DirectAiService(app.aiKeyConfigurationRepository, app.localAiUsageRepository, model, current)
                    object : AiWorkerService {
                        override suspend fun quota() = delegate.quota()
                        override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse {
                            return delegate.hints(request).also { responseUsage = it.usage }
                        }
                    }
                }, cancelPending = model::cancelPending, localProfiles = local.manager)
            val stale = recommender.prepare(catalog)
            assertTrue(stale.summary.inferred.isNotEmpty())
            local.profiles.setSources(ProfileSourceSettings(false, false, false))
            val disabled = recommender.prepare(catalog)
            assertTrue(disabled.summary.inferred.isEmpty())
            val staleFailure = runCatching { recommender.recommend(catalog, stale) { true } }.exceptionOrNull()
            assertTrue(staleFailure is WorkerProblem && staleFailure.code == "STALE_PROFILE")
            assertEquals(0, modelCalls.get())
            local.profiles.setSources(ProfileSourceSettings(true, false, true))
            val consent = recommender.prepare(catalog)
            assertTrue(consent.summary.explicit.any { it.topic == "科幻" && it.direction == PreferenceDirection.LIKE })
            assertTrue(consent.summary.explicit.any { it.topic == "喜剧" && it.direction == PreferenceDirection.DISLIKE })
            assertTrue(consent.summary.inferred.isNotEmpty())
            assertEquals(beforeUsage, app.localAiUsageRepository.snapshot())
            val profilesBeforeRequest = local.tables(true)
            val importer = TmdbImportUseCase(transport, app.tmdbCredentialRepository,
                { permission.copy(importAllowed = true) }, CandidateImportRepository(local.db, local.movies), AddMovieUseCase(local.movies))
            val phase = mutableStateOf(0)
            val result = mutableStateOf<AiIntegratedResult?>(null)
            val failure = mutableStateOf<Throwable?>(null)
            val importedId = mutableStateOf<Long?>(null)
            val busy = mutableStateOf(false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                ui.setContent {
                    YingJiTheme(ThemeMode.DARK) {
                        when (phase.value) {
                            0 -> PersonalizedConsentDialog(consent, false, { phase.value = -1 }, {
                                if (!busy.value) {
                                    busy.value = true
                                    scope.launch {
                                        try { result.value = withContext(Dispatchers.Default) { recommender.recommend(catalog, consent) { true } }; phase.value = 1 }
                                        catch (e: Throwable) { failure.value = e }
                                        finally { busy.value = false }
                                    }
                                }
                            })
                            1 -> TextButton(onClick = { phase.value = 2 }, modifier = Modifier.testTag("live_fill_form")) { Text("填写已确认候选") }
                            2 -> MovieFormScreen(title = "新增作品", categories = listOf(Category(101, "实网分类", false)),
                                tags = listOf(Tag(101, "实网标签")), initialName = result.value!!.items.first().candidate.title!!,
                                isSaving = busy.value, onBack = { phase.value = 1 }, onSave = { form ->
                                    if (!busy.value) {
                                        busy.value = true
                                        scope.launch {
                                            try {
                                                importedId.value = importer(RecommendedWorkInput(result.value!!.items.first().candidate.identity,
                                                    true, form.name, form.categoryId, form.categoryName, form.tagIds, form.status,
                                                    form.currentEpisode, form.totalEpisode, form.posterPath, form.rating))
                                                phase.value = 3
                                            } catch (e: Throwable) { failure.value = e }
                                            finally { busy.value = false }
                                        }
                                    }
                                })
                            3 -> Text("隔离测试入库成功", Modifier.testTag("live_import_success"))
                            else -> Text("已取消")
                        }
                    }
                }
                ui.onNodeWithTag("tmdb_personal_consent_scroll").performScrollToNode(hasTestTag("tmdb_personal_explicit"))
                ui.onNodeWithTag("tmdb_personal_explicit").assertTextEquals("明确偏好：喜剧 · 排除；科幻 · 喜欢")
                ui.onNodeWithTag("tmdb_personal_consent_confirm").performClick()
                ui.waitUntil(120000) { result.value != null || failure.value != null }
                assertNull("REAL_MODEL_TASK_FAILED", failure.value?.javaClass?.simpleName)
                val ranked = requireNotNull(result.value)
                assertNull("MODEL_FELL_BACK_INSTEAD_OF_SUCCESS", ranked.problem)
                assertTrue("NO_ELIGIBLE_RESULT_FOR_TAG_IMPORT", ranked.items.isNotEmpty())
                assertTrue(ranked.items.all { row -> row.candidate in catalog.candidates && row.candidate.topics.none { it == "喜剧" } })
                assertEquals(profilesBeforeRequest, local.tables(true))
                assertEquals(1L, app.localAiUsageRepository.snapshot().started - beforeUsage.started)
                assertEquals(1L, app.localAiUsageRepository.snapshot().succeeded - beforeUsage.succeeded)
                ui.onNodeWithTag("live_fill_form").performClick()
                ui.onNodeWithText("实网分类").performScrollTo().performClick()
                ui.onNodeWithText("实网标签").performScrollTo().performClick()
                ui.onNodeWithText("保存").performScrollTo().performClick()
                ui.waitUntil(60000) { importedId.value != null || failure.value != null }
                assertNull("REAL_DETAIL_IMPORT_FAILED", failure.value?.javaClass?.simpleName)
                ui.onNodeWithTag("live_import_success").assertIsDisplayed()
                val id = requireNotNull(importedId.value)
                local.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM movie_tags WHERE movieId=? AND tagId=101", arrayOf(id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
                local.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM movie_categories WHERE movieId=? AND categoryId=101", arrayOf(id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
                local.db.close()
                val reopened = Room.databaseBuilder(app, AppDatabase::class.java, dbName).build()
                try {
                    reopened.openHelper.readableDatabase.query("SELECT COUNT(*) FROM movie_tags WHERE movieId=? AND tagId=101", arrayOf(id)).use {
                        assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                    }
                } finally { reopened.close() }
                output.put("nonemptyExplicitAndRatingSummary", true).put("sourceSwitchChangedSummaryWithoutNetwork", true)
                    .put("staleConsentBlockedBeforeHttp", true).put("explicitExclusionPreserved", true)
                    .put("tagAndCategoryAssociationSaved", true).put("tagAssociationReopened", true)
                    .put("successfulModelTasks", 1).put("rankedCount", ranked.items.size)
                    .put("modelHttpAttempts", modelCalls.get()).put("tmdbHttpAttempts", tmdbCalls.get())
                responseUsage?.let { usage ->
                    output.put("usageComplete", usage.complete).put("retries", usage.retries)
                        .put("promptTokens", usage.promptTokens ?: JSONObject.NULL)
                        .put("completionTokens", usage.completionTokens ?: JSONObject.NULL)
                        .put("totalTokens", usage.totalTokens ?: JSONObject.NULL)
                }
                assertEquals(keyBefore, digest(keyFile))
                assertEquals(tokenBefore, digest(tokenFile))
                passed = true
            } finally { model.cancelPending(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        } finally {
            local.close()
            check(app.deleteDatabase(dbName))
            output.put("modelHttpAttempts", modelCalls.get()).put("tmdbHttpAttempts", tmdbCalls.get())
                .put("ownedDatabaseRemoved", !app.getDatabasePath(dbName).exists())
                .put("credentialsUnchanged", digest(keyFile) == keyBefore && digest(tokenFile) == tokenBefore)
                .put("passed", passed)
            val directory = File(app.filesDir, "live-profile-results").apply { mkdirs() }
            File(directory, nonce + ".json").writeText(output.toString())
        }
    }
}
