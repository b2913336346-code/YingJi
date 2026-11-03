package com.yingji.app.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.yingji.app.data.repository.PosterRepository
import java.io.File
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.domain.ai.TmdbPoster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TmdbPosterPreviewLoaderTest {
    private class Reply(val bytes: ByteArray, var status: Int = 200) : HttpURLConnection(URL("https://image.tmdb.org")) {
        var mime = "image/png"
        var encoding: String? = null
        var length = -1L
        var disconnected = false
        var hold = false
        var reads = 0
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun getResponseCode(): Int {
            started.countDown()
            if (hold && !released.await(5, TimeUnit.SECONDS)) throw IOException("Fixture timeout")
            return status
        }
        override fun getInputStream(): ByteArrayInputStream { reads++; return ByteArrayInputStream(bytes) }
        override fun getContentType() = mime
        override fun getContentEncoding() = encoding
        override fun getContentLengthLong() = length
    }
    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        return ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 90, it)); bitmap.recycle() }.toByteArray()
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
    private fun snapshot(): Map<String, Pair<Long, Long>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return listOf(context.filesDir, context.cacheDir).flatMap { root -> root.walkTopDown().filter { it.isFile }.map {
            it.absolutePath to (it.length() to it.lastModified())
        }.toList() }.toMap()
    }
    @Test fun officialHttpsPreviewDecodesInMemoryWithoutCredentialsCacheOrLocalFileWrites() = runBlocking {
        val response = Reply(png())
        var seen: URL? = null
        val reader = MemoryOnlyTmdbPosterLoader { seen = it; response }
        val before = snapshot()
        val bitmap = reader.load(TmdbPoster("/fictionalPoster.png"))
        assertEquals("https://image.tmdb.org/t/p/w500/fictionalPoster.png", seen.toString())
        assertEquals("GET", response.requestMethod)
        assertNull(response.getRequestProperty("Authorization"))
        assertEquals("no-store", response.getRequestProperty("Cache-Control"))
        assertFalse(response.useCaches)
        assertFalse(response.instanceFollowRedirects)
        assertTrue(response.disconnected)
        assertEquals(1, response.reads)
        assertTrue(bitmap.width in 1..512 && bitmap.height in 1..512)
        assertEquals(Color.GRAY, bitmap.getPixel(0, 0))
        assertEquals(before, snapshot())
        bitmap.recycle()
    }
    @Test fun redirectsAuthenticationFailuresAndRateLimitsNeverReadOrRetry() = runBlocking {
        for (status in listOf(301, 302, 401, 403, 404, 429, 500)) {
            val response = Reply(png(), status)
            var opens = 0
            val reader = MemoryOnlyTmdbPosterLoader { opens++; response }
            assertTrue(runCatching { reader.load(TmdbPoster("/fictionalPoster.png")) }.isFailure)
            assertEquals(1, opens)
            assertEquals(0, response.reads)
            assertTrue(response.disconnected)
        }
    }
    @Test fun htmlCompressionOversizedAndCorruptImagesAreRejectedWithoutWritingFiles() = runBlocking {
        val cases = listOf(Reply(png()).apply { mime = "text/html" }, Reply(png()).apply { encoding = "gzip" },
            Reply(png()).apply { length = 2097153 }, Reply(ByteArray(2097153)), Reply("not an image".toByteArray()),
            Reply(png()).apply { mime = "image/jpeg" })
        val before = snapshot()
        for (response in cases) {
            assertTrue(runCatching { MemoryOnlyTmdbPosterLoader { response }.load(TmdbPoster("/fictionalPoster.png")) }.isFailure)
            assertTrue(response.disconnected)
        }
        assertEquals(before, snapshot())
    }
    @Test fun oddDimensionsNeverExceedThePersistedPosterLimit() = runBlocking<Unit> {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            for ((width, height) in listOf(1025 to 513, 513 to 1025, 2049 to 1025)) {
                val response = Reply(image(width, height, format)).apply { mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg" }
                val bitmap = MemoryOnlyTmdbPosterLoader { response }.load(TmdbPoster("/oddBoundary.png"))
                try {
                    assertTrue("Decoded " + format + " exceeds the 512px save limit: " + bitmap.width + "x" + bitmap.height,
                        bitmap.width in 1..512 && bitmap.height in 1..512)
                    val files = PosterRepository(ApplicationProvider.getApplicationContext<Context>())
                    val saved = files.persistRemote(bitmap)
                    try { assertTrue(File(saved).isFile) } finally { files.deleteManagedFile(saved) }
                } finally { bitmap.recycle() }
            }
        }
    }
    @Test fun leavingPreviewCancelsTheConnectionWithoutWaitingForTimeout() = runBlocking {
        val response = Reply(png()).apply { hold = true }
        val pending = launch(Dispatchers.Default) { MemoryOnlyTmdbPosterLoader { response }.load(TmdbPoster("/fictionalPoster.png")) }
        assertTrue(response.started.await(5, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(response.disconnected)
        assertEquals(0, response.reads)
    }
    @Test fun successAndFailureOnlyResumeAfterTheConnectionIsDisconnected() = runBlocking<Unit> {
        for (success in listOf(true, false)) {
            val response = Reply(png()).apply { if (!success) mime = "text/html" }
            // An unconfined continuation observes completion on the producer thread,
            // so resuming before finally disconnects cannot be hidden by dispatch timing.
            withContext(Dispatchers.Unconfined) {
                val result = runCatching { MemoryOnlyTmdbPosterLoader { response }.load(TmdbPoster("/completionOrder.png")) }
                try {
                    assertTrue("Connection must be disconnected before delivering its result", response.disconnected)
                    assertEquals(success, result.isSuccess)
                } finally { result.getOrNull()?.recycle() }
            }
        }
    }

}
