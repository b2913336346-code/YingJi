package com.yingji.app.domain.usecase

import android.graphics.Bitmap
import com.yingji.app.data.ai.TmdbPosterPreviewLoader
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.PosterRepository
import com.yingji.app.domain.ai.TmdbPoster
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class RemotePosterSaveException(message: String, cause: Throwable? = null) : IOException(message, cause)
data class RemotePosterSaveResult(val localPath: String, val previousFileCleanupDeferred: Boolean)

/** No UI or automatic trigger. A single application-scoped instance owns explicit saves. */
class SaveRemotePosterUseCase(
    private val movies: MovieRepository,
    private val files: PosterRepository,
    private val source: TmdbPosterPreviewLoader,
) {
    private val activeSave = Mutex()

    suspend operator fun invoke(movieId: Long, poster: TmdbPoster, confirmed: Boolean): RemotePosterSaveResult {
        if (!confirmed) throw RemotePosterSaveException("请先确认使用此海报")
        if (movieId <= 0) throw RemotePosterSaveException("作品不存在")
        if (!activeSave.tryLock()) throw RemotePosterSaveException("海报正在保存，请勿重复操作")
        val requestContext = currentCoroutineContext()
        var bitmap: Bitmap? = null
        var newPath: String? = null
        var commitStarted = false
        var committed = false
        suspend fun discardNew() {
            val path = newPath ?: return
            if (committed) return
            withContext(NonCancellable) {
                // Before commit no other caller has seen this new UUID path. Once a commit was
                // attempted, retain the file if the database cannot prove it unreferenced.
                runCatching {
                    if (!commitStarted) files.deleteManagedFile(path)
                    else movies.removePosterFileIfUnused(path) { files.deleteManagedFile(path) }
                }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val target = movies.remotePosterTarget(movieId)
            bitmap = source.load(poster)
            newPath = files.persistRemote(requireNotNull(bitmap))
            currentCoroutineContext().ensureActive()
            val path = requireNotNull(newPath)
            return withContext(NonCancellable) {
                // Once the short commit starts, finish it before responding to cancellation.
                commitStarted = true
                movies.replaceRemotePoster(target, path) { requestContext.ensureActive() }
                committed = true
                val cleaned = target.posterPath?.let { previous ->
                    runCatching { movies.removePosterFileIfUnused(previous) { files.deleteManagedFile(previous) } }.isSuccess
                } ?: true
                RemotePosterSaveResult(path, previousFileCleanupDeferred = !cleaned)
            }
        } catch (cancelled: CancellationException) {
            discardNew()
            throw cancelled
        } catch (exception: Exception) {
            discardNew()
            throw RemotePosterSaveException("海报保存未完成，请重新打开详情确认", exception)
        } finally {
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            activeSave.unlock()
        }
    }
}
