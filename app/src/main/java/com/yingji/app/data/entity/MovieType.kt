package com.yingji.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "movie_types",
    indices = [Index(value = ["name"], unique = true)],
)
data class MovieType(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isSystem: Boolean,
    val sortOrder: Int,
)
