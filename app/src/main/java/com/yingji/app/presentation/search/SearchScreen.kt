package com.yingji.app.presentation.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yingji.app.ui.common.MinimalEmptyState
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.WatchTimeField
import com.yingji.app.presentation.movie.DisplayModeToggle
import com.yingji.app.presentation.movie.MovieCollectionContent
import com.yingji.app.presentation.movie.toggled
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private enum class WatchDateTarget { FROM, TO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (Long?) -> Unit,
    onTagSelected: (Long?) -> Unit,
    onStatusSelected: (MovieStatus?) -> Unit = {},
    onTimeFieldSelected: (WatchTimeField?) -> Unit = {},
    onFromTimeSelected: (Long?) -> Unit = {},
    onToTimeSelected: (Long?) -> Unit = {},
    onSortSelected: (MovieSortOption) -> Unit = {},
    onMovieClick: (Long) -> Unit,
    defaultDisplayMode: MovieDisplayMode = MovieDisplayMode.LIST,
) {
    var displayMode by rememberSaveable(defaultDisplayMode) {
        mutableStateOf(defaultDisplayMode)
    }
    var dateTarget by rememberSaveable { mutableStateOf<WatchDateTarget?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    DisplayModeToggle(
                        displayMode = displayMode,
                        onToggle = { displayMode = displayMode.toggled() },
                    )
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val filterMaxHeight = maxHeight * 0.65f
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .heightIn(max = filterMaxHeight)
                        .testTag("search_filters")
                        .verticalScroll(rememberScrollState()),
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(text = "名称、拼音或首字母") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "清空搜索")
                                }
                            }
                        },
                        singleLine = true,
                    )
                    Text(
                        text = "分类",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = state.selectedCategoryId == null,
                                onClick = { onCategorySelected(null) },
                                label = { Text(text = "全部") },
                            )
                        }
                        items(state.categories, key = { it.id }) { category ->
                            FilterChip(
                                selected = state.selectedCategoryId == category.id,
                                onClick = {
                                    onCategorySelected(
                                        if (state.selectedCategoryId == category.id) null else category.id,
                                    )
                                },
                                label = { Text(text = category.name) },
                            )
                        }
                    }
                    Text(
                        text = "标签",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = state.selectedTagId == null,
                                onClick = { onTagSelected(null) },
                                label = { Text(text = "全部") },
                            )
                        }
                        items(state.tags, key = { it.id }) { tag ->
                            FilterChip(
                                selected = state.selectedTagId == tag.id,
                                onClick = {
                                    onTagSelected(if (state.selectedTagId == tag.id) null else tag.id)
                                },
                                label = { Text(text = tag.name) },
                            )
                        }
                    }
                    SearchOptionRow(
                        title = "观看状态",
                        allLabel = "全部",
                        allSelected = state.selectedStatus == null,
                        onAllSelected = { onStatusSelected(null) },
                        options = MovieStatus.entries,
                        selected = { state.selectedStatus == it },
                        label = MovieStatus::value,
                        onSelected = onStatusSelected,
                    )
                    SearchOptionRow(
                        title = "观看时间",
                        allLabel = "不限",
                        allSelected = state.timeField == null,
                        onAllSelected = { onTimeFieldSelected(null) },
                        options = WatchTimeField.entries,
                        selected = { state.timeField == it },
                        label = WatchTimeField::displayName,
                        onSelected = onTimeFieldSelected,
                    )
                    if (state.timeField != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { dateTarget = WatchDateTarget.FROM },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = state.fromTime?.let(::formatWatchDate) ?: "开始日期")
                            }
                            OutlinedButton(
                                onClick = { dateTarget = WatchDateTarget.TO },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = state.toTime?.let(::formatWatchDate) ?: "结束日期")
                            }
                        }
                    }
                    SearchOptionRow(
                        title = "排序",
                        allLabel = "",
                        allSelected = false,
                        onAllSelected = {},
                        options = MovieSortOption.entries,
                        selected = { state.sortOption == it },
                        label = MovieSortOption::displayName,
                        onSelected = onSortSelected,
                        showAll = false,
                    )
                }
                MovieCollectionContent(
                    movies = state.movies,
                    categoryNamesByMovieId = state.categoryNamesByMovieId,
                    displayMode = displayMode,
                    onMovieClick = onMovieClick,
                    emptyMessage = state.errorMessage ?: "没有找到匹配的作品",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    dateTarget?.let { target ->
        WatchDatePickerDialog(
            initialTime = if (target == WatchDateTarget.FROM) state.fromTime else state.toTime,
            onDismiss = { dateTarget = null },
            onConfirm = { selectedDateMillis ->
                if (target == WatchDateTarget.FROM) {
                    onFromTimeSelected(pickerDateToRangeStart(selectedDateMillis))
                } else {
                    onToTimeSelected(pickerDateToRangeEnd(selectedDateMillis))
                }
                dateTarget = null
            },
        )
    }
}

@Composable
private fun <T> SearchOptionRow(
    title: String,
    allLabel: String,
    allSelected: Boolean,
    onAllSelected: () -> Unit,
    options: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    showAll: Boolean = true,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showAll) {
            item {
                FilterChip(
                    selected = allSelected,
                    onClick = onAllSelected,
                    label = { Text(text = allLabel) },
                )
            }
        }
        items(options) { option ->
            FilterChip(
                selected = selected(option),
                onClick = { onSelected(option) },
                label = { Text(text = label(option)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchDatePickerDialog(
    initialTime: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val pickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initialTime?.let(::timeToPickerDate),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedDateMillis != null,
                onClick = {
                    pickerState.selectedDateMillis?.let(onConfirm)
                },
            ) { Text(text = "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

internal fun pickerDateToRangeStart(
    selectedDateMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long = Instant.ofEpochMilli(selectedDateMillis)
    .atZone(ZoneOffset.UTC)
    .toLocalDate()
    .atStartOfDay(zoneId)
    .toInstant()
    .toEpochMilli()

internal fun pickerDateToRangeEnd(
    selectedDateMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long = Instant.ofEpochMilli(selectedDateMillis)
    .atZone(ZoneOffset.UTC)
    .toLocalDate()
    .plusDays(1)
    .atStartOfDay(zoneId)
    .toInstant()
    .toEpochMilli() - 1L

private fun timeToPickerDate(timeMillis: Long): Long = Instant.ofEpochMilli(timeMillis)
    .atZone(ZoneId.systemDefault())
    .toLocalDate()
    .atStartOfDay(ZoneOffset.UTC)
    .toInstant()
    .toEpochMilli()

private fun formatWatchDate(timeMillis: Long): String = Instant.ofEpochMilli(timeMillis)
    .atZone(ZoneId.systemDefault())
    .toLocalDate()
    .format(DateTimeFormatter.ISO_LOCAL_DATE)
