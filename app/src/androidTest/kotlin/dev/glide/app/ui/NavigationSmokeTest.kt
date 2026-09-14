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
 * Instrumented UI test verifying top-level navigation and primary workflows:
 *   - Home -> History -> Home
 *   - Home -> Settings -> Home
 *   - Home -> Send (Selection) -> Back -> Home
 *   - Home -> Receive (ReceiveReadiness) -> Stop -> Home
 *
 * Runs on live Android emulator / physical device.
 * GLIDE-057: Complete emulator/system UI automation.
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
        composeTestRule.onNodeWithText("Fast, private, local device sharing").assertIsDisplayed()
    }

    @Test
    fun navigateToHistory_andReturnToHome() {
        composeTestRule.waitForIdle()

        // Tap History tab
        composeTestRule.onNodeWithContentDescription("Navigate to History tab").performClick()
        composeTestRule.waitForIdle()

        // Verify History screen title
        composeTestRule.onNodeWithText("Transfer history").assertIsDisplayed()

        // Tap Home tab
        composeTestRule.onNodeWithContentDescription("Navigate to Home tab").performClick()
        composeTestRule.waitForIdle()

        // Verify back on Home
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }

    @Test
    fun navigateToSettings_andReturnToHome() {
        composeTestRule.waitForIdle()

        // Tap Settings tab
        composeTestRule.onNodeWithContentDescription("Navigate to Settings tab").performClick()
        composeTestRule.waitForIdle()

        // Verify Settings back button / screen is displayed
        composeTestRule.onNodeWithContentDescription("Navigate back from settings").assertIsDisplayed()

        // Tap Home tab
        composeTestRule.onNodeWithContentDescription("Navigate to Home tab").performClick()
        composeTestRule.waitForIdle()

        // Verify back on Home
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }

    @Test
    fun navigateToSend_opensSelectionScreen_andBackReturnsHome() {
        composeTestRule.waitForIdle()

        // Click Send files button
        composeTestRule.onNodeWithText("Send files").performClick()
        composeTestRule.waitForIdle()

        // Verify on Selection screen
        composeTestRule.onNodeWithText("Select files to send").assertIsDisplayed()

        // Click Back button
        composeTestRule.onNodeWithContentDescription("Navigate back").performClick()
        composeTestRule.waitForIdle()

        // Verify back on Home
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }

    @Test
    fun navigateToReceive_opensReceiveReadinessScreen_andStopReturnsHome() {
        composeTestRule.waitForIdle()

        // Click Receive files button
        composeTestRule.onNodeWithText("Receive files").performClick()
        composeTestRule.waitForIdle()

        // Verify on Receive readiness screen
        composeTestRule.onNodeWithText("Ready to receive").assertIsDisplayed()

        // Click Stop receiving back button
        composeTestRule.onNodeWithContentDescription("Stop receiving and return to Home").performClick()
        composeTestRule.waitForIdle()

        // Verify back on Home
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }
}
