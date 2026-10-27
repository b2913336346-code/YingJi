package com.yingji.app.presentation.movie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import com.yingji.app.ui.common.YingJiSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.formatMovieRating
import com.yingji.app.ui.common.LocalPoster

private val DetailCardShape = RoundedCornerShape(16.dp)
private val DetailChipShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MovieDetailScreen(
    movie: Movie,
    categories: List<Category>,
    selectedCategories: List<Category>,
    tags: List<Tag>,
    selectedTags: List<Tag>,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStatusChange: (MovieStatus) -> Unit,
    onCategoryChange: (Category, Boolean) -> Unit,
    onTagChange: (Tag, Boolean) -> Unit,
    actionState: MovieActionState = MovieActionState(),
    onActionMessageConsumed: () -> Unit = {},
    relationMessage: String? = null,
    onRelationMessageConsumed: () -> Unit = {},
    remotePosterState: RemotePosterState = RemotePosterState(),
    onRemotePosterOpen: () -> Unit = {},
    onRemotePosterConfirm: () -> Unit = {},
    onRemotePosterDismiss: () -> Unit = {},
    onRemotePosterMessageConsumed: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionState.message) {
        actionState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            onActionMessageConsumed()
        }
    }

    LaunchedEffect(relationMessage) {
        relationMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onRelationMessageConsumed()
        }
    }

    LaunchedEffect(remotePosterState.message) {
        remotePosterState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            onRemotePosterMessageConsumed()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { YingJiSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "作品详情",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑作品")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("movie_detail_content")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = DetailCardShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                LocalPoster(
                    path = movie.posterPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .testTag("detail_local_poster"),
                )
            }

            RemotePosterControls(remotePosterState, onRemotePosterOpen, onRemotePosterConfirm, onRemotePosterDismiss)

            DetailCard {
                Text(
                    text = movie.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                DetailInfoRow(
                    Icons.Filled.BarChart,
                    "进度：${movie.currentEpisode} / ${movie.totalEpisode ?: "-"}",
                )
                DetailInfoRow(Icons.Filled.StarBorder,
                    "评分：" + formatMovieRating(movie.rating) + if (movie.rating == null) "" else " / 10")
            }

            DetailCard {
                DetailSectionTitle(Icons.Filled.Visibility, "观看状态")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MovieStatus.entries.forEach { status ->
                        DetailSelectionChip(
                            text = status.value,
                            selected = movie.status == status.value,
                            enabled = !actionState.isRunning,
                            onClick = { onStatusChange(status) },
                        )
                    }
                }
            }

            DetailCard {
                DetailSectionTitle(Icons.Filled.Folder, "分类")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { category ->
                        val selected = selectedCategories.any { it.id == category.id }
                        DetailSelectionChip(
                            text = category.name,
                            selected = selected,
                            onClick = {
                                if (!selected) onCategoryChange(category, true)
                            },
                        )
                    }
                }
            }

            DetailCard {
                DetailSectionTitle(Icons.AutoMirrored.Filled.Label, "标签")
                if (tags.isEmpty()) {
                    Text(
                        text = "暂无标签，请在“我的”中新增",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            val selected = selectedTags.any { it.id == tag.id }
                            DetailSelectionChip(
                                text = tag.name,
                                selected = selected,
                                onClick = { onTagChange(tag, !selected) },
                            )
                        }
                    }
                }
            }

            DetailActionButton("观看历史", Icons.Filled.History, onHistory)
            DetailActionButton("编辑作品", Icons.Filled.Edit, onEdit)
            OutlinedButton(
                onClick = { confirmDelete = true },
                enabled = !actionState.isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = "删除作品")
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(text = "删除作品") },
            text = { Text(text = "确认删除“${movie.name}”吗？") },
            confirmButton = {
                TextButton(
                    onClick = onDelete,
                    enabled = !actionState.isRunning,
                ) {
                    Text(text = "确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(text = "取消") }
            },
        )
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = DetailCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun DetailInfoRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DetailSectionTitle(icon: ImageVector, title: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DetailSelectionChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text = text) },
        shape = DetailChipShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    )
}

@Composable
private fun DetailActionButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = text)
    }
}
