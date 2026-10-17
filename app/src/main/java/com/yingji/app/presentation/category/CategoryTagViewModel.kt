package com.yingji.app.presentation.category

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.data.repository.SaveNameResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryTagUiState(
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val categoryMovieCounts: Map<Long, Long> = emptyMap(),
    val tagMovieCounts: Map<Long, Long> = emptyMap(),
    val movieCategories: List<MovieCategory> = emptyList(),
    val categoryNamesByMovieId: Map<Long, String> = emptyMap(),
)

data class MovieCollectionUiState(
    val title: String,
    val movies: List<Movie>,
)

data class MovieRelationsUiState(
    val isLoaded: Boolean = false,
    val categoryId: Long? = null,
    val tagIds: Set<Long> = emptySet(),
)

data class CategoryTagActionState(
    val isSaving: Boolean = false,
    val saveSucceeded: Boolean = false,
    val message: String? = null,
)

class CategoryTagViewModel(
    private val repository: CategoryRepository,
) : ViewModel() {
    private val _actionState = MutableStateFlow(CategoryTagActionState())
    val actionState: StateFlow<CategoryTagActionState> = _actionState.asStateFlow()

    val uiState = combine(
        repository.observeCategories(),
        repository.observeTags(),
        repository.observeCategoryMovieCounts(),
        repository.observeTagMovieCounts(),
        repository.observeMovieCategories(),
    ) { categories, tags, categoryCounts, tagCounts, movieCategories ->
        val categoryNamesById = categories.associate { it.id to it.name }
        CategoryTagUiState(
            categories = categories,
            tags = tags,
            categoryMovieCounts = categoryCounts.associate { it.relationId to it.movieCount },
            tagMovieCounts = tagCounts.associate { it.relationId to it.movieCount },
            movieCategories = movieCategories,
            categoryNamesByMovieId = movieCategories.mapNotNull { relation ->
                categoryNamesById[relation.categoryId]?.let { name -> relation.movieId to name }
            }.toMap(),
        )
    }.catch { exception ->
        if (exception is CancellationException) throw exception
        Log.e(TAG, "Failed to load categories or tags", exception)
        emit(CategoryTagUiState())
        publishMessage("分类和标签加载失败")
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CategoryTagUiState(),
    )

    init {
        viewModelScope.launch {
            try {
                repository.ensureSystemCategories()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to ensure system categories", exception)
                publishMessage("分类数据初始化失败")
            }
        }
    }

    fun observeCategoriesForMovie(movieId: Long): Flow<List<Category>> =
        repository.observeCategoriesForMovie(movieId)

    fun observeTagsForMovie(movieId: Long): Flow<List<Tag>> =
        repository.observeTagsForMovie(movieId)

    fun observeCategoryCollection(categoryId: Long): Flow<MovieCollectionUiState> = combine(
        repository.observeCategory(categoryId),
        repository.observeMoviesForCategory(categoryId),
    ) { category, movies ->
        MovieCollectionUiState(
            title = category?.name ?: "分类不存在",
            movies = movies,
        )
    }

    fun observeTagCollection(tagId: Long): Flow<MovieCollectionUiState> = combine(
        repository.observeTag(tagId),
        repository.observeMoviesForTag(tagId),
    ) { tag, movies ->
        MovieCollectionUiState(
            title = tag?.name ?: "标签不存在",
            movies = movies,
        )
    }

    fun observeMovieRelations(movieId: Long): Flow<MovieRelationsUiState> = combine(
        repository.observeCategoriesForMovie(movieId),
        repository.observeTagsForMovie(movieId),
    ) { categories, tags ->
        MovieRelationsUiState(
            isLoaded = true,
            categoryId = categories.singleOrNull()?.id,
            tagIds = tags.mapTo(mutableSetOf()) { it.id },
        )
    }

    fun addCustomCategory(name: String) {
        saveName { repository.addCustomCategory(name) }
    }

    fun renameCategory(category: Category, name: String) {
        saveName { repository.renameCategory(category, name) }
    }

    fun deleteCustomCategory(category: Category) {
        runDatabaseAction("删除分类失败") { repository.deleteCategory(category) }
    }

    fun addTag(name: String) {
        saveName { repository.addTag(name) }
    }

    fun renameTag(tag: Tag, name: String) {
        saveName { repository.renameTag(tag, name) }
    }

    fun deleteTag(tag: Tag) {
        runDatabaseAction("删除标签失败") { repository.deleteTag(tag) }
    }

    fun setMovieCategory(movieId: Long, category: Category, selected: Boolean) {
        if (!selected) return
        runDatabaseAction("更新分类失败") {
            repository.addCategoryToMovie(movieId, category.id)
        }
    }

    fun setMovieTag(movieId: Long, tag: Tag, selected: Boolean) {
        runDatabaseAction("更新标签失败") {
            if (selected) {
                repository.addTagToMovie(movieId, tag.id)
            } else {
                repository.removeTagFromMovie(movieId, tag.id)
            }
        }
    }

    fun consumeActionResult() {
        _actionState.update { it.copy(saveSucceeded = false, message = null) }
    }

    private fun saveName(save: suspend () -> SaveNameResult) {
        if (_actionState.value.isSaving) return
        _actionState.value = CategoryTagActionState(isSaving = true)
        viewModelScope.launch {
            val result = try {
                save()
            } catch (exception: CancellationException) {
                _actionState.update { it.copy(isSaving = false) }
                throw exception
            } catch (exception: Exception) {
                SaveNameResult.Failure(exception)
            }
            _actionState.value = when (result) {
                SaveNameResult.Success -> CategoryTagActionState(saveSucceeded = true)
                SaveNameResult.InvalidName -> CategoryTagActionState(message = "名称不能为空")
                SaveNameResult.AlreadyExists -> CategoryTagActionState(message = "名称已存在")
                SaveNameResult.NotEditable -> CategoryTagActionState(message = "系统分类不能重命名")
                is SaveNameResult.Failure -> {
                    Log.e(TAG, "Failed to save category or tag", result.exception)
                    CategoryTagActionState(message = "保存失败，请稍后重试")
                }
            }
        }
    }

    private fun runDatabaseAction(
        failureMessage: String,
        action: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, failureMessage, exception)
                publishMessage(failureMessage)
            }
        }
    }

    private fun publishMessage(message: String) {
        _actionState.update { it.copy(message = message) }
    }

    companion object {
        private const val TAG = "CategoryTagViewModel"

        fun factory(repository: CategoryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CategoryTagViewModel(repository) as T
            }
    }
}
