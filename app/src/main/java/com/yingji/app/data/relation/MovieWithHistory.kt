package com.yingji.app.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.WatchHistory

data class MovieWithHistory(
    @Embedded
    val movie: Movie,
    @Relation(
        parentColumn = "id",
        entityColumn = "movieId",
    )
    val history: List<WatchHistory>,
)
