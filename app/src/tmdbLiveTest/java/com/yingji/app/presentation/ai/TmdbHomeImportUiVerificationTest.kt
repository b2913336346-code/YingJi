package com.yingji.app.presentation.ai

import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.MainActivity
import com.yingji.app.YingJiApplication
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Test-only encrypted relay. The host receives ciphertext and public material, never the saved token. */
private object UiImportBridge {
    const val CLONE = "com.yingji.app.tmdb.uiacceptance"
    const val APPROVAL = "owned-clone-home-import-once"
    val app get() = ApplicationProvider.getApplicationContext<YingJiApplication>()
    fun gate(approval: String?, nonce: String?) {
        require(approval == APPROVAL) { "EXPLICIT_UI_IMPORT_APPROVAL_REQUIRED" }
        require(nonce != null && Regex("[a-f0-9]{32}").matches(nonce)) { "VALID_NONCE_REQUIRED" }
    }
    fun nonce(): String {
        val args = InstrumentationRegistry.getArguments()
        gate(args.getString("tmdbHomeImportApproved"), args.getString("tmdbNonce"))
        return requireNotNull(args.getString("tmdbNonce"))
    }
    fun directory() = File(app.filesDir, "tmdb-home-import-ui").apply { check(exists() || mkdir()) }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun digest(file: File): String? = if (file.isFile) digest(file.readBytes()) else null
    fun bytes(value: String) = Base64.decode(value, Base64.NO_WRAP)
    fun encoded(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private val oaep get() = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    fun seal(raw: ByteArray, publicKey: PublicKey, nonce: String): JSONObject {
        require(publicKey is RSAPublicKey && publicKey.modulus.bitLength() == 2048)
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD((CLONE + ":" + nonce).toByteArray())
            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(Cipher.ENCRYPT_MODE, publicKey, oaep)
            return JSONObject().put("nonce", nonce).put("wrapped", encoded(rsa.doFinal(aes)))
                .put("iv", encoded(iv)).put("ciphertext", encoded(cipher.doFinal(raw)))
        } finally { aes.fill(0) }
    }
    fun open(value: JSONObject, privateKey: PrivateKey, nonce: String): ByteArray {
        require(value.getString("nonce") == nonce)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.DECRYPT_MODE, privateKey, oaep)
        val aes = rsa.doFinal(bytes(value.getString("wrapped")))
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, bytes(value.getString("iv"))))
            cipher.updateAAD((CLONE + ":" + nonce).toByteArray())
            return cipher.doFinal(bytes(value.getString("ciphertext")))
        } finally { aes.fill(0) }
    }
}

