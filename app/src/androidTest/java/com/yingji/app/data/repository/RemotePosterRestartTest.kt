package com.yingji.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.AiCandidateImport
import com.yingji.app.data.ai.TmdbPosterPreviewLoader
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.*
import com.yingji.app.domain.ai.TmdbPoster
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.SaveRemotePosterUseCase
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Host runs prepare, force-stops ONLY its own package, then runs verify in a new process. */
class RemotePosterRestartTest {
    @Test fun localPosterAndRelationsSurviveARealProcessRestartWithoutAnAutomaticDownload() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val fixture = requireNotNull(args.getString("remotePosterFixture"))
        require(Regex("[a-f0-9]{32}").matches(fixture))
        val phase = args.getString("remotePosterPhase")
        require(phase in listOf("prepare", "verify"))
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "remote-poster-restart-" + fixture + ".db"
        val directory = File(base.filesDir, "remote-poster-restart-" + fixture)
        if (phase == "prepare") {
            check(!base.getDatabasePath(name).exists()); check(!directory.exists()); check(directory.mkdirs())
        }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
        DatabaseHealthMonitor.resetForTesting()
        val db = Room.databaseBuilder(base, AppDatabase::class.java, name).build()
        val movies = MovieRepository(db)
        val files = PosterRepository(context)
        val calls = AtomicInteger()
        val save = SaveRemotePosterUseCase(movies, files, TmdbPosterPreviewLoader {
            calls.incrementAndGet()
            check(phase == "prepare") { "Verification must not download" }
            Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        })
        try {
            if (phase == "prepare") {
                db.categoryDao().insert(Category(7, "保留分类", false)); db.tagDao().insert(Tag(7, "保留标签"))
                val id = movies.insertWithRelations(Movie(name = "重启海报夹具", type = "保留分类", status = MovieStatus.WATCHING.value,
                    currentEpisode = 2, totalEpisode = 12, createTime = 100, rating = 8.5), 7, setOf(7))
                db.candidateImportDao().insert(AiCandidateImport("tmdb", "movie:7", id))
                val result = save(id, TmdbPoster("/fictionalRestartPoster.png"), true)
                assertTrue(File(result.localPath).isFile); assertEquals(1, calls.get())
            } else {
                val movie = movies.observeAll().first().single()
                assertEquals("重启海报夹具", movie.name); assertEquals(8.5, movie.rating)
                assertEquals(100L, movie.createTime); assertEquals(2, movie.currentEpisode); assertEquals(12, movie.totalEpisode)
                assertEquals(MovieStatus.WATCHING.value, movie.status)
                assertEquals(movie.id, db.candidateImportDao().movieId("tmdb", "movie:7"))
                assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(movie.id)); assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(movie.id))
                assertEquals(1, db.watchHistoryDao().getForMovie(movie.id).count { it.endTime == null })
                val path = requireNotNull(movie.posterPath)
                assertEquals(File(directory, "posters").canonicalFile, File(path).parentFile?.canonicalFile)
                val bitmap = requireNotNull(BitmapFactory.decodeFile(path))
                assertEquals(Color.GRAY, bitmap.getPixel(0, 0)); bitmap.recycle()
                assertEquals(0, calls.get())
                db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            println("REMOTE_POSTER_RESTART phase=" + phase + " pid=" + Process.myPid())
        } finally { db.close(); DatabaseHealthMonitor.resetForTesting() }
        if (phase == "verify") { assertTrue(base.deleteDatabase(name)); assertTrue(directory.deleteRecursively()) }
    }
}
