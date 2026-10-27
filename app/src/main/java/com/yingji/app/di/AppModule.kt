package com.yingji.app.di

import android.content.Context
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.MovieTypeRepository
import com.yingji.app.data.repository.PosterRepository
import com.yingji.app.data.repository.ThemeRepository

object AppModule {
    fun provideProfileRepository(database: AppDatabase, movies: MovieRepository) =
        com.yingji.app.data.ai.ProfileRepository(database, com.yingji.app.data.ai.ObserveLibraryRepository(movies))

    fun provideDatabase(context: Context): AppDatabase =
        DatabaseModule.provideDatabase(context)

    fun provideMovieRepository(database: AppDatabase): MovieRepository =
        MovieRepository(database)

    fun provideMovieTypeRepository(database: AppDatabase): MovieTypeRepository =
        MovieTypeRepository(database.movieTypeDao())

    fun provideHistoryRepository(database: AppDatabase): HistoryRepository =
        HistoryRepository(database)

    fun provideCategoryRepository(database: AppDatabase): CategoryRepository =
        CategoryRepository(
            categoryDao = database.categoryDao(),
            tagDao = database.tagDao(),
        )

    fun providePosterRepository(context: Context): PosterRepository =
        PosterRepository(context.applicationContext)

    fun provideSaveRemotePosterUseCase(movies: MovieRepository, posters: PosterRepository) =
        com.yingji.app.domain.usecase.SaveRemotePosterUseCase(movies, posters,
            com.yingji.app.data.ai.MemoryOnlyTmdbPosterLoader())

    fun provideThemeRepository(context: Context): ThemeRepository =
        ThemeRepository(context.applicationContext)
}
