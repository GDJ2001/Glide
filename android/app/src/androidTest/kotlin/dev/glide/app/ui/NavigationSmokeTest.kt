package dev.glide.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glide.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI test verifying top-level navigation and primary workflows.
 * Runs on live Android device (CPH2371, Android 13).
 *
 * Note: navigateToReceive test is excluded — the receive flow requires runtime
 * Bluetooth/location permissions that trigger a system dialog, blocking Compose
 * hierarchy access. Covered by ReceiveReadinessViewModel unit tests instead.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_displaysSendAndReceiveActions() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Receive files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Paired devices").assertIsDisplayed()
    }

    @Test
    fun navigateToHistory_andReturnToHome() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Navigate to History tab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transfer history").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Navigate to Home tab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }

    @Test
    fun navigateToSettings_andReturnToHome() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Navigate to Settings tab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Navigate back from settings").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Navigate to Home tab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }

    @Test
    fun navigateToSend_opensSelectionScreen_andBackReturnsHome() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Send files").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select files to send").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Navigate back").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }
}
