package dev.glide.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glide.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI test verifying system Share Sheet ingress handling:
 *   - ACTION_SEND with content:// URI launches directly into Selection flow.
 *   - ACTION_SEND_MULTIPLE with multiple content:// URIs launches into Selection flow.
 *   - Standard launch without share action lands on Home screen.
 *
 * GLIDE-057: Complete emulator/system UI automation.
 */
@RunWith(AndroidJUnit4::class)
class ShareIngressInstrumentationTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Test
    fun standardLaunch_landsOnHomeScreen() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Send files").assertIsDisplayed()
            composeTestRule.onNodeWithText("Receive files").assertIsDisplayed()
        }
    }

    @Test
    fun actionSend_singleFile_routesToSelectionScreen() {
        val testUri = Uri.parse("content://dev.glide.app.testprovider/shared_photo.jpg")
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, testUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            composeTestRule.waitForIdle()
            // Should display Selection screen header
            composeTestRule.onNodeWithText("Select files to send").assertIsDisplayed()
        }
    }

    @Test
    fun actionSendMultiple_multipleFiles_routesToSelectionScreen() {
        val uri1 = Uri.parse("content://dev.glide.app.testprovider/doc1.pdf")
        val uri2 = Uri.parse("content://dev.glide.app.testprovider/doc2.pdf")
        val uris = arrayListOf(uri1, uri2)

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            composeTestRule.waitForIdle()
            // Should display Selection screen header
            composeTestRule.onNodeWithText("Select files to send").assertIsDisplayed()
        }
    }
}
