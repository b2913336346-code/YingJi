package com.yingji.app.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.*
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class BackupArchiveValidationTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var area: File
    @Before fun setup() { area = File(app.cacheDir, "backup-boundaries-" + UUID.randomUUID()).apply { check(mkdir()) } }
    @After fun cleanup() { check(area.canonicalFile.parentFile == app.cacheDir.canonicalFile); area.walkBottomUp().forEach { check(it.canonicalPath.startsWith(area.canonicalPath)); check(it.delete()) } }
    private fun valid(): BackupData {
        val tables = BACKUP_TABLES.associate { it.name to JSONArray() }
        tables.getValue("ai_profile_state").put(JSONArray(listOf(1, 0, 0, 1)))
        tables.getValue("categories").put(JSONArray(listOf(1, "电影", 1)))
        tables.getValue("movies").put(JSONArray(listOf(1, "TEST_ONLY_BOUNDARY", JSONObject.NULL, "电影", "想看", 0, 0, 0, 8.5)))
        tables.getValue("movie_categories").put(JSONArray(listOf(1, 1)))
        return BackupData(tables, BackupPreferences("SYSTEM", "LIST", "CREATED_DESC"))
    }
    private fun rejected(block: () -> Unit) { var failed = false; try { block() } catch (_: Exception) { failed = true }; assertTrue("Invalid archive must be rejected", failed) }
    @Test fun invalidScalarTypesEnumsRatingsAndProgressAreRejected() {
        BackupData.parse(valid().json().toString().toByteArray())
        val cases = listOf<Pair<Int, Any>>(0 to 1.5, 1 to "", 4 to "unknown", 5 to -1, 6 to -1, 8 to 8.7, 8 to 11.0)
        cases.forEach { (column, value) -> val d = valid(); d.rows("movies").single().put(column, value); rejected { BackupData.parse(d.json().toString().toByteArray()) } }
    }
    @Test fun duplicateIdsBrokenRelationsAndUnknownFieldsAreRejected() {
        val duplicate = valid(); duplicate.tables.getValue("movies").put(duplicate.rows("movies").single()); rejected { BackupData.parse(duplicate.json().toString().toByteArray()) }
        val broken = valid(); broken.rows("movie_categories").single().put(1, 999); rejected { BackupData.parse(broken.json().toString().toByteArray()) }
        val unknown = valid().json().put("unexpected", "TEST_ONLY"); rejected { BackupData.parse(unknown.toString().toByteArray()) }
    }
    @Test fun futureFormatsAndMalformedUtf8AreRejected() {
        val json = valid().json().put("format", 2); rejected { BackupData.parse(json.toString().toByteArray()) }
        val fractional = valid().json().put("schema", 5.5); rejected { BackupData.parse(fractional.toString().toByteArray()) }
        rejected { BackupData.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
    }
    // Stored local headers let the parser, rather than ZipOutputStream, encounter duplicate names.
    private fun zip(entries: List<Pair<String, ByteArray>>): File = File(area, UUID.randomUUID().toString() + ".zip").also { file ->
        DataOutputStream(FileOutputStream(file)).use { out ->
            fun int(n: Int) = out.writeInt(Integer.reverseBytes(n))
            fun short(n: Int) = out.writeShort(java.lang.Short.reverseBytes(n.toShort()).toInt())
            entries.forEach { (name, data) ->
                val bytes = name.toByteArray(Charsets.UTF_8); val crc = CRC32().apply { update(data) }.value.toInt()
                int(0x04034b50); short(20); short(0); short(0); short(0); short(0); int(crc); int(data.size); int(data.size); short(bytes.size); short(0); out.write(bytes); out.write(data)
            }
        }
    }
    private fun unpack(file: File) { val dest = File(area, UUID.randomUUID().toString()).apply { mkdir() }; BackupArchive.unpack(file, dest) {} }
    @Test fun duplicateUnknownAndTraversalZipEntriesAreRejected() {
        listOf(listOf("manifest.json", "manifest.json"), listOf("unknown.json"), listOf("../outside"), listOf("/absolute")).forEach { names ->
            rejected { unpack(zip(names.map { it to "{}".toByteArray() })) }
        }
        assertFalse(File(area.parentFile, "outside").exists())
    }
    @Test fun boundedCopyStopsAtTheLimitAndRespondsToCancellation() {
        val oversized = File(area, "oversized")
        rejected { BackupArchive.copyBounded(ByteArrayInputStream(ByteArray(512)), oversized, 128) }
        assertTrue(oversized.length() <= 128)
        val cancelled = File(area, "cancelled"); var checks = 0
        rejected { BackupArchive.copyBounded(ByteArrayInputStream(ByteArray(128 * 1024)), cancelled, 1024 * 1024) { if (++checks == 2) throw CancellationException("TEST_ONLY_CANCEL") } }
        assertEquals(2, checks); assertEquals(64L * 1024, cancelled.length())
    }
    @Test fun oversizedManifestAndInvalidImageAreRejected() {
        rejected { unpack(zip(listOf("manifest.json" to ByteArray(4097)))) }
        val invalid = File(area, "not-an-image").apply { writeBytes(ByteArray(64)) }
        rejected { BackupArchive.image(invalid) }
    }
}
