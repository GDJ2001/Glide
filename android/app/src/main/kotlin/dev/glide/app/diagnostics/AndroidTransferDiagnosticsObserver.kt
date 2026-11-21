package dev.glide.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.SessionState
import dev.glide.core.diagnostics.R3AndroidConditions
import dev.glide.core.diagnostics.R3Boolean
import dev.glide.core.diagnostics.R3ByteCounts
import dev.glide.core.diagnostics.R3CancellationMetrics
import dev.glide.core.diagnostics.R3DatasetInfo
import dev.glide.core.diagnostics.R3DiagnosticConstants
import dev.glide.core.diagnostics.R3DiagnosticSample
import dev.glide.core.diagnostics.R3DiagnosticSanitizer
import dev.glide.core.diagnostics.R3DiskActivity
import dev.glide.core.diagnostics.R3Double
import dev.glide.core.diagnostics.R3HardwareInfo
import dev.glide.core.diagnostics.R3Int
import dev.glide.core.diagnostics.R3IoOptions
import dev.glide.core.diagnostics.R3Long
import dev.glide.core.diagnostics.R3NetworkLinkInfo
import dev.glide.core.diagnostics.R3OutcomeInfo
import dev.glide.core.diagnostics.R3PhaseDurationsMs
import dev.glide.core.diagnostics.R3PlatformInfo
import dev.glide.core.diagnostics.R3ResourceUsage
import dev.glide.core.diagnostics.R3RuntimeInfo
import dev.glide.core.diagnostics.R3StorageInfo
import dev.glide.core.diagnostics.R3TestConfiguration
import dev.glide.core.diagnostics.R3ThroughputCalculator
import dev.glide.core.diagnostics.R3ThroughputInfo
import dev.glide.core.diagnostics.R3TransferDiagnosticReport
import dev.glide.core.domain.DeviceRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.NetworkInterface
import java.time.Instant
import java.util.UUID
import kotlin.math.max

/**
 * Passive, bounded R3 diagnostic observer for Android transfers (`glide-r3-diagnostic-v1`).
 *
 * Observes [SessionState] emissions, auto-save timing, cancellation timestamps, and 1 Hz
 * runtime/thermal/battery samples without modifying [dev.glide.core.coordinator.TransferCoordinator],
 * [dev.glide.core.coordinator.TransferReducer], or [dev.glide.core.stream.StreamHasher].
 */
