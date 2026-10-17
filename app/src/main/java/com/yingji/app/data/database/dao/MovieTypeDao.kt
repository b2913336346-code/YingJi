package com.yingji.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yingji.app.data.entity.MovieType
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieTypeDao {
    @Query(
        """
        SELECT * FROM movie_types
        ORDER BY isSystem DESC, sortOrder ASC, name COLLATE NOCASE ASC
        """,
    )
    fun observeAll(): Flow<List<MovieType>>

    @Query("SELECT * FROM movie_types WHERE name COLLATE NOCASE = :name LIMIT 1")
    suspend fun findByName(name: String): MovieType?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(movieType: MovieType): Long
}
