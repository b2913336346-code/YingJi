package com.yingji.app.data.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.YingJiApplication
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.usecase.AddMovieUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Manual source set only. No production writes, UI mutations, model requests or metadata reports. */
class TmdbImportLiveVerificationTest {
    private val app get() = ApplicationProvider.getApplicationContext<YingJiApplication>()
    private val approval = "one-candidate-isolated-import-existing-token"
    private val grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE,
        "User-attested development import scope recorded in 2026-10-23-tmdb-authorization-scope.json", true, true, true)
    private fun gate(value: String?, nonce: String?) {
        require(value == approval) { "EXPLICIT_LIVE_APPROVAL_REQUIRED" }
        require(nonce != null && Regex("[a-f0-9]{32}").matches(nonce)) { "VALID_ONE_USE_NONCE_REQUIRED" }
    }
    private fun digest(file: File): String? = if (!file.isFile) null else
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private class Memory : TmdbSecureCredentialStore {
        private var value: String? = null
        override suspend fun read() = TmdbCredentialRecord(value?.let(TmdbSecret::fromInput), 100, "fixture")
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String {
            value = key?.useValue { it }; return "fixture"
        }
    }
    private class Bounded(private val delegate: TmdbCatalogTransport) : TmdbCatalogTransport by delegate {
        var calls = 0; private set
        val details = mutableListOf<TmdbCatalogRequest>()
        override suspend fun read(request: TmdbCatalogRequest): String {
            check(calls < 10) { "LOGICAL_REQUEST_BUDGET_EXCEEDED" }; calls++
            if (request.id != null) details.add(request)
            return delegate.read(request)
        }
    }
    private fun synthetic(fail: Boolean = false) = object : TmdbCatalogTransport {
        override val isMock = false // App-authored official-protocol fixture; never opens a socket.
        override fun revision() = "fixture"
        override fun isCurrent(revision: String) = revision == "fixture"
        override suspend fun read(request: TmdbCatalogRequest): String {
            if (fail) throw TmdbProblem(TmdbProblemCode.OFFLINE)
            return if (request.id == null) """{"page":1,"results":[{"id":7}]}"""
                else if (request.kind == TmdbMediaKind.MOVIE) """{"id":7,"title":"虚构联调电影","release_date":"2024-01-01","runtime":90}"""
                else """{"id":7,"name":"虚构联调剧集","first_air_date":"2023-01-01","episode_run_time":[45]}"""
        }
    }
    private suspend fun exercise(transport: Bounded, keys: TmdbCredentialRepository): JSONObject {
        // The name is generated here, never accepted from arguments. Only this fixture may be removed.
        val name = "tmdb-import-live-" + UUID.randomUUID() + ".db"
        val file = app.getDatabasePath(name)
        check(file.canonicalFile != app.getDatabasePath("yingji.db").canonicalFile)
        check(Regex("tmdb-import-live-[a-f0-9-]{36}\\.db").matches(name))
        check(!file.exists())
        var db: AppDatabase? = null
        val result = JSONObject().put("productionDatabaseWritten", false).put("metadataInReport", false)
        fun open(): AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, name).build().also { db = it }
        try {
            var current = open()
            current.categoryDao().insert(Category(7, "隔离测试分类", false))
            current.tagDao().insert(Tag(7, "隔离测试标签"))
            fun service(database: AppDatabase): TmdbImportUseCase {
                val movies = MovieRepository(database)
                return TmdbImportUseCase(transport, keys, { grant }, CandidateImportRepository(database, movies), AddMovieUseCase(movies))
            }
            val catalog = TmdbCandidateCatalogRepository(transport, { grant }).load()
            val selected = catalog.candidates.firstOrNull { it.identity.sourceId == "tmdb" && it.identity.externalId.startsWith("movie:") }
                ?: throw IllegalStateException("VERIFIED_MOVIE_REQUIRED")
            check(CandidateValidator.error(selected, catalog, System.currentTimeMillis(), false, importing = true) == null)
            val input = RecommendedWorkInput(selected.identity, true, requireNotNull(selected.title), 7, "隔离测试分类", setOf(7))
            val before = transport.calls
            check(runCatching { service(current)(input.copy(confirmed = false)) }.exceptionOrNull() is CandidateImportException)
            assertEquals("NO_CONFIRMATION_MUST_NOT_READ", before, transport.calls)
            assertTrue("NO_CONFIRMATION_MUST_NOT_WRITE", MovieRepository(current).observeAll().first().isEmpty())
            val id = service(current)(input)
            assertEquals("SAVE_MUST_RECHECK_ONE_DETAIL", before + 1, transport.calls)
            val typed = selected.identity.externalId.split(":")
            val request = TmdbCatalogRequest(TmdbMediaKind.MOVIE, typed[1].toInt())
            assertTrue("SELECTED_DETAIL_MISMATCH", transport.details.last() == request)
            val movie = MovieRepository(current).observeAll().first().single()
            assertTrue("NAME_MUST_MATCH_CONFIRMED_INPUT", movie.id == id && movie.name == input.name)
            assertNull("NO_REMOTE_POSTER_IMPORT", movie.posterPath); assertNull(movie.rating)
            assertEquals(listOf(7L), current.categoryDao().getCategoryIdsForMovie(id))
            assertEquals(listOf(7L), current.tagDao().getTagIdsForMovie(id))
            assertTrue("IDENTITY_MAPPING_REQUIRED", current.candidateImportDao().movieId("tmdb", selected.identity.externalId) == id)
            current.close(); db = null; current = open()
            assertTrue("MAPPING_MUST_SURVIVE_REOPEN", current.candidateImportDao().movieId("tmdb", selected.identity.externalId) == id)
            assertEquals(listOf(7L), current.categoryDao().getCategoryIdsForMovie(id))
            assertEquals(listOf(7L), current.tagDao().getTagIdsForMovie(id))
            check(runCatching { service(current)(input) }.exceptionOrNull() is CandidateImportException)
            assertEquals("DUPLICATE_MUST_NOT_ADD_WORK", 1, MovieRepository(current).observeAll().first().size)
            assertEquals("DUPLICATE_RECHECK_MUST_BE_BOUNDED", before + 2, transport.calls)
            current.openHelper.readableDatabase.query("PRAGMA integrity_check").use { check(it.moveToFirst() && it.getString(0) == "ok") }
            current.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
            result.put("confirmationRequired", true).put("confirmedImport", true).put("selectedDetailRechecked", true)
                .put("relationsPreserved", true).put("mappingSurvivedDatabaseReopen", true).put("duplicateBlocked", true)
                .put("logicalRequests", transport.calls).put("httpAttemptsMeasured", false).put("httpAttemptUpperBound", transport.calls * 2)
            return result
        } finally {
            db?.close()
            check(file.canonicalFile == app.getDatabasePath(name).canonicalFile)
            check(app.deleteDatabase(name)) { "OWNED_FIXTURE_CLEANUP_FAILED" }
            check(listOf("", "-wal", "-shm").none { File(file.path + it).exists() })
            result.put("isolatedMetadataDatabaseRemoved", true)
        }
    }
    @Test fun preflightRejectsMissingInvalidOrUnscopedApproval() {
        for ((value, nonce) in listOf(null to null, approval to null, "read-only" to "a".repeat(32), approval to "bad", approval to "../"))
            assertTrue(runCatching { gate(value, nonce) }.isFailure)
        gate(approval, "a".repeat(32))
    }
    @Test fun preflightSyntheticConfirmationReopenAndDuplicateUseOnlyOwnedDatabase() = runBlocking<Unit> {
        val keys = TmdbCredentialRepository(Memory()); keys.save("TEST_ONLY_TMDB_import_preflight")
        val transport = Bounded(synthetic()); val result = exercise(transport, keys)
        assertTrue(result.getBoolean("isolatedMetadataDatabaseRemoved"))
        assertTrue(result.getBoolean("confirmationRequired")); assertTrue(result.getBoolean("confirmedImport"))
        assertTrue(result.getBoolean("mappingSurvivedDatabaseReopen")); assertTrue(result.getBoolean("duplicateBlocked"))
        assertEquals(6, transport.calls)
    }
    @Test fun preflightOfflineFailureStillRemovesOnlyOwnedDatabase() = runBlocking<Unit> {
        val keys = TmdbCredentialRepository(Memory()); keys.save("TEST_ONLY_TMDB_import_offline")
        val transport = Bounded(synthetic(fail = true))
        val before = app.databaseList().filter { it.startsWith("tmdb-import-live-") }.toSet()
        val error = runCatching { exercise(transport, keys) }.exceptionOrNull()
        assertTrue(error is TmdbProblem && error.code == TmdbProblemCode.OFFLINE)
        assertEquals(1, transport.calls)
        assertEquals(before, app.databaseList().filter { it.startsWith("tmdb-import-live-") }.toSet())
    }
    @Test fun approvedExistingTokenRechecksAndImportsOneCandidateOnlyIntoIsolatedDatabase() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("tmdbNonce")
        gate(args.getString("tmdbImportLiveApproved"), nonce)
        val directory = File(app.filesDir, "tmdb-import-live-results"); check(directory.exists() || directory.mkdir())
        check(File(directory, nonce + "-consumed").createNewFile()) { "LIVE_NONCE_ALREADY_CONSUMED" }
        val credential = File(app.noBackupFilesDir, "tmdb-credentials/config.enc")
        val model = File(app.noBackupFilesDir, "ai-byok/config.enc")
        val keyBefore = digest(credential); val modelBefore = digest(model)
        val result = JSONObject().put("passed", false).put("provider", "TMDB").put("deepSeekCalled", false)
            .put("realProfileTransferred", false).put("metadataInReport", false).put("screenshotsCaptured", false)
            .put("productionDatabaseWritten", false).put("credentialWrittenOrDeleted", false)
        var passed = false
        try {
            check(keyBefore != null) { "EXISTING_ENCRYPTED_TOKEN_REQUIRED" }
            val keys = app.tmdbCredentialRepository; keys.refresh()
            check(keys.state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED)
            val transport = Bounded(TmdbAuthenticatedTransport(TmdbHttpClient.forReadOnlyPreview(), keys))
            val details = exercise(transport, keys)
            details.keys().forEach { name -> result.put(name, details.get(name)) }
            passed = true
        } catch (error: Throwable) {
            result.put("problem", if (error is TmdbProblem) error.code.name else "IMPORT_OR_SAFETY_ASSERTION_FAILED")
        } finally {
            val unchanged = keyBefore == digest(credential) && modelBefore == digest(model)
            result.put("configurationCiphertextUnchanged", unchanged).put("passed", passed && unchanged)
            File(directory, nonce + ".json").writeText(result.toString())
        }
        // No provider titles, response bodies, header values or full exception causes in test output.
        assertTrue("TMDB_ISOLATED_IMPORT_LIVE_FAILED", result.getBoolean("passed"))
    }
}
