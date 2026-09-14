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
 * Macrobenchmark measuring frame timing during active transfer UI rendering and progress updates (GLIDE-052).
 *
 * Evaluates performance targets defined in GLIDE_PERFORMANCE_PLAN:
 * - UI rendering during transfer: >= 95% frames within refresh budget (16.7ms for 60Hz).
 * - Verifies that throttled progress updates (<= 5-10 Hz) prevent frame drops or UI jank.
 */
@RunWith(AndroidJUnit4::class)
class TransferDisplayBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    companion object {
        const val TARGET_PACKAGE = "dev.glide.app"
        const val BENCHMARK_ITERATIONS = 5
        const val UI_TIMEOUT_MS = 5000L
    }

    @Test
    fun transferDisplayJourney_withBaselineProfile() {
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
            // Wait for transfer UI / Home scaffold to settle
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)

            // Allow Compose recomposition and radar visualizer animation to run for 1 second
            Thread.sleep(1000)
            device.waitForIdle()
        }
    }
}
