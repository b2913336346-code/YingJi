package com.yingji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.database.model.RelationMovieCount
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isSystem DESC, name ASC")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :categoryId LIMIT 1")
    fun observeById(categoryId: Long): Flow<Category?>

    @Query(
        """
        SELECT categoryId AS relationId, COUNT(DISTINCT movieId) AS movieCount
        FROM movie_categories
        GROUP BY categoryId
        """,
    )
    fun observeMovieCounts(): Flow<List<RelationMovieCount>>

    @Query(
        """
        SELECT DISTINCT movies.* FROM movies
        INNER JOIN movie_categories ON movies.id = movie_categories.movieId
        WHERE movie_categories.categoryId = :categoryId
        ORDER BY movies.createTime DESC
        """,
    )
    fun observeMovies(categoryId: Long): Flow<List<Movie>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Query("SELECT EXISTS(SELECT 1 FROM categories WHERE name = :name AND id != :excludedId)")
    suspend fun nameExistsExcludingId(name: String, excludedId: Long): Boolean

    @Update
    suspend fun update(category: Category): Int

    @Delete
    suspend fun delete(category: Category)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMovieCategory(link: MovieCategory)

    @Query("SELECT categoryId FROM movie_categories WHERE movieId = :movieId")
    suspend fun getCategoryIdsForMovie(movieId: Long): List<Long>

    @Query("DELETE FROM movie_categories WHERE movieId = :movieId")
    suspend fun removeAllCategoriesFromMovie(movieId: Long)

    @Transaction
    suspend fun replaceMovieCategory(movieId: Long, categoryId: Long) {
        removeAllCategoriesFromMovie(movieId)
        addMovieCategory(MovieCategory(movieId = movieId, categoryId = categoryId))
    }

    @Query("DELETE FROM movie_categories WHERE movieId = :movieId AND categoryId = :categoryId")
    suspend fun removeMovieCategory(movieId: Long, categoryId: Long)

    @Transaction
    @Query(
        """
        SELECT categories.* FROM categories
        INNER JOIN movie_categories ON categories.id = movie_categories.categoryId
        WHERE movie_categories.movieId = :movieId
        ORDER BY categories.isSystem DESC, categories.name ASC
        """,
    )
    fun observeForMovie(movieId: Long): Flow<List<Category>>

    @Query("SELECT * FROM movie_categories")
    fun observeMovieCategories(): Flow<List<MovieCategory>>
}
