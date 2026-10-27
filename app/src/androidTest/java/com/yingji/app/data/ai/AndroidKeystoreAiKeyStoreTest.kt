package com.yingji.app.data.ai

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.R
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.KeyStore
import java.util.UUID

class AndroidKeystoreAiKeyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val folder = TemporaryFolder(File(context.noBackupFilesDir, "byok-tests").apply { mkdirs() })
    private val ownedFiles = mutableListOf<File>()
    private fun file() = File(folder.newFolder(), "config.enc").also { ownedFiles.add(it) }
    private fun alias(file: File) = DataInputStream(file.inputStream()).use { it.readInt(); it.readUTF() }
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private suspend fun save(store: AiSecureKeyStore, value: String = "TEST_ONLY_BYOK_ciphertext") = AiSecret.fromInput(value).use { store.replace(it, 100, AiKeyVerificationKind.MOCK) }
    private suspend fun value(store: AiSecureKeyStore) = store.read().use { it!!.key!!.useValue { s -> s } }
    @After fun cleanupAliases() { ownedFiles.filter { it.exists() }.forEach { file -> runCatching { keys().deleteEntry(alias(file)) } } }
    @Test fun keystoreRoundTripUsesNonExportableAesAndAuthenticatedCiphertextOnly() = runBlocking<Unit> {
        val f = file(); val store = AndroidKeystoreAiKeyStore(f); save(store)
        val first = f.readBytes(); val oldAlias = alias(f)
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("TEST_ONLY_BYOK_ciphertext")); assertNull(keys().getKey(oldAlias, null).encoded)
        assertEquals("TEST_ONLY_BYOK_ciphertext", value(AndroidKeystoreAiKeyStore(f)))
        save(store); assertFalse(first.contentEquals(f.readBytes())); assertNotEquals(oldAlias, alias(f)); assertFalse(keys().containsAlias(oldAlias))
        assertEquals("TEST_ONLY_BYOK_ciphertext", value(store))
    }
    @Test fun failedAtomicReplacementAndDeleteKeepPreviousBytesAndAlias() = runBlocking<Unit> {
        val f = file(); save(AndroidKeystoreAiKeyStore(f)); val before = f.readBytes(); val oldAlias = alias(f)
        val failing = AndroidKeystoreAiKeyStore(f) { atomic, bytes ->
            val stream = atomic.startWrite()
            try { stream.write(bytes, 0, 12); throw IOException("Injected write failure") }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
        for (deleting in listOf(false, true)) {
            try { if (deleting) failing.replace(null, null, null) else save(failing, "TEST_ONLY_BYOK_new"); fail("Failure hidden") }
            catch (e: AiKeyException) { assertEquals(AiKeyProblem.STORAGE_FAILED, e.problem) }
            assertArrayEquals(before, f.readBytes()); assertTrue(keys().containsAlias(oldAlias))
            assertEquals("TEST_ONLY_BYOK_ciphertext", value(AndroidKeystoreAiKeyStore(f)))
        }
    }
    @Test fun silentlyUncommittedWriteCannotClaimReplacementOrDeleteOrDestroyOldAlias() = runBlocking<Unit> {
        val f = file(); save(AndroidKeystoreAiKeyStore(f)); val before = f.readBytes(); val oldAlias = alias(f)
        val silentFailure = AndroidKeystoreAiKeyStore(f) { _, _ -> /* Simulate an uncommitted final rename. */ }
        val repo = AiKeyConfigurationRepository(silentFailure, LocalMockAiKeyVerifier()); repo.refresh()
        for (deleting in listOf(false, true)) {
            try { if (deleting) repo.delete() else repo.verifyAndSave("TEST_ONLY_BYOK_uncommitted"); fail("Uncommitted write reported success") }
            catch (e: AiKeyException) { assertEquals(AiKeyProblem.STORAGE_FAILED, e.problem) }
            assertEquals(AiKeyStorageStatus.CONFIGURED, repo.state.value.storage)
            assertArrayEquals(before, f.readBytes()); assertTrue(keys().containsAlias(oldAlias))
            assertEquals("TEST_ONLY_BYOK_ciphertext", value(AndroidKeystoreAiKeyStore(f)))
        }
    }
    @Test fun deletionPersistsTombstoneAndDestroysPreviousDecryptionAlias() = runBlocking<Unit> {
        val f = file(); val store = AndroidKeystoreAiKeyStore(f); save(store)
        val old = f.readBytes(); val oldAlias = alias(f); val marker = File(folder.root, "other-ai-and-library").apply { writeText("unchanged") }
        val revision = store.replace(null, null, null)
        AndroidKeystoreAiKeyStore(f).read().use { record -> assertNull(record!!.key); assertNull(record.verifiedAt); assertNull(record.kind); assertEquals(revision, record.revision) }
        assertFalse(keys().containsAlias(oldAlias)); assertEquals("unchanged", marker.readText())
        val copied = file(); copied.writeBytes(old)
        try { AndroidKeystoreAiKeyStore(copied).read(); fail("Deleted credential recovered") } catch (_: AiKeyRecoveryRequired) { }
    }
    @Test fun lostKeystoreEntryAfterRestoreRequiresReconfigurationWithoutErasingCiphertext() = runBlocking<Unit> {
        val f = file(); val store = AndroidKeystoreAiKeyStore(f); save(store); val before = f.readBytes()
        keys().deleteEntry(alias(f)); val repo = AiKeyConfigurationRepository(store, LocalMockAiKeyVerifier()); repo.refresh()
        assertEquals(AiKeyStorageStatus.RECOVERY_REQUIRED, repo.state.value.storage); assertFalse(repo.state.value.canRequestLiveAi)
        assertArrayEquals(before, f.readBytes()); repo.verifyAndSave("TEST_ONLY_BYOK_reconfigured")
        assertEquals("TEST_ONLY_BYOK_reconfigured", value(AndroidKeystoreAiKeyStore(f)))
    }
    @Test fun tamperedCiphertextIsRejectedAndReadNeverAutomaticallyDeletesIt() = runBlocking<Unit> {
        val f = file(); val store = AndroidKeystoreAiKeyStore(f); save(store)
        val bytes = f.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); f.writeBytes(bytes)
        val repo = AiKeyConfigurationRepository(store, LocalMockAiKeyVerifier()); repo.refresh()
        assertEquals(AiKeyStorageStatus.RECOVERY_REQUIRED, repo.state.value.storage); assertArrayEquals(bytes, f.readBytes())
    }
    @Test fun corruptHeaderCannotDeleteAnUnauthenticatedAliasDuringReconfiguration() = runBlocking<Unit> {
        val first = file(); val second = file(); save(AndroidKeystoreAiKeyStore(first)); save(AndroidKeystoreAiKeyStore(second), "TEST_ONLY_BYOK_second")
        val firstBytes = first.readBytes(); val originalAlias = alias(first); val secondAlias = alias(second)
        assertEquals(originalAlias.length, secondAlias.length)
        val corrupted = ByteArrayOutputStream(); DataOutputStream(corrupted).use { it.writeInt(0x59424B31); it.writeUTF(secondAlias) }
        val headerBytes = corrupted.toByteArray(); System.arraycopy(headerBytes, 0, firstBytes, 0, headerBytes.size); first.writeBytes(firstBytes)
        save(AndroidKeystoreAiKeyStore(first), "TEST_ONLY_BYOK_replacement")
        assertTrue(keys().containsAlias(secondAlias)); assertEquals("TEST_ONLY_BYOK_second", value(AndroidKeystoreAiKeyStore(second)))
        keys().deleteEntry(originalAlias)
    }
    @Test fun publicConstructorUsesNoBackupDirectoryAndBackupPoliciesPreserveOrdinaryData() = runBlocking<Unit> {
        val isolated = folder.newFolder()
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = isolated }
        val store = AndroidKeystoreAiKeyStore(wrapped); save(store)
        val f = File(isolated, "ai-byok/config.enc"); ownedFiles.add(f); assertTrue(f.isFile)
        assertFalse(f.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        for (id in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val parser = context.resources.getXml(id); val included = mutableListOf<String>(); val excluded = mutableListOf<String>()
            parser.use { while (it.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val path = it.getAttributeValue(null, "path"); val domain = it.getAttributeValue(null, "domain"); if (path != null) {
                        if (it.name == "include") included.add(domain + ":" + path) else if (it.name == "exclude") excluded.add(domain + ":" + path)
                    }
                }; it.next()
            } }
            assertTrue(included.containsAll(listOf("database:.", "file:posters/", "file:datastore/")))
            assertFalse(included.contains("file:.")); assertFalse(included.contains("root:."))
            assertTrue(excluded.contains("file:datastore/ai_byok.preferences_pb"))
        }
    }
}
