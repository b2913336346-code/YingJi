package com.yingji.app.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_history",
    foreignKeys = [
        ForeignKey(
            entity = Movie::class,
            parentColumns = ["id"],
            childColumns = ["movieId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("movieId")],
)
data class WatchHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val movieId: Long,
    val startTime: Long,
    val endTime: Long? = null,
    val duration: Long = 0,
    val watchNumber: Int,
)
