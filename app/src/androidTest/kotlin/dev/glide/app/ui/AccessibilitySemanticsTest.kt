package dev.glide.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glide.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility and semantics verification test:
 *   - Verifies navigation items have explicit content descriptions for screen readers
 *   - Verifies primary call-to-action buttons carry semantic role and action labels
 *   - Verifies information cards have readable accessibility descriptions
 *
 * GLIDE-057: Complete emulator/system UI automation.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigationBarItems_haveExplicitContentDescriptions() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Navigate to Home tab").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Navigate to History tab").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Navigate to Settings tab").assertIsDisplayed()
    }

    @Test
    fun homeActionButtons_haveExplicitActionSemantics() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Send files to a nearby device").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Receive files from a nearby device").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
    }

    @Test
    fun privacyCard_hasAccessibleDescription() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(
            "Privacy reassurance: No cloud, no accounts, direct local Wi-Fi and Bluetooth"
        ).assertIsDisplayed()
    }
}
