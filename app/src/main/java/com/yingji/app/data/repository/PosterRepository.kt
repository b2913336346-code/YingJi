package com.yingji.app.data.repository

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PosterRepository(
    private val context: Context,
) {
    suspend fun persist(source: String?): String? = withContext(Dispatchers.IO) {
        if (source.isNullOrBlank()) return@withContext null

        val existingFile = File(source)
        if (existingFile.isAbsolute && existingFile.exists()) return@withContext source

        var destination: File? = null
        runCatching {
            val sourceUri = Uri.parse(source)
            val posterDirectory = File(context.filesDir, POSTER_DIRECTORY)
            if (!posterDirectory.exists() && !posterDirectory.mkdirs()) return@runCatching null
            destination = File(posterDirectory, "${UUID.randomUUID()}.jpg")
            val input = when (sourceUri.scheme) {
                "file" -> sourceUri.path?.let(::File)?.inputStream()
                else -> context.contentResolver.openInputStream(sourceUri)
            } ?: return@runCatching null

            input.use { sourceStream ->
                destination?.outputStream()?.use(sourceStream::copyTo)
            }
            destination?.absolutePath
        }.getOrElse {
            destination?.delete()
            null
        }
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        runCatching {
            val file = File(path)
            val posterDirectory = File(context.filesDir, POSTER_DIRECTORY).canonicalFile
            val target = file.canonicalFile
            if (target.parentFile == posterDirectory && target.exists()) {
                target.delete()
            }
        }
    }

    companion object {
        const val POSTER_DIRECTORY = "posters"
    }
}
