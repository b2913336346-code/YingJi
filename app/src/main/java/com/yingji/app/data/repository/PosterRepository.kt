package com.yingji.app.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class PosterSaveException(cause: Throwable) : IOException("海报保存失败，请重新选择图片后重试", cause)

class PosterRepository internal constructor(
    private val context: Context,
    private val publishRemote: (File, File) -> Unit,
) {
    constructor(context: Context) : this(context, { temporary, target ->
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(requireNotNull(target.parentFile))
    })

    suspend fun persist(source: String?): String? {
        if (source.isNullOrBlank()) return null
        var destination: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val directory = File(context.filesDir, POSTER_DIRECTORY)
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw IOException("无法创建海报目录")
                }
                val uri = Uri.parse(source)
                val input = when (uri.scheme) {
                    null -> File(source).inputStream()
                    "file" -> File(uri.path ?: throw IOException("海报路径无效")).inputStream()
                    "content" -> context.contentResolver.openInputStream(uri)
                    else -> throw IOException("不支持的本地海报路径")
                } ?: throw IOException("无法读取所选海报")
                input.use { sourceStream ->
                    val target = File(directory, UUID.randomUUID().toString() + ".jpg")
                    destination = target
                    target.outputStream().use { output ->
                        sourceStream.copyTo(output)
                        output.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    target.absolutePath
                }
            }
        } catch (exception: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { destination?.delete() }
            if (exception is CancellationException) throw exception
            throw PosterSaveException(exception)
        }
    }


    /** Only called after explicit confirmation. Preview never invokes this persistence method. */
    suspend fun persistRemote(bitmap: Bitmap): String {
        var temporary: File? = null
        var published: File? = null
        try {
            return withContext(Dispatchers.IO) {
                check(!bitmap.isRecycled && bitmap.width in 1..512 && bitmap.height in 1..512) { "海报图片无效" }
                currentCoroutineContext().ensureActive()
                val directory = File(context.filesDir, POSTER_DIRECTORY)
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建海报目录")
                val staged = File.createTempFile(".remote-", ".part", directory)
                temporary = staged
                staged.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("海报写入失败")
                    output.fd.sync()
                }
                if (staged.length() !in 1..2097152) throw IOException("海报文件大小无效")
                val verified = BitmapFactory.decodeFile(staged.absolutePath) ?: throw IOException("海报校验失败")
                try { check(verified.width == bitmap.width && verified.height == bitmap.height) { "海报校验失败" } }
                finally { verified.recycle() }
                currentCoroutineContext().ensureActive()
                val target = File(directory, UUID.randomUUID().toString() + ".png")
                check(!target.exists()) { "海报目标已存在" }
                published = target
                publishRemote(staged, target)
                currentCoroutineContext().ensureActive()
                check(target.isFile && target.length() in 1..2097152) { "海报文件未保存" }
                target.absolutePath
            }
        } catch (exception: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { temporary?.delete() }
                runCatching { published?.delete() }
            }
            throw exception
        }
    }

    /** The caller must first protect the database reference check against concurrent writes. */
    suspend fun deleteManagedFile(path: String) = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, POSTER_DIRECTORY).canonicalFile
        val target = File(path).canonicalFile
        if (target.parentFile == directory && target.exists()) {
            if (!target.isFile || !target.delete()) throw IOException("旧海报清理失败")
            syncDirectory(directory)
        }
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        runCatching {
            val file = File(path)
            val directory = File(context.filesDir, POSTER_DIRECTORY).canonicalFile
            val target = file.canonicalFile
            if (target.parentFile == directory && target.exists()) target.delete()
        }
    }

    companion object {
        const val POSTER_DIRECTORY = "posters"

        private fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}
