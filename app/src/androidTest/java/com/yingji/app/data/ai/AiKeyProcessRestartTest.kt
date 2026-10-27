package com.yingji.app.data.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.DataInputStream
import java.security.KeyStore

class AiKeyProcessRestartTest {
    @Test fun encryptedKeyAndDeletionRemainCorrectAcrossActualProcesses() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments(); val id = requireNotNull(args.getString("byokFixture")); val phase = requireNotNull(args.getString("byokPhase"))
        require(Regex("byok-restart-[a-f0-9-]{36}").matches(id)); require(phase in setOf("prepare", "verify", "verify-deleted"))
        val context = ApplicationProvider.getApplicationContext<Context>(); val dir = File(context.noBackupFilesDir, id); val file = File(dir, "config.enc")
        val version = File(dir, "revision.txt"); val store = AndroidKeystoreAiKeyStore(file)
        val repo = AiKeyConfigurationRepository(store, LocalMockAiKeyVerifier()) { 123456789L }
        when (phase) {
            "prepare" -> {
                check(!file.exists()); repo.verifyAndSave("  TEST_ONLY_BYOK_restart  ")
                version.writeText(repo.state.value.revision!!)
                assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("TEST_ONLY_BYOK_restart"))
            }
            "verify" -> {
                check(file.isFile); repo.refresh(); assertEquals(AiKeyStorageStatus.CONFIGURED, repo.state.value.storage)
                assertEquals(AiKeyVerificationKind.MOCK, repo.state.value.kind); assertEquals(123456789L, repo.state.value.lastVerifiedAt)
                assertEquals(version.readText(), repo.state.value.revision)
                store.read().use { assertEquals("TEST_ONLY_BYOK_restart", it!!.key!!.useValue { text -> text }) }
                repo.delete(); assertNotEquals(version.readText(), repo.state.value.revision); version.writeText(repo.state.value.revision!!)
            }
            else -> {
                check(file.isFile); repo.refresh(); assertEquals(AiKeyStorageStatus.NOT_CONFIGURED, repo.state.value.storage)
                assertEquals(version.readText(), repo.state.value.revision); assertFalse(repo.state.value.canRequestLiveAi)
                store.read().use { assertNull(it!!.key); assertNull(it.verifiedAt) }
                DataInputStream(file.inputStream()).use { it.readInt(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(it.readUTF()) }
                check(file.delete()); check(version.delete()); check(dir.delete())
            }
        }
        Log.i("BYOK_S1_RESTART", "phase=" + phase + " pid=" + Process.myPid())
    }
}
