package com.yingji.app.data.repository

import com.yingji.app.data.database.dao.MovieTypeDao
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.MovieType
import kotlinx.coroutines.flow.Flow

class MovieTypeRepository(
    private val movieTypeDao: MovieTypeDao,
) {
    fun observeAll(): Flow<List<MovieType>> =
        DatabaseHealthMonitor.protectRead(movieTypeDao.observeAll())

    suspend fun ensureSystemTypes() {
        DatabaseHealthMonitor.protectWrite {
            SYSTEM_TYPE_NAMES.forEachIndexed { index, name ->
                movieTypeDao.insert(
                    MovieType(
                        name = name,
                        isSystem = true,
                        sortOrder = index,
                    ),
                )
            }
        }
    }

    suspend fun saveType(value: String): String = DatabaseHealthMonitor.protectWrite {
        val normalizedName = value.trim().ifEmpty { DEFAULT_TYPE }
        movieTypeDao.findByName(normalizedName)?.let { return@protectWrite it.name }
        movieTypeDao.insert(
            MovieType(
                name = normalizedName,
                isSystem = normalizedName in SYSTEM_TYPE_NAMES,
                sortOrder = SYSTEM_TYPE_NAMES.indexOf(normalizedName).takeIf { it >= 0 }
                    ?: CUSTOM_TYPE_SORT_ORDER,
            ),
        )
        movieTypeDao.findByName(normalizedName)?.name ?: normalizedName
    }

    companion object {
        const val DEFAULT_TYPE = "电影"
        const val CUSTOM_TYPE_SORT_ORDER = 1_000
        val SYSTEM_TYPE_NAMES = listOf(
            "动漫",
            "电影",
            "电视剧",
            "综艺",
            "纪录片",
            "短剧",
            "其他",
        )
    }
}
