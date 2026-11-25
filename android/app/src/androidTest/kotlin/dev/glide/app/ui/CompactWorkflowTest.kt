package dev.glide.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.glide.app.ui.selection.SelectionScreen
import dev.glide.app.ui.selection.SelectionUiState
import dev.glide.core.domain.DeviceRole
import dev.glide.designsystem.GlideTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompactWorkflowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun validationStatusAndCancelDoNotOverlap() {
        assertSelectionProgress(SelectionUiState.Validating(currentCount = 2, totalCount = 4), "Preparing 2 of 4")
    }

    @Test
    fun stagingStatusAndCancelDoNotOverlap() {
        assertSelectionProgress(SelectionUiState.StagingSource("long_document.pdf", 2048), "long_document.pdf")
    }

    @Test
    fun homeRecentActivityOpensTheChosenSession() {
        var openedSession: String? = null
        compose.setContent {
            GlideTheme {
                HomeScreen(
                    onNavigateToSend = {},
                    onNavigateToReceive = {},
                    recentActivities = listOf(
                        RecentActivity(
                            sessionId = "session-42",
                            role = DeviceRole.Sender,
                            peerLabel = "Test PC",
                            startedAtMs = 0,
                            outcome = "Completed",
                            itemCount = 1,
                            totalBytes = 1024
                        )
                    ),
                    onOpenRecentActivity = { openedSession = it }
                )
            }
        }
        compose.onNodeWithText("Sent to Test PC").performClick()
        compose.runOnIdle { assertEquals("session-42", openedSession) }
    }

    private fun assertSelectionProgress(state: SelectionUiState, label: String) {
        compose.setContent {
            GlideTheme {
                SelectionScreen(onBack = {}, onProceedToSend = {}, initialState = state)
            }
        }
        val status = compose.onNodeWithText(label, substring = true).assertIsDisplayed().fetchSemanticsNode()
        val cancel = compose.onNodeWithContentDescription("Cancel staging").assertIsDisplayed()
        val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
        assertTrue(status.boundsInRoot.right <= cancelBounds.left)
        cancel.performClick()
        compose.onNodeWithContentDescription("Cancel staging").assertDoesNotExist()
    }
}
