package dev.glide.benchmark

import android.content.Intent
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
 * Macrobenchmark measuring frame timing during active simulated transfer UI rendering,
 * throttled progress updates (<= 5 Hz), integrity verification, saving, and completion (GLIDE-052 / R3).
 */
@RunWith(AndroidJUnit4::class)
class TransferDisplayBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    companion object {
        const val TARGET_PACKAGE = "dev.glide.app"
        const val TARGET_ACTIVITY = "dev.glide.app.MainActivity"
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
                checkNotNull(device.wait(Until.findObject(By.text("Send files")), UI_TIMEOUT_MS)) {
                    "Home screen did not settle during setupBlock"
                }
                pressHome()
            }
        ) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(TARGET_PACKAGE, TARGET_ACTIVITY)
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("benchmark_scenario", "simulated_transfer_journey")
            }
            startActivityAndWait(intent)

            // 1. Active transferring card with throttled progress updates
            checkNotNull(
                device.wait(Until.findObject(By.res("transfer_progress_card")), UI_TIMEOUT_MS)
                    ?: device.wait(Until.findObject(By.textContains("simulated_transfer_benchmark.bin")), 1000L)
            ) { "TransferProgressCard did not render for simulated_transfer_journey" }
            checkNotNull(device.wait(Until.findObject(By.textContains("Simulated peer")), UI_TIMEOUT_MS)) {
                "Simulated peer label did not render on TransferProgressCard"
            }

            // 2. Integrity verification state
            checkNotNull(
                device.wait(Until.findObject(By.text("Verifying file integrity")), UI_TIMEOUT_MS)
            ) { "Verifying file integrity state did not render" }

            // 3. Saving verified files state
            checkNotNull(
                device.wait(Until.findObject(By.textContains("Saving verified files")), UI_TIMEOUT_MS)
            ) { "Saving verified files state did not render" }

            // 4. Terminal Completed + saved status
            checkNotNull(
                device.wait(Until.findObject(By.text("Transfer complete")), UI_TIMEOUT_MS)
            ) { "Transfer complete card did not render" }
            checkNotNull(
                device.wait(Until.findObject(By.textContains("Saved Download/Glide (simulated)")), UI_TIMEOUT_MS)
            ) { "Simulated saved destination confirmation did not render" }

            device.waitForIdle()
        }
    }
}
