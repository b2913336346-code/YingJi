package com.yingji.app.presentation.movie

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yingji.app.domain.model.MovieSortOption

@Composable
fun MovieSortMenu(
    selected: MovieSortOption,
    onSelected: (MovieSortOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "作品排序" },
        ) {
            Text("排序：${selected.displayName}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MovieSortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("未记录观看时间的作品排在最后") },
                enabled = false,
                onClick = {},
            )
        }
    }
}
