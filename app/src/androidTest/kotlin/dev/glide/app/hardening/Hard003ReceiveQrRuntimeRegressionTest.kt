package dev.glide.app.hardening

import androidx.compose.ui.test.assertDoesNotExist
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
 * Production-path regression for HARD-003/F-02.
 *
 * The Receive flow must never publish the historical placeholder endpoint as if it came from a live
 * LAN listener. A repaired implementation should show the endpoint/fingerprint minted by the actual
 * bound listener/runtime (or a not-ready/error state) instead.
 */
@RunWith(AndroidJUnit4::class)
class Hard003ReceiveQrRuntimeRegressionTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun receiveQr_mustNotExposeHistoricalPlaceholderEndpoint() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Receive files").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Ready to receive").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Show QR code for local network pairing").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Receive QR code bottom sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Local address: 192.168.1.50:4242").assertDoesNotExist()
    }
}
