package dev.glide.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark measuring frame timing, recomposition, and UI jank during navigation journeys (GLIDE-052).
 *
 * Evaluates performance targets defined in GLIDE_PERFORMANCE_PLAN:
 * - UI rendering: >= 95% frames within refresh budget (16.7ms for 60Hz).
 * - Zero sustained jank or frozen frames across navigation transitions.
 */
@RunWith(AndroidJUnit4::class)
class NavigationBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    companion object {
        const val TARGET_PACKAGE = "dev.glide.app"
        const val BENCHMARK_ITERATIONS = 5
        const val UI_TIMEOUT_MS = 5000L
    }

    @Test
    fun navigationJourney_withBaselineProfile() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
            iterations = BENCHMARK_ITERATIONS,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait()
            }
        ) {
            // Wait for interactive Home screen
            checkNotNull(device.wait(Until.findObject(By.text("Send files")), UI_TIMEOUT_MS)) {
                "Home screen did not render"
            }

            // Navigate to History (explicit wait + fail-fast selector)
            val historyNav = checkNotNull(
                device.wait(Until.findObject(By.res("nav_history")), UI_TIMEOUT_MS)
                    ?: device.wait(Until.findObject(By.descContains("Navigate to History")), 1000L)
            ) { "History navigation target not found" }
            historyNav.click()
            checkNotNull(device.wait(Until.findObject(By.text("Transfer history")), UI_TIMEOUT_MS)) {
                "History screen did not render after clicking History navigation"
            }

            // Navigate to Settings (explicit wait + fail-fast selector)
            val settingsNav = checkNotNull(
                device.wait(Until.findObject(By.res("nav_settings")), UI_TIMEOUT_MS)
                    ?: device.wait(Until.findObject(By.descContains("Navigate to Settings")), 1000L)
            ) { "Settings navigation target not found" }
            settingsNav.click()
            checkNotNull(device.wait(Until.findObject(By.desc("Navigate back from settings")), UI_TIMEOUT_MS)) {
                "Settings screen did not render after clicking Settings navigation"
            }

            // Return to Home
            device.pressBack()
            checkNotNull(device.wait(Until.findObject(By.text("Send files")), UI_TIMEOUT_MS)) {
                "Failed to return to Home screen after back navigation"
            }
            device.waitForIdle()
        }
    }
}
