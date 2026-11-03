package com.yingji.app.data.ai

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Explicit manual source set: TLS handshake only, never sends an HTTP request/token to TMDB. */
class TmdbProxyTlsVerificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun nonce(): String = requireNotNull(InstrumentationRegistry.getArguments().getString("tmdbNonce")).also {
        require(Regex("[a-f0-9]{32}").matches(it))
    }
    private fun write(name: String, value: JSONObject) {
        val file = File(context.filesDir, "tmdb-live-results/" + name + "-" + nonce() + ".json")
        file.parentFile!!.mkdirs(); file.writeText(value.toString())
    }
    @Test fun temporaryProxySupportsVerifiedTlsWithoutCredential() {
        require(InstrumentationRegistry.getArguments().getString("tmdbProxyTlsApproved") == "credential-free")
        val result = JSONObject().put("credentialSent", false).put("httpToTmdbSent", false)
        var passed = false
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val proxy = cm.defaultProxy
            assertNotNull("GLOBAL_PROXY_NOT_APPLIED", proxy)
            assertEquals("10.0.2.2", proxy!!.host); assertEquals(7897, proxy.port)
            val selected = ProxySelector.getDefault().select(URI("https://api.themoviedb.org/"))
            assertTrue("JAVA_PROXY_SELECTOR_NOT_APPLIED", selected.any { it.type() == Proxy.Type.HTTP &&
                (it.address() as? InetSocketAddress)?.let { address -> address.hostString == proxy.host && address.port == proxy.port } == true })
            Socket().use { socket ->
                socket.connect(InetSocketAddress(proxy.host, proxy.port), 10000); socket.soTimeout = 10000
                socket.getOutputStream().write("CONNECT api.themoviedb.org:443 HTTP/1.1\r\nHost: api.themoviedb.org:443\r\n\r\n".toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                val input = socket.getInputStream(); val header = ByteArrayOutputStream()
                while (true) {
                    val n = input.read(); check(n >= 0 && header.size() < 8192) { "PROXY_CONNECT_FAILED" }; header.write(n)
                    val bytes = header.toByteArray()
                    if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
                }
                assertTrue("PROXY_CONNECT_REJECTED", Regex("HTTP/1[.][01] 200 .*", RegexOption.DOT_MATCHES_ALL).matches(header.toString("US-ASCII")))
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(socket, "api.themoviedb.org", 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = 10000
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    assertTrue("UNEXPECTED_TLS_PROTOCOL", tls.session.protocol.startsWith("TLS"))
                    result.put("protocol", tls.session.protocol).put("verifiedPeer", "api.themoviedb.org")
                }
            }
            passed = true
        } catch (e: Throwable) { result.put("problemType", e.javaClass.simpleName) }
        finally { result.put("passed", passed); write("proxy-tls", result) }
        assertTrue("CREDENTIAL_FREE_PROXY_TLS_FAILED", passed)
    }
    @Test fun restoredProxyMatchesOriginalAbsentConfiguration() {
        require(InstrumentationRegistry.getArguments().getString("tmdbProxyRestoreApproved") == "expect-original-absent")
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val proxy = cm.defaultProxy
        val selected = ProxySelector.getDefault().select(URI("https://api.themoviedb.org/"))
        val restored = proxy == null && selected.none { it.type() == Proxy.Type.HTTP }
        write("proxy-restored", JSONObject().put("restored", restored).put("defaultProxyAbsent", proxy == null)
            .put("httpProxyNotSelected", selected.none { it.type() == Proxy.Type.HTTP }).put("networkRequestSent", false))
        assertTrue("ORIGINAL_ABSENT_PROXY_NOT_RESTORED", restored)
    }
}
