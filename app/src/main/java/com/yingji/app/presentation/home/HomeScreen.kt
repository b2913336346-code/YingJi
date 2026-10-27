package com.yingji.app.presentation.home

import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingji.app.presentation.ai.*
import com.yingji.app.data.entity.Movie
import com.yingji.app.presentation.movie.MovieListScreen
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption

@Composable
fun HomeScreen(
    movies: List<Movie>,
    categoryNamesByMovieId: Map<Long, String>,
    onMovieClick: (Long) -> Unit,
    onAddMovieClick: () -> Unit,
    onSearchClick: () -> Unit,
    defaultDisplayMode: MovieDisplayMode = MovieDisplayMode.LIST,
    sortOption: MovieSortOption? = null,
    onSortOptionChange: (MovieSortOption) -> Unit = {},
    aiViewModel: AiHomeViewModel? = null,
    onAddAiMovie: (com.yingji.app.domain.ai.MockRecommendation) -> Unit = {},
    onAddTmdbMovie: ((com.yingji.app.domain.ai.CatalogCandidate) -> Unit)? = null,
) {
    val aiState = aiViewModel?.state?.collectAsStateWithLifecycle()?.value
    // Session-only selection: never restore a network source or its results from saved state.
    var tmdbSelected by remember(aiViewModel) { mutableStateOf(false) }
    val tmdbModel: TmdbCredentialViewModel? = if (aiViewModel?.tmdbCredentials != null && aiViewModel.tmdbPreview != null) {
        viewModel(key = "tmdb-home-preview", factory = TmdbCredentialViewModel.factory(aiViewModel.tmdbCredentials, aiViewModel.tmdbPreview, aiViewModel.tmdbRanking, aiViewModel.tmdbRankingChanges, aiViewModel.personalizedRecommendation))
    } else null
    tmdbModel?.preview?.collectAsStateWithLifecycle()?.value
    val rankingState = tmdbModel?.ranking?.collectAsStateWithLifecycle()?.value
    val visibleTmdbRows = tmdbModel?.visibleRows
    val pendingImport = tmdbModel?.pendingImport?.collectAsStateWithLifecycle()?.value
    val personalizedConsent = tmdbModel?.personalizedConsent?.collectAsStateWithLifecycle()?.value
    DisposableEffect(tmdbModel) { onDispose { tmdbModel?.clearPreview() } }
    val canSwitch = aiState != null && !aiState.initializing && !aiState.saving && aiState.result != AiResultState.LOADING &&
        aiState.feedbackSaving == null && aiState.step == null && aiState.pendingAdd == null && !aiState.managementOpen && !aiState.directConsentPending
    val sourceControls: (@Composable () -> Unit)? = if (tmdbModel != null) ({
        AiCandidateSourceControls(tmdbSelected, canSwitch) { selected ->
            if (canSwitch && selected != tmdbSelected) { tmdbModel.clearPreview(); tmdbSelected = selected }
        }
    }) else null
    val aiFooter = buildList<@Composable () -> Unit> {
        if (aiViewModel != null && aiState != null) {
            if (tmdbSelected && tmdbModel != null && sourceControls != null) {
                add { TmdbHomeCard(tmdbModel, !aiState.readFailed, aiViewModel::openManagement, sourceControls, onAddTmdbMovie != null) }
                visibleTmdbRows?.forEach { item -> add { TmdbCandidateCard(item, "tmdb_home_", if (onAddTmdbMovie == null || rankingState?.status == TmdbPreviewStatus.LOADING) null else ({ tmdbModel.requestImport(item) })) } }
                add { TmdbAttribution("tmdb_home_credits") }
            } else {
                add { AiHomeCard(aiState, aiViewModel, sourceControls) }
                aiState.recommendations.forEach { item -> add { AiRecommendationCard(item, aiState, aiViewModel) } }
            }
        }
    }
    MovieListScreen(
        title = "首页",
        movies = movies,
        categoryNamesByMovieId = categoryNamesByMovieId,
        onMovieClick = onMovieClick,
        onAddMovieClick = onAddMovieClick,
        onSearchClick = onSearchClick,
        defaultDisplayMode = defaultDisplayMode,
        sortOption = sortOption,
        onSortOptionChange = onSortOptionChange,
        footerItems = aiFooter,
    )
    if (tmdbSelected && tmdbModel != null && personalizedConsent != null) {
        PersonalizedConsentDialog(personalizedConsent, tmdbModel.personalizedUsesMockKey, tmdbModel::cancelPersonalizedConsent, tmdbModel::confirmPersonalizedConsent)
    }
    if (tmdbSelected && tmdbModel != null && pendingImport != null && onAddTmdbMovie != null) {
        AlertDialog(onDismissRequest = tmdbModel::cancelImport,
            title = { Text("加入此作品？") },
            text = { Text(pendingImport.title.orEmpty() + "：确认后进入现有新增表单，仅预填名称。分类必选，标签可选。海报可本地选择；保存作品后也可到详情确认使用在线海报。最终保存时会联网重验所选候选；校验或保存失败不会新增作品。候选草稿仅存本次会话，关闭或重启后需重新读取。") },
            confirmButton = { TextButton(onClick = { tmdbModel.confirmImport()?.let(onAddTmdbMovie) }, modifier = Modifier.testTag("tmdb_add_confirm")) { Text("确认并填写") } },
            dismissButton = { TextButton(onClick = tmdbModel::cancelImport, modifier = Modifier.testTag("tmdb_add_cancel")) { Text("取消") } })
    }
    if (aiViewModel != null && aiState != null) AiHomeDialogs(aiState, aiViewModel, onAddAiMovie)
}
