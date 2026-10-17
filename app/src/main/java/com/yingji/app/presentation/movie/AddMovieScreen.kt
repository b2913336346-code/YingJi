package com.yingji.app.presentation.movie

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.resolveCurrentEpisodeForStatusTransition
import com.yingji.app.ui.common.LocalPoster

private val FormCardShape = RoundedCornerShape(16.dp)
private val FormFieldShape = RoundedCornerShape(10.dp)
private val FormChipShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MovieFormScreen(
    title: String,
    movie: Movie? = null,
    categories: List<Category>,
    tags: List<Tag>,
    initialCategoryId: Long? = null,
    initialTagIds: Set<Long> = emptySet(),
    isSaving: Boolean = false,
    saveMessage: String? = null,
    onSaveMessageConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onSave: (MovieFormData) -> Unit,
) {
    var name by rememberSaveable(movie?.id) { mutableStateOf(movie?.name.orEmpty()) }
    var status by rememberSaveable(movie?.id) {
        mutableStateOf(movie?.status ?: MovieStatus.WANT_TO_WATCH.value)
    }
    var currentEpisode by rememberSaveable(movie?.id) {
        mutableStateOf(movie?.currentEpisode?.toString() ?: "0")
    }
    var totalEpisode by rememberSaveable(movie?.id) {
        mutableStateOf(movie?.totalEpisode?.toString().orEmpty())
    }
    var posterPath by rememberSaveable(movie?.id) { mutableStateOf(movie?.posterPath) }
    var selectedCategoryId by rememberSaveable(movie?.id) { mutableStateOf(initialCategoryId) }
    var selectedTagIds by rememberSaveable(movie?.id) { mutableStateOf(initialTagIds.toList()) }
    var showNameError by rememberSaveable { mutableStateOf(false) }
    var showCategoryError by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(saveMessage) {
        saveMessage?.let {
            snackbarHostState.showSnackbar(it)
            onSaveMessageConsumed()
        }
    }

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) posterPath = result.uriContent?.toString()
    }
    val launchCrop: (android.net.Uri) -> Unit = { uri ->
        cropLauncher.launch(
            CropImageContractOptions(
                uri = uri,
                cropImageOptions = CropImageOptions(
                    fixAspectRatio = true,
                    aspectRatioX = 2,
                    aspectRatioY = 3,
                    outputCompressFormat = Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 90,
                ),
            ),
        )
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(launchCrop) }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(launchCrop) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSaving) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = FormCardShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                LocalPoster(
                    path = posterPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormImageButton(
                    text = "相册选择",
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                FormImageButton(
                    text = "文件选择",
                    onClick = { fileLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                )
            }

            FormCard(title = "作品名称") {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showNameError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(text = "请输入作品名称") },
                    shape = FormFieldShape,
                    singleLine = true,
                    isError = showNameError,
                    supportingText = if (showNameError) {
                        { Text(text = "请输入作品名称") }
                    } else {
                        null
                    },
                )
            }

            FormCard(title = "作品分类") {
                if (categories.isEmpty()) {
                    FormEmptyText("暂无可用分类")
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categories.forEach { category ->
                            FormSelectionChip(
                                text = category.name,
                                selected = selectedCategoryId == category.id,
                                onClick = {
                                    selectedCategoryId = category.id
                                    showCategoryError = false
                                },
                            )
                        }
                    }
                }
                if (showCategoryError) {
                    Text(
                        text = "请选择作品分类",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            FormCard(title = "标签（可选）") {
                if (tags.isEmpty()) {
                    FormEmptyText("暂无标签，可直接保存")
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            val selected = tag.id in selectedTagIds
                            FormSelectionChip(
                                text = tag.name,
                                selected = selected,
                                onClick = {
                                    selectedTagIds = if (selected) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                },
                            )
                        }
                    }
                }
            }

            FormCard(title = "观看状态") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MovieStatus.entries.forEach { item ->
                        FormSelectionChip(
                            text = item.value,
                            selected = status == item.value,
                            onClick = {
                                val currentStatus = MovieStatus.entries.first {
                                    it.value == status
                                }
                                val currentEpisodeValue = currentEpisode.toIntOrNull() ?: 0
                                val resolvedEpisode = resolveCurrentEpisodeForStatusTransition(
                                    currentStatus = currentStatus,
                                    newStatus = item,
                                    currentEpisode = currentEpisodeValue,
                                    totalEpisode = totalEpisode.toIntOrNull(),
                                )
                                if (resolvedEpisode != currentEpisodeValue) {
                                    currentEpisode = resolvedEpisode.toString()
                                }
                                status = item.value
                            },
                        )
                    }
                }
            }

            FormCard(title = "当前集数") {
                OutlinedTextField(
                    value = currentEpisode,
                    onValueChange = { value -> currentEpisode = value.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = FormFieldShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }

            FormCard(title = "总集数（可选）") {
                OutlinedTextField(
                    value = totalEpisode,
                    onValueChange = { value -> totalEpisode = value.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = FormFieldShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }

            Button(
                onClick = {
                    showNameError = name.isBlank()
                    val selectedCategory = categories.firstOrNull {
                        it.id == selectedCategoryId
                    }
                    showCategoryError = selectedCategory == null
                    if (!showNameError && !showCategoryError) {
                        onSave(
                            MovieFormData(
                                name = name,
                                categoryName = requireNotNull(selectedCategory).name,
                                status = MovieStatus.entries.first { it.value == status },
                                currentEpisode = currentEpisode.toIntOrNull() ?: 0,
                                totalEpisode = totalEpisode.toIntOrNull(),
                                posterPath = posterPath,
                                categoryId = requireNotNull(selectedCategoryId),
                                tagIds = selectedTagIds.toSet(),
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isSaving,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = "保存中")
                } else {
                    Text(text = "保存", fontWeight = FontWeight.Medium)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun FormCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = FormCardShape,
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
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            content()
        }
    }
}

@Composable
private fun FormImageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(text = text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FormEmptyText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun FormSelectionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = text) },
        shape = FormChipShape,
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

