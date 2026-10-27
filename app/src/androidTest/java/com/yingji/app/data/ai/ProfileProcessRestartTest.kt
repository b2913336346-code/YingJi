package com.yingji.app.data.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileProcessRestartTest {
    @Test fun profileAndOriginalDataSurviveARealProcessRestart() = runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        val name=requireNotNull(args.getString("a2FixtureName"))
        require(Regex("a2-restart-[a-f0-9-]+[.]db").matches(name))
        val phase=requireNotNull(args.getString("a2Phase"))
        val context=ApplicationProvider.getApplicationContext<Context>()
        DatabaseHealthMonitor.resetForTesting()
        if(phase=="prepare")check(!context.getDatabasePath(name).exists())
        else check(context.getDatabasePath(name).isFile)
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name)
            .addMigrations(DatabaseModule.MIGRATION_1_2,DatabaseModule.MIGRATION_2_3,DatabaseModule.MIGRATION_3_4,DatabaseModule.MIGRATION_4_5)
            .openHelperFactory(PreservingOpenHelperFactory()).build()
        try {
            val repo=ProfileRepository(db,ObserveLibraryRepository(MovieRepository(db)))
            val feature=ProfileFeature.tag(7,"科幻")
            when(phase){
                "prepare" -> {
                    db.categoryDao().insert(Category(7,"电影",false));db.tagDao().insert(Tag(7,"科幻"))
                    MovieRepository(db).insertWithRelations(Movie(id=41,name="重启保留",type="电影",status="想看",createTime=100,rating=8.5),7,setOf(7))
                    repo.setSources(ProfileSourceSettings(ratingsEnabled=true,behaviorEnabled=true))
                    repo.setExplicitPreference(feature,PreferenceDirection.DISLIKE)
                    repo.recordFeedback("first",feature,PreferenceDirection.LIKE)
                    repo.suppressInference(feature)
                }
                "verify" -> {
                    val value=repo.getProfile()
                    assertEquals(ProfileSourceSettings(true,true,true),value.settings)
                    assertEquals(ProfileToken(4,1),value.token)
                    assertEquals(PreferenceDirection.DISLIKE,value.effectiveDirections[feature.id])
                    assertEquals(setOf(feature.id),value.suppressedFeatureIds)
                    assertFalse(value.inferredPreferences.any { it.feature.id==feature.id })
                    assertEquals(1,db.aiProfileDao().feedback().size)
                    val movie=db.movieDao().observeById(41).first()!!
                    assertEquals("重启保留",movie.name);assertEquals(8.5,movie.rating)
                    assertEquals(listOf(7L),db.categoryDao().getCategoryIdsForMovie(41))
                    assertEquals(listOf(7L),db.tagDao().getTagIdsForMovie(41))
                    repo.undoSuppression(feature.id)
                    assertTrue(repo.getProfile().inferredPreferences.any { it.feature.id==feature.id })
                }
                else -> error("Unknown restart phase")
            }
            Log.i("YingJiA2Restart","PHASE="+phase+"; PID="+Process.myPid())
        } finally {
            db.close()
            if(phase=="verify")context.deleteDatabase(name)
            DatabaseHealthMonitor.resetForTesting()
        }
    }
}