/** Only explicit named methods may run. Every mutation except the encrypted export is clone-only. */
class TmdbHomeImportBridgeTest {
    @Test fun preflightCipherRoundTrip() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val raw = "TEST_ONLY_ui_bridge".toByteArray(); val nonce = "a".repeat(32)
        val encrypted = UiImportBridge.seal(raw, pair.public, nonce)
        assertArrayEquals(raw, UiImportBridge.open(encrypted, pair.private, nonce))
        assertFalse(encrypted.toString().contains("TEST_ONLY"))
    }
    @Test fun preflightTamperingWrongKeyAndWrongNonceFailClosed() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val nonce = "a".repeat(32); val encrypted = UiImportBridge.seal("TEST_ONLY".toByteArray(), pair.public, nonce)
        assertTrue(runCatching { UiImportBridge.open(encrypted, pair.private, "b".repeat(32)) }.isFailure)
        assertTrue(runCatching { UiImportBridge.open(encrypted, other.private, nonce) }.isFailure)
        val payload = UiImportBridge.bytes(encrypted.getString("ciphertext")); payload[0] = (payload[0].toInt() xor 1).toByte()
        encrypted.put("ciphertext", UiImportBridge.encoded(payload))
        assertTrue(runCatching { UiImportBridge.open(encrypted, pair.private, nonce) }.isFailure)
    }
    @Test fun preflightApprovalAndNonceAreRequired() {
        for ((value, nonce) in listOf(null to null, UiImportBridge.APPROVAL to null, "read-only" to "a".repeat(32), UiImportBridge.APPROVAL to "../"))
            assertTrue(runCatching { UiImportBridge.gate(value, nonce) }.isFailure)
        UiImportBridge.gate(UiImportBridge.APPROVAL, "a".repeat(32))
    }
    @Test fun createClonePublicEnvelopeKey() {
        val nonce = UiImportBridge.nonce(); check(UiImportBridge.app.packageName == UiImportBridge.CLONE)
        val file = File(UiImportBridge.directory(), nonce + "-public.json"); check(!file.exists())
        val alias = "tmdb-ui-import-" + nonce
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT).setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
        }.generateKeyPair()
        file.writeText(JSONObject().put("nonce", nonce).put("publicKey", UiImportBridge.encoded(pair.public.encoded)).toString())
    }
    @Test fun exportOriginalTokenAsCloneCiphertextOnly() = runBlocking<Unit> {
        val nonce = UiImportBridge.nonce(); val app = UiImportBridge.app; check(app.packageName == "com.yingji.app")
        val consumed = File(UiImportBridge.directory(), nonce + "-export-consumed"); check(consumed.createNewFile())
        val encoded = requireNotNull(InstrumentationRegistry.getArguments().getString("tmdbClonePublicKey")); require(encoded.length < 1024)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(UiImportBridge.bytes(encoded)))
        val file = File(app.noBackupFilesDir, "tmdb-credentials/config.enc"); val before = UiImportBridge.digest(file)
        check(before != null)
        app.tmdbCredentialRepository.withCurrentToken { token, current ->
            check(current()); val raw = token.useValue { it.toByteArray(Charsets.UTF_8) }
            try { val envelope = UiImportBridge.seal(raw, publicKey, nonce); check(current())
                File(UiImportBridge.directory(), nonce + "-envelope.json").writeText(envelope.toString())
            } finally { raw.fill(0) }
        }
        check(UiImportBridge.digest(file) == before)
    }
    @Test fun installEnvelopeIntoOwnedCloneOnly() = runBlocking<Unit> {
        val nonce = UiImportBridge.nonce(); val app = UiImportBridge.app; check(app.packageName == UiImportBridge.CLONE)
        val envelopeFile = File(UiImportBridge.directory(), nonce + "-envelope.json")
        val alias = "tmdb-ui-import-" + nonce; val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = File(app.noBackupFilesDir, "tmdb-credentials/config.enc"); check(!existing.exists())
        val raw = UiImportBridge.open(JSONObject(envelopeFile.readText()), store.getKey(alias, null) as PrivateKey, nonce)
        try { app.tmdbCredentialRepository.save(raw.toString(Charsets.UTF_8)) }
        finally { raw.fill(0); store.deleteEntry(alias); check(envelopeFile.delete()) }
        check(!store.containsAlias(alias)); check(existing.exists())
        check(app.movieRepository.observeAll().first().isEmpty())
        app.categoryRepository.ensureSystemCategories(); app.categoryRepository.addTag("验收标签")
        app.mockAiHomeRepository.saveSetup(AiDemoSetup())
        File(UiImportBridge.directory(), nonce + "-installed.json").writeText(JSONObject().put("encrypted", true).put("relayPrivateKeyRemoved", true).toString())
    }
}

