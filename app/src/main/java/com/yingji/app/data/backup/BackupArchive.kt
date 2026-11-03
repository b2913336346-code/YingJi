package com.yingji.app.data.backup

import android.graphics.BitmapFactory
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

internal object BackupArchive {
    const val MAX_IMAGE = 5L * 1024 * 1024
    private const val MAX_DATA = 16L * 1024 * 1024
    fun copyBounded(input: InputStream, target: File, limit: Long, cancel: () -> Unit = {}): String {
        require(!target.exists()) { "临时目标已存在" }
        require(target.parentFile!!.usableSpace > 1024 * 1024) { "可用空间不足，未完成备份或恢复" }
        val hash = MessageDigest.getInstance("SHA-256"); var total = 0L; var checkedAt = 0L
        FileOutputStream(target).use { out ->
            val bytes = ByteArray(64 * 1024)
            while (true) { cancel(); val n = input.read(bytes); if (n < 0) break; require(n > 0) { "文件读取未完成" }; total += n; if (total - checkedAt >= 1024 * 1024) { require(target.parentFile!!.usableSpace > 1024 * 1024) { "可用空间不足，未完成备份或恢复" }; checkedAt = total }; require(total <= limit) { "备份条目过大" }; hash.update(bytes, 0, n); out.write(bytes, 0, n) }
            out.fd.sync()
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun image(file: File) {
        require(file.length() in 1..MAX_IMAGE) { "海报文件过大或为空" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 20_000_000 && bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) { "海报无法解码或像素过大" }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        require(decoded != null) { "海报实际解码失败" }
        decoded.recycle()
    }
    fun pack(data: BackupData, images: Map<String, File>, zip: File, cancel: () -> Unit) {
        data.validate(true)
        val content = data.json().toString().toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_DATA) { "资料 JSON 过大" }
        val manifest = JSONObject().put("format", 1).put("schema", 5).put("dataSha256", sha256(content)).put("movies", data.movieCount)
        FileOutputStream(zip).use { raw ->
            ZipOutputStream(raw).use { out ->
                fun entry(name: String, write: () -> Unit) { cancel(); out.putNextEntry(ZipEntry(name)); write(); out.closeEntry() }
                entry("manifest.json") { out.write(manifest.toString().toByteArray(Charsets.UTF_8)) }
                entry("data.json") { out.write(content) }
                images.toSortedMap().forEach { (id, file) -> entry("posters/$id") { file.inputStream().use { source -> val b = ByteArray(65536); while (true) { cancel(); val n = source.read(b); if (n < 0) break; out.write(b, 0, n) } } } }
            }
        }
        require(zip.length() <= BackupCipher.MAX_BYTES) { "备份包过大" }
    }
    fun unpack(zip: File, directory: File, cancel: () -> Unit): BackupData {
        val seen = mutableSetOf<String>(); var total = 0L
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                cancel(); val entry = input.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && seen.add(name) && seen.size <= 10_002 && (name in setOf("manifest.json", "data.json") || Regex("posters/[a-f0-9]{64}").matches(name))) { "备份条目名、路径或数量无效" }
                val target = File(directory, if (name.startsWith("posters/")) "image_" + name.substringAfter('/') else name)
                val limit = when (name) { "manifest.json" -> 4096L; "data.json" -> MAX_DATA; else -> MAX_IMAGE }
                val hash = copyBounded(input, target, limit, cancel)
                total += target.length(); require(total <= BackupCipher.MAX_BYTES) { "备份解包大小超限" }
                if (name.startsWith("posters/")) { require(hash == name.substringAfter('/')) { "海报校验失败" }; image(target) }
                input.closeEntry()
            }
        }
        require(seen.containsAll(setOf("manifest.json", "data.json"))) { "备份文件不完整" }
        val bytes = File(directory, "data.json").readBytes()
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        require(manifest.keys().asSequence().toSet() == setOf("format", "schema", "dataSha256", "movies") && isExactInteger(manifest, "format", 1) && isExactInteger(manifest, "schema", 5) && manifest.getString("dataSha256") == sha256(bytes)) { "备份清单校验失败" }
        val data = BackupData.parse(bytes)
        val images = data.rows("movies").filter { !it.isNull(2) }.map { it.getString(2) }.toSet()
        require(isExactInteger(manifest, "movies", data.movieCount.toLong()) && seen == setOf("manifest.json", "data.json") + images.map { "posters/$it" }) { "备份海报引用或数量不匹配" }
        return data
    }
}
