package com.yingji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yingji.app.data.entity.WatchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY startTime DESC, id DESC")
    fun observeAll(): Flow<List<WatchHistory>>

    @Query("SELECT * FROM watch_history WHERE movieId = :movieId ORDER BY startTime DESC, id DESC")
    fun observeByMovieId(movieId: Long): Flow<List<WatchHistory>>

    @Query("SELECT * FROM watch_history WHERE id = :historyId LIMIT 1")
    suspend fun getById(historyId: Long): WatchHistory?

    @Query("SELECT * FROM watch_history WHERE movieId = :movieId ORDER BY startTime DESC, id DESC")
    suspend fun getForMovie(movieId: Long): List<WatchHistory>

    @Insert
    suspend fun insert(history: WatchHistory): Long

    @Update
    suspend fun update(history: WatchHistory)

    @Delete
    suspend fun delete(history: WatchHistory)
}
