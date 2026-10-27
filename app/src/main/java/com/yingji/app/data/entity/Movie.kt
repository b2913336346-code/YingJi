package com.yingji.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "movies")
data class Movie(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val posterPath: String? = null,
    val type: String,
    val status: String,
    val currentEpisode: Int = 0,
    val totalEpisode: Int? = null,
    val createTime: Long,
    val rating: Double? = null,
)
