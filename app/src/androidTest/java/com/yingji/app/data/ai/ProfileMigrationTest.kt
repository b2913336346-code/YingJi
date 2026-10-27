package com.yingji.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.*
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileMigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    @Test fun versionOnePreservesAllBaseRowsAndReopensWithAiData() = verify(1)
    @Test fun versionTwoPreservesAllBaseRowsAndReopensWithAiData() = verify(2)
    @Test fun versionThreePreservesRatingsPostersAndRelationsAndReopensWithAiData() = verify(3)

    private fun verify(version: Int) = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "a2-migration-" + UUID.randomUUID() + ".db"
        val poster = File(context.cacheDir,name+".jpg").apply { writeBytes(byteArrayOf(2,4,6)) }
        try {
            migration.createDatabase(name,version).use { old ->
                old.execSQL("INSERT INTO movies (id,name,posterPath,type,status,currentEpisode,totalEpisode,createTime) VALUES (41,'保留作品',?,'电影','已看完',12,12,100)",arrayOf(poster.path))
                if(version==3) old.execSQL("UPDATE movies SET rating=8.5 WHERE id=41")
                old.execSQL("INSERT INTO categories (id,name,isSystem) VALUES (7,'电影',0)")
                old.execSQL("INSERT INTO tags (id,name) VALUES (7,'科幻')")
                old.execSQL("INSERT INTO movie_categories VALUES (41,7)")
                old.execSQL("INSERT INTO movie_tags VALUES (41,7)")
                old.execSQL("INSERT INTO watch_history (id,movieId,startTime,endTime,duration,watchNumber) VALUES (9,41,100,200,100,1)")
                if(version>=2)old.execSQL("INSERT INTO movie_types (id,name,isSystem,sortOrder) VALUES (77,'OVA',0,1000)")
            }
            migration.runMigrationsAndValidate(name,4,true,DatabaseModule.MIGRATION_1_2,DatabaseModule.MIGRATION_2_3,DatabaseModule.MIGRATION_3_4,DatabaseModule.MIGRATION_4_5).close()
            fun open()=Room.databaseBuilder(context,AppDatabase::class.java,name)
                .addMigrations(DatabaseModule.MIGRATION_1_2,DatabaseModule.MIGRATION_2_3,DatabaseModule.MIGRATION_3_4,DatabaseModule.MIGRATION_4_5)
                .openHelperFactory(PreservingOpenHelperFactory()).build()
            lateinit var saved: ProfileSnapshot
            val first = open()
            try {
                val db = first
                assertEquals(5,db.openHelper.readableDatabase.version)
                val movie = db.movieDao().observeById(41).first()!!
                assertEquals("保留作品",movie.name);assertEquals(poster.path,movie.posterPath)
                assertEquals("已看完",movie.status);assertEquals(12,movie.currentEpisode);assertEquals(12,movie.totalEpisode)
                assertEquals(100L,movie.createTime);assertEquals(if(version==3)8.5 else null,movie.rating)
                assertEquals(listOf(7L),db.categoryDao().getCategoryIdsForMovie(41))
                assertEquals(listOf(7L),db.tagDao().getTagIdsForMovie(41))
                assertEquals(7L,db.categoryDao().observeById(7).first()!!.id)
                assertEquals(7L,db.tagDao().observeById(7).first()!!.id)
                val history=db.watchHistoryDao().getById(9)!!
                assertEquals(41L,history.movieId);assertEquals(100L,history.startTime);assertEquals(200L,history.endTime)
                assertEquals(100L,history.duration);assertEquals(1,history.watchNumber)
                if(version>=2)db.openHelper.readableDatabase.query("SELECT id FROM movie_types WHERE name='OVA'").use { assertTrue(it.moveToFirst());assertEquals(77L,it.getLong(0)) }
                val repo=ProfileRepository(db,ObserveLibraryRepository(MovieRepository(db)))
                repo.setSources(ProfileSourceSettings(ratingsEnabled=true,behaviorEnabled=true))
                repo.setExplicitPreference(ProfileFeature.topic("纪录片"),PreferenceDirection.LIKE)
                repo.recordFeedback("feedback",ProfileFeature.tag(7,"科幻"),PreferenceDirection.DISLIKE)
                saved=repo.suppressInference(ProfileFeature.category(7,"电影"))
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst());assertEquals("ok",it.getString(0)) }
            } finally { first.close() }
            val second = open()
            try {
                val db = second
                val repo=ProfileRepository(db,ObserveLibraryRepository(MovieRepository(db)))
                assertEquals(saved,repo.getProfile())
                assertTrue(repo.isCurrent(saved.token))
                assertEquals(1,db.aiProfileDao().feedback().size)
                assertEquals(41L,db.movieDao().observeById(41).first()!!.id)
                val backupRoot=File(context.cacheDir,name+"-backup")
                try {
                    val store=DatabaseBackupStore(context.getDatabasePath(name),backupRoot)
                    val record=store.capture()
                    assertTrue(record.complete)
                    val checked=store.verifyWorkingCopy(record)
                    assertTrue(checked.validation.contains("副本检查通过"))
                } finally { backupRoot.deleteRecursively() }
            } finally { second.close() }
            assertArrayEquals(byteArrayOf(2,4,6),poster.readBytes())
        } finally { context.deleteDatabase(name);poster.delete();DatabaseHealthMonitor.resetForTesting() }
    }
}
