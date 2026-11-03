package com.yingji.app.data.backup

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, bounded authenticated frames; a final authenticated empty frame detects truncation. */
internal object BackupCipher {
    const val MAX_BYTES = 128L * 1024 * 1024
    private const val CHUNK = 256 * 1024
    private const val ITERATIONS = 600_000
    private val magic = "YINGJI01".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 8 + 4 + 4 + 16 + 8
    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, cancelled: () -> Unit = {}) {
        require(password.size in 12..256) { "备份密码需为 12–256 个字符" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(magic).putInt(1).putInt(ITERATIONS).put(salt).put(nonce).array()
        val key = derive(password, salt)
        try {
            val target = DataOutputStream(output); target.write(header)
            val buffer = ByteArray(CHUNK); var counter = 0; var total = 0L
            while (true) {
                cancelled()
                var length = 0
                while (length < buffer.size) { val n = input.read(buffer, length, buffer.size - length); if (n < 0) break; if (n == 0) throw IOException("备份输入无法继续"); length += n }
                total += length; require(total <= MAX_BYTES) { "备份文件超过 128 MiB 限制" }
                val cipher = frame(Cipher.ENCRYPT_MODE, key, nonce, header, counter++, length)
                target.writeInt(length); target.write(cipher.doFinal(buffer, 0, length))
                if (length == 0) break
            }
            target.flush()
        } finally { key.fill(0) }
    }
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray, cancelled: () -> Unit = {}) {
        require(password.size in 12..256) { "备份密码需为 12–256 个字符" }
        val source = DataInputStream(input); val header = ByteArray(HEADER); source.readFully(header)
        val h = ByteBuffer.wrap(header); val m = ByteArray(8); h.get(m)
        require(m.contentEquals(magic) && h.int == 1 && h.int == ITERATIONS) { "不支持的备份格式或加密参数" }
        val salt = ByteArray(16); h.get(salt); val nonce = ByteArray(8); h.get(nonce)
        val key = derive(password, salt)
        try {
            var counter = 0; var total = 0L
            while (true) {
                cancelled()
                val length = source.readInt()
                require(length in 0..CHUNK) { "备份分块无效" }
                total += length; require(total <= MAX_BYTES && counter <= MAX_BYTES / CHUNK + 1) { "备份文件过大" }
                val encrypted = ByteArray(length + 16); source.readFully(encrypted)
                val plain = frame(Cipher.DECRYPT_MODE, key, nonce, header, counter++, length).doFinal(encrypted)
                require(plain.size == length) { "备份分块损坏" }
                if (length == 0) { require(source.read() == -1) { "备份有额外内容" }; break }
                output.write(plain)
            }
            output.flush()
        } finally { key.fill(0) }
    }
    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    private fun frame(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray, counter: Int, length: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).put(nonce).putInt(counter).array()))
            updateAAD(header); updateAAD(ByteBuffer.allocate(8).putInt(counter).putInt(length).array())
        }
}
