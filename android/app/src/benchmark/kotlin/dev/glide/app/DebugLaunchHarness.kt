package dev.glide.app

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.glide.app.navigation.GlideRoute
import dev.glide.app.service.TransferRuntime
import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.DeviceRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Benchmark implementation of [DebugLaunchHarness].
 *
 * No-op for all normal launches (StartupBenchmark, NavigationBenchmark) so baseline
 * startup/navigation matches production release. Only activates when explicitly requested
 * via `benchmark_scenario = "simulated_transfer_journey"` to drive a deterministic,
 * explicitly labeled simulated transfer display journey (<= 5 Hz updates) for FrameTimingMetric.
 */
object DebugLaunchHarness {

    const val EXTRA_BENCHMARK_SCENARIO = "benchmark_scenario"
    const val SCENARIO_SIMULATED_TRANSFER_JOURNEY = "simulated_transfer_journey"
    private const val SIMULATED_SESSION_ID = "simulated-transfer-session"
    private const val SIMULATED_PEER_NAME = "Simulated peer"
    private const val SIMULATED_FILE_NAME = "simulated_transfer_benchmark.bin"
    private const val SIMULATED_TOTAL_BYTES = 100_000_000L

    @Volatile
    private var simulationJob: Job? = null

    fun prepareLaunchOverrides(
        activity: Activity,
        intent: Intent?,
        transferRuntime: TransferRuntime
    ): LaunchOverrides {
        if (intent?.getStringExtra(EXTRA_BENCHMARK_SCENARIO) != SCENARIO_SIMULATED_TRANSFER_JOURNEY) {
            return LaunchOverrides()
        }
        return startSimulatedTransferJourney(activity, transferRuntime)
    }

    fun onNewIntent(
        activity: Activity,
        intent: Intent?,
        transferRuntime: TransferRuntime
    ): GlideRoute? {
        if (intent?.getStringExtra(EXTRA_BENCHMARK_SCENARIO) != SCENARIO_SIMULATED_TRANSFER_JOURNEY) {
            return null
        }
        startSimulatedTransferJourney(activity, transferRuntime)
        return GlideRoute.Transfer(SIMULATED_SESSION_ID)
    }

    private fun startSimulatedTransferJourney(
        activity: Activity,
        transferRuntime: TransferRuntime
    ): LaunchOverrides {
        simulationJob?.cancel()
        transferRuntime.setAutoSaveStatusForTesting(ExportUiStatus.Idle)

        val initialState = TransferUiState.Transferring(
            sessionId = SIMULATED_SESSION_ID,
            role = DeviceRole.Receiver,
            peerName = SIMULATED_PEER_NAME,
            itemName = SIMULATED_FILE_NAME,
            transferredBytes = 10_000_000L,
            totalBytes = SIMULATED_TOTAL_BYTES,
            progressFraction = 0.10f,
            speedBytesPerSec = 42_000_000L
        )
        transferRuntime.setTransferStateForTesting(initialState)

        val owner = activity as? LifecycleOwner
        if (owner != null) {
            simulationJob = owner.lifecycleScope.launch(Dispatchers.Main.immediate) {
                val steps = listOf(
                    25_000_000L to 0.25f,
                    40_000_000L to 0.40f,
                    55_000_000L to 0.55f,
                    70_000_000L to 0.70f,
                    85_000_000L to 0.85f,
                    98_000_000L to 0.98f
                )
                for ((bytes, fraction) in steps) {
                    delay(250L) // 4 Hz <= 5 Hz UI update budget
                    transferRuntime.setTransferStateForTesting(
                        initialState.copy(
                            transferredBytes = bytes,
                            progressFraction = fraction
                        )
                    )
                }

                delay(250L)
                transferRuntime.setTransferStateForTesting(
                    TransferUiState.Verifying(
                        sessionId = SIMULATED_SESSION_ID,
                        role = DeviceRole.Receiver,
                        peerName = SIMULATED_PEER_NAME,
                        itemName = SIMULATED_FILE_NAME,
                        totalBytes = SIMULATED_TOTAL_BYTES
                    )
                )

                delay(750L)
                transferRuntime.setAutoSaveStatusForTesting(ExportUiStatus.Exporting)
                transferRuntime.setTransferStateForTesting(
                    TransferUiState.Completed(
                        sessionId = SIMULATED_SESSION_ID,
                        role = DeviceRole.Receiver,
                        peerName = SIMULATED_PEER_NAME,
                        totalBytes = SIMULATED_TOTAL_BYTES,
                        itemName = SIMULATED_FILE_NAME,
                        committedFileKey = "simulated-key"
                    )
                )

                delay(750L)
                transferRuntime.setAutoSaveStatusForTesting(
                    ExportUiStatus.Success("Download/Glide (simulated)")
                )
            }
        }

        return LaunchOverrides(
            debugRoute = GlideRoute.Transfer(SIMULATED_SESSION_ID),
            debugUiState = initialState,
            debugAutoSaveStatus = ExportUiStatus.Idle
        )
    }
}
