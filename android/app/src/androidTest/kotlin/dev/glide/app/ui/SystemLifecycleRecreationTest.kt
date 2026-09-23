package dev.glide.app.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.glide.app.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System-level lifecycle and recreation UI tests using UIAutomator:
 *   - Device rotation / orientation change preserves screen route and state
 *   - Backgrounding (Home button) and foreground reentry preserves UI state
 *   - System hardware/gesture Back button navigates backstack predictably
 *
 * GLIDE-057: Complete emulator/system UI automation.
 */
@RunWith(AndroidJUnit4::class)
class SystemLifecycleRecreationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.setOrientationNatural()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.setOrientationNatural()
        device.waitForIdle()
    }

    @Test
    fun rotation_preservesHistoryScreenState() {
        composeTestRule.waitForIdle()

        // Navigate to History tab
        composeTestRule.onNodeWithContentDescription("Navigate to History tab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transfer history").assertIsDisplayed()

        // Rotate device to landscape
        device.setOrientationLeft()
        device.waitForIdle()
        composeTestRule.waitForIdle()

        // Verify still on History screen
        composeTestRule.onNodeWithText("Transfer history").assertIsDisplayed()

        // Rotate back to portrait
        device.setOrientationNatural()
        device.waitForIdle()
        composeTestRule.waitForIdle()

        // Verify still on History screen
        composeTestRule.onNodeWithText("Transfer history").assertIsDisplayed()
    }

    @Test
    fun systemBackButton_popsSelectionScreenBackToHome() {
        composeTestRule.waitForIdle()

        // Navigate to Send
        composeTestRule.onNodeWithText("Send files").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select files to send").assertIsDisplayed()

        // Press system back button
        device.pressBack()
        device.waitForIdle()
        composeTestRule.waitForIdle()

        // Verify returned to Home screen
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }

    @Test
    fun backgroundAndForegroundReentry_preservesAppState() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()

        // Press Home to background app
        device.pressHome()
        device.waitForIdle()

        // Bring app back to foreground via launcher intent
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
        device.waitForIdle()
        composeTestRule.waitForIdle()

        // Verify still on Home screen
        composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
    }
}
