package dev.glide.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark measuring cold and warm startup latencies for Glide under Milestone M9 (GLIDE-052).
 *
 * Evaluates performance targets defined in GLIDE_PERFORMANCE_PLAN:
 * - Cold startup to useful Home: p95 <= 1.5s midrange; <= 2.5s low-end.
 * - Warm startup: p95 <= 700ms midrange.
 *
 * Compares unoptimized JIT execution (CompilationMode.None) against Baseline Profile-accelerated
 * AOT execution (CompilationMode.Partial with Baseline Profile).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    companion object {
        const val TARGET_PACKAGE = "dev.glide.app"
        const val BENCHMARK_ITERATIONS = 5
    }

    @Test
    fun coldStartup_withoutBaselineProfile() = measureStartup(
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD
    )

    @Test
    fun coldStartup_withBaselineProfile() = measureStartup(
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.COLD
    )

    @Test
    fun warmStartup_withBaselineProfile() = measureStartup(
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.WARM
    )

    private fun measureStartup(
        compilationMode: CompilationMode,
        startupMode: StartupMode
    ) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            iterations = BENCHMARK_ITERATIONS,
            startupMode = startupMode,
            setupBlock = {
                pressHome()
            }
        ) {
            startActivityAndWait()
        }
    }
}