class AndroidTransferDiagnosticsObserver(
    private val context: Context?,
    private val diagnosticsDir: File = defaultDiagnosticsDir(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val samplerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nanoClock: () -> Long = { System.nanoTime() },
    private val utcNow: () -> String = { Instant.now().toString() },
    private val systemSampler: SystemMetricsSampler? = null,
    private val maxLogFileBytes: Long = R3DiagnosticConstants.MAX_LOG_FILE_BYTES,
    private val maxLogFiles: Int = R3DiagnosticConstants.MAX_LOG_FILES,
    private val maxAttemptFiles: Int = 20
) {
    interface SystemMetricsSampler {
        fun captureSnapshot(): SystemMetricsSnapshot
    }

    data class SystemMetricsSnapshot(
        val managedHeapMiB: Double? = null,
        val nativeHeapMiB: Double? = null,
        val gcCount: Int? = null,
        val gcBlockingTimeMs: Double? = null,
        val bytesAllocatedTotal: Long? = null,
        val cpuPercent: Double? = null,
        val processReadBytes: Long? = null,
        val processWriteBytes: Long? = null,
        val thermalStatus: String = R3DiagnosticConstants.UNAVAILABLE,
        val thermalHeadroom: Double? = null,
        val batteryLevelPercent: Int? = null,
        val batteryTemperatureCelsius: Double? = null,
        val isCharging: Boolean? = null,
        val batterySaverEnabled: Boolean? = null
    )

    private val lock = Any()
    private val writeMutex = Mutex()

    private val _latestReport = MutableStateFlow<R3TransferDiagnosticReport?>(null)
    val latestReport: StateFlow<R3TransferDiagnosticReport?> = _latestReport.asStateFlow()

    private var activeAttempt: ActiveAttempt? = null
    private var samplerJob: Job? = null

    fun onSessionStarted(
        scope: CoroutineScope,
        sessionId: String,
        role: DeviceRole,
        transportMode: String = "lan-tls13",
        pairingMode: String = "unavailable"
    ) {
        val snapshot = captureSystemSnapshot()
        val nowNs = nanoClock()
        val attempt = ActiveAttempt(
            attemptId = UUID.randomUUID().toString().replace("-", ""),
            sessionId = sessionId,
            role = role,
            transportMode = transportMode,
            pairingMode = pairingMode,
            startedUtc = utcNow(),
            startedNs = nowNs,
            baselineSnapshot = snapshot,
            stagingFreeBytesBefore = queryFreeBytes(context?.filesDir),
            destinationFreeBytesBefore = queryFreeBytes(publicDownloadsDir())
        )
        synchronized(lock) {
            samplerJob?.cancel()
            activeAttempt = attempt
        }

        samplerJob = scope.launch(samplerDispatcher) {
            while (isActive) {
                delay(1000L)
                val current = synchronized(lock) { activeAttempt }
                if (current == null || current.attemptId != attempt.attemptId || current.terminalEmitted) {
                    break
                }
                val sample = recordOneSecondSample(current)
                if (sample != null) {
                    launch(ioDispatcher) {
                        appendJsonlLine(sample.toJsonLine())
                    }
                }
            }
        }
    }

    fun onSessionState(sessionState: SessionState) {
        val nowNs = nanoClock()
        synchronized(lock) {
            val attempt = activeAttempt ?: return
            if (attempt.sessionId != sessionState.sessionId.value) return
            attempt.lastSessionState = sessionState
            attempt.latestTotalBytesTransferred = computeTransferredBytes(sessionState)
            attempt.latestDeclaredBytes = sessionState.batch?.totalBytes ?: attempt.latestDeclaredBytes
            attempt.latestItemCount = sessionState.batch?.items?.size ?: attempt.latestItemCount

            val phase = sessionState.phase
            if (phase != attempt.currentPhase) {
                attempt.accumulatePhaseTicks(attempt.currentPhase, nowNs)
                attempt.currentPhase = phase
                attempt.currentPhaseStartNs = nowNs

                if (phase == SessionPhase.TransferringItem && attempt.payloadFirstStartNs == null) {
                    attempt.payloadFirstStartNs = nowNs
                }
                if (phase == SessionPhase.VerifyingItem) {
                    attempt.verificationEndNs = null
                }
                if ((phase == SessionPhase.AwaitingReceipt || phase == SessionPhase.Completed) &&
                    attempt.verificationEndNs == null
                ) {
                    attempt.verificationEndNs = nowNs
                }
            }
        }
    }

    fun onSavingStarted() {
        val nowNs = nanoClock()
        synchronized(lock) {
            val attempt = activeAttempt ?: return
            if (attempt.savingStartNs == null) {
                attempt.savingStartNs = nowNs
            }
        }
    }

    fun onSavingFinished(exportStatus: ExportUiStatus) {
        val nowNs = nanoClock()
        synchronized(lock) {
            val attempt = activeAttempt ?: return
            attempt.savingEndNs = nowNs
            attempt.latestExportStatus = exportStatus
        }
    }

    fun onCancellationRequested(sessionId: String, requestedNs: Long = nanoClock()) {
        synchronized(lock) {
            val attempt = activeAttempt ?: return
            if (attempt.sessionId != sessionId) return
            attempt.cancellationRequested = true
            if (attempt.cancelRequestedNs == null) {
                attempt.cancelRequestedNs = requestedNs
            }
        }
    }

    fun onCancellationUiUpdated(sessionId: String, uiUpdatedNs: Long = nanoClock()) {
        synchronized(lock) {
            val attempt = activeAttempt ?: return
            if (attempt.sessionId != sessionId) return
            if (attempt.cancelUiUpdatedNs == null) {
                attempt.cancelUiUpdatedNs = uiUpdatedNs
            }
        }
    }

    fun onTerminalState(
        scope: CoroutineScope,
        sessionState: SessionState,
        exportStatus: ExportUiStatus
    ) {
        val nowNs = nanoClock()
        val endSnapshot = captureSystemSnapshot()
        val report: R3TransferDiagnosticReport
        val terminalSample: R3DiagnosticSample
        synchronized(lock) {
            val attempt = activeAttempt ?: return
            if (attempt.sessionId != sessionState.sessionId.value || attempt.terminalEmitted) {
                return
            }
            attempt.lastSessionState = sessionState
            attempt.latestExportStatus = exportStatus
            attempt.latestTotalBytesTransferred = computeTransferredBytes(sessionState)
            attempt.latestDeclaredBytes = sessionState.batch?.totalBytes ?: attempt.latestDeclaredBytes
            attempt.latestItemCount = sessionState.batch?.items?.size ?: attempt.latestItemCount
            attempt.accumulatePhaseTicks(attempt.currentPhase, nowNs)
            attempt.currentPhase = sessionState.phase
            attempt.terminalEmitted = true
            if (attempt.cancellationRequested && attempt.cancelResourcesReleasedNs == null) {
                attempt.cancelResourcesReleasedNs = nowNs
            }
            if (attempt.cancellationRequested && attempt.cancelUiUpdatedNs == null) {
                attempt.cancelUiUpdatedNs = nowNs
            }

            samplerJob?.cancel()
            samplerJob = null

            report = buildReport(attempt, sessionState, endSnapshot, nowNs)
            terminalSample = buildSample(
                attempt = attempt,
                snapshot = endSnapshot,
                elapsedSeconds = nanosToMs(nowNs - attempt.startedNs) / 1000.0,
                byteDelta = 0L,
                isTerminal = true,
                terminalStatus = report.outcome.status
            )
            _latestReport.value = report
        }

        scope.launch(ioDispatcher) {
            appendJsonlLine(terminalSample.toJsonLine())
            writeAttemptReport(report)
        }
    }

    private fun recordOneSecondSample(attempt: ActiveAttempt): R3DiagnosticSample? {
        val nowNs = nanoClock()
        val snapshot = captureSystemSnapshot()
        return synchronized(lock) {
            if (attempt.terminalEmitted) return@synchronized null
            if (attempt.oneSecondRatesMBps.size >= R3DiagnosticConstants.MAX_ONE_SECOND_SAMPLES) {
                return@synchronized null
            }

            val bytesNow = attempt.latestTotalBytesTransferred
            val byteDelta = (bytesNow - attempt.lastSampleBytes).coerceAtLeast(0L)
            attempt.lastSampleBytes = bytesNow

            val dtSec = max(0.001, (nowNs - attempt.lastSampleNs) / 1_000_000_000.0)
            attempt.lastSampleNs = nowNs

            val rateMBps = R3ThroughputCalculator.round2(
                byteDelta / (dtSec * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB)
            )
            attempt.oneSecondByteDeltas.add(byteDelta)
            attempt.oneSecondRatesMBps.add(rateMBps)
            attempt.updateResourcePeaks(snapshot)

            buildSample(
                attempt = attempt,
                snapshot = snapshot,
                elapsedSeconds = R3ThroughputCalculator.round2((nowNs - attempt.startedNs) / 1_000_000_000.0),
                byteDelta = byteDelta,
                isTerminal = false,
                terminalStatus = ""
            )
        }
    }

    private fun buildSample(
        attempt: ActiveAttempt,
        snapshot: SystemMetricsSnapshot,
        elapsedSeconds: Double,
        byteDelta: Long,
        isTerminal: Boolean,
        terminalStatus: String
    ): R3DiagnosticSample {
        val fiveSecRate = if (attempt.oneSecondByteDeltas.size >= 5) {
            val tail = attempt.oneSecondByteDeltas.takeLast(5).sum()
            R3Double.Available(
                R3ThroughputCalculator.round2(tail / (5.0 * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB))
            )
        } else {
            R3Double.Unavailable
        }
        val payloadMs = nanosToMs(attempt.payloadTicks)
        val avgPayloadRate = if (payloadMs > 0.0) {
            R3Double.Available(
                R3ThroughputCalculator.round2(
                    attempt.latestTotalBytesTransferred /
                        ((payloadMs / 1000.0) * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB)
                )
            )
        } else {
            R3Double.Unavailable
        }
        val allocRate = if (snapshot.bytesAllocatedTotal != null &&
            attempt.baselineSnapshot.bytesAllocatedTotal != null &&
            elapsedSeconds > 0.0
        ) {
            val deltaBytes = (snapshot.bytesAllocatedTotal - attempt.baselineSnapshot.bytesAllocatedTotal)
                .coerceAtLeast(0L)
            R3Double.Available(
                R3ThroughputCalculator.round2(
                    deltaBytes / (elapsedSeconds * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB)
                )
            )
        } else {
            R3Double.Unavailable
        }

        val unsmoothedRate = R3Double.Available(
            R3ThroughputCalculator.round2(byteDelta / R3DiagnosticConstants.DECIMAL_BYTES_PER_MB)
        )
        val savingMs = if (attempt.savingStartNs != null) {
            val end = attempt.savingEndNs ?: nanoClock()
            R3Double.Available(nanosToMs(end - attempt.savingStartNs!!))
        } else {
            R3Double.Unavailable
        }

        return R3DiagnosticSample(
            timestampUtc = utcNow(),
            attemptId = attempt.attemptId,
            direction = if (attempt.role == DeviceRole.Sender) "Send" else "Receive",
            phase = attempt.currentPhase.name,
            elapsedSeconds = elapsedSeconds,
            preparationMs = R3Double.Available(nanosToMs(attempt.preparationTicks)),
            connectionMs = R3Double.Available(nanosToMs(attempt.connectionTicks)),
            consentWaitMs = R3Double.Available(nanosToMs(attempt.consentTicks)),
            payloadTransferMs = R3Double.Available(payloadMs),
            verificationMs = R3Double.Available(nanosToMs(attempt.verificationTicks)),
            savingMs = savingMs,
            commitMs = savingMs,
            finalAcknowledgementMs = R3Double.Available(nanosToMs(attempt.finalAckTicks)),
            declaredTotalBytes = attempt.latestDeclaredBytes,
            payloadBytesTransferred = attempt.latestTotalBytesTransferred,
            verifiedBytes = computeVerifiedBytes(attempt.lastSessionState),
            unsmoothedPayloadRateMBps = unsmoothedRate,
            averagePayloadRateMBps = avgPayloadRate,
            fiveSecondWindowRateMBps = fiveSecRate,
            cpuPercent = R3Double.of(snapshot.cpuPercent?.let { R3ThroughputCalculator.round2(it) }),
            managedAllocatedMBPerSec = allocRate,
            managedHeapMiB = R3Double.of(snapshot.managedHeapMiB?.let { R3ThroughputCalculator.round2(it) }),
            nativeMemoryMiB = R3Double.of(snapshot.nativeHeapMiB?.let { R3ThroughputCalculator.round2(it) }),
            gcPauseMs = R3Double.of(snapshot.gcBlockingTimeMs?.let { R3ThroughputCalculator.round2(it) }),
            gcCount = R3Int.of(snapshot.gcCount),
            processReadBytes = R3Long.of(snapshot.processReadBytes),
            processWriteBytes = R3Long.of(snapshot.processWriteBytes),
            thermalStatus = snapshot.thermalStatus,
            batteryLevelPercent = R3Int.of(snapshot.batteryLevelPercent),
            isTerminal = isTerminal,
            terminalStatus = terminalStatus
        )
    }

    private fun buildReport(
        attempt: ActiveAttempt,
        sessionState: SessionState,
        endSnapshot: SystemMetricsSnapshot,
        endNs: Long
    ): R3TransferDiagnosticReport {
        val totalWallMs = nanosToMs(endNs - attempt.startedNs)
        val payloadMs = nanosToMs(attempt.payloadTicks)
        val verificationMs = nanosToMs(attempt.verificationTicks)
        val savingMs = if (attempt.savingStartNs != null) {
            val end = attempt.savingEndNs ?: endNs
            R3Double.Available(nanosToMs(end - attempt.savingStartNs!!))
        } else {
            R3Double.Unavailable
        }
        val acceptanceToVerifiedMs = if (attempt.payloadFirstStartNs != null && attempt.verificationEndNs != null) {
            R3Double.Available(nanosToMs(attempt.verificationEndNs!! - attempt.payloadFirstStartNs!!))
        } else {
            R3Double.Unavailable
        }

        val verifiedBytes = computeVerifiedBytes(sessionState)
        val verifiedItemCount = sessionState.itemStates.values.count { it.phase == ItemPhase.Verified }
        val declaredTotalBytes = sessionState.batch?.totalBytes ?: attempt.latestDeclaredBytes
        val payloadTransferred = attempt.latestTotalBytesTransferred

        val avgPayloadRate = if (payloadMs > 0.0) {
            R3Double.Available(
                R3ThroughputCalculator.round2(
                    payloadTransferred / ((payloadMs / 1000.0) * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB)
                )
            )
        } else {
            R3Double.Unavailable
        }
        val acceptanceRate = if (acceptanceToVerifiedMs is R3Double.Available && acceptanceToVerifiedMs.value > 0.0) {
            R3Double.Available(
                R3ThroughputCalculator.round2(
                    verifiedBytes /
                        ((acceptanceToVerifiedMs.value / 1000.0) * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB)
                )
            )
        } else {
            R3Double.Unavailable
        }

        val fiveSecWindows = R3ThroughputCalculator.computeFiveSecondWindows(attempt.oneSecondByteDeltas)
        val (firstQuarter, finalQuarter, quarterRatio) =
            R3ThroughputCalculator.computeSteadyQuarters(attempt.oneSecondRatesMBps)

        val statusString = when (sessionState.phase) {
            SessionPhase.Completed -> "Completed"
            SessionPhase.Cancelled -> "Cancelled"
            SessionPhase.DeliveryUnconfirmed -> "DeliveryUnconfirmed"
            SessionPhase.Failed, SessionPhase.Interrupted -> {
                if (sessionState.failure?.code == "USER_REJECTED" ||
                    sessionState.failure?.code == "CONNECTION_REJECTED"
                ) "Rejected" else "Failed"
            }
            else -> sessionState.phase.name
        }

        val uiCancelLatency = if (attempt.cancellationRequested &&
            attempt.cancelRequestedNs != null &&
            attempt.cancelUiUpdatedNs != null
        ) {
            R3Double.Available(nanosToMs(attempt.cancelUiUpdatedNs!! - attempt.cancelRequestedNs!!))
        } else {
            R3Double.Unavailable
        }
        val releaseCancelLatency = if (attempt.cancellationRequested &&
            attempt.cancelRequestedNs != null &&
            attempt.cancelResourcesReleasedNs != null
        ) {
            R3Double.Available(nanosToMs(attempt.cancelResourcesReleasedNs!! - attempt.cancelRequestedNs!!))
        } else {
            R3Double.Unavailable
        }

        attempt.updateResourcePeaks(endSnapshot)
        val avgCpu = if (attempt.cpuSamples.isNotEmpty()) {
            R3Double.Available(R3ThroughputCalculator.round2(attempt.cpuSamples.average()))
        } else {
            R3Double.of(endSnapshot.cpuPercent?.let { R3ThroughputCalculator.round2(it) })
        }
        val peakCpu = if (attempt.cpuSamples.isNotEmpty()) {
            R3Double.Available(R3ThroughputCalculator.round2(attempt.cpuSamples.max()))
        } else {
            R3Double.of(endSnapshot.cpuPercent?.let { R3ThroughputCalculator.round2(it) })
        }
        val managedDelta = if (attempt.baselineSnapshot.managedHeapMiB != null &&
            attempt.peakManagedHeapMiB != null
        ) {
            R3Double.Available(
                R3ThroughputCalculator.round2(attempt.peakManagedHeapMiB!! - attempt.baselineSnapshot.managedHeapMiB)
            )
        } else {
            R3Double.Unavailable
        }
        val allocatedTotalMB = if (attempt.baselineSnapshot.bytesAllocatedTotal != null &&
            endSnapshot.bytesAllocatedTotal != null
        ) {
            val delta = (endSnapshot.bytesAllocatedTotal - attempt.baselineSnapshot.bytesAllocatedTotal)
                .coerceAtLeast(0L)
            R3Double.Available(R3ThroughputCalculator.round2(delta / R3DiagnosticConstants.DECIMAL_BYTES_PER_MB))
        } else {
            R3Double.Unavailable
        }
        val allocatedRateMBps = if (allocatedTotalMB is R3Double.Available && totalWallMs > 0.0) {
            R3Double.Available(R3ThroughputCalculator.round2(allocatedTotalMB.value / (totalWallMs / 1000.0)))
        } else {
            R3Double.Unavailable
        }
        val gcPauseTotal = if (attempt.baselineSnapshot.gcBlockingTimeMs != null &&
            endSnapshot.gcBlockingTimeMs != null
        ) {
            R3Double.Available(
                R3ThroughputCalculator.round2(
                    (endSnapshot.gcBlockingTimeMs - attempt.baselineSnapshot.gcBlockingTimeMs).coerceAtLeast(0.0)
                )
            )
        } else {
            R3Double.of(endSnapshot.gcBlockingTimeMs?.let { R3ThroughputCalculator.round2(it) })
        }
        val gcCountTotal = if (attempt.baselineSnapshot.gcCount != null && endSnapshot.gcCount != null) {
            R3Int.Available((endSnapshot.gcCount - attempt.baselineSnapshot.gcCount).coerceAtLeast(0))
        } else {
            R3Int.of(endSnapshot.gcCount)
        }

        val readIoDelta = if (attempt.baselineSnapshot.processReadBytes != null &&
            endSnapshot.processReadBytes != null
        ) {
            R3Long.Available((endSnapshot.processReadBytes - attempt.baselineSnapshot.processReadBytes).coerceAtLeast(0L))
        } else {
            R3Long.Unavailable
        }
        val writeIoDelta = if (attempt.baselineSnapshot.processWriteBytes != null &&
            endSnapshot.processWriteBytes != null
        ) {
            R3Long.Available((endSnapshot.processWriteBytes - attempt.baselineSnapshot.processWriteBytes).coerceAtLeast(0L))
        } else {
            R3Long.Unavailable
        }
        val readRate = if (readIoDelta is R3Long.Available && totalWallMs > 0.0) {
            R3Double.Available(
                R3ThroughputCalculator.round2(
                    readIoDelta.value / ((totalWallMs / 1000.0) * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB)
                )
            )
        } else {
            R3Double.Unavailable
        }
        val writeRate = if (writeIoDelta is R3Long.Available && totalWallMs > 0.0) {
            R3Double.Available(
                R3ThroughputCalculator.round2(
                    writeIoDelta.value / ((totalWallMs / 1000.0) * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB)
                )
            )
        } else {
            R3Double.Unavailable
        }

        val networkInfo = queryNetworkLinkInfo(attempt.transportMode)
        val envInfo = queryPlatformAndHardware()

        return R3TransferDiagnosticReport(
            attemptId = attempt.attemptId,
            timestampUtc = attempt.startedUtc,
            sourceCommit = R3DiagnosticConstants.UNAVAILABLE,
            artifactSha256 = R3DiagnosticConstants.UNAVAILABLE,
            appVersion = queryAppVersion(),
            platform = envInfo.first,
            hardware = envInfo.second,
            runtime = envInfo.third,
            networkLink = networkInfo,
            storage = R3StorageInfo(
                stagingLocation = if (attempt.role == DeviceRole.Receiver) "filesDir/staging" else "content-resolver",
                stagingVolumeOrMount = "filesDir",
                stagingFilesystem = R3DiagnosticConstants.UNAVAILABLE,
                stagingStorageType = R3DiagnosticConstants.UNAVAILABLE,
                stagingFreeBytesBefore = R3Long.of(attempt.stagingFreeBytesBefore),
                destinationLocation = if (attempt.role == DeviceRole.Receiver) "Download/Glide" else R3DiagnosticConstants.UNAVAILABLE,
                destinationVolumeOrMount = if (attempt.role == DeviceRole.Receiver) "MediaStore:Download/Glide" else R3DiagnosticConstants.UNAVAILABLE,
                destinationFilesystem = if (attempt.role == DeviceRole.Receiver) "fuse-mediastore" else R3DiagnosticConstants.UNAVAILABLE,
                destinationStorageType = R3DiagnosticConstants.UNAVAILABLE,
                destinationFreeBytesBefore = R3Long.of(attempt.destinationFreeBytesBefore),
                sameVolumeStagingEligible = R3Boolean.Unavailable,
                commitStrategy = if (attempt.role == DeviceRole.Receiver) "MediaStoreAtomic" else R3DiagnosticConstants.UNAVAILABLE
            ),
            dataset = R3DatasetInfo(
                datasetId = "session-${attempt.sessionId.take(16)}",
                contentProfile = "production-user",
                itemCount = attempt.latestItemCount,
                declaredTotalBytes = declaredTotalBytes
            ),
            testConfiguration = R3TestConfiguration(
                role = if (attempt.role == DeviceRole.Sender) "sender" else "receiver",
                direction = if (attempt.role == DeviceRole.Sender) "Send" else "Receive",
                pairingMode = attempt.pairingMode,
                powerSource = when (endSnapshot.isCharging) {
                    true -> "ac"
                    false -> "battery"
                    null -> R3DiagnosticConstants.UNAVAILABLE
                },
                ioOptions = R3IoOptions(
                    chunkBufferBytes = R3Int.Available(65536),
                    discardStorageForBenchmark = R3Boolean.Available(false)
                )
            ),
            outcome = R3OutcomeInfo(
                status = statusString,
                success = sessionState.phase == SessionPhase.Completed,
                rejected = statusString == "Rejected",
                cancelled = sessionState.phase == SessionPhase.Cancelled,
                errorCategory = sessionState.failure?.code ?: "None",
                terminalMessage = sessionState.failure?.message ?: "$statusString ($verifiedItemCount item(s), $verifiedBytes bytes)",
                verifiedItemCount = verifiedItemCount,
                verifiedBytes = verifiedBytes,
                sha256Verified = R3Boolean.Available(
                    sessionState.phase == SessionPhase.Completed &&
                        (sessionState.itemStates.isEmpty() || sessionState.itemStates.values.all { it.phase == ItemPhase.Verified })
                )
            ),
            phaseDurationsMs = R3PhaseDurationsMs(
                totalWallMs = R3Double.Available(totalWallMs),
                preparationMs = R3Double.Available(nanosToMs(attempt.preparationTicks)),
                connectionMs = R3Double.Available(nanosToMs(attempt.connectionTicks)),
                userConsentMs = R3Double.Available(nanosToMs(attempt.consentTicks)),
                payloadTransferMs = R3Double.Available(payloadMs),
                verificationMs = R3Double.Available(verificationMs),
                savingMs = savingMs,
                finalAcknowledgementMs = R3Double.Available(nanosToMs(attempt.finalAckTicks)),
                acceptanceToVerifiedMs = acceptanceToVerifiedMs
            ),
            bytes = R3ByteCounts(
                declaredTotalBytes = declaredTotalBytes,
                payloadBytesTransferred = payloadTransferred,
                verifiedBytes = verifiedBytes
            ),
            throughput = R3ThroughputInfo(
                averagePayloadRateMBps = avgPayloadRate,
                acceptanceToVerifiedRateMBps = acceptanceRate,
                oneSecondRatesMBps = attempt.oneSecondRatesMBps.toList(),
                fiveSecondRateWindowsMBps = fiveSecWindows,
                firstSteadyQuarterRateMBps = firstQuarter,
                finalSteadyQuarterRateMBps = finalQuarter,
                finalToFirstQuarterRatio = quarterRatio
            ),
            resources = R3ResourceUsage(
                cpuPercentAvg = avgCpu,
                cpuPercentPeak = peakCpu,
                managedHeapBaselineMiB = R3Double.of(attempt.baselineSnapshot.managedHeapMiB?.let { R3ThroughputCalculator.round2(it) }),
                managedHeapPeakMiB = R3Double.of(attempt.peakManagedHeapMiB?.let { R3ThroughputCalculator.round2(it) }),
                managedHeapDeltaMiB = managedDelta,
                managedAllocatedTotalMB = allocatedTotalMB,
                managedAllocatedRateAvgMBPerSec = allocatedRateMBps,
                nativeOrPrivateMemoryBaselineMiB = R3Double.of(attempt.baselineSnapshot.nativeHeapMiB?.let { R3ThroughputCalculator.round2(it) }),
                nativeOrPrivateMemoryPeakMiB = R3Double.of(attempt.peakNativeHeapMiB?.let { R3ThroughputCalculator.round2(it) }),
                gcPauseTotalMs = gcPauseTotal,
                gen0Collections = gcCountTotal
            ),
            diskActivity = R3DiskActivity(
                processReadIoBytes = readIoDelta,
                processWriteIoBytes = writeIoDelta,
                processReadMBpsAvg = readRate,
                processWriteMBpsAvg = writeRate
            ),
            cancellation = R3CancellationMetrics(
                cancellationRequested = attempt.cancellationRequested,
                uiCancelLatencyMs = uiCancelLatency,
                resourceReleaseLatencyMs = releaseCancelLatency
            ),
            androidConditions = R3AndroidConditions(
                thermalStatusStart = attempt.baselineSnapshot.thermalStatus,
                thermalStatusPeak = attempt.peakThermalStatus,
                thermalStatusEnd = endSnapshot.thermalStatus,
                thermalHeadroom = R3Double.of(endSnapshot.thermalHeadroom?.let { R3ThroughputCalculator.round2(it) }),
                batteryLevelPercentStart = R3Int.of(attempt.baselineSnapshot.batteryLevelPercent),
                batteryLevelPercentEnd = R3Int.of(endSnapshot.batteryLevelPercent),
                isCharging = R3Boolean.of(endSnapshot.isCharging),
                batterySaverEnabled = R3Boolean.of(endSnapshot.batterySaverEnabled),
                batteryTemperatureCelsiusStart = R3Double.of(
                    attempt.baselineSnapshot.batteryTemperatureCelsius?.let { R3ThroughputCalculator.round2(it) }
                ),
                batteryTemperatureCelsiusEnd = R3Double.of(
                    endSnapshot.batteryTemperatureCelsius?.let { R3ThroughputCalculator.round2(it) }
                )
            )
        ).sanitized()
    }

    private suspend fun appendJsonlLine(line: String) {
        writeMutex.withLock {
            runCatching {
                diagnosticsDir.mkdirs()
                val logFile = File(diagnosticsDir, "glide-diagnostics.jsonl")
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                if (logFile.exists() && logFile.length() + bytes.size > maxLogFileBytes) {
                    rotateLogs()
                }
                logFile.appendBytes(bytes)
            }
        }
    }

    private fun rotateLogs() {
        val oldest = File(diagnosticsDir, "glide-diagnostics.jsonl.${maxLogFiles - 1}")
        if (oldest.exists()) oldest.delete()
        for (i in (maxLogFiles - 2) downTo 1) {
            val src = File(diagnosticsDir, "glide-diagnostics.jsonl.$i")
            if (src.exists()) {
                src.renameTo(File(diagnosticsDir, "glide-diagnostics.jsonl.${i + 1}"))
            }
        }
        val active = File(diagnosticsDir, "glide-diagnostics.jsonl")
        if (active.exists()) {
            active.renameTo(File(diagnosticsDir, "glide-diagnostics.jsonl.1"))
        }
    }

    private suspend fun writeAttemptReport(report: R3TransferDiagnosticReport) {
        writeMutex.withLock {
            runCatching {
                diagnosticsDir.mkdirs()
                val target = File(diagnosticsDir, "r3-attempt-${report.attemptId}.json")
                target.writeText(report.toJson(pretty = true), Charsets.UTF_8)
                val attemptFiles = diagnosticsDir.listFiles { f ->
                    f.isFile && f.name.startsWith("r3-attempt-") && f.name.endsWith(".json")
                }?.sortedByDescending { it.lastModified() } ?: emptyList()
                if (attemptFiles.size > maxAttemptFiles) {
                    attemptFiles.drop(maxAttemptFiles).forEach { it.delete() }
                }
            }
        }
    }

    private fun captureSystemSnapshot(): SystemMetricsSnapshot {
        systemSampler?.let { return it.captureSnapshot() }

        val runtime = Runtime.getRuntime()
        val managedHeapMiB = (runtime.totalMemory() - runtime.freeMemory()) / R3DiagnosticConstants.BYTES_PER_MIB
        val nativeHeapMiB = runCatching {
            Debug.getNativeHeapAllocatedSize() / R3DiagnosticConstants.BYTES_PER_MIB
        }.getOrNull()

        val runtimeStats = runCatching { Debug.getRuntimeStats() }.getOrNull()
        val gcCount = runtimeStats?.get("art.gc.gc-count")?.toIntOrNull()
        val gcBlockingMs = runtimeStats?.get("art.gc.blocking-gc-time")?.toDoubleOrNull()
        val bytesAllocated = runtimeStats?.get("art.gc.bytes-allocated")?.toLongOrNull()

        val (readIo, writeIo) = readProcSelfIo()
        val cpuPct = readProcSelfCpuPercent()

        val powerManager = runCatching {
            context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull()
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            runCatching { mapThermalStatus(powerManager.currentThermalStatus) }
                .getOrDefault(R3DiagnosticConstants.UNAVAILABLE)
        } else {
            R3DiagnosticConstants.UNAVAILABLE
        }
        val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && powerManager != null) {
            runCatching {
                val value = powerManager.getThermalHeadroom(0)
                if (value.isFinite() && value >= 0f) value.toDouble() else null
            }.getOrNull()
        } else {
            null
        }
        val batterySaver = runCatching { powerManager?.isPowerSaveMode }.getOrNull()

        var batteryPct: Int? = null
        var batteryTempC: Double? = null
        var isCharging: Boolean? = null
        runCatching {
            val intent = context?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryPct = ((level * 100.0) / scale).toInt()
                }
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tempTenths != Int.MIN_VALUE && tempTenths > -500) {
                    batteryTempC = tempTenths / 10.0
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (status != -1) {
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        }

        return SystemMetricsSnapshot(
            managedHeapMiB = managedHeapMiB,
            nativeHeapMiB = nativeHeapMiB,
            gcCount = gcCount,
            gcBlockingTimeMs = gcBlockingMs,
            bytesAllocatedTotal = bytesAllocated,
            cpuPercent = cpuPct,
            processReadBytes = readIo,
            processWriteBytes = writeIo,
            thermalStatus = thermalStatus,
            thermalHeadroom = thermalHeadroom,
            batteryLevelPercent = batteryPct,
            batteryTemperatureCelsius = batteryTempC,
            isCharging = isCharging,
            batterySaverEnabled = batterySaver
        )
    }

    private var prevCpuTicks: Long? = null
    private var prevWallNs: Long? = null

    private fun readProcSelfCpuPercent(): Double? = runCatching {
        val statText = File("/proc/self/stat").readText()
        val rparen = statText.lastIndexOf(')')
        if (rparen < 0) return null
        val fields = statText.substring(rparen + 2).trim().split(' ')
        if (fields.size <= 14) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        val totalTicks = utime + stime
        val nowNs = nanoClock()
        val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
        val prevTicks = prevCpuTicks
        val prevNs = prevWallNs
        prevCpuTicks = totalTicks
        prevWallNs = nowNs
        if (prevTicks == null || prevNs == null || nowNs <= prevNs) return null
        val cpuSeconds = (totalTicks - prevTicks).coerceAtLeast(0L).toDouble() / clkTck.toDouble()
        val wallSeconds = (nowNs - prevNs).toDouble() / 1_000_000_000.0
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        ((cpuSeconds / (wallSeconds * cores)) * 100.0).coerceIn(0.0, 100.0)
    }.getOrNull()

    private fun readProcSelfIo(): Pair<Long?, Long?> = runCatching {
        val ioFile = File("/proc/self/io")
        if (!ioFile.canRead()) return null to null
        var readBytes: Long? = null
        var writeBytes: Long? = null
        ioFile.forEachLine { line ->
            when {
                line.startsWith("read_bytes:") ->
                    readBytes = line.substringAfter(':').trim().toLongOrNull()
                line.startsWith("write_bytes:") ->
                    writeBytes = line.substringAfter(':').trim().toLongOrNull()
            }
        }
        readBytes to writeBytes
    }.getOrDefault(null to null)

    private fun queryFreeBytes(dir: File?): Long? {
        if (dir == null) return null
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            StatFs(dir.absolutePath).availableBytes
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun publicDownloadsDir(): File? = runCatching {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }.getOrNull()

    private fun queryAppVersion(): String = runCatching {
        val ctx = context ?: return "1.0.0"
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        info.versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    private fun queryPlatformAndHardware(): Triple<R3PlatformInfo, R3HardwareInfo, R3RuntimeInfo> {
        val fingerprint: String = (Build.FINGERPRINT as String?).orEmpty()
        val model: String? = Build.MODEL
        val hardwareName: String? = Build.HARDWARE
        val releaseVersion: String = (Build.VERSION.RELEASE as String?) ?: R3DiagnosticConstants.UNAVAILABLE
        val supportedAbis: Array<String>? = Build.SUPPORTED_ABIS

        val isEmulator = fingerprint.contains("generic", ignoreCase = true) ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            (model?.contains("Emulator", ignoreCase = true) == true) ||
            (model?.contains("Android SDK built for", ignoreCase = true) == true) ||
            (hardwareName?.contains("ranchu", ignoreCase = true) == true) ||
            (hardwareName?.contains("goldfish", ignoreCase = true) == true)

        val arch = supportedAbis?.firstOrNull()
            ?: System.getProperty("os.arch")
            ?: R3DiagnosticConstants.UNAVAILABLE

        val platform = R3PlatformInfo(
            osFamily = "android",
            osVersion = "Android $releaseVersion (API ${Build.VERSION.SDK_INT})",
            architecture = arch,
            executionEnvironment = if (isEmulator) "android-emulator" else "physical",
            deviceModel = model?.takeIf { it.isNotBlank() } ?: R3DiagnosticConstants.UNAVAILABLE
        )

        val totalRamMiB = runCatching {
            val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                R3ThroughputCalculator.round2(memInfo.totalMem / R3DiagnosticConstants.BYTES_PER_MIB)
            } else null
        }.getOrNull()

        val hardware = R3HardwareInfo(
            cpuModel = hardwareName?.takeIf { it.isNotBlank() } ?: R3DiagnosticConstants.UNAVAILABLE,
            logicalCores = R3Int.Available(Runtime.getRuntime().availableProcessors()),
            totalRamMiB = R3Double.of(totalRamMiB)
        )

        val isDebuggable = context?.applicationInfo?.let {
            (it.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } ?: false
        val runtime = R3RuntimeInfo(
            runtimeName = "ART",
            runtimeVersion = "Android ${Build.VERSION.SDK_INT} ART (${System.getProperty("java.vm.version") ?: "unknown"})",
            buildConfiguration = if (isDebuggable) "Debug" else "Release",
            gcMode = "ART-CC",
            aotOrBaselineProfile = R3DiagnosticConstants.UNAVAILABLE
        )
        return Triple(platform, hardware, runtime)
    }

    private fun queryNetworkLinkInfo(transportMode: String): R3NetworkLinkInfo {
        var linkType = R3DiagnosticConstants.UNAVAILABLE
        var interfaceName = R3DiagnosticConstants.UNAVAILABLE
        var mtu: R3Int = R3Int.Unavailable
        var addrClass = R3DiagnosticConstants.UNAVAILABLE

        runCatching {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(network)
            val linkProps = cm?.getLinkProperties(network)
            if (caps != null) {
                linkType = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> R3DiagnosticConstants.UNAVAILABLE
                }
            }
            linkProps?.interfaceName?.let { ifName ->
                interfaceName = ifName
                runCatching {
                    val nif = NetworkInterface.getByName(ifName)
                    if (nif != null && nif.mtu > 0) mtu = R3Int.Available(nif.mtu)
                }
            }
            val firstAddr = linkProps?.linkAddresses?.firstOrNull()?.address
            if (firstAddr != null) {
                addrClass = when {
                    firstAddr.isLoopbackAddress -> "loopback"
                    firstAddr.isLinkLocalAddress -> "link-local"
                    firstAddr.isSiteLocalAddress -> "rfc1918"
                    else -> "other"
                }
            }
        }

        return R3NetworkLinkInfo(
            transport = transportMode,
            interfaceName = interfaceName,
            linkType = linkType,
            mtuBytes = mtu,
            localAddressClass = addrClass
        )
    }

    private fun computeTransferredBytes(state: SessionState?): Long {
        if (state == null) return 0L
        val batch = state.batch
        if (batch == null) {
            return state.currentItemState?.transferredBytes ?: 0L
        }
        var total = 0L
        for (item in batch.items) {
            val itemState = state.itemStates[item.id]
            total += when {
                itemState != null -> itemState.transferredBytes
                state.currentItemState?.item?.id == item.id -> state.currentItemState?.transferredBytes ?: 0L
                else -> 0L
            }
        }
        return total
    }

    private fun computeVerifiedBytes(state: SessionState?): Long {
        if (state == null) return 0L
        return state.itemStates.values
            .filter { it.phase == ItemPhase.Verified }
            .sumOf { it.item.sizeBytes }
    }

    private fun nanosToMs(nanos: Long): Double =
        R3ThroughputCalculator.round2(nanos.coerceAtLeast(0L) / 1_000_000.0)

    private fun mapThermalStatus(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> R3DiagnosticConstants.UNAVAILABLE
    }

    private class ActiveAttempt(
        val attemptId: String,
        val sessionId: String,
        val role: DeviceRole,
        val transportMode: String,
        val pairingMode: String,
        val startedUtc: String,
        val startedNs: Long,
        val baselineSnapshot: SystemMetricsSnapshot,
        val stagingFreeBytesBefore: Long?,
        val destinationFreeBytesBefore: Long?
    ) {
        var currentPhase: SessionPhase = SessionPhase.Idle
        var currentPhaseStartNs: Long = startedNs
        var lastSampleNs: Long = startedNs
        var lastSampleBytes: Long = 0L

        var preparationTicks: Long = 0L
        var connectionTicks: Long = 0L
        var consentTicks: Long = 0L
        var payloadTicks: Long = 0L
        var verificationTicks: Long = 0L
        var finalAckTicks: Long = 0L

        var payloadFirstStartNs: Long? = null
        var verificationEndNs: Long? = null
        var savingStartNs: Long? = null
        var savingEndNs: Long? = null

        var cancellationRequested: Boolean = false
        var cancelRequestedNs: Long? = null
        var cancelUiUpdatedNs: Long? = null
        var cancelResourcesReleasedNs: Long? = null

        var latestTotalBytesTransferred: Long = 0L
        var latestDeclaredBytes: Long = 0L
        var latestItemCount: Int = 0
        var lastSessionState: SessionState? = null
        var latestExportStatus: ExportUiStatus = ExportUiStatus.Idle
        var terminalEmitted: Boolean = false

        val oneSecondByteDeltas = mutableListOf<Long>()
        val oneSecondRatesMBps = mutableListOf<Double>()
        val cpuSamples = mutableListOf<Double>()

        var peakManagedHeapMiB: Double? = baselineSnapshot.managedHeapMiB
        var peakNativeHeapMiB: Double? = baselineSnapshot.nativeHeapMiB
        var peakThermalStatus: String = baselineSnapshot.thermalStatus

        fun accumulatePhaseTicks(phase: SessionPhase, nowNs: Long) {
            val delta = (nowNs - currentPhaseStartNs).coerceAtLeast(0L)
            when (phase) {
                SessionPhase.Idle, SessionPhase.PreparingItem -> preparationTicks += delta
                SessionPhase.Negotiating, SessionPhase.Offering -> connectionTicks += delta
                SessionPhase.AwaitingConsent -> consentTicks += delta
                SessionPhase.TransferringItem -> payloadTicks += delta
                SessionPhase.VerifyingItem -> verificationTicks += delta
                SessionPhase.AwaitingReceipt -> finalAckTicks += delta
                SessionPhase.Completed,
                SessionPhase.DeliveryUnconfirmed,
                SessionPhase.Cancelled,
                SessionPhase.Failed,
                SessionPhase.Interrupted -> Unit
            }
        }

        fun updateResourcePeaks(snapshot: SystemMetricsSnapshot) {
            snapshot.managedHeapMiB?.let {
                peakManagedHeapMiB = max(peakManagedHeapMiB ?: it, it)
            }
            snapshot.nativeHeapMiB?.let {
                peakNativeHeapMiB = max(peakNativeHeapMiB ?: it, it)
            }
            snapshot.cpuPercent?.let { cpuSamples.add(it) }
            if (thermalRank(snapshot.thermalStatus) > thermalRank(peakThermalStatus)) {
                peakThermalStatus = snapshot.thermalStatus
            }
        }

        private fun thermalRank(label: String): Int = when (label) {
            "none" -> 0
            "light" -> 1
            "moderate" -> 2
            "severe" -> 3
            "critical" -> 4
            "emergency" -> 5
            "shutdown" -> 6
            else -> -1
        }
    }

    companion object {
        fun defaultDiagnosticsDir(context: Context?): File =
            if (context != null) File(context.filesDir, "diagnostics")
            else File(System.getProperty("java.io.tmpdir") ?: ".", "glide-diagnostics")
    }
}
