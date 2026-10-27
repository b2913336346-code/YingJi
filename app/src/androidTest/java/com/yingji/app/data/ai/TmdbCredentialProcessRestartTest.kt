package com.yingji.app.data.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import java.io.*
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TmdbCredentialProcessRestartTest {
    @Test fun localTokenAndDeletionSurviveThreeProcesses() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments(); val name = requireNotNull(args.getString("tmdbFixture")); val phase = requireNotNull(args.getString("tmdbPhase"))
        require(Regex("tmdb-restart-[a-f0-9-]+").matches(name)); require(phase in setOf("prepare", "verify", "verify-delete"))
        val context = ApplicationProvider.getApplicationContext<Context>(); val dir = File(context.noBackupFilesDir, name)
        require(dir.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        if (phase == "prepare") check(!dir.exists() && dir.mkdirs()) else check(dir.isDirectory)
        val file = File(dir, "config.enc"); val metadata = File(dir, "revision.txt"); val store = TmdbCredentialStore(file); val repo = TmdbCredentialRepository(store) { 123 }
        repo.refresh()
        if (phase == "prepare") { assertEquals(TmdbCredentialStatus.NOT_CONFIGURED, repo.state.value.status); repo.save("TEST_ONLY_TMDB_restart"); metadata.writeText(requireNotNull(repo.state.value.revision)) }
        else if (phase == "verify") {
            assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, repo.state.value.status); assertEquals(123L, repo.state.value.savedAt)
            assertEquals(metadata.readText(), repo.state.value.revision)
            store.read().use { assertEquals("TEST_ONLY_TMDB_restart", it!!.key!!.useValue { s -> s }) }
            repo.delete(); metadata.writeText(requireNotNull(repo.state.value.revision))
        } else {
            assertEquals(TmdbCredentialStatus.NOT_CONFIGURED, repo.state.value.status); assertNull(repo.state.value.savedAt); assertEquals(metadata.readText(), repo.state.value.revision)
            store.read().use { assertNull(it!!.key) }
        }
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("TEST_ONLY_TMDB_restart"))
        Log.i("TmdbCredentialRestart", "PHASE=" + phase + "; PID=" + Process.myPid())
        if (phase == "verify-delete") {
            DataInputStream(file.inputStream()).use { it.readInt(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(it.readUTF()) }
            check(file.delete()); check(metadata.delete()); check(dir.delete())
        }
    }
}
