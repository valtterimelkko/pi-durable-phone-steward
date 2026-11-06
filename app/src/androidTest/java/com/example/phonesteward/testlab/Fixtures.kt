package com.example.phonesteward.testlab

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasSetTextAction

/** Layout helpers shared by the Test Lab tests. */
object Layout {

    private val TABS = listOf("Chat", "Proposals", "Rules", "Status")

    /** The top edge, in root pixels, of the highest bottom-navigation label. */
    fun bottomBarTop(rule: ComposeTestRule): Float =
        TABS.minOf { label ->
            rule.onAllNodesWithText(label)[0].bounds().top
        }

    fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    /** The Chat message field: the one node on screen that takes typed text. */
    fun chatInput(rule: ComposeTestRule): SemanticsNodeInteraction = rule.onNode(hasSetTextAction())
}
