package com.yingji.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yingji.app.YingJiApplication
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.database.DatabaseHealthState
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.presentation.category.CategoryTagViewModel
import com.yingji.app.presentation.category.MovieCollectionUiState
import com.yingji.app.presentation.category.MovieRelationsUiState
import com.yingji.app.presentation.history.HistoryScreen
import com.yingji.app.presentation.history.HistoryViewModel
import com.yingji.app.presentation.movie.MovieDetailScreen
import com.yingji.app.presentation.movie.MovieFormScreen
import com.yingji.app.presentation.movie.MovieViewModel
import com.yingji.app.presentation.movie.MovieListScreen
import com.yingji.app.presentation.movie.FinishedScreen
import com.yingji.app.presentation.movie.WantToWatchScreen
import com.yingji.app.presentation.movie.WatchingScreen
import com.yingji.app.presentation.settings.SettingsScreen
import com.yingji.app.presentation.settings.SettingsViewModel
import com.yingji.app.presentation.search.SearchScreen
import com.yingji.app.presentation.search.SearchViewModel
import com.yingji.app.presentation.statistics.StatisticsScreen
import com.yingji.app.presentation.statistics.StatisticsViewModel
import com.yingji.app.ui.common.MinimalEmptyState
import com.yingji.app.ui.common.MinimalMotionDuration
import com.yingji.app.ui.common.BottomNavigationBarShape

