package com.yingji.app.data.ai

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.Category
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.AddMovieUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Host runs prepare and verify in distinct processes, using this UUID-owned database only. */
class CandidateProcessRestartTest {
    @Test fun identityAndOriginalStateSurviveRealProcessRestart() = runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        val name=requireNotNull(args.getString("a4Fixture"))
        require(Regex("a4-restart-[0-9a-f-]{36}\\.db").matches(name))
        val phase=requireNotNull(args.getString("a4Phase"));require(phase in setOf("prepare","verify"))
        val context=ApplicationProvider.getApplicationContext<Context>()
        DatabaseHealthMonitor.resetForTesting()
        if(phase=="prepare")check(!context.getDatabasePath(name).exists())
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).openHelperFactory(PreservingOpenHelperFactory()).build()
        try {
            val movies=MovieRepository(db)
            val add=AddRecommendedWorkUseCase(MockCandidateCatalogRepository(),CandidateImportRepository(db,movies),AddMovieUseCase(movies))
            val input=RecommendedWorkInput(CandidateIdentity(MockCandidateCatalogRepository.SOURCE_ID,"mock-0"),true,
                "重启保留的 Mock",7,"分类",rating=8.5,status=MovieStatus.WATCHING,allowMock=true)
            if(phase=="prepare") {
                db.categoryDao().insert(Category(7,"分类",false));add(input)
            } else {
                val before=movies.observeAll().first().single()
                assertEquals("重启保留的 Mock",before.name);assertEquals(8.5,before.rating!!,0.0);assertEquals("观看中",before.status)
                assertEquals(before.id,db.candidateImportDao().movieId(input.identity.sourceId,input.identity.externalId))
                assertTrue(runCatching { add(input.copy(name="不能覆盖",rating=0.0,status=MovieStatus.FINISHED)) }.exceptionOrNull() is CandidateImportException)
                assertEquals(listOf(before),movies.observeAll().first());assertEquals(1,db.watchHistoryDao().getForMovie(before.id).size)
                assertEquals(listOf(7L),db.categoryDao().getCategoryIdsForMovie(before.id))
            }
            Log.i("A4_RESTART",phase+" pid="+Process.myPid())
        } finally { db.close();if(phase=="verify")context.deleteDatabase(name);DatabaseHealthMonitor.resetForTesting() }
    }
}
