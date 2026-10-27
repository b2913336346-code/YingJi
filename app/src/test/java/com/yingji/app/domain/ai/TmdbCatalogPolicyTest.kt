package com.yingji.app.domain.ai

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class TmdbCatalogPolicyTest {
    private class Memory : TmdbSecureCredentialStore {
        var value: String? = null; var time: Long? = null; var revision = "empty"; var fail = false
        override suspend fun read() = TmdbCredentialRecord(value?.let(TmdbSecret::fromInput), time, revision)
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String {
            if (fail) throw IOException("injected")
            value = key?.useValue { it }; time = savedAt; revision = java.util.UUID.randomUUID().toString(); return revision
        }
    }
    private suspend fun code(expected: TmdbProblemCode, block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: TmdbProblem) { assertEquals(expected, e.code) }
    }
    @Test fun endpointsAreFixedHttpsGetTargetsWithoutKeyOrPrivateQuery() {
        for (kind in TmdbMediaKind.entries) for (id in listOf(null, 7, Int.MAX_VALUE)) {
            val endpoint = TmdbCatalogRequest(kind, id).endpoint; val uri = URI(endpoint)
            assertEquals("https", uri.scheme); assertEquals("api.themoviedb.org", uri.host); assertEquals(-1, uri.port); assertNull(uri.userInfo)
            assertEquals(if (id == null) "/3/discover/" + kind.path else "/3/" + kind.path + "/" + id, uri.path)
            assertTrue(uri.query.startsWith("language=zh-CN")); assertFalse(endpoint.contains("api_key")); assertFalse(endpoint.contains("profile"))
        }
        for (id in listOf(0, -1)) try { TmdbCatalogRequest(TmdbMediaKind.MOVIE, id); fail("Invalid id accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun sourcePermissionRequiresExplicitAiDisplayEvidenceAndExactExpiry() {
        assertFalse(TmdbSourcePermission().permits(100))
        val valid = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "Fixture permission", true, true, true, 100)
        assertTrue(valid.permits(99)); assertFalse(valid.permits(100)); assertFalse(valid.permits(-1))
        assertFalse(valid.copy(evidence = " ").permits(99)); assertFalse(valid.copy(aiRecommendationAllowed = false).permits(99))
        assertFalse(valid.copy(displayAllowed = false).permits(99)); assertFalse(valid.copy(state = CandidateLicenseState.REVOKED).permits(99))
    }
    @Test fun missingCredentialNeverInvokesLeaseBlock() = runBlocking {
        val repo = TmdbCredentialRepository(Memory()); var called = false
        code(TmdbProblemCode.KEY_REQUIRED) { repo.withCurrentToken { _, _ -> called = true } }; assertFalse(called)
    }
    @Test fun replacementRevokesHeldTokenAndRejectsLateResult() = runBlocking {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory); repo.save("TEST_ONLY_TMDB_old")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var revoked = 0
        val held = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            repo.withCurrentToken(onRevoke = { revoked++ }) { token, current ->
                assertEquals("TEST_ONLY_TMDB_old", token.useValue { it }); entered.complete(Unit); release.await(); assertFalse(current()); "late"
            }
        }.exceptionOrNull() }
        entered.await(); repo.save("TEST_ONLY_TMDB_new"); release.complete(Unit)
        assertEquals(1, revoked); assertEquals(TmdbProblemCode.STALE_KEY, (held.await() as TmdbProblem).code)
        repo.withCurrentToken { key, current -> assertTrue(current()); assertEquals("TEST_ONLY_TMDB_new", key.useValue { it }) }
    }
    @Test fun failedDeletionStillRevokesHeldResultButPreservesOldCredential() = runBlocking {
        val memory = Memory(); val repo = TmdbCredentialRepository(memory); repo.save("TEST_ONLY_TMDB_retained")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var revoked = false
        val held = async(start = CoroutineStart.UNDISPATCHED) { runCatching { repo.withCurrentToken(onRevoke = { revoked = true }) { _, _ -> entered.complete(Unit); release.await() } }.exceptionOrNull() }
        entered.await(); memory.fail = true
        try { repo.delete(); fail("Failure hidden") } catch (e: TmdbCredentialException) { assertEquals(TmdbCredentialProblem.STORAGE_FAILED, e.problem) }
        assertTrue(revoked); release.complete(Unit); assertEquals(TmdbProblemCode.STALE_KEY, (held.await() as TmdbProblem).code)
        assertEquals("TEST_ONLY_TMDB_retained", memory.value); assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status)
    }
}
