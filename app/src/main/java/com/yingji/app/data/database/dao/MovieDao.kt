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

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :movieId LIMIT 1")
    fun observeWithHistory(movieId: Long): Flow<MovieWithHistory?>

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :movieId LIMIT 1")
    fun observeWithTags(movieId: Long): Flow<MovieWithTags?>

    @Insert
    suspend fun insert(movie: Movie): Long

    @Update
    suspend fun update(movie: Movie)

    @Delete
    suspend fun delete(movie: Movie)
}
