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
import com.yingji.app.ui.common.YingJiSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.yingji.app.presentation.ai.AiHomeViewModel
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
import com.yingji.app.presentation.common.DatabaseProtectionHost
import com.yingji.app.presentation.category.CategoryTagViewModel
import com.yingji.app.presentation.category.MovieRelationsUiState
import com.yingji.app.presentation.history.HistoryScreen
import com.yingji.app.presentation.history.HistoryViewModel
import androidx.compose.runtime.DisposableEffect
import com.yingji.app.presentation.movie.RemotePosterViewModel
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
fun YingJiNavGraph(aiHomeRepository: com.yingji.app.domain.ai.AiHomeRepository? = null, aiHomeViewModelOverride: AiHomeViewModel? = null,
    remotePosterFactory: ((Long) -> androidx.lifecycle.ViewModelProvider.Factory)? = null) {
    val navController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val application = LocalContext.current.applicationContext as YingJiApplication
    val protection = application.databaseProtection
    val protectionState by protection.state.collectAsStateWithLifecycle()
    val health by DatabaseHealthMonitor.state.collectAsStateWithLifecycle()
    LaunchedEffect(protection) { protection.initialize() }
    if (protectionState.initializing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (health is DatabaseHealthState.Unavailable) {
        DatabaseProtectionHost(protection, protectionState)
        return
    }
    val aiHomeViewModel: AiHomeViewModel = aiHomeViewModelOverride ?: viewModel(factory = AiHomeViewModel.factory(aiHomeRepository ?: application.aiHomeRepository,
        if (aiHomeRepository == null) application.aiKeyConfigurationRepository else null,
        if (aiHomeRepository == null) application.localProfileManagementRepository else null,
        if (aiHomeRepository == null) application.tmdbCredentialRepository else null,
        if (aiHomeRepository == null) application.tmdbCandidatePreview else null,
        if (aiHomeRepository == null) application.tmdbMockRanking else null,
        if (aiHomeRepository == null) application.personalizedRecommendation else null))
    LaunchedEffect(aiHomeViewModel) {
        aiHomeViewModel.keyConfigurationRequests.collect { navController.navigate(Screen.AiModelConfiguration.route) { launchSingleTop = true } }
    }
    // Confirmed TMDB drafts remain in memory; only the pending flag survives process recreation.
    var tmdbDraft by remember { mutableStateOf<com.yingji.app.domain.ai.CatalogCandidate?>(null) }
    var tmdbDraftPending by rememberSaveable { mutableStateOf(false) }
    fun clearTmdbDraft() { tmdbDraft = null; tmdbDraftPending = false }
    LaunchedEffect(currentDestination?.route) { if (currentDestination?.route != null && currentDestination?.route != Screen.AddMovie.route) clearTmdbDraft() }
    var aiMovieDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var aiCandidateSource by rememberSaveable { mutableStateOf<String?>(null) }
    var aiCandidateExternalId by rememberSaveable { mutableStateOf<String?>(null) }
    val movieViewModel: MovieViewModel = viewModel(
        factory = MovieViewModel.factory(
            movieRepository = application.movieRepository,
            posterRepository = application.posterRepository,
            addRecommendedWork = application.addRecommendedWork,
            importTmdbWork = application.importTmdbWork::invoke,
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
    val catalogUiState by searchViewModel.catalogUiState.collectAsStateWithLifecycle()
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val movieDisplayMode by settingsViewModel.movieDisplayMode.collectAsStateWithLifecycle()
    val displayModeSaveError by settingsViewModel.displayModeSaveError.collectAsStateWithLifecycle()
    val movieSortOption by settingsViewModel.movieSortOption.collectAsStateWithLifecycle()
    val sortSaveError by settingsViewModel.sortSaveError.collectAsStateWithLifecycle()
    val statisticsUiState by statisticsViewModel.uiState.collectAsStateWithLifecycle()
    val databaseHealthState by DatabaseHealthMonitor.state.collectAsStateWithLifecycle()
    val databaseSnackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(searchUiState.errorMessage) {
        searchUiState.errorMessage?.let { databaseSnackbarHostState.showSnackbar(it) }
    }

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
        clearTmdbDraft()
        aiMovieDraft = null
        aiCandidateSource = null
        aiCandidateExternalId = null
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
        snackbarHost = { YingJiSnackbarHost(databaseSnackbarHostState) },
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
                    movies = catalogUiState.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onAddMovieClick = openAddMovie,
                    onSearchClick = { navController.navigate(Screen.Search.route) },
                    aiViewModel = aiHomeViewModel,
                    onAddTmdbMovie = { candidate ->
                        aiMovieDraft = null; aiCandidateSource = null; aiCandidateExternalId = null
                        tmdbDraft = candidate; tmdbDraftPending = true
                        navController.navigate(Screen.AddMovie.route) { launchSingleTop = true }
                    },
                    onAddAiMovie = { item ->
                        clearTmdbDraft()
                        aiMovieDraft = item.title
                        aiCandidateSource = item.candidateIdentity?.sourceId.orEmpty()
                        aiCandidateExternalId = item.candidateIdentity?.externalId.orEmpty()
                        navController.navigate(Screen.AddMovie.route) { launchSingleTop = true }
                    },
                    defaultDisplayMode = movieDisplayMode,
                    sortOption = catalogUiState.sortOption,
                    onSortOptionChange = searchViewModel::selectSortOption,
                )
            }
            composable(Screen.WantToWatch.route) {
                WantToWatchScreen(
                    movies = catalogUiState.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onAddMovieClick = openAddMovie,
                    defaultDisplayMode = movieDisplayMode,
                    sortOption = catalogUiState.sortOption,
                    onSortOptionChange = searchViewModel::selectSortOption,
                )
            }
            composable(Screen.Watching.route) {
                WatchingScreen(
                    movies = catalogUiState.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onAddMovieClick = openAddMovie,
                    defaultDisplayMode = movieDisplayMode,
                    sortOption = catalogUiState.sortOption,
                    onSortOptionChange = searchViewModel::selectSortOption,
                )
            }
            composable(Screen.Finished.route) {
                FinishedScreen(
                    movies = catalogUiState.movies,
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onAddMovieClick = openAddMovie,
                    defaultDisplayMode = movieDisplayMode,
                    sortOption = catalogUiState.sortOption,
                    onSortOptionChange = searchViewModel::selectSortOption,
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
                    displayModeSaveError = displayModeSaveError,
                    onDisplayModeSaveErrorConsumed = settingsViewModel::consumeDisplayModeSaveError,
                    sortOption = movieSortOption,
                    onSortOptionChange = settingsViewModel::setMovieSortOption,
                    sortSaveError = sortSaveError,
                    onSortSaveErrorConsumed = settingsViewModel::consumeSortSaveError,
                    onStatisticsClick = { navController.navigate(Screen.Statistics.route) },
                    movieCount = movieUiState.movies.size.takeIf { movieUiState.isLoaded },
                    onAiModelConfigurationClick = { navController.navigate(Screen.AiModelConfiguration.route) { launchSingleTop = true } },
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
            composable(Screen.AiModelConfiguration.route) {
                val config: com.yingji.app.presentation.ai.AiKeyConfigurationViewModel = viewModel(factory =
                    com.yingji.app.presentation.ai.AiKeyConfigurationViewModel.factory(application.aiKeyConfigurationRepository))
                com.yingji.app.presentation.ai.AiKeyConfigurationScreen(config) { navController.popBackStack() }
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
                MovieListScreen(
                    title = catalogUiState.categories.firstOrNull { it.id == categoryId }?.name ?: "分类",
                    movies = catalogUiState.moviesByCategory[categoryId].orEmpty(),
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onBack = { navController.popBackStack() },
                    emptyMessage = "暂无作品",
                    defaultDisplayMode = movieDisplayMode,
                    sortOption = catalogUiState.sortOption,
                    onSortOptionChange = searchViewModel::selectSortOption,
                )
            }
            composable(
                route = Screen.TagMovies.route,
                arguments = listOf(navArgument("tagId") { type = NavType.LongType }),
            ) { entry ->
                val tagId = entry.arguments?.getLong("tagId") ?: -1L
                MovieListScreen(
                    title = catalogUiState.tags.firstOrNull { it.id == tagId }?.name ?: "标签",
                    movies = catalogUiState.moviesByTag[tagId].orEmpty(),
                    categoryNamesByMovieId = categoryTagUiState.categoryNamesByMovieId,
                    onMovieClick = openMovie,
                    onBack = { navController.popBackStack() },
                    emptyMessage = "暂无作品",
                    defaultDisplayMode = movieDisplayMode,
                    sortOption = catalogUiState.sortOption,
                    onSortOptionChange = searchViewModel::selectSortOption,
                )
            }
            composable(Screen.AddMovie.route) {
                if (tmdbDraftPending && tmdbDraft == null) {
                    androidx.compose.material3.AlertDialog(onDismissRequest = { clearTmdbDraft(); navController.popBackStack() },
                        title = { Text("候选会话已失效") }, text = { Text("请返回首页重新读取候选；本次未自动保存或转为普通新增。") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { clearTmdbDraft(); navController.popBackStack() }) { Text("返回首页") } })
                } else CompositionLocalProvider(LocalSaveableStateRegistry provides if (tmdbDraftPending) null else LocalSaveableStateRegistry.current) {
                MovieFormScreen(
                    title = Screen.AddMovie.title,
                    initialName = tmdbDraft?.title ?: aiMovieDraft.orEmpty(),
                    categories = categoryTagUiState.categories,
                    tags = categoryTagUiState.tags,
                    isSaving = movieFormActionState.isSaving,
                    saveMessage = movieFormActionState.message,
                    onSaveMessageConsumed = movieViewModel::consumeFormMessage,
                    onBack = { clearTmdbDraft(); navController.popBackStack() },
                    onSave = { data ->
                        movieViewModel.addMovie(data, recommendedIdentity = tmdbDraft?.identity ?: if (aiMovieDraft == null) null else
                            com.yingji.app.domain.ai.CandidateIdentity(aiCandidateSource.orEmpty(), aiCandidateExternalId.orEmpty())) {
                            clearTmdbDraft(); navController.popBackStack()
                        }
                    },
                )
                }
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
                    val remotePoster: RemotePosterViewModel = viewModel(viewModelStoreOwner = entry, key = "remote-poster-" + movie.id,
                        factory = remotePosterFactory?.invoke(movie.id) ?: RemotePosterViewModel.factory(
                            hasSource = { application.movieRepository.hasRemotePosterSource(movie.id) },
                            lookup = { application.tmdbPosterLookup(application.movieRepository.remotePosterTarget(movie.id).externalId) },
                            save = { poster -> application.saveRemotePosterUseCase(movie.id, poster, confirmed = true) }))
                    val remotePosterState by remotePoster.uiState.collectAsStateWithLifecycle()
                    DisposableEffect(remotePoster) {
                        remotePoster.enter()
                        onDispose { remotePoster.leave() }
                    }
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
                        relationMessage = categoryTagActionState.message,
                        onRelationMessageConsumed = categoryTagViewModel::consumeActionResult,
                        remotePosterState = remotePosterState,
                        onRemotePosterOpen = remotePoster::open,
                        onRemotePosterConfirm = remotePoster::confirm,
                        onRemotePosterDismiss = remotePoster::dismiss,
                        onRemotePosterMessageConsumed = remotePoster::consumeMessage,
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
