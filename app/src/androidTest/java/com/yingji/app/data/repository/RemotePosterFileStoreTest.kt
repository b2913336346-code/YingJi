package com.yingji.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemotePosterFileStoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    @Before fun setup() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        directory = File(base.cacheDir, "remote-poster-files-" + UUID.randomUUID()).apply { check(mkdirs()) }
        context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun image() = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }

    @Test fun defaultPublisherProducesAValidatedPrivatePngAndLeavesNoTemporaryFile() = runBlocking {
        val bitmap = image()
        val path = try { PosterRepository(context).persistRemote(bitmap) } finally { bitmap.recycle() }
        val target = File(path)
        assertEquals(File(directory, "posters").canonicalFile, target.parentFile?.canonicalFile)
        assertTrue(target.name.endsWith(".png"))
        assertEquals(setOf(target.name), target.parentFile!!.list()!!.toSet())
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), target.readBytes().take(8).toByteArray())
        requireNotNull(BitmapFactory.decodeFile(path)).let { decoded ->
            assertNotNull(decoded); assertEquals(96, decoded.width); assertEquals(144, decoded.height)
            assertEquals(Color.GRAY, decoded.getPixel(0, 0)); decoded.recycle()
        }
    }
    @Test fun publisherSeesOnlyAValidatedTemporaryFileBeforeFinalPublication() = runBlocking {
        var checked = false
        val store = PosterRepository(context) { temporary, target ->
            assertTrue(temporary.name.startsWith(".remote-")); assertTrue(temporary.name.endsWith(".part"))
            assertFalse(target.exists())
            val decoded = requireNotNull(BitmapFactory.decodeFile(temporary.path))
            assertNotNull(decoded); assertEquals(Color.GRAY, decoded.getPixel(0, 0)); decoded.recycle()
            checked = true
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        val bitmap = image()
        val path = try { store.persistRemote(bitmap) } finally { bitmap.recycle() }
        assertTrue(checked); assertTrue(File(path).isFile)
        assertFalse(File(directory, "posters").list()!!.any { it.endsWith(".part") })
    }
    @Test fun failedPublicationRemovesOnlyItsTemporaryAndNewFinalFiles() = runBlocking {
        val folder = File(directory, "posters").apply { mkdirs() }
        val old = File(folder, "existing.png").apply { writeText("original evidence") }
        val store = PosterRepository(context) { temporary, target ->
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            throw IOException("Synthetic publish failure")
        }
        val bitmap = image()
        try { assertTrue(runCatching { store.persistRemote(bitmap) }.isFailure) } finally { bitmap.recycle() }
        assertEquals("original evidence", old.readText())
        assertEquals(setOf(old.name), folder.list()!!.toSet())
    }
    @Test fun recycledAndOversizedDecodedImagesNeverCreateAFile() = runBlocking {
        val recycled = image().apply { recycle() }
        assertTrue(runCatching { PosterRepository(context).persistRemote(recycled) }.isFailure)
        val oversized = Bitmap.createBitmap(513, 1, Bitmap.Config.ARGB_8888)
        try { assertTrue(runCatching { PosterRepository(context).persistRemote(oversized) }.isFailure) } finally { oversized.recycle() }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun noSpaceBeforeWritingCleansTheTemporaryFileAndPreservesExistingBytes() = runBlocking {
        assertNoSpaceRollback(0)
    }

    @Test fun noSpaceAfterPartialWritingCleansTheTemporaryFileAndPreservesExistingBytes() = runBlocking {
        assertNoSpaceRollback(32)
    }

    private suspend fun assertNoSpaceRollback(bytesBeforeFailure: Int) {
        val folder = File(directory, "posters").apply { check(mkdirs()) }
        val original = byteArrayOf(11, 22, 33, 44)
        val old = File(folder, "existing.png").apply { writeBytes(original) }
        var wrote = -1L
        var published = false
        val store = PosterRepository(context, writeRemote = { _, staged ->
            staged.outputStream().use { output ->
                output.write(ByteArray(bytesBeforeFailure) { 85 })
                output.flush()
                wrote = staged.length()
                throw IOException("Injected write failure", android.system.ErrnoException("write", android.system.OsConstants.ENOSPC))
            }
        }, publishRemote = { _, _ -> published = true; fail("Incomplete file must not be published") })
        val bitmap = image()
        val failure = try { runCatching { store.persistRemote(bitmap) }.exceptionOrNull() } finally { bitmap.recycle() }
        assertTrue(failure is IOException)
        assertEquals(android.system.OsConstants.ENOSPC, (failure?.cause as android.system.ErrnoException).errno)
        assertEquals(bytesBeforeFailure.toLong(), wrote)
        assertFalse(published)
        assertArrayEquals(original, old.readBytes())
        assertEquals(setOf(old.name), folder.list()!!.toSet())
    }

    @Test fun aTruncatedWriteThatReturnsNormallyIsRejectedBeforePublication() = runBlocking {
        var published = false
        val store = PosterRepository(context, writeRemote = { _, staged ->
            staged.writeBytes(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        }, publishRemote = { _, _ -> published = true; fail("Truncated PNG must not be published") })
        val bitmap = image()
        try { assertTrue(runCatching { store.persistRemote(bitmap) }.exceptionOrNull() is IOException) }
        finally { bitmap.recycle() }
        assertFalse(published)
        assertTrue(File(directory, "posters").listFiles()!!.isEmpty())
    }

    @Test fun managedCleanupCannotDeleteAnOutsideFile() = runBlocking {
        val outside = File(directory, "outside.png").apply { writeText("keep") }
        PosterRepository(context).deleteManagedFile(outside.path)
        assertEquals("keep", outside.readText())
    }
}
