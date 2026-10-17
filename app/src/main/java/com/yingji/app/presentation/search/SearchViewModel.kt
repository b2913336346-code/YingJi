package com.yingji.app.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.ThemeRepository
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.WatchTimeField
import com.yingji.app.domain.usecase.FilterAndSortMoviesUseCase
import com.yingji.app.domain.usecase.MovieSearchCriteria
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SearchUiState(
    val query: String = "",
    val movies: List<Movie> = emptyList(),
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val selectedCategoryId: Long? = null,
    val selectedTagId: Long? = null,
    val selectedStatus: MovieStatus? = null,
    val timeField: WatchTimeField? = null,
    val fromTime: Long? = null,
    val toTime: Long? = null,
    val sortOption: MovieSortOption = MovieSortOption.CREATED_DESC,
    val categoryNamesByMovieId: Map<Long, String> = emptyMap(),
)

private data class SearchData(
    val movies: List<Movie>,
    val categories: List<Category>,
    val tags: List<Tag>,
    val movieCategories: List<MovieCategory>,
    val movieTags: List<MovieTag>,
    val histories: List<WatchHistory>,
)

private data class SearchFilters(
    val query: String = "",
    val categoryId: Long? = null,
    val tagId: Long? = null,
    val status: MovieStatus? = null,
    val timeField: WatchTimeField? = null,
    val fromTime: Long? = null,
    val toTime: Long? = null,
    val sortOverride: MovieSortOption? = null,
)

class SearchViewModel(
    movieRepository: MovieRepository,
    categoryRepository: CategoryRepository,
    historyRepository: HistoryRepository,
    settingsRepository: ThemeRepository,
) : ViewModel() {
    private val filters = MutableStateFlow(SearchFilters())
    private val filterAndSortMovies = FilterAndSortMoviesUseCase()

    private val classificationData = combine(
        movieRepository.observeAll(),
        categoryRepository.observeCategories(),
        categoryRepository.observeTags(),
        categoryRepository.observeMovieCategories(),
        categoryRepository.observeMovieTags(),
    ) { movies, categories, tags, movieCategories, movieTags ->
        SearchData(movies, categories, tags, movieCategories, movieTags, emptyList())
    }

    private val data = combine(
        classificationData,
        historyRepository.observeAll(),
    ) { data, histories ->
        data.copy(histories = histories)
    }

    val uiState = combine(
        data,
        filters,
        settingsRepository.movieSortOption,
    ) { data, filters, defaultSortOption ->
        val sortOption = filters.sortOverride ?: defaultSortOption
        val categoryNamesById = data.categories.associate { it.id to it.name }
        SearchUiState(
            query = filters.query,
            movies = filterAndSortMovies(
                movies = data.movies,
                histories = data.histories,
                movieCategories = data.movieCategories,
                movieTags = data.movieTags,
                criteria = MovieSearchCriteria(
                    query = filters.query,
                    categoryId = filters.categoryId,
                    tagId = filters.tagId,
                    status = filters.status,
                    timeField = filters.timeField,
                    fromTime = filters.fromTime,
                    toTime = filters.toTime,
                    sortOption = sortOption,
                ),
            ),
            categories = data.categories,
            tags = data.tags,
            selectedCategoryId = filters.categoryId,
            selectedTagId = filters.tagId,
            selectedStatus = filters.status,
            timeField = filters.timeField,
            fromTime = filters.fromTime,
            toTime = filters.toTime,
            sortOption = sortOption,
            categoryNamesByMovieId = data.movieCategories.mapNotNull { relation ->
                categoryNamesById[relation.categoryId]?.let { name -> relation.movieId to name }
            }.toMap(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SearchUiState(),
    )

    fun updateQuery(value: String) {
        filters.value = filters.value.copy(query = value)
    }

    fun selectCategory(categoryId: Long?) {
        filters.value = filters.value.copy(categoryId = categoryId)
    }

    fun selectTag(tagId: Long?) {
        filters.value = filters.value.copy(tagId = tagId)
    }

    fun selectStatus(status: MovieStatus?) {
        filters.value = filters.value.copy(status = status)
    }

    fun selectTimeField(field: WatchTimeField?) {
        filters.value = if (field == null) {
            filters.value.copy(timeField = null, fromTime = null, toTime = null)
        } else {
            filters.value.copy(timeField = field)
        }
    }

    fun setFromTime(value: Long?) {
        filters.value = filters.value.copy(
            fromTime = value,
            toTime = filters.value.toTime?.takeIf { value == null || it >= value },
        )
    }

    fun setToTime(value: Long?) {
        filters.value = filters.value.copy(
            fromTime = filters.value.fromTime?.takeIf { value == null || it <= value },
            toTime = value,
        )
    }

    fun selectSortOption(option: MovieSortOption) {
        filters.value = filters.value.copy(sortOverride = option)
    }

    companion object {
        fun factory(
            movieRepository: MovieRepository,
            categoryRepository: CategoryRepository,
            historyRepository: HistoryRepository,
            settingsRepository: ThemeRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(
                    movieRepository,
                    categoryRepository,
                    historyRepository,
                    settingsRepository,
                ) as T
        }
    }
}
