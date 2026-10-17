package com.yingji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.database.model.RelationMovieCount
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE id = :tagId LIMIT 1")
    fun observeById(tagId: Long): Flow<Tag?>

    @Query(
        """
        SELECT tagId AS relationId, COUNT(DISTINCT movieId) AS movieCount
        FROM movie_tags
        GROUP BY tagId
        """,
    )
    fun observeMovieCounts(): Flow<List<RelationMovieCount>>

    @Query(
        """
        SELECT DISTINCT movies.* FROM movies
        INNER JOIN movie_tags ON movies.id = movie_tags.movieId
        WHERE movie_tags.tagId = :tagId
        ORDER BY movies.createTime DESC
        """,
    )
    fun observeMovies(tagId: Long): Flow<List<Movie>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Tag): Long

    @Query("SELECT EXISTS(SELECT 1 FROM tags WHERE name = :name AND id != :excludedId)")
    suspend fun nameExistsExcludingId(name: String, excludedId: Long): Boolean

    @Update
    suspend fun update(tag: Tag): Int

    @Delete
    suspend fun delete(tag: Tag)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMovieTag(link: MovieTag)

    @Query("SELECT tagId FROM movie_tags WHERE movieId = :movieId")
    suspend fun getTagIdsForMovie(movieId: Long): List<Long>

    @Query("DELETE FROM movie_tags WHERE movieId = :movieId AND tagId = :tagId")
    suspend fun removeMovieTag(movieId: Long, tagId: Long)

    @Transaction
    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN movie_tags ON tags.id = movie_tags.tagId
        WHERE movie_tags.movieId = :movieId
        ORDER BY tags.name ASC
        """,
    )
    fun observeForMovie(movieId: Long): Flow<List<Tag>>

    @Query("SELECT * FROM movie_tags")
    fun observeMovieTags(): Flow<List<MovieTag>>
}
