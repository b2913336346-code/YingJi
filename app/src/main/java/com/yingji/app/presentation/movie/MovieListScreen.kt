package com.yingji.app.presentation.movie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.ui.common.LocalPoster
import com.yingji.app.ui.common.MinimalEmptyState
import com.yingji.app.ui.common.MinimalMotionDuration
import com.yingji.app.ui.common.YingJiTopBar

@Composable
fun MovieListScreen(
    title: String,
    movies: List<Movie>,
    categoryNamesByMovieId: Map<Long, String> = emptyMap(),
    onMovieClick: (Long) -> Unit,
    onAddMovieClick: (() -> Unit)? = null,
    onSearchClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    emptyMessage: String = "还没有作品",
    defaultDisplayMode: MovieDisplayMode = MovieDisplayMode.LIST,
    sortOption: MovieSortOption? = null,
    onSortOptionChange: (MovieSortOption) -> Unit = {},
    footerItems: List<@Composable () -> Unit> = emptyList(),
) {
    var displayMode by rememberSaveable(title, defaultDisplayMode) {
        mutableStateOf(defaultDisplayMode)
    }
    Scaffold(
        topBar = {
            YingJiTopBar(
                title = title,
                navigationIcon = {
                    onBack?.let { onClick ->
                        IconButton(onClick = onClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    DisplayModeToggle(
                        displayMode = displayMode,
                        onToggle = { displayMode = displayMode.toggled() },
                    )
                    onSearchClick?.let { onClick ->
                        IconButton(onClick = onClick) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            onAddMovieClick?.let { onClick ->
                FloatingActionButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "新增作品",
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            sortOption?.let { selected ->
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    MovieSortMenu(selected, onSortOptionChange)
                }
            }
            MovieCollectionContent(
                movies = movies,
                categoryNamesByMovieId = categoryNamesByMovieId,
                displayMode = displayMode,
                onMovieClick = onMovieClick,
                emptyMessage = emptyMessage,
                modifier = Modifier.weight(1f),
                footerItems = footerItems,
                footerBottomPadding = if (onAddMovieClick != null && footerItems.isNotEmpty()) 88.dp else 16.dp,
            )
        }
    }
}

@Composable
fun MovieCollectionContent(
    movies: List<Movie>,
    categoryNamesByMovieId: Map<Long, String>,
    displayMode: MovieDisplayMode,
    onMovieClick: (Long) -> Unit,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    footerItems: List<@Composable () -> Unit> = emptyList(),
    footerBottomPadding: Dp = 16.dp,
) {
    Crossfade(
        targetState = movies.isEmpty() to displayMode,
        animationSpec = tween(MinimalMotionDuration),
        modifier = modifier.fillMaxSize(),
        label = "作品展示方式切换",
    ) { (isEmpty, currentMode) ->
        when {
            isEmpty && footerItems.isEmpty() -> MinimalEmptyState(
                message = emptyMessage,
                modifier = Modifier.fillMaxSize(),
            )
            isEmpty || currentMode == MovieDisplayMode.LIST -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("movie_collection"),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = footerBottomPadding),
                verticalArrangement = remember(footerItems.size) { BottomFooterArrangement(10.dp, footerItems.size) },
            ) {
                if (isEmpty) item(key = "empty") {
                    MinimalEmptyState(emptyMessage, Modifier.fillMaxWidth().height(160.dp))
                }
                items(items = movies, key = { it.id }) { movie ->
                    MovieListItem(
                        movie = movie,
                        categoryName = categoryNamesByMovieId[movie.id] ?: movie.type,
                        onClick = { onMovieClick(movie.id) },
                    )
                }
                footerItems.forEachIndexed { index, content ->
                    item(key = "footer_" + index) { content() }
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().testTag("movie_collection"),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = footerBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = remember(footerItems.size) { BottomFooterArrangement(12.dp, footerItems.size) },
            ) {
                gridItems(items = movies, key = { it.id }) { movie ->
                    MoviePosterItem(
                        movie = movie,
                        categoryName = categoryNamesByMovieId[movie.id] ?: movie.type,
                        onClick = { onMovieClick(movie.id) },
                    )
                }
                footerItems.forEachIndexed { index, content ->
                    item(key = "footer_" + index, span = { GridItemSpan(maxLineSpan) }) { content() }
                }
            }
        }
    }
}

/** Keep collection rows at the top; use spare viewport space before full-width footer rows. */
private class BottomFooterArrangement(
    override val spacing: Dp,
    private val footerCount: Int,
) : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = spacing.roundToPx()
        val footerStart = sizes.size - footerCount
        val spare = if (footerCount > 0) {
            (totalSize - sizes.sum() - gap * (sizes.size - 1).coerceAtLeast(0)).coerceAtLeast(0)
        } else 0
        var position = 0
        sizes.forEachIndexed { index, size ->
            if (index == footerStart) position += spare
            outPositions[index] = position
            position += size + gap
        }
    }
}

@Composable
fun DisplayModeToggle(
    displayMode: MovieDisplayMode,
    onToggle: () -> Unit,
) {
    val showPosterWall = displayMode == MovieDisplayMode.LIST
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (showPosterWall) {
                Icons.Filled.GridView
            } else {
                Icons.AutoMirrored.Filled.ViewList
            },
            contentDescription = if (showPosterWall) "切换到海报墙" else "切换到列表",
        )
    }
}

internal fun MovieDisplayMode.toggled(): MovieDisplayMode = when (this) {
    MovieDisplayMode.LIST -> MovieDisplayMode.POSTER_WALL
    MovieDisplayMode.POSTER_WALL -> MovieDisplayMode.LIST
}

@Composable
private fun MovieListItem(
    movie: Movie,
    categoryName: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LocalPoster(
                path = movie.posterPath,
                maxDecodeSizePx = 256,
                modifier = Modifier
                    .width(72.dp)
                    .height(108.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = movie.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = categoryName)
                    Text(text = movie.status)
                }
            }
        }
    }
}

@Composable
private fun MoviePosterItem(
    movie: Movie,
    categoryName: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalPoster(
                path = movie.posterPath,
                maxDecodeSizePx = 512,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = movie.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$categoryName · ${movie.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
