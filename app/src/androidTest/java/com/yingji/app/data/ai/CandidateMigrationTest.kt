package com.yingji.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class CandidateMigrationTest {
    @get:Rule val migration=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),AppDatabase::class.java)
    private val tables=listOf("movies","watch_history","categories","tags","movie_categories","movie_tags","movie_types",
        "ai_profile_state","ai_explicit_preferences","ai_profile_corrections","ai_profile_feedback","ai_inferred_preferences")
    private fun snapshot(db: SupportSQLiteDatabase)=tables.associateWith { table ->
        db.query("SELECT * FROM "+table+" ORDER BY rowid").use { c -> buildList {
            add(c.columnNames.joinToString("|"));while(c.moveToNext())add((0 until c.columnCount).joinToString("|") { i -> if(c.isNull(i))"<null>" else c.getString(i) })
        } }
    }
    @Test fun versionFourToFivePreservesEveryBaseAndProfileFieldAndProtectionAcceptsNewVersion() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="a4-migration-"+UUID.randomUUID()+".db"
        val poster=File(context.cacheDir,name+".jpg").apply { writeBytes(byteArrayOf(4,5,6)) }
        val backup=File(context.cacheDir,name+"-backup")
        try {
            val old=migration.createDatabase(name,4)
            old.execSQL("INSERT INTO movies VALUES (41,'保留作品',?,'电影','已看完',12,12,100,8.5)",arrayOf(poster.path))
            old.execSQL("INSERT INTO categories VALUES (7,'电影',0)");old.execSQL("INSERT INTO tags VALUES (7,'科幻')")
            old.execSQL("INSERT INTO movie_categories VALUES (41,7)");old.execSQL("INSERT INTO movie_tags VALUES (41,7)")
            old.execSQL("INSERT INTO watch_history VALUES (9,41,100,200,100,1)")
            old.execSQL("INSERT INTO movie_types VALUES (77,'OVA',0,1000)")
            old.execSQL("INSERT INTO ai_profile_state VALUES (1,1,0,1,8,3,'fingerprint',200)")
            old.execSQL("INSERT INTO ai_explicit_preferences VALUES ('topic:科幻','科幻','LIKE',200)")
            old.execSQL("INSERT INTO ai_profile_corrections VALUES ('tag:7',200)")
            old.execSQL("INSERT INTO ai_profile_feedback VALUES ('event','topic:纪录片','纪录片','DISLIKE',200)")
            old.execSQL("INSERT INTO ai_inferred_preferences VALUES ('category:7','RATING','电影',1,1,0,0,0)")
            val before=snapshot(old);old.close()
            val migrated=migration.runMigrationsAndValidate(name,5,true,DatabaseModule.MIGRATION_4_5)
            assertEquals(before,snapshot(migrated));migrated.close()
            val db=Room.databaseBuilder(context,AppDatabase::class.java,name).openHelperFactory(PreservingOpenHelperFactory()).build()
            try {
                assertEquals(5,db.openHelper.readableDatabase.version);assertEquals(before,snapshot(db.openHelper.readableDatabase))
                assertNull(db.candidateImportDao().movieId("source","external"))
                db.candidateImportDao().insert(AiCandidateImport("source","external",41))
                assertEquals(41L,db.candidateImportDao().movieId("source","external"))
                db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst());assertEquals("ok",it.getString(0)) }
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                val store=DatabaseBackupStore(context.getDatabasePath(name),backup)
                val record=store.capture();assertTrue(record.complete)
                assertTrue(store.verifyWorkingCopy(record).validation.contains("副本检查通过（Schema 5"))
            } finally { db.close() }
            assertArrayEquals(byteArrayOf(4,5,6),poster.readBytes())
        } finally { context.deleteDatabase(name);poster.delete();backup.deleteRecursively();DatabaseHealthMonitor.resetForTesting() }
    }
}
