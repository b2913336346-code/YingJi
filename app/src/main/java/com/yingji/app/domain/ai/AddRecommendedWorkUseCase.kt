package com.yingji.app.domain.ai

import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.isValidMovieRating
import com.yingji.app.domain.usecase.AddMovieUseCase

class CandidateImportException(message: String) : IllegalArgumentException(message)
data class RecommendedWorkInput(
    val identity: CandidateIdentity,
    val confirmed: Boolean,
    val name: String,
    val categoryId: Long,
    val categoryName: String,
    val tagIds: Set<Long> = emptySet(),
    val status: MovieStatus = MovieStatus.WANT_TO_WATCH,
    val currentEpisode: Int = 0,
    val totalEpisode: Int? = null,
    val posterPath: String? = null,
    val rating: Double? = null,
    val allowMock: Boolean = false,
)
interface CandidateImportStore {
    /** Both hooks must run inside the existing add use case's single protected Room transaction. */
    suspend fun checkBeforeInsert(identity: CandidateIdentity, originalTitle: String, savedTitle: String)
    suspend fun recordAfterInsert(identity: CandidateIdentity, movieId: Long)
}
class AddRecommendedWorkUseCase(
    private val catalog: CandidateCatalogRepository,
    private val imports: CandidateImportStore,
    private val addMovie: AddMovieUseCase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(input: RecommendedWorkInput, validateBeforeWrite: () -> Unit = {}): Long {
        if (!input.confirmed) throw CandidateImportException("请先确认加入候选作品")
        if (input.categoryId <= 0 || input.categoryName.isBlank()) throw CandidateImportException("请选择分类")
        if (input.name.isBlank() || input.tagIds.any { it <= 0 } || !isValidMovieRating(input.rating) || input.currentEpisode < 0 ||
            input.totalEpisode?.let { it <= 0 || it < input.currentEpisode } == true) throw CandidateImportException("作品表单数据无效")
        val path = input.posterPath
        if (path != null && !(path.startsWith("/") || path.startsWith("content://") || path.startsWith("file://")))
            throw CandidateImportException("海报仅支持本地选择")
        val data = catalog.load()
        val candidate = data.candidates.filter { it.identity == input.identity }.distinct().singleOrNull()
            ?: throw CandidateImportException("候选不存在或身份冲突，请重新获取推荐")
        CandidateValidator.error(candidate, data, clock(), input.allowMock, importing = true)?.let { throw CandidateImportException(it) }
        return addMovie(input.name, input.categoryName, input.status, input.currentEpisode, input.totalEpisode,
            input.posterPath, input.categoryId, input.tagIds, input.rating,
            beforeInsert = { validateBeforeWrite(); imports.checkBeforeInsert(input.identity, candidate.title!!, input.name) },
            afterInsert = { id -> imports.recordAfterInsert(input.identity, id); validateBeforeWrite() })
    }
}
