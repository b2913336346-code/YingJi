package com.yingji.app.presentation.movie

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MovieFormScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addRequiresCategoryAndSupportsMultipleTags() {
        var submitted: MovieFormData? = null
        var saving by mutableStateOf(false)
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                MovieFormScreen(
                    title = "新增作品",
                    categories = listOf(Category(1L, "动漫分类", true)),
                    tags = listOf(Tag(2L, "热血标签"), Tag(3L, "战斗标签")),
                    isSaving = saving,
                    onBack = {},
                    onSave = {
                        submitted = it
                        saving = true
                    },
                )
            }
        }

        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("测试作品")
        composeRule.onAllNodesWithText("作品类型").assertCountEquals(0)
        composeRule.onNodeWithText("保存").performScrollTo().performClick()
        composeRule.onNodeWithText("请选择作品分类").assertExists()
        composeRule.runOnIdle { assertNull(submitted) }

        composeRule.onNodeWithText("动漫分类").performScrollTo().performClick()
        composeRule.onNodeWithText("热血标签").performScrollTo().performClick()
        composeRule.onNodeWithText("战斗标签").performScrollTo().performClick()
        composeRule.onNodeWithText("保存").performScrollTo().performClick()
        composeRule.onNodeWithText("保存中").assertExists()

        composeRule.runOnIdle {
            assertEquals(1L, submitted?.categoryId)
            assertEquals("动漫分类", submitted?.categoryName)
            assertEquals(setOf(2L, 3L), submitted?.tagIds)
        }
    }

    @Test
    fun editLoadsExistingRelationsAndCancelDoesNotSave() {
        var saved: MovieFormData? = null
        var backClicks = 0
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                MovieFormScreen(
                    title = "编辑作品",
                    movie = Movie(
                        id = 8L,
                        name = "已有作品",
                        type = "动漫",
                        status = "观看中",
                        createTime = 1L,
                    ),
                    categories = listOf(Category(1L, "动漫分类", true)),
                    tags = listOf(Tag(2L, "热血标签")),
                    initialCategoryId = 1L,
                    initialTagIds = setOf(2L),
                    onBack = { backClicks += 1 },
                    onSave = { saved = it },
                )
            }
        }

        composeRule.onNodeWithText("动漫分类").performScrollTo().assertIsSelected()
        composeRule.onAllNodesWithText("作品类型").assertCountEquals(0)
        composeRule.onNodeWithText("热血标签").performScrollTo().assertIsSelected()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.runOnIdle {
            assertEquals(1, backClicks)
            assertNull(saved)
        }
    }
}
