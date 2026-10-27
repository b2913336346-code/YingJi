package com.yingji.app.presentation.movie

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RatingAndPosterScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val movie = Movie(id = 1, name = "评分作品", posterPath = "/missing-poster.jpg", type = "电影",
        status = MovieStatus.WANT_TO_WATCH.value, createTime = 1, rating = 8.5)
    private var submitted: MovieFormData? = null
    private var saves = 0
    private var back = 0

    @Test fun ratingValidationRejectsInvalidValuesWithoutSaving() {
        showForm()
        listOf("-0.5", "10.5", "8.25", "NaN", "Infinity").forEach { input ->
            composeRule.onNodeWithTag("movie_rating_input").performScrollTo().performTextReplacement(input)
            save()
            composeRule.onNodeWithText("请输入 0–10 分，按 0.5 分递增").assertExists()
            composeRule.runOnIdle { assertEquals(0, saves); assertNull(submitted) }
        }
    }

    @Test fun zeroHalfPointsAndTenAreSubmittedWithoutLosingSelectedCategory() {
        showForm()
        listOf("0" to 0.0, "8.5" to 8.5, "10" to 10.0).forEach { (input, expected) ->
            composeRule.onNodeWithTag("movie_rating_input").performScrollTo().performTextReplacement(input)
            save()
            composeRule.runOnIdle { assertEquals(expected, submitted?.rating); assertEquals(1L, submitted?.categoryId) }
        }
        composeRule.runOnIdle { assertEquals(3, saves) }
    }

    @Test fun clearingRatingSavesNullRatherThanZero() {
        showForm()
        composeRule.onNodeWithText("清除评分").performScrollTo().performClick()
        save()
        composeRule.runOnIdle { assertEquals(1, saves); assertNull(submitted?.rating) }
    }

    @Test fun removingPosterIsSubmittedOnSaveAndCanBeCancelled() {
        showForm()
        composeRule.onNodeWithText("移除海报").performScrollTo().performClick()
        composeRule.onNodeWithText("无海报").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.runOnIdle { assertEquals(1, back); assertEquals(0, saves); assertEquals("/missing-poster.jpg", movie.posterPath) }
        save()
        composeRule.runOnIdle { assertNull(submitted?.posterPath); assertEquals(8.5, submitted?.rating) }
    }

    @Test fun detailDisplaysRatingAndUnratedStateInBothThemes() {
        val current = mutableStateOf(movie)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        composeRule.setContent {
            YingJiTheme(theme.value) {
                MovieDetailScreen(current.value, emptyList(), emptyList(), emptyList(), emptyList(),
                    onBack = {}, onHistory = {}, onEdit = {}, onDelete = {}, onStatusChange = {},
                    onCategoryChange = { _, _ -> }, onTagChange = { _, _ -> })
            }
        }
        composeRule.onNodeWithText("评分：8.5 / 10").performScrollTo().assertIsDisplayed()
        capture("detail-light.png")
        composeRule.runOnIdle { current.value = movie.copy(rating = null); theme.value = ThemeMode.DARK }
        composeRule.onNodeWithText("评分：未评分").performScrollTo().assertIsDisplayed()
        capture("detail-dark.png")
    }

    @Test fun formRatingAndRemovalUseExistingFormInDarkTheme() {
        showForm(ThemeMode.DARK)
        composeRule.onNodeWithTag("movie_rating_input").performScrollTo().assertIsDisplayed()
        capture("form-dark.png")
    }

    private fun showForm(theme: ThemeMode = ThemeMode.LIGHT) {
        composeRule.setContent {
            YingJiTheme(theme) {
                MovieFormScreen("编辑作品", movie, listOf(Category(1, "电影", true)), emptyList(),
                    initialCategoryId = 1, onBack = { back++ },
                    onSave = { submitted = it; saves++ })
            }
        }
    }
    private fun save() = composeRule.onNodeWithText("保存").performScrollTo().performClick()
    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "b3-ui-screenshots").apply { mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
