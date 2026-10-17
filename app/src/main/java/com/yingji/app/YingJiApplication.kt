package com.yingji.app

import android.app.Application
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.MovieTypeRepository
import com.yingji.app.data.repository.PosterRepository
import com.yingji.app.data.repository.ThemeRepository
import com.yingji.app.di.AppModule

class YingJiApplication : Application() {
    private val database by lazy {
        DatabaseModule.provideDatabase(this)
    }

    val movieRepository: MovieRepository by lazy {
        AppModule.provideMovieRepository(database)
    }

    val movieTypeRepository: MovieTypeRepository by lazy {
        AppModule.provideMovieTypeRepository(database)
    }

    val historyRepository: HistoryRepository by lazy {
        AppModule.provideHistoryRepository(database)
    }

    val categoryRepository: CategoryRepository by lazy {
        AppModule.provideCategoryRepository(database)
    }

    val posterRepository: PosterRepository by lazy {
        AppModule.providePosterRepository(this)
    }

    val themeRepository: ThemeRepository by lazy {
        AppModule.provideThemeRepository(this)
    }
}