/** Same MainActivity, navigation, ViewModels, repositories and normal Room DB; only the application ID differs. */
class TmdbHomeImportUiVerificationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = UiImportBridge.app
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    private fun text(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun count() = runBlocking { app.movieRepository.observeAll().first().size }
    private fun mapping(id: Long): Pair<String, String> = DatabaseModule.provideDatabase(app).openHelper.readableDatabase
        .query("SELECT sourceId, externalId FROM ai_candidate_imports WHERE movieId=?", arrayOf(id)).use {
            check(it.moveToFirst()); val row = it.getString(0) to it.getString(1); check(!it.moveToNext()); row
        }
    @Test fun actualHomeConfirmationFormSaveAndColdRestartUseNormalProductionChain() {
        val nonce = UiImportBridge.nonce(); check(app.packageName == UiImportBridge.CLONE)
        val phase = InstrumentationRegistry.getArguments().getString("tmdbHomeImportPhase"); require(phase in setOf("exercise", "restart"))
        val directory = UiImportBridge.directory(); val stateFile = File(directory, nonce + "-state.json")
        val keyFile = File(app.noBackupFilesDir, "tmdb-credentials/config.enc"); val beforeKey = UiImportBridge.digest(keyFile)
        val result = JSONObject().put("phase", phase).put("pid", Process.myPid()).put("passed", false)
            .put("isolatedApplicationId", true).put("originalLibraryWritten", false).put("deepSeekCalled", false)
            .put("profileTransferred", false).put("screenshotsCaptured", false).put("metadataInReport", false)
            .put("readActions", 0).put("saveActions", 0).put("httpAttemptsMeasured", false)
        var passed = false
        try {
            check(beforeKey != null)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
            scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
            if (phase == "exercise") {
                check(!stateFile.exists()); check(File(directory, nonce + "-exercise-consumed").createNewFile()); check(count() == 0)
                scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
                scroll("tmdb_home_status"); check(text("tmdb_home_status") == "尚未读取候选")
                scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick(); result.put("readActions", 1)
                scroll("tmdb_home_status")
                ui.waitUntil(120000) { val value = text("tmdb_home_status"); value != "尚未读取候选" && value != "正在校验 TMDB 候选" }
                check(Regex("已校验 [1-6] 项").containsMatchIn(text("tmdb_home_status")))
                val movie = SemanticsMatcher("typed movie import button") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tmdb_add_movie:") == true }
                ui.onNodeWithTag("movie_collection").performScrollToNode(movie)
                val tag = ui.onAllNodes(movie).fetchSemanticsNodes().first().config[SemanticsProperties.TestTag]
                val externalId = tag.removePrefix("tmdb_add_")
                ui.onNodeWithTag(tag).performClick(); ui.onNodeWithTag("tmdb_add_cancel").performClick(); check(count() == 0)
                ui.onNodeWithTag(tag).performClick()
                val confirmation = ui.onNode(hasText("：确认后进入现有新增表单", substring = true)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
                ui.onNodeWithTag("tmdb_add_confirm").performClick()
                val name = ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().first().config[SemanticsProperties.EditableText].text
                check(name.isNotBlank() && confirmation.startsWith(name + "：")); check(count() == 0)
                ui.onNodeWithText("保存").performScrollTo().performClick()
                ui.onNodeWithText("请选择作品分类").performScrollTo().assertIsDisplayed(); check(count() == 0)
                ui.onNodeWithText("电影", substring = false).performScrollTo().performClick()
                ui.onNodeWithText("验收标签", substring = false).performScrollTo().performClick()
                ui.onNodeWithText("保存").performScrollTo().performClick(); result.put("saveActions", 1)
                ui.waitUntil(60000) { count() == 1 }
                ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("movie_collection").performScrollToNode(hasText(name)); ui.onNodeWithText(name).assertIsDisplayed()
                val stored = runBlocking { app.movieRepository.observeAll().first().single() }; check(stored.name == name)
                check(stored.posterPath == null && stored.rating == null && stored.status == "想看")
                val identity = mapping(stored.id); check(identity.first == "tmdb" && identity.second == externalId)
                val database = DatabaseModule.provideDatabase(app)
                val category = runBlocking { app.categoryRepository.observeCategories().first().single { it.name == "电影" } }
                val selectedTag = runBlocking { app.categoryRepository.observeTags().first().single { it.name == "验收标签" } }
                runBlocking { check(database.categoryDao().getCategoryIdsForMovie(stored.id) == listOf(category.id)); check(database.tagDao().getTagIdsForMovie(stored.id) == listOf(selectedTag.id)) }
                ui.onNodeWithText(name).performClick(); ui.onNodeWithText("作品详情").assertIsDisplayed(); ui.onNodeWithContentDescription("返回").performClick()
                stateFile.writeText(JSONObject().put("pid", Process.myPid()).put("movieId", stored.id)
                    .put("nameDigest", UiImportBridge.digest(name.toByteArray())).put("identityDigest", UiImportBridge.digest(externalId.toByteArray()))
                    .put("categoryId", category.id).put("tagId", selectedTag.id).put("keyDigest", beforeKey).toString())
                result.put("cancelDidNotWrite", true).put("onlyNamePrefilled", true).put("categoryRequired", true)
                    .put("confirmedSharedFormSave", true).put("sourceMappingCorrect", true).put("localRelationsPreserved", true)
                    .put("homeListReflectedSave", true).put("detailNavigationPassed", true).put("logicalReadUpperBound", 9).put("httpAttemptUpperBound", 18)
            } else {
                val state = JSONObject(stateFile.readText()); check(state.getInt("pid") != Process.myPid()); check(count() == 1)
                val stored = runBlocking { app.movieRepository.observeAll().first().single() }; check(stored.id == state.getLong("movieId"))
                check(UiImportBridge.digest(stored.name.toByteArray()) == state.getString("nameDigest"))
                val identity = mapping(stored.id); check(identity.first == "tmdb" && UiImportBridge.digest(identity.second.toByteArray()) == state.getString("identityDigest"))
                val database = DatabaseModule.provideDatabase(app)
                runBlocking { check(database.categoryDao().getCategoryIdsForMovie(stored.id) == listOf(state.getLong("categoryId"))); check(database.tagDao().getTagIdsForMovie(stored.id) == listOf(state.getLong("tagId"))) }
                check(beforeKey == state.getString("keyDigest"))
                ui.onNodeWithTag("movie_collection").performScrollToNode(hasText(stored.name)); ui.onNodeWithText(stored.name).assertIsDisplayed()
                scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
                scroll("tmdb_home_status"); check(text("tmdb_home_status") == "尚未读取候选")
                result.put("workAndRelationsSurvivedColdRestart", true).put("freshProcess", true).put("sourceDefaultMock", true)
                    .put("previewIdleWithoutReadAction", true).put("httpAttemptUpperBound", 0)
            }
            passed = true
        } catch (_: Throwable) {
            // Compose diagnostics can contain real titles. Export only this generic failure, without its cause.
            result.put("problem", "UI_OR_PERSISTENCE_ASSERTION_FAILED")
        } finally {
            val unchanged = beforeKey == UiImportBridge.digest(keyFile)
            result.put("configurationCiphertextUnchanged", unchanged).put("passed", passed && unchanged)
            File(directory, nonce + "-" + phase + ".json").writeText(result.toString())
        }
        assertTrue("CLONE_HOME_IMPORT_UI_FAILED", result.getBoolean("passed"))
    }
}
