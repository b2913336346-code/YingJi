package com.yingji.app.presentation.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.mutableStateOf
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.presentation.category.CategoryTagActionState
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultSortSelectionDispatchesCallback() {
        var selectedSort: MovieSortOption? = null
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = emptyList(),
                    tags = emptyList(),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    sortOption = MovieSortOption.CREATED_DESC,
                    onSortOptionChange = { selectedSort = it },
                    onStatisticsClick = {},
                    onAddCategory = {},
                    onDeleteCategory = {},
                    onAddTag = {},
                    onDeleteTag = {},
                )
            }
        }

        composeRule
            .onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("查看统计")))
            .performScrollToNode(hasText("默认排序"))
        composeRule.onNodeWithText(MovieSortOption.CREATED_ASC.displayName).performClick()

        composeRule.runOnIdle {
            assertEquals(MovieSortOption.CREATED_ASC, selectedSort)
        }
    }

    @Test
    fun categoryAndTagWithSameDatabaseIdCanBeDisplayed() {
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = listOf(
                        Category(id = 1L, name = "测试分类", isSystem = false),
                    ),
                    tags = listOf(
                        Tag(id = 1L, name = "测试标签"),
                    ),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onStatisticsClick = {},
                    onAddCategory = {},
                    onDeleteCategory = {},
                    onAddTag = {},
                    onDeleteTag = {},
                )
            }
        }

        composeRule
            .onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("查看统计")))
            .performScrollToNode(hasText("测试标签"))
        composeRule.onNode(hasText("测试标签")).assertExists()
    }

    @Test
    fun countsAndIndependentCategoryAndTagClicksAreDisplayed() {
        var openedCategoryId: Long? = null
        var openedTagId: Long? = null
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = listOf(Category(id = 7L, name = "动漫测试", isSystem = true)),
                    tags = listOf(Tag(id = 9L, name = "校园测试")),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onStatisticsClick = {},
                    onAddCategory = {},
                    onDeleteCategory = {},
                    onAddTag = {},
                    onDeleteTag = {},
                    categoryMovieCounts = mapOf(7L to 12L),
                    tagMovieCounts = mapOf(9L to 3L),
                    onCategoryClick = { openedCategoryId = it },
                    onTagClick = { openedTagId = it },
                )
            }
        }

        composeRule
            .onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("查看统计")))
            .performScrollToNode(hasText("动漫测试"))
        composeRule.onNodeWithText("12 个作品").assertExists()
        composeRule.onNode(hasClickAction() and hasText("动漫测试")).performClick()
        composeRule
            .onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("查看统计")))
            .performScrollToNode(hasText("校园测试"))
        composeRule.onNodeWithText("3 个作品").assertExists()
        composeRule.onNode(hasClickAction() and hasText("校园测试")).performClick()

        composeRule.runOnIdle {
            assertEquals(7L, openedCategoryId)
            assertEquals(9L, openedTagId)
        }
    }

    @Test
    fun deleteUsesIndependentClickAreaAndRequiresConfirmation() {
        var openClicks = 0
        var deleteClicks = 0
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = listOf(Category(id = 2L, name = "待删除分类", isSystem = false)),
                    tags = emptyList(),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onStatisticsClick = {},
                    onAddCategory = {},
                    onDeleteCategory = { deleteClicks += 1 },
                    onAddTag = {},
                    onDeleteTag = {},
                    onCategoryClick = { openClicks += 1 },
                )
            }
        }

        composeRule
            .onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("查看统计")))
            .performScrollToNode(hasText("待删除分类"))
        composeRule.onNodeWithContentDescription("删除待删除分类").performClick()
        composeRule.onNodeWithText("删除分类").assertExists()
        composeRule.runOnIdle {
            assertEquals(0, openClicks)
            assertEquals(0, deleteClicks)
        }

        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(0, deleteClicks) }

        composeRule.onNodeWithContentDescription("删除待删除分类").performClick()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.runOnIdle {
            assertEquals(0, openClicks)
            assertEquals(1, deleteClicks)
        }
    }

    @Test
    fun addCategoryDialogValidatesInputAndSubmitsEnteredName() {
        var submittedName: String? = null
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = emptyList(),
                    tags = emptyList(),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onStatisticsClick = {},
                    onAddCategory = { submittedName = it },
                    onDeleteCategory = {},
                    onAddTag = {},
                    onDeleteTag = {},
                )
            }
        }

        composeRule.onAllNodesWithText("新增")[0].performClick()
        composeRule.onNodeWithText("新增自定义分类").assertExists()
        composeRule.onNodeWithText("保存").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("  新分类  ")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.runOnIdle { assertEquals("  新分类  ", submittedName) }
    }

    @Test
    fun themeStatisticsAndDialogCancelCallbacksRemainAvailable() {
        var selectedTheme: ThemeMode? = null
        var statisticsClicks = 0
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = emptyList(),
                    tags = emptyList(),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = { selectedTheme = it },
                    onStatisticsClick = { statisticsClicks += 1 },
                    onAddCategory = {},
                    onDeleteCategory = {},
                    onAddTag = {},
                    onDeleteTag = {},
                )
            }
        }

        composeRule.onNodeWithText("深色").performClick()
        composeRule.onNodeWithText("查看统计").performClick()
        composeRule.onAllNodesWithText("新增")[0].performClick()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.runOnIdle {
            assertEquals(ThemeMode.DARK, selectedTheme)
            assertEquals(1, statisticsClicks)
        }
        composeRule.onNodeWithText("新增自定义分类").assertDoesNotExist()
    }

    @Test
    fun renameCategoryPrefillsNameRejectsBlankAndKeepsIndependentClickArea() {
        val category = Category(id = 7L, name = "原分类", isSystem = false)
        var openClicks = 0
        var savedCategory: Category? = null
        var savedName: String? = null
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = listOf(category),
                    tags = emptyList(),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onStatisticsClick = {},
                    onAddCategory = {},
                    onDeleteCategory = {},
                    onAddTag = {},
                    onDeleteTag = {},
                    onCategoryClick = { openClicks += 1 },
                    onRenameCategory = { target, name ->
                        savedCategory = target
                        savedName = name
                    },
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("查看统计")))
            .performScrollToNode(hasText("原分类"))
        composeRule.onNodeWithContentDescription("编辑原分类").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("原分类")).assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("   ")
        composeRule.onNodeWithText("名称不能为空").assertExists()
        composeRule.onNodeWithText("保存").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("新分类")
        composeRule.onNodeWithText("名称不能为空").assertDoesNotExist()
        composeRule.onNodeWithText("保存").performClick()

        composeRule.runOnIdle {
            assertEquals(0, openClicks)
            assertEquals(category, savedCategory)
            assertEquals("新分类", savedName)
        }
    }

    @Test
    fun tagSaveErrorsStayInDialogAndSuccessfulRetryClosesIt() {
        val state = mutableStateOf(CategoryTagActionState())
        var submissions = 0
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = emptyList(),
                    tags = listOf(Tag(id = 9L, name = "原标签")),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onStatisticsClick = {},
                    onAddCategory = {},
                    onDeleteCategory = {},
                    onAddTag = {},
                    onDeleteTag = {},
                    actionState = state.value,
                    onActionResultConsumed = {
                        state.value = state.value.copy(saveSucceeded = false, message = null)
                    },
                    onRenameTag = { _, _ ->
                        submissions += 1
                        state.value = CategoryTagActionState(isSaving = true)
                    },
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("查看统计")))
            .performScrollToNode(hasText("原标签"))
        composeRule.onNodeWithContentDescription("编辑原标签").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("原标签")).assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("重复标签")
        composeRule.onNodeWithText("保存").performClick()
        composeRule.onNodeWithText("保存").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, submissions) }

        composeRule.runOnIdle { state.value = CategoryTagActionState(message = "名称已存在") }
        composeRule.onNodeWithText("编辑标签").assertExists()
        composeRule.onNodeWithText("名称已存在").assertExists()
        composeRule.onNode(hasSetTextAction() and hasText("重复标签")).assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("新标签")
        composeRule.onNodeWithText("名称已存在").assertDoesNotExist()
        composeRule.onNodeWithText("保存").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            state.value = CategoryTagActionState(message = "保存失败，请稍后重试")
        }
        composeRule.onNodeWithText("保存失败，请稍后重试").assertExists()
        composeRule.onNodeWithText("编辑标签").assertExists()
        composeRule.onNodeWithText("保存").performClick()
        composeRule.runOnIdle { state.value = CategoryTagActionState(saveSucceeded = true) }
        composeRule.onNodeWithText("编辑标签").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(3, submissions) }
    }

    @Test
    fun cancelRenameDoesNotWriteAndSystemCategoryHasNoEditEntry() {
        var writes = 0
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    categories = listOf(
                        Category(id = 1L, name = "电影", isSystem = true),
                        Category(id = 2L, name = "自定义", isSystem = false),
                    ),
                    tags = emptyList(),
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onStatisticsClick = {},
                    onAddCategory = {},
                    onDeleteCategory = {},
                    onAddTag = {},
                    onDeleteTag = {},
                    onRenameCategory = { _, _ -> writes += 1 },
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("查看统计")))
            .performScrollToNode(hasText("自定义"))
        composeRule.onNodeWithContentDescription("编辑电影").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("编辑自定义").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("取消的名称")
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithText("重命名分类").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, writes) }
    }
}
