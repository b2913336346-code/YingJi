package com.yingji.app.data.backup

import java.io.*
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class BackupCipherTest {
    private val password get() = "TEST_ONLY_BackupPassword_2026".toCharArray()
    private fun encrypt(bytes: ByteArray) = ByteArrayOutputStream().also { BackupCipher.encrypt(bytes.inputStream(), it, password) }.toByteArray()
    private fun decrypt(bytes: ByteArray, secret: CharArray = password) = ByteArrayOutputStream().also { BackupCipher.decrypt(bytes.inputStream(), it, secret) }.toByteArray()
    private inline fun <reified T : Exception> rejects(block: () -> Unit) { try { block(); fail("Input was accepted") } catch (e: Exception) { assertTrue(e.javaClass.name, e is T) } }
    @Test fun roundTripAcrossFrameBoundaries() { val plain = ByteArray(530_019) { (it % 239).toByte() }; assertArrayEquals(plain, decrypt(encrypt(plain))) }
    @Test fun emptyStreamStillAuthenticatesHeaderAndEnd() { assertArrayEquals(byteArrayOf(), decrypt(encrypt(byteArrayOf()))) }
    @Test fun repeatedPasswordUsesFreshSaltAndNonce() { val a = encrypt("private library".toByteArray()); val b = encrypt("private library".toByteArray()); assertFalse(a.contentEquals(b)); assertArrayEquals(decrypt(a), decrypt(b)) }
    @Test fun wrongPasswordIsRejected() { rejects<AEADBadTagException> { decrypt(encrypt(byteArrayOf(1, 2)), "TEST_ONLY_WrongPassword123".toCharArray()) } }
    @Test fun modifiedCiphertextIsRejected() { val b = encrypt(ByteArray(91) { 7 }); b[48] = (b[48].toInt() xor 1).toByte(); rejects<AEADBadTagException> { decrypt(b) } }
    @Test fun truncatedAuthenticatedEndIsRejected() { val b = encrypt(ByteArray(30)); rejects<EOFException> { decrypt(b.copyOf(b.size - 20)) } }
    @Test fun appendedBytesAreRejected() { rejects<IllegalArgumentException> { decrypt(encrypt(ByteArray(30)) + byteArrayOf(1)) } }
    @Test fun untrustedKdfParametersAreRejected() { val b = encrypt(ByteArray(1)); ByteBuffer.wrap(b).putInt(12, Int.MAX_VALUE); rejects<IllegalArgumentException> { decrypt(b) } }
    @Test fun reorderedFramesAreRejected() { val b = encrypt(ByteArray(524_300) { it.toByte() }); val size = 262_144 + 20; val swapped = b.copyOfRange(0, 40) + b.copyOfRange(40 + size, 40 + 2 * size) + b.copyOfRange(40, 40 + size) + b.copyOfRange(40 + 2 * size, b.size); rejects<AEADBadTagException> { decrypt(swapped) } }
    @Test fun shortPasswordRejectedBeforeWriting() { val out = ByteArrayOutputStream(); rejects<IllegalArgumentException> { BackupCipher.encrypt(byteArrayOf(1).inputStream(), out, "short".toCharArray()) }; assertEquals(0, out.size()) }
}
