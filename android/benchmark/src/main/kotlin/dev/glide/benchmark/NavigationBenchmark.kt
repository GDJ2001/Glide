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
            // Wait for Home screen to settle
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)

            // Navigate to History if visible
            val historyButton = device.findObject(By.descContains("History").clazz("android.widget.Button"))
                ?: device.findObject(By.textContains("History"))
            historyButton?.click()
            device.waitForIdle()

            // Navigate to Settings if visible
            val settingsButton = device.findObject(By.descContains("Settings").clazz("android.widget.Button"))
                ?: device.findObject(By.textContains("Settings"))
            settingsButton?.click()
            device.waitForIdle()

            // Return to Home
            device.pressBack()
            device.waitForIdle()
        }
    }
}
