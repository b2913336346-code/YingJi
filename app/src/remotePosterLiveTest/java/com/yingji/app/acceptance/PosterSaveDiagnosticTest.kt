package com.yingji.app.acceptance

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.usecase.SaveRemotePosterUseCase
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Properties
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Explicit, bounded TMDB-only probe. Stores fixed stage names, classes and timings, never URLs or bodies. */
class PosterSaveDiagnosticTest {
    private val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
    private val events = JSONArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun event(stage: String, value: Any) { synchronized(events) { events.put(JSONObject().put("stage", stage).put("value", value)) } }
    private fun traceFailure(stage: String, error: Exception) {
        event(stage + "_exception", error.javaClass.simpleName)
        generateSequence<Throwable>(error) { it.cause }.take(6).forEachIndexed { depth, cause ->
            event(stage + "_cause_" + depth, cause.javaClass.simpleName)
            val reason = when {
                cause.message?.contains("Trust anchor for certification path not found") == true -> "TRUST_ANCHOR_NOT_FOUND"
                cause.message?.contains("SSLV3_ALERT_HANDSHAKE_FAILURE") == true -> "ALERT_HANDSHAKE_FAILURE"
                cause.message?.contains("SSLV3_ALERT_INTERNAL_ERROR") == true -> "ALERT_INTERNAL_ERROR"
                cause.message?.contains("Connection closed by peer") == true -> "PEER_CLOSED"
                cause is java.security.cert.CertificateExpiredException -> "CERTIFICATE_EXPIRED"
                cause is java.security.cert.CertificateNotYetValidException -> "CERTIFICATE_NOT_YET_VALID"
                else -> null
            }
            if (reason != null) event(stage + "_reason_" + depth, reason)
        }
    }
    private fun <T> observed(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        return try { block() }
        catch (e: Exception) { traceFailure(stage, e); throw e }
        finally { event(stage + "_ms", (System.nanoTime() - start) / 1000000) }
    }
    private suspend fun <T> observedSuspend(stage: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        return try { block() }
        catch (e: Exception) { traceFailure(stage, e); throw e }
        finally { event(stage + "_ms", (System.nanoTime() - start) / 1000000) }
    }
    private fun connection(url: URL, route: String): HttpURLConnection {
        check(url.protocol == "https" && url.host in setOf("api.themoviedb.org", "image.tmdb.org") && url.port == -1 && url.userInfo == null)
        return when (route) {
            "system" -> url.openConnection()
            "direct" -> url.openConnection(Proxy.NO_PROXY)
            "usb-proxy" -> url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 17897)))
            else -> error("UNSUPPORTED_DIAGNOSTIC_ROUTE")
        } as HttpsURLConnection
    }
    private inner class Traced(private val original: HttpURLConnection, private val prefix: String) : HttpURLConnection(original.url) {
        override fun connect() = original.connect()
        override fun disconnect() = original.disconnect()
        override fun usingProxy() = original.usingProxy()
        override fun setConnectTimeout(value: Int) { original.connectTimeout = value }
        override fun setReadTimeout(value: Int) { original.readTimeout = value }
        override fun setUseCaches(value: Boolean) { original.useCaches = value }
        override fun setInstanceFollowRedirects(value: Boolean) { original.instanceFollowRedirects = value }
        override fun setRequestMethod(value: String) { original.requestMethod = value }
        override fun setRequestProperty(key: String, value: String) { original.setRequestProperty(key, value) }
        override fun getResponseCode(): Int = observed(prefix + "_headers") { original.responseCode.also { event(prefix + "_status", it) } }
        override fun getContentType(): String? = original.contentType
        override fun getContentEncoding(): String? = original.contentEncoding
        override fun getContentLengthLong(): Long = original.contentLengthLong
        override fun getInputStream(): InputStream = object : FilterInputStream(observed(prefix + "_stream") { original.inputStream }) {
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int = observed(prefix + "_read") { super.read(buffer, offset, size) }
        }
    }
    @Test fun diagnosedExplicitSaveKeepsAllOtherFields() = runBlocking<Unit> {
        check(app.packageName == "com.yingji.app.posterlive.acceptance")
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("posterLive") == "explicit")
        val route = args.getString("posterDiagnosticRoute") ?: "system"
        check(route in setOf("system", "direct", "usb-proxy"))
        val cdnRoute = args.getString("posterDiagnosticCdnRoute") ?: route
        check(cdnRoute in setOf("system", "direct", "usb-proxy"))
        val nonce = args.getString("posterLiveNonce").orEmpty()
        check(Regex("[a-f0-9]{32}").matches(nonce))
        val fixture = Properties().apply { File(app.filesDir, "remote-poster-live-ba0a1ce9a6ac47e98d310c8afc3c436c.properties").inputStream().use { load(it) } }
        val id = fixture.getProperty("id").toLong()
        val db = DatabaseModule.provideDatabase(app)
        app.tmdbCredentialRepository.refresh()
        check(app.tmdbCredentialRepository.state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED)
        val before = requireNotNull(db.movieDao().getById(id))
        val old = requireNotNull(before.posterPath)
        val oldHash = digest(File(old).readBytes())
        val oldFiles = File(app.filesDir, "posters").list().orEmpty().toSet()
        suspend fun relations() = listOf(db.categoryDao().getCategoryIdsForMovie(id), db.tagDao().getTagIdsForMovie(id), db.watchHistoryDao().getForMovie(id), db.candidateImportDao().tmdbSourcesForMovie(id)).toString()
        val beforeRelations = relations()
        val report = JSONObject().put("formalDatabaseAccessed", false).put("paidModelCalled", false).put("route", route).put("cdnRoute", cdnRoute)
        var apiCalls = 0; var cdnCalls = 0
        try {
            val credentials = app.tmdbCredentialRepository
            val transport = TmdbAuthenticatedTransport(TmdbHttpClient(true) { url ->
                check(++apiCalls <= 2); event("api_open", apiCalls); Traced(connection(url, route), "api")
            }, credentials)
            val lookup = TmdbPosterLookup(transport, credentials, { TmdbSourcePermission(CandidateLicenseState.ACTIVE, "Authorized isolated TMDB diagnosis", true, true, true) })
            event("lookup_started", true)
            val selected = observedSuspend("lookup") { lookup(app.movieRepository.remotePosterTarget(id).externalId) }
            event("lookup_finished", true)
            val loader = MemoryOnlyTmdbPosterLoader { url ->
                check(++cdnCalls <= 1); event("cdn_open", cdnCalls); Traced(connection(url, cdnRoute), "cdn")
            }
            val saved = observedSuspend("save") { SaveRemotePosterUseCase(app.movieRepository, app.posterRepository, loader)(id, selected, true) }
            val after = requireNotNull(db.movieDao().getById(id))
            assertEquals(before.copy(posterPath = null), after.copy(posterPath = null)); assertEquals(beforeRelations, relations())
            assertNotEquals(old, saved.localPath); assertEquals(saved.localPath, after.posterPath)
            assertNotNull(android.graphics.BitmapFactory.decodeFile(saved.localPath))
            assertEquals(oldFiles.size, File(app.filesDir, "posters").list().orEmpty().size)
            report.put("saved", true).put("otherFieldsAndRelationsUnchanged", true).put("singleReplacementFile", true)
        } catch (e: Exception) {
            val after = requireNotNull(db.movieDao().getById(id))
            val unchanged = after == before && beforeRelations == relations() && File(old).isFile && digest(File(old).readBytes()) == oldHash && oldFiles == File(app.filesDir, "posters").list().orEmpty().toSet()
            report.put("saved", false).put("oldDataPreserved", unchanged).put("safeExceptionClass", e.javaClass.simpleName)
            if (e is TmdbProblem) report.put("safeTmdbProblemCode", e.code.name)
            assertTrue("FAILED_SAVE_CHANGED_OLD_DATA", unchanged)
            throw AssertionError("DIAGNOSED_SAVE_FAILED_SEE_REDACTED_REPORT")
        } finally {
            report.put("apiOpens", apiCalls).put("cdnOpens", cdnCalls).put("events", events)
            File(app.filesDir, "poster-save-diagnostic-" + nonce + ".json").writeText(report.toString())
        }
    }
}
