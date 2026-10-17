package com.yingji.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    data object Home : Screen("home", "首页", Icons.Filled.Home)
    data object WantToWatch : Screen("want_to_watch", "想看", Icons.Filled.Star)
    data object Watching : Screen("watching", "观看中", Icons.Filled.PlayArrow)
    data object Finished : Screen("finished", "已看完", Icons.Filled.CheckCircle)
    data object Mine : Screen("mine", "我的", Icons.Filled.Person)
    data object AddMovie : Screen("movie/add", "新增作品", Icons.Filled.Add)
    data object MovieDetail : Screen("movie/{movieId}", "作品详情", Icons.Filled.Movie) {
        fun createRoute(movieId: Long): String = "movie/$movieId"
    }
    data object EditMovie : Screen("movie/{movieId}/edit", "编辑作品", Icons.Filled.Edit) {
        fun createRoute(movieId: Long): String = "movie/$movieId/edit"
    }
    data object MovieHistory : Screen("movie/{movieId}/history", "观看历史", Icons.Filled.History) {
        fun createRoute(movieId: Long): String = "movie/$movieId/history"
    }
    data object Search : Screen("search", "搜索", Icons.Filled.Search)
    data object Statistics : Screen("statistics", "统计", Icons.Filled.BarChart)
    data object CategoryMovies : Screen("category/{categoryId}", "分类作品", Icons.Filled.Category) {
        fun createRoute(categoryId: Long): String = "category/$categoryId"
    }
    data object TagMovies : Screen("tag/{tagId}", "标签作品", Icons.AutoMirrored.Filled.Label) {
        fun createRoute(tagId: Long): String = "tag/$tagId"
    }

    companion object {
        val bottomNavigationItems = listOf(Home, WantToWatch, Watching, Finished, Mine)
    }
}
