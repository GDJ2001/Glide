package dev.glide.app.ui

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.glide.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Run on a compact phone at normal and 200% system font scale. */
class VisualRegressionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun launch(state: String, theme: String = "light"): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .putExtra("debug_state", state)
                .putExtra("debug_theme", theme)
        )
    }

    private fun assertSingleLine(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(text)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Text should stay readable on one line: $text", 1, layouts.single().lineCount)
    }

    @Test
    fun darkPreferenceUsesLightSystemBarIcons() {
        launch("theme_dark", "dark").use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Send files").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertFalse(controller.isAppearanceLightStatusBars)
                assertFalse(controller.isAppearanceLightNavigationBars)
            }
        }
    }

    @Test
    fun lightThemeScannerKeepsLightSystemBarIconsAfterRecreation() {
        launch("discovery_peers", "light").use { scenario ->
            compose.onNodeWithText("Scan QR code or paste invite").performScrollTo().performClick()
            repeat(2) { attempt ->
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Scan QR code").fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                scenario.onActivity { activity ->
                    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    assertFalse(controller.isAppearanceLightStatusBars)
                    assertFalse(controller.isAppearanceLightNavigationBars)
                }
                if (attempt == 0) scenario.recreate()
            }
        }
    }

    @Test
    fun batchCounterRemainsReadableBesidePeerName() {
        launch("transfer_active_multi").use { assertSingleLine("File 2 of 4") }
    }

    @Test
    fun historySummaryAndCloseDoNotCollapseIntoVerticalLetters() {
        launch("history_detail_available").use {
            assertSingleLine("49.6 MB")
            assertSingleLine("Close")
        }
    }

    @Test
    fun selectionActionsKeepTheirCompleteLabels() {
        launch("share_test_multiple").use {
            assertSingleLine("Add photos")
            assertSingleLine("Add files")
            assertSingleLine("Add folder")
        }
    }
}
