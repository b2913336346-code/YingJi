package com.yingji.app.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.yingji.app.domain.ai.*
import java.io.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Isolated TMDB namespace; follows the existing BYOK AES-GCM/AtomicFile pattern without touching DeepSeek keys. */
class TmdbCredentialStore internal constructor(
    private val file: File,
    private val writeEncrypted: (AtomicFile, ByteArray) -> Unit = ::atomicWrite,
) : TmdbSecureCredentialStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "tmdb-credentials/config.enc"))
    private val mutex = Mutex()
    private val atomic = AtomicFile(file)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun header(alias: String) = ByteArrayOutputStream().apply { DataOutputStream(this).use { it.writeInt(MAGIC); it.writeUTF(alias) } }.toByteArray()
    private fun alias(input: DataInputStream): String {
        if (input.readInt() != MAGIC) throw TmdbCredentialRecoveryRequired()
        return input.readUTF().also { if (!Regex("yingji[.]tmdb[.]v1[.][a-f0-9-]{36}").matches(it)) throw TmdbCredentialRecoveryRequired() }
    }
    private fun readBytes(): ByteArray? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomic.openRead().use { stream ->
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break
                if (bytes.size() + count > MAX_BYTES) throw TmdbCredentialRecoveryRequired(); bytes.write(buffer, 0, count) }
            bytes.toByteArray()
        }
    }
    private fun readRecord(): TmdbCredentialRecord? {
        try {
            val bytes = readBytes() ?: return null
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val alias = alias(input)
                val ivSize = input.readInt(); if (ivSize != 12) throw TmdbCredentialRecoveryRequired()
                val iv = ByteArray(ivSize); input.readFully(iv)
                val size = input.readInt(); if (size !in 16..MAX_BYTES || size > input.available()) throw TmdbCredentialRecoveryRequired()
                val encrypted = ByteArray(size); input.readFully(encrypted); if (input.available() != 0) throw TmdbCredentialRecoveryRequired()
                val key = keys().getKey(alias, null) as? SecretKey ?: throw TmdbCredentialRecoveryRequired()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); cipher.updateAAD(header(alias))
                val plain = cipher.doFinal(encrypted)
                try {
                    DataInputStream(ByteArrayInputStream(plain)).use { payload ->
                        if (!payload.readBoolean()) {
                            if (payload.available() != 0) throw TmdbCredentialRecoveryRequired()
                            TmdbCredentialRecord(null, null, alias.substringAfterLast('.'))
                        } else {
                            val secret = TmdbSecret.fromInput(payload.readUTF())
                            try {
                                val time = payload.readLong(); if (time < 0) throw TmdbCredentialRecoveryRequired()
                                if (payload.available() != 0) throw TmdbCredentialRecoveryRequired()
                                TmdbCredentialRecord(secret, time, alias.substringAfterLast('.'))
                            } catch (e: Exception) { secret.close(); throw e }
                        }
                    }
                } finally { plain.fill(0) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw TmdbCredentialRecoveryRequired() }
    }
    override suspend fun read(): TmdbCredentialRecord? = withContext(Dispatchers.IO) { mutex.withLock { readRecord() } }
    override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String = withContext(Dispatchers.IO) { mutex.withLock {
        require((key == null && savedAt == null) || (key != null && savedAt != null && savedAt >= 0))
        val oldAlias = try { readRecord()?.use { PREFIX + it.revision } } catch (_: Exception) { null }
        val revision = UUID.randomUUID().toString(); val nextAlias = PREFIX + revision
        var committed = false
        try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(nextAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            val aes = generator.generateKey()
            val payload = ByteArrayOutputStream().apply { DataOutputStream(this).use { output ->
                output.writeBoolean(key != null)
                if (key != null) { key.useValue { output.writeUTF(it) }; output.writeLong(savedAt!!) }
            } }.toByteArray()
            val encrypted: ByteArray; val iv: ByteArray
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, aes); cipher.updateAAD(header(nextAlias))
                iv = cipher.iv; check(iv.size == 12); encrypted = cipher.doFinal(payload)
            } finally { payload.fill(0) }
            val bytes = ByteArrayOutputStream().apply { DataOutputStream(this).use { output ->
                output.write(header(nextAlias)); output.writeInt(iv.size); output.write(iv); output.writeInt(encrypted.size); output.write(encrypted)
            } }.toByteArray()
            check(bytes.size <= MAX_BYTES)
            file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
            writeEncrypted(atomic, bytes)
            // AtomicFile may report a failed final rename only through platform logs.
            // Confirm the exact ciphertext before reporting success or destroying the old alias.
            check(readBytes()?.contentEquals(bytes) == true)
            committed = true
            // Only the previous alias belonging to this record can be removed, after atomic success.
            if (oldAlias != null && oldAlias != nextAlias) runCatching { keys().deleteEntry(oldAlias) }
            revision
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw TmdbCredentialException(TmdbCredentialProblem.STORAGE_FAILED) }
        finally {
            if (!committed) {
                // An unreadable post-write state is uncertain: retain both aliases for recovery.
                val definitelyUnreferenced = runCatching { readRecord()?.use { it.revision != revision } ?: true }.getOrDefault(false)
                if (definitelyUnreferenced) runCatching { keys().deleteEntry(nextAlias) }
            }
        }
    } }
    companion object {
        private const val PREFIX = "yingji.tmdb.v1."
        private const val MAGIC = 0x59544D31
        private const val MAX_BYTES = 16384
        internal fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
            val stream = file.startWrite()
            try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
}
