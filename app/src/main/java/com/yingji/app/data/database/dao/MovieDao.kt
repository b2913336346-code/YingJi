package com.yingji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.relation.MovieWithHistory
import com.yingji.app.data.relation.MovieWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies ORDER BY createTime DESC")
    fun observeAll(): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE id = :movieId LIMIT 1")
    fun observeById(movieId: Long): Flow<Movie?>

    @Query("SELECT * FROM movies WHERE id = :movieId LIMIT 1")
    suspend fun getById(movieId: Long): Movie?

    @Query("UPDATE movies SET posterPath = :newPath WHERE id = :movieId AND posterPath IS :expectedPath")
    suspend fun replacePosterPath(movieId: Long, expectedPath: String?, newPath: String): Int

    @Query("SELECT name FROM movies")
    suspend fun readNames(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM movies WHERE posterPath = :path)")
    suspend fun isPosterReferenced(path: String): Boolean

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :movieId LIMIT 1")
    fun observeWithHistory(movieId: Long): Flow<MovieWithHistory?>

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :movieId LIMIT 1")
    fun observeWithTags(movieId: Long): Flow<MovieWithTags?>

    @Query("""
        SELECT m.id AS movieId, 'category:' || c.id AS featureId, c.name AS label, m.rating AS rating
        FROM movies m JOIN movie_categories mc ON m.id=mc.movieId JOIN categories c ON c.id=mc.categoryId
        WHERE m.rating IS NOT NULL
        UNION ALL
        SELECT m.id AS movieId, 'tag:' || t.id AS featureId, t.name AS label, m.rating AS rating
        FROM movies m JOIN movie_tags mt ON m.id=mt.movieId JOIN tags t ON t.id=mt.tagId
        WHERE m.rating IS NOT NULL
        ORDER BY movieId, featureId
    """)
    suspend fun readRatingFeatures(): List<com.yingji.app.data.database.model.RatingFeatureRow>

    @Query("""
        SELECT m.id AS movieId, 'category:' || c.id AS featureId, c.name AS label,
               COUNT(h.id) AS completedWatches, MAX(COUNT(h.id)-1, 0) AS repeatWatches
        FROM movies m JOIN watch_history h ON m.id=h.movieId
        JOIN movie_categories mc ON m.id=mc.movieId JOIN categories c ON c.id=mc.categoryId
        WHERE h.endTime IS NOT NULL AND h.endTime>=h.startTime
        GROUP BY m.id, c.id
        UNION ALL
        SELECT m.id AS movieId, 'tag:' || t.id AS featureId, t.name AS label,
               COUNT(h.id) AS completedWatches, MAX(COUNT(h.id)-1, 0) AS repeatWatches
        FROM movies m JOIN watch_history h ON m.id=h.movieId
        JOIN movie_tags mt ON m.id=mt.movieId JOIN tags t ON t.id=mt.tagId
        WHERE h.endTime IS NOT NULL AND h.endTime>=h.startTime
        GROUP BY m.id, t.id
        ORDER BY movieId, featureId
    """)
    suspend fun readBehaviorFeatures(): List<com.yingji.app.data.database.model.BehaviorFeatureRow>

    @Query("""SELECT (SELECT COUNT(*) FROM movies) + (SELECT COUNT(*) FROM categories)
        + (SELECT COUNT(*) FROM tags) + (SELECT COUNT(*) FROM movie_categories)
        + (SELECT COUNT(*) FROM movie_tags)""")
    fun observeRatingProfileInputs(): Flow<Long>

    @Query("""SELECT (SELECT COUNT(*) FROM movies) + (SELECT COUNT(*) FROM watch_history)
        + (SELECT COUNT(*) FROM categories) + (SELECT COUNT(*) FROM tags)
        + (SELECT COUNT(*) FROM movie_categories) + (SELECT COUNT(*) FROM movie_tags)""")
    fun observeBehaviorProfileInputs(): Flow<Long>

    @Insert
    suspend fun insert(movie: Movie): Long

    @Update
    suspend fun update(movie: Movie)

    @Delete
    suspend fun delete(movie: Movie)
}