@Composable
fun YingJiNavGraph() {
    val navController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val application = LocalContext.current.applicationContext as YingJiApplication
    val movieViewModel: MovieViewModel = viewModel(
        factory = MovieViewModel.factory(
            movieRepository = application.movieRepository,
            posterRepository = application.posterRepository,
        ),
    )
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(application.historyRepository),
    )
    val categoryTagViewModel: CategoryTagViewModel = viewModel(
        factory = CategoryTagViewModel.factory(application.categoryRepository),
    )
    val searchViewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.factory(
            movieRepository = application.movieRepository,
            categoryRepository = application.categoryRepository,
            historyRepository = application.historyRepository,
            settingsRepository = application.themeRepository,
        ),
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(application.themeRepository),
    )
    val statisticsViewModel: StatisticsViewModel = viewModel(
        factory = StatisticsViewModel.factory(
            movieRepository = application.movieRepository,
            historyRepository = application.historyRepository,
        ),
    )
    val movieUiState by movieViewModel.uiState.collectAsStateWithLifecycle()
    val movieFormActionState by movieViewModel.formActionState.collectAsStateWithLifecycle()
    val movieActionState by movieViewModel.actionState.collectAsStateWithLifecycle()
    val historyActionState by historyViewModel.actionState.collectAsStateWithLifecycle()
    val categoryTagUiState by categoryTagViewModel.uiState.collectAsStateWithLifecycle()
    val categoryTagActionState by categoryTagViewModel.actionState.collectAsStateWithLifecycle()
    val searchUiState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val movieDisplayMode by settingsViewModel.movieDisplayMode.collectAsStateWithLifecycle()
    val movieSortOption by settingsViewModel.movieSortOption.collectAsStateWithLifecycle()
    val statisticsUiState by statisticsViewModel.uiState.collectAsStateWithLifecycle()
    val databaseHealthState by DatabaseHealthMonitor.state.collectAsStateWithLifecycle()
    val databaseSnackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(databaseHealthState) {
        val unavailable = databaseHealthState as? DatabaseHealthState.Unavailable
        if (unavailable != null) {
            databaseSnackbarHostState.showSnackbar(
                message = unavailable.message,
                duration = SnackbarDuration.Indefinite,
            )
        }
    }
    val bottomRoutes = Screen.bottomNavigationItems.map { it.route }.toSet()
    val showBottomNavigation = currentDestination?.route in bottomRoutes
    val openMovie: (Long) -> Unit = { movieId ->
        navController.navigate(Screen.MovieDetail.createRoute(movieId))
    }
    val openAddMovie: () -> Unit = {
        navController.navigate(Screen.AddMovie.route)
    }
    val openCategory: (Long) -> Unit = { categoryId ->
        navController.navigate(Screen.CategoryMovies.createRoute(categoryId)) {
            launchSingleTop = true
        }
    }
    val openTag: (Long) -> Unit = { tagId ->
        navController.navigate(Screen.TagMovies.createRoute(tagId)) {
            launchSingleTop = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(databaseSnackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomNavigation,
                enter = fadeIn(tween(MinimalMotionDuration)),
                exit = fadeOut(tween(MinimalMotionDuration)),
            ) {
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BottomNavigationBarShape),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                ) {
                    Screen.bottomNavigationItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Home.route) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title,
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) {
        innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(MinimalMotionDuration)) },
            exitTransition = { fadeOut(tween(MinimalMotionDuration)) },
            popEnterTransition = { fadeIn(tween(MinimalMotionDuration)) },
            popExitTransition = { fadeOut(tween(MinimalMotionDuration)) },
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    movies = movieUiState.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onAddMovieClick = openAddMovie,
                    onSearchClick = { navController.navigate(Screen.Search.route) },
                    defaultDisplayMode = movieDisplayMode,
                )
            }
            composable(Screen.WantToWatch.route) {
                WantToWatchScreen(
                    movies = movieUiState.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onAddMovieClick = openAddMovie,
                    defaultDisplayMode = movieDisplayMode,
                )
            }
            composable(Screen.Watching.route) {
                WatchingScreen(
                    movies = movieUiState.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onAddMovieClick = openAddMovie,
                    defaultDisplayMode = movieDisplayMode,
                )
            }
            composable(Screen.Finished.route) {
                FinishedScreen(
                    movies = movieUiState.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onAddMovieClick = openAddMovie,
                    defaultDisplayMode = movieDisplayMode,
                )
            }
            composable(Screen.Mine.route) {
                SettingsScreen(
                    categories = categoryTagUiState.categories,
                    tags = categoryTagUiState.tags,
                    themeMode = themeMode,
                    onThemeModeChange = settingsViewModel::setThemeMode,
                    displayMode = movieDisplayMode,
                    onDisplayModeChange = settingsViewModel::setMovieDisplayMode,
                    sortOption = movieSortOption,
                    onSortOptionChange = settingsViewModel::setMovieSortOption,
                    onStatisticsClick = { navController.navigate(Screen.Statistics.route) },
                    onAddCategory = categoryTagViewModel::addCustomCategory,
                    onRenameCategory = categoryTagViewModel::renameCategory,
                    onDeleteCategory = categoryTagViewModel::deleteCustomCategory,
                    onAddTag = categoryTagViewModel::addTag,
                    onRenameTag = categoryTagViewModel::renameTag,
                    onDeleteTag = categoryTagViewModel::deleteTag,
                    categoryMovieCounts = categoryTagUiState.categoryMovieCounts,
                    tagMovieCounts = categoryTagUiState.tagMovieCounts,
                    onCategoryClick = openCategory,
                    onTagClick = openTag,
                    actionState = categoryTagActionState,
                    onActionResultConsumed = categoryTagViewModel::consumeActionResult,
                )
            }
            composable(Screen.Statistics.route) {
                StatisticsScreen(
                    state = statisticsUiState,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.Search.route) {
                SearchScreen(
                    state = searchUiState,
                    onBack = { navController.popBackStack() },
                    onQueryChange = searchViewModel::updateQuery,
                    onCategorySelected = searchViewModel::selectCategory,
                    onTagSelected = searchViewModel::selectTag,
                    onStatusSelected = searchViewModel::selectStatus,
                    onTimeFieldSelected = searchViewModel::selectTimeField,
                    onFromTimeSelected = searchViewModel::setFromTime,
                    onToTimeSelected = searchViewModel::setToTime,
                    onSortSelected = searchViewModel::selectSortOption,
                    onMovieClick = openMovie,
                    defaultDisplayMode = movieDisplayMode,
                )
            }
            composable(
                route = Screen.CategoryMovies.route,
                arguments = listOf(navArgument("categoryId") { type = NavType.LongType }),
            ) { entry ->
                val categoryId = entry.arguments?.getLong("categoryId") ?: -1L
                val collectionFlow = remember(categoryId) {
                    categoryTagViewModel.observeCategoryCollection(categoryId)
                }
                val collection by collectionFlow.collectAsStateWithLifecycle(
                    initialValue = MovieCollectionUiState(title = "分类", movies = emptyList()),
                )
                MovieListScreen(
                    title = collection.title,
                    movies = collection.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onBack = { navController.popBackStack() },
                    emptyMessage = "暂无作品",
                    defaultDisplayMode = movieDisplayMode,
                )
            }
            composable(
                route = Screen.TagMovies.route,
                arguments = listOf(navArgument("tagId") { type = NavType.LongType }),
            ) { entry ->
                val tagId = entry.arguments?.getLong("tagId") ?: -1L
                val collectionFlow = remember(tagId) {
                    categoryTagViewModel.observeTagCollection(tagId)
                }
                val collection by collectionFlow.collectAsStateWithLifecycle(
                    initialValue = MovieCollectionUiState(title = "标签", movies = emptyList()),
                )
                MovieListScreen(
                    title = collection.title,
                    movies = collection.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onBack = { navController.popBackStack() },
                    emptyMessage = "暂无作品",
                    defaultDisplayMode = movieDisplayMode,
                )
            }
            composable(Screen.AddMovie.route) {
                MovieFormScreen(
                    title = Screen.AddMovie.title,
                    categories = categoryTagUiState.categories,
                    tags = categoryTagUiState.tags,
                    isSaving = movieFormActionState.isSaving,
                    saveMessage = movieFormActionState.message,
                    onSaveMessageConsumed = movieViewModel::consumeFormMessage,
                    onBack = { navController.popBackStack() },
                    onSave = { data ->
                        movieViewModel.addMovie(data) {
                            navController.popBackStack()
                        }
                    },
                )
            }
            composable(
                route = Screen.MovieDetail.route,
                arguments = listOf(navArgument("movieId") { type = NavType.LongType }),
            ) { entry ->
                val movieId = entry.arguments?.getLong("movieId")
                val movie = movieUiState.movies.firstOrNull { it.id == movieId }
                if (movie != null) {
                    val selectedCategoriesFlow = remember(movie.id) {
                        categoryTagViewModel.observeCategoriesForMovie(movie.id)
                    }
                    val selectedTagsFlow = remember(movie.id) {
                        categoryTagViewModel.observeTagsForMovie(movie.id)
                    }
                    val selectedCategories by selectedCategoriesFlow.collectAsStateWithLifecycle(
                        initialValue = emptyList(),
                    )
                    val selectedTags by selectedTagsFlow.collectAsStateWithLifecycle(
                        initialValue = emptyList(),
                    )
                    MovieDetailScreen(
                        movie = movie,
                        categories = categoryTagUiState.categories,
                        selectedCategories = selectedCategories,
                        tags = categoryTagUiState.tags,
                        selectedTags = selectedTags,
                        onBack = { navController.popBackStack() },
                        onHistory = {
                            navController.navigate(Screen.MovieHistory.createRoute(movie.id))
                        },
                        onEdit = {
                            navController.navigate(Screen.EditMovie.createRoute(movie.id))
                        },
                        onDelete = {
                            movieViewModel.deleteMovie(movie) {
                                navController.popBackStack()
                            }
                        },
                        onStatusChange = { status ->
                            movieViewModel.updateStatus(movie, status)
                        },
                        onCategoryChange = { category, selected ->
                            categoryTagViewModel.setMovieCategory(movie.id, category, selected)
                        },
                        onTagChange = { tag, selected ->
                            categoryTagViewModel.setMovieTag(movie.id, tag, selected)
                        },
                        actionState = movieActionState,
                        onActionMessageConsumed = movieViewModel::consumeActionMessage,
                    )
                } else {
                    LoadingOrMissingMovie(isLoaded = movieUiState.isLoaded)
                }
            }
            composable(
                route = Screen.EditMovie.route,
                arguments = listOf(navArgument("movieId") { type = NavType.LongType }),
            ) { entry ->
                val movieId = entry.arguments?.getLong("movieId")
                val movie = movieUiState.movies.firstOrNull { it.id == movieId }
                if (movie != null) {
                    val relationsFlow = remember(movie.id) {
                        categoryTagViewModel.observeMovieRelations(movie.id)
                    }
                    val relations by relationsFlow.collectAsStateWithLifecycle(
                        initialValue = MovieRelationsUiState(),
                    )
                    if (relations.isLoaded) {
                        MovieFormScreen(
                            title = Screen.EditMovie.title,
                            movie = movie,
                            categories = categoryTagUiState.categories,
                            tags = categoryTagUiState.tags,
                            initialCategoryId = relations.categoryId,
                            initialTagIds = relations.tagIds,
                            isSaving = movieFormActionState.isSaving,
                            saveMessage = movieFormActionState.message,
                            onSaveMessageConsumed = movieViewModel::consumeFormMessage,
                            onBack = { navController.popBackStack() },
                            onSave = { data ->
                                movieViewModel.updateMovie(
                                    movie = movie,
                                    data = data,
                                    onComplete = { navController.popBackStack() },
                                )
                            },
                        )
                    } else {
                        LoadingOrMissingMovie(isLoaded = false)
                    }
                } else {
                    LoadingOrMissingMovie(isLoaded = movieUiState.isLoaded)
                }
            }
            composable(
                route = Screen.MovieHistory.route,
                arguments = listOf(navArgument("movieId") { type = NavType.LongType }),
            ) { entry ->
                val movieId = entry.arguments?.getLong("movieId") ?: -1L
                val movie = movieUiState.movies.firstOrNull { it.id == movieId }
                val historyFlow = remember(movieId) {
                    historyViewModel.observeByMovieId(movieId)
                }
                val histories by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                if (movie != null) {
                    HistoryScreen(
                        movie = movie,
                        histories = histories,
                        onBack = { navController.popBackStack() },
                        onStartWatching = {
                            historyViewModel.startWatching(movie.id)
                        },
                        onEndWatching = historyViewModel::endWatching,
                        onUpdateTimes = historyViewModel::updateTimes,
                        actionState = historyActionState,
                        onActionMessageConsumed = historyViewModel::consumeActionMessage,
                    )
                } else {
                    LoadingOrMissingMovie(isLoaded = movieUiState.isLoaded)
                }
            }
        }
    }
}

@Composable
private fun LoadingOrMissingMovie(isLoaded: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoaded) {
            MinimalEmptyState(message = "作品不存在")
        } else {
            CircularProgressIndicator()
        }
    }
}
