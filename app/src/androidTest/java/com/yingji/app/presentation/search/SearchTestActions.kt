package com.yingji.app.presentation.search

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction

// Chips are inside horizontal LazyRows. Scroll their stable vertical parent explicitly;
// performScrollTo() alone would only scroll the nearest (horizontal) ancestor.
internal fun ComposeContentTestRule.scrollToSearchFilter(matcher: SemanticsMatcher) =
    onNode(matcher and hasAnyAncestor(hasTestTag("search_filters"))).also { target ->
        val container = onNodeWithTag("search_filters")
        val viewport = container.fetchSemanticsNode().boundsInRoot
        val node = target.fetchSemanticsNode()
        val delta = node.positionInRoot.y - viewport.top
        container.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, delta) }
        target.assertIsDisplayed()
    }
