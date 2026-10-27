package com.yingji.app.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yingji.app.domain.ai.TmdbPoster
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface TmdbPosterPreviewLoader {
    suspend fun load(poster: TmdbPoster): Bitmap
}

/** Images live only in the caller's memory. No credential, disk cache or work-library dependency. */
class MemoryOnlyTmdbPosterLoader internal constructor(
    private val open: (URL) -> HttpURLConnection,
) : TmdbPosterPreviewLoader {
    constructor() : this({ it.openConnection() as HttpsURLConnection })

    override suspend fun load(poster: TmdbPoster): Bitmap = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { runCatching { active.get()?.disconnect() } }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            var connection: HttpURLConnection? = null
            fun checkActive() { if (!continuation.isActive) throw IOException("Preview cancelled") }
            try {
                checkActive()
                val url = URL(poster.previewUrl)
                require(url.protocol == "https" && url.host == "image.tmdb.org" && url.port == -1 &&
                    url.userInfo == null && url.query == null && url.ref == null)
                connection = open(url)
                val current = requireNotNull(connection)
                active.set(current)
                checkActive()
                current.instanceFollowRedirects = false
                current.useCaches = false
                current.connectTimeout = 10000
                current.readTimeout = 15000
                current.requestMethod = "GET"
                current.setRequestProperty("Accept", "image/jpeg,image/png")
                current.setRequestProperty("Accept-Encoding", "identity")
                current.setRequestProperty("Cache-Control", "no-store")
                if (current.responseCode != 200) throw IOException("Poster preview unavailable")
                checkActive()
                val mime = current.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
                if (mime !in setOf("image/jpeg", "image/png") ||
                    current.contentEncoding?.let { !it.equals("identity", true) } == true ||
                    current.contentLengthLong > MAX_BYTES) throw IOException("Invalid poster image")
                checkActive()
                val bytes = current.inputStream.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkActive()
                        val size = stream.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > MAX_BYTES) throw IOException("Poster image too large")
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                checkActive()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..10000 || bounds.outHeight !in 1..10000 ||
                    bounds.outWidth.toLong() * bounds.outHeight > 40000000L || bounds.outMimeType != mime)
                    throw IOException("Invalid poster image")
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) > 512 * sample) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("Invalid poster image")
                if (continuation.isActive) continuation.resumeWith(Result.success(bitmap)) else bitmap.recycle()
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("海报预览失败")))
            } finally {
                active.set(null)
                runCatching { connection?.disconnect() }
            }
        })
    }

    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
