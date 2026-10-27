package com.yingji.app.data.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.BuildConfig
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Run prepare in the Mock APK, then verify after an in-place update to the Live APK. */
class DirectDevelopmentModeUpgradeTest {
    @Test fun encryptedMockValidationSurvivesApkUpgradeButNeverBecomesLiveValidation() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val name = requireNotNull(args.getString("modeUpgradeFixture"))
        val phase = requireNotNull(args.getString("modeUpgradePhase"))
        require(Regex("development-mode-[a-f0-9-]+[.]enc").matches(name))
        require(phase in setOf("prepare", "verify"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".devmode"))
        val file = File(context.noBackupFilesDir, name)
        val proof = File(context.filesDir, name + ".sha")
        fun digest() = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val keys = AiKeyConfigurationRepository(AndroidKeystoreAiKeyStore(file), AiHomeRepositoryFactory.verifier(context))
        if (phase == "prepare") {
            assertFalse(BuildConfig.AI_DIRECT_VERIFICATION_ENABLED)
            check(!file.exists() && !proof.exists())
            keys.refresh(); keys.verifyAndSave("TEST_ONLY_BYOK_mode_upgrade")
            assertEquals(AiKeyVerificationKind.MOCK, keys.state.value.kind)
            assertFalse(keys.state.value.canRequestLiveAi)
            proof.writeText(digest())
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("TEST_ONLY_BYOK_mode_upgrade"))
        } else {
            assertTrue(BuildConfig.AI_DIRECT_VERIFICATION_ENABLED)
            check(file.isFile && proof.isFile)
            val before = proof.readText()
            keys.refresh()
            assertEquals(AiKeyStorageStatus.CONFIGURED, keys.state.value.storage)
            assertEquals(AiKeyVerificationKind.MOCK, keys.state.value.kind)
            assertEquals(AiKeyVerificationKind.LIVE, keys.verificationKind)
            assertFalse(keys.state.value.canRequestLiveAi)
            val error = runCatching { keys.verifyAndSave("TEST_ONLY_BYOK_mode_upgrade") }.exceptionOrNull() as AiKeyException
            assertEquals(AiKeyProblem.INVALID_KEY, error.problem)
            assertEquals(before, digest())
            assertEquals(AiKeyVerificationKind.MOCK, keys.state.value.kind)
            keys.delete(); check(file.delete()); check(proof.delete())
        }
        Log.i("DirectModeUpgrade", "PHASE=" + phase + "; PID=" + Process.myPid())
    }
}
