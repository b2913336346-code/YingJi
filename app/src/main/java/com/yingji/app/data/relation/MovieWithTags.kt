package com.yingji.app.data.relation

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.Tag

data class MovieWithTags(
    @Embedded
    val movie: Movie,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = MovieTag::class,
            parentColumn = "movieId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<Tag>,
)
