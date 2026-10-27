package com.yingji.app.data.database

import com.yingji.app.data.ai.*
import androidx.room.Database
import androidx.room.RoomDatabase
import com.yingji.app.data.database.dao.CategoryDao
import com.yingji.app.data.database.dao.MovieDao
import com.yingji.app.data.database.dao.MovieTypeDao
import com.yingji.app.data.database.dao.TagDao
import com.yingji.app.data.database.dao.WatchHistoryDao
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.MovieType
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.entity.WatchHistory

@Database(
    entities = [
        Movie::class,
        WatchHistory::class,
        Category::class,
        Tag::class,
        MovieCategory::class,
        MovieTag::class,
        MovieType::class,
        AiProfileState::class,
        AiExplicitPreference::class,
        AiProfileCorrection::class,
        AiProfileFeedback::class,
        AiInferredPreference::class,
        AiCandidateImport::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun candidateImportDao(): CandidateImportDao
    abstract fun aiProfileDao(): AiProfileDao
    abstract fun movieDao(): MovieDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun movieTypeDao(): MovieTypeDao
}
