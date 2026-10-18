package com.yingji.app.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.presentation.category.CategoryTagActionState
import com.yingji.app.ui.common.MinimalEmptyState
import com.yingji.app.ui.common.YingJiTopBar

private enum class AddType { CATEGORY, TAG }

private sealed interface DeleteTarget {
    data class CategoryTarget(val category: Category) : DeleteTarget
    data class TagTarget(val tag: Tag) : DeleteTarget
}

private sealed interface EditTarget {
    val name: String

    data class CategoryTarget(val category: Category) : EditTarget {
        override val name: String = category.name
    }

    data class TagTarget(val tag: Tag) : EditTarget {
        override val name: String = tag.name
    }
}

@Composable
fun SettingsScreen(
    categories: List<Category>,
    tags: List<Tag>,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    displayMode: MovieDisplayMode = MovieDisplayMode.LIST,
    onDisplayModeChange: (MovieDisplayMode) -> Unit = {},
    displayModeSaveError: String? = null,
    onDisplayModeSaveErrorConsumed: () -> Unit = {},
    sortOption: MovieSortOption = MovieSortOption.CREATED_DESC,
    onSortOptionChange: (MovieSortOption) -> Unit = {},
    sortSaveError: String? = null,
    onSortSaveErrorConsumed: () -> Unit = {},
    onStatisticsClick: () -> Unit,
    onAddCategory: (String) -> Unit,
    onRenameCategory: (Category, String) -> Unit = { _, _ -> },
    onDeleteCategory: (Category) -> Unit,
    onAddTag: (String) -> Unit,
    onRenameTag: (Tag, String) -> Unit = { _, _ -> },
    onDeleteTag: (Tag) -> Unit,
    categoryMovieCounts: Map<Long, Long> = emptyMap(),
    tagMovieCounts: Map<Long, Long> = emptyMap(),
    onCategoryClick: (Long) -> Unit = {},
    onTagClick: (Long) -> Unit = {},
    actionState: CategoryTagActionState = CategoryTagActionState(),
    onActionResultConsumed: () -> Unit = {},
) {
    var addType by remember { mutableStateOf<AddType?>(null) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(sortSaveError) {
        sortSaveError?.let { message ->
            snackbarHostState.showSnackbar(message)
            onSortSaveErrorConsumed()
        }
    }

    LaunchedEffect(displayModeSaveError) {
        displayModeSaveError?.let { message ->
            snackbarHostState.showSnackbar(message)
            onDisplayModeSaveErrorConsumed()
        }
    }

    LaunchedEffect(actionState.saveSucceeded, actionState.message, addType, editTarget) {
        when {
            actionState.saveSucceeded -> {
                addType = null
                editTarget = null
                onActionResultConsumed()
            }
            actionState.message != null && addType == null && editTarget == null -> {
                snackbarHostState.showSnackbar(actionState.message)
                onActionResultConsumed()
            }
        }
    }

    Scaffold(
        topBar = { YingJiTopBar(title = "我的") },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "主题",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                )
            }
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ThemeMode.entries) { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(text = mode.displayName) },
                            modifier = if (mode == ThemeMode.SYSTEM) {
                                Modifier.padding(start = 16.dp)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onStatisticsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(text = "查看统计")
                }
            }
            item {
                Text(
                    text = "默认展示方式",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                )
            }
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(MovieDisplayMode.entries) { mode ->
                        FilterChip(
                            selected = displayMode == mode,
                            onClick = { onDisplayModeChange(mode) },
                            label = { Text(text = mode.displayName) },
                        )
                    }
                }
            }
            item {
                Text(
                    text = "默认排序",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                )
            }
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(MovieSortOption.entries) { option ->
                        FilterChip(
                            selected = sortOption == option,
                            onClick = { onSortOptionChange(option) },
                            label = { Text(text = option.displayName) },
                        )
                    }
                }
            }
            item {
                SectionHeader(
                    title = "分类",
                    onAdd = {
                        onActionResultConsumed()
                        addType = AddType.CATEGORY
                    },
                )
            }
            items(categories, key = { "category:${it.id}" }) { category ->
                ManagementRow(
                    name = category.name,
                    movieCount = categoryMovieCounts[category.id] ?: 0L,
                    leadingIcon = Icons.Filled.Category,
                    canDelete = !category.isSystem,
                    canEdit = !category.isSystem,
                    onOpen = { onCategoryClick(category.id) },
                    onEdit = {
                        onActionResultConsumed()
                        editTarget = EditTarget.CategoryTarget(category)
                    },
                    onDelete = { deleteTarget = DeleteTarget.CategoryTarget(category) },
                )
            }
            item {
                SectionHeader(
                    title = "标签",
                    onAdd = {
                        onActionResultConsumed()
                        addType = AddType.TAG
                    },
                )
            }
            if (tags.isEmpty()) {
                item {
                    MinimalEmptyState(
                        message = "还没有自定义标签",
                        modifier = Modifier.fillMaxWidth(),
                        graphicSize = 40.dp,
                    )
                }
            }
            items(tags, key = { "tag:${it.id}" }) { tag ->
                ManagementRow(
                    name = tag.name,
                    movieCount = tagMovieCounts[tag.id] ?: 0L,
                    leadingIcon = Icons.AutoMirrored.Filled.Label,
                    canDelete = true,
                    canEdit = true,
                    onOpen = { onTagClick(tag.id) },
                    onEdit = {
                        onActionResultConsumed()
                        editTarget = EditTarget.TagTarget(tag)
                    },
                    onDelete = { deleteTarget = DeleteTarget.TagTarget(tag) },
                )
            }
        }
    }

    addType?.let { type ->
        NameDialog(
            title = if (type == AddType.CATEGORY) "新增自定义分类" else "新增标签",
            initialValue = "",
            onDismiss = {
                onActionResultConsumed()
                addType = null
            },
            isSaving = actionState.isSaving,
            errorMessage = actionState.message,
            onNameChanged = onActionResultConsumed,
            onConfirm = { name ->
                if (type == AddType.CATEGORY) onAddCategory(name) else onAddTag(name)
            },
        )
    }

    editTarget?.let { target ->
        NameDialog(
            title = if (target is EditTarget.CategoryTarget) "重命名分类" else "编辑标签",
            initialValue = target.name,
            onDismiss = {
                onActionResultConsumed()
                editTarget = null
            },
            isSaving = actionState.isSaving,
            errorMessage = actionState.message,
            onNameChanged = onActionResultConsumed,
            onConfirm = { name ->
                when (target) {
                    is EditTarget.CategoryTarget -> onRenameCategory(target.category, name)
                    is EditTarget.TagTarget -> onRenameTag(target.tag, name)
                }
            },
        )
    }

    deleteTarget?.let { target ->
        val targetName = when (target) {
            is DeleteTarget.CategoryTarget -> target.category.name
            is DeleteTarget.TagTarget -> target.tag.name
        }
        val targetType = if (target is DeleteTarget.CategoryTarget) "分类" else "标签"
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = "删除$targetType") },
            text = {
                Text(text = "确定删除“$targetName”吗？只会移除该${targetType}及其作品关联，不会删除影视作品。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (target) {
                            is DeleteTarget.CategoryTarget -> onDeleteCategory(target.category)
                            is DeleteTarget.TagTarget -> onDeleteTag(target.tag)
                        }
                        deleteTarget = null
                    },
                ) { Text(text = "删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = "取消")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(text = "新增")
        }
    }
}

@Composable
private fun ManagementRow(
    name: String,
    movieCount: Long,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    canEdit: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(leadingIcon, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "$movieCount 个作品",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = "查看$name 作品")
            }
            if (canEdit) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑$name")
                }
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除$name")
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    isSaving: Boolean,
    errorMessage: String?,
    onNameChanged: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var hasEdited by remember(initialValue) { mutableStateOf(false) }
    val inputError = if (hasEdited && value.isBlank()) "名称不能为空" else errorMessage
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    hasEdited = true
                    onNameChanged()
                },
                label = { Text(text = "名称") },
                singleLine = true,
                enabled = !isSaving,
                isError = inputError != null,
                supportingText = inputError?.let { message -> { Text(text = message) } },
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank() && !isSaving,
                onClick = { onConfirm(value) },
            ) { Text(text = "保存") }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss,
            ) { Text(text = "取消") }
        },
    )
}
