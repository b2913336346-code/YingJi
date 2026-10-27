package com.yingji.app.data.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.domain.ai.*
import java.io.*
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class TmdbCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val folder = TemporaryFolder(File(context.noBackupFilesDir, "tmdb-store-tests").apply { mkdirs() })
    private val aliases = mutableSetOf<String>()
    private fun file() = File(folder.newFolder(), "config.enc")
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(file: File) = DataInputStream(file.inputStream()).use { it.readInt(); it.readUTF() }.also { aliases.add(it) }
    private suspend fun save(store: TmdbSecureCredentialStore, value: String = "TEST_ONLY_TMDB_ciphertext") = TmdbSecret.fromInput(value).use { store.replace(it, 100) }
    private suspend fun value(store: TmdbSecureCredentialStore) = store.read().use { it!!.key!!.useValue { s -> s } }
    @After fun cleanup() { aliases.forEach { runCatching { keys().deleteEntry(it) } } }
    @Test fun roundTripUsesNonExportableAesDifferentAliasesAndNoPlaintext() = runBlocking<Unit> {
        val file = file(); val store = TmdbCredentialStore(file); save(store)
        val before = file.readBytes(); val old = alias(file)
        assertTrue(old.startsWith("yingji.tmdb.v1.")); assertNull(keys().getKey(old, null).encoded)
        assertFalse(before.toString(Charsets.ISO_8859_1).contains("TEST_ONLY_TMDB_ciphertext"))
        assertEquals("TEST_ONLY_TMDB_ciphertext", value(TmdbCredentialStore(file)))
        save(store); val current = alias(file); assertNotEquals(old, current)
        assertFalse(before.contentEquals(file.readBytes())); assertFalse(keys().containsAlias(old))
        TmdbCredentialStore(file).read().use { assertEquals(100L, it!!.savedAt) }
        save(store, "x".repeat(4096)); alias(file); assertEquals(4096, value(TmdbCredentialStore(file)).length)
    }
    @Test fun atomicWriteFailureAndSilentFailurePreservePreviousCiphertext() = runBlocking<Unit> {
        val file = file(); save(TmdbCredentialStore(file)); val before = file.readBytes(); val old = alias(file)
        val partial = TmdbCredentialStore(file) { atomic, bytes ->
            val stream = atomic.startWrite()
            try { stream.write(bytes, 0, 12); throw IOException("Injected failure") }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
        val silent = TmdbCredentialStore(file) { _, _ -> }
        for (store in listOf(partial, silent)) for (deleting in listOf(false, true)) {
            try { if (deleting) store.replace(null, null) else save(store, "TEST_ONLY_TMDB_new"); fail("Failure hidden") }
            catch (e: TmdbCredentialException) { assertEquals(TmdbCredentialProblem.STORAGE_FAILED, e.problem) }
            assertArrayEquals(before, file.readBytes()); assertTrue(keys().containsAlias(old))
            assertEquals("TEST_ONLY_TMDB_ciphertext", value(TmdbCredentialStore(file)))
        }
    }
    @Test fun deletionAndReconfigurationCannotRestoreOldToken() = runBlocking<Unit> {
        val file = file(); val store = TmdbCredentialStore(file); save(store); val oldBytes = file.readBytes(); val old = alias(file)
        val revision = store.replace(null, null); alias(file)
        TmdbCredentialStore(file).read().use { assertNull(it!!.key); assertNull(it.savedAt); assertEquals(revision, it.revision) }
        assertFalse(keys().containsAlias(old)); val copied = file(); copied.writeBytes(oldBytes)
        try { TmdbCredentialStore(copied).read(); fail("Deleted credential recovered") } catch (_: TmdbCredentialRecoveryRequired) { }
        save(store, "TEST_ONLY_TMDB_reconfigured"); alias(file)
        assertEquals("TEST_ONLY_TMDB_reconfigured", value(TmdbCredentialStore(file)))
    }
    @Test fun tamperOrLostAliasRequiresRecoveryWithoutDeletingFile() = runBlocking<Unit> {
        for (lost in listOf(false, true)) {
            val file = file(); save(TmdbCredentialStore(file)); val old = alias(file)
            if (lost) keys().deleteEntry(old) else { val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes) }
            val before = file.readBytes(); val repo = TmdbCredentialRepository(TmdbCredentialStore(file)); repo.refresh()
            assertEquals(TmdbCredentialStatus.RECOVERY_REQUIRED, repo.state.value.status); assertArrayEquals(before, file.readBytes())
            repo.save("TEST_ONLY_TMDB_recovered"); alias(file); assertEquals("TEST_ONLY_TMDB_recovered", value(TmdbCredentialStore(file)))
        }
    }
    @Test fun separatePublicStorageAndAliasesNeverModifyDeepSeekOrOtherData() = runBlocking<Unit> {
        val isolated = folder.newFolder(); val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = isolated }
        val tmdb = TmdbCredentialStore(wrapped); val deepSeek = AndroidKeystoreAiKeyStore(wrapped)
        AiSecret.fromInput("TEST_ONLY_BYOK_preserved").use { deepSeek.replace(it, 10, AiKeyVerificationKind.MOCK) }
        val deepFile = File(isolated, "ai-byok/config.enc"); val deepAlias = alias(deepFile); val deepBytes = deepFile.readBytes()
        val marker = File(isolated, "ordinary-data").apply { writeText("unchanged") }
        save(tmdb); val tmdbFile = File(isolated, "tmdb-credentials/config.enc"); val tmdbAlias = alias(tmdbFile)
        assertNotEquals(deepAlias, tmdbAlias); assertTrue(tmdbFile.canonicalPath.startsWith(isolated.canonicalPath + File.separator))
        assertFalse(tmdbFile.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        tmdb.replace(null, null); alias(tmdbFile); assertArrayEquals(deepBytes, deepFile.readBytes()); assertTrue(keys().containsAlias(deepAlias))
        deepSeek.read().use { assertEquals("TEST_ONLY_BYOK_preserved", it!!.key!!.useValue { s -> s }) }
        save(tmdb); alias(tmdbFile); val keptTmdb = tmdbFile.readBytes(); deepSeek.replace(null, null, null); alias(deepFile)
        assertArrayEquals(keptTmdb, tmdbFile.readBytes()); assertEquals("TEST_ONLY_TMDB_ciphertext", value(tmdb)); assertEquals("unchanged", marker.readText())
    }
    @Test fun copyingDeepSeekEnvelopeCannotReadOrDeleteDeepSeekAlias() = runBlocking<Unit> {
        val deep = file(); AiSecret.fromInput("TEST_ONLY_BYOK_separate").use { AndroidKeystoreAiKeyStore(deep).replace(it, 10, AiKeyVerificationKind.MOCK) }
        val deepAlias = alias(deep); val target = file(); target.writeBytes(deep.readBytes()); val tmdb = TmdbCredentialStore(target)
        try { tmdb.read(); fail("Cross-provider ciphertext accepted") } catch (_: TmdbCredentialRecoveryRequired) { }
        save(tmdb); alias(target); assertTrue(keys().containsAlias(deepAlias))
        AndroidKeystoreAiKeyStore(deep).read().use { assertEquals("TEST_ONLY_BYOK_separate", it!!.key!!.useValue { s -> s }) }
    }
}
