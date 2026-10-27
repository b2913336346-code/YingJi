package com.yingji.app.data.ai

import androidx.room.*
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*

@Entity(tableName = "ai_candidate_imports", primaryKeys = ["sourceId", "externalId"],
    foreignKeys = [ForeignKey(entity = Movie::class, parentColumns = ["id"], childColumns = ["movieId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("movieId")])
data class AiCandidateImport(val sourceId: String, val externalId: String, val movieId: Long)
@Dao
interface CandidateImportDao {
    @Query("SELECT movieId FROM ai_candidate_imports WHERE sourceId=:sourceId AND externalId=:externalId")
    suspend fun movieId(sourceId: String, externalId: String): Long?
    @Query("SELECT * FROM ai_candidate_imports WHERE movieId=:movieId AND sourceId='tmdb'")
    suspend fun tmdbSourcesForMovie(movieId: Long): List<AiCandidateImport>
    @Insert suspend fun insert(value: AiCandidateImport)
}

class CandidateImportRepository(private val database: AppDatabase, private val movies: MovieRepository) : CandidateImportStore {
    override suspend fun checkBeforeInsert(identity: CandidateIdentity, originalTitle: String, savedTitle: String) {
        check(database.inTransaction()) { "候选校验必须在新增事务内执行" }
        if (database.candidateImportDao().movieId(identity.sourceId, identity.externalId) != null)
            throw CandidateImportException("此候选已加入影迹，请查看已有作品")
        val names = setOf(originalTitle, savedTitle).map(::normalizedCandidateText).toSet()
        if (movies.readWorkNames().any { normalizedCandidateText(it) in names })
            throw CandidateImportException("作品库已有同名作品，无法确认身份，请先检查已有作品")
    }
    override suspend fun recordAfterInsert(identity: CandidateIdentity, movieId: Long) {
        check(database.inTransaction()) { "候选身份必须与作品在同一事务内保存" }
        database.candidateImportDao().insert(AiCandidateImport(identity.sourceId, identity.externalId, movieId))
    }
}
