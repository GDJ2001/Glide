package dev.glide.app.diagnostics

import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.core.coordinator.ItemPhase
import dev.glide.core.coordinator.ItemTransferState
import dev.glide.core.coordinator.SessionPhase
import dev.glide.core.coordinator.SessionState
import dev.glide.core.diagnostics.R3DiagnosticConstants
import dev.glide.core.diagnostics.R3Double
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTransferDiagnosticsObserverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsBoundedR3ReportWithPhaseDurationsAndRedactedTerminalMessage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var nanoNow = 1_000_000_000L
        val diagDir = tempFolder.newFolder("diagnostics")

        val observer = AndroidTransferDiagnosticsObserver(
            context = null,
            diagnosticsDir = diagDir,
            ioDispatcher = dispatcher,
            samplerDispatcher = dispatcher,
            nanoClock = { nanoNow },
            utcNow = { "2026-11-19T10:15:30Z" },
            systemSampler = object : AndroidTransferDiagnosticsObserver.SystemMetricsSampler {
                override fun captureSnapshot() = AndroidTransferDiagnosticsObserver.SystemMetricsSnapshot(
                    managedHeapMiB = 24.5,
                    nativeHeapMiB = 18.0,
                    gcCount = 2,
                    gcBlockingTimeMs = 1.5,
                    bytesAllocatedTotal = 1_000_000L,
                    cpuPercent = 22.0,
                    thermalStatus = "none",
                    batteryLevelPercent = 98,
                    batteryTemperatureCelsius = 27.5,
                    isCharging = true,
                    batterySaverEnabled = false
                )
            }
        )

        val sid = SessionId("11111111-1111-4111-8111-111111111111")
        val itemId = ItemId("22222222-2222-4222-8222-222222222222")
        val item = TransferItem(
            id = itemId,
            name = "payload.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 10_000_000L
        )
        val batch = TransferBatch(BatchId("33333333-3333-4333-8333-333333333333"), listOf(item))

        observer.onSessionStarted(this, sid.value, DeviceRole.Receiver)

        nanoNow += 50_000_000L // 50ms
        observer.onSessionState(
            SessionState(
                sessionId = sid,
                role = DeviceRole.Receiver,
                phase = SessionPhase.TransferringItem,
                batch = batch,
                currentItemIndex = 0,
                itemStates = mapOf(itemId to ItemTransferState(item, ItemPhase.Streaming, transferredBytes = 5_000_000L))
            )
        )

        advanceTimeBy(1100L)
        nanoNow += 1_000_000_000L // 1000ms payload
        observer.onSessionState(
            SessionState(
                sessionId = sid,
                role = DeviceRole.Receiver,
                phase = SessionPhase.VerifyingItem,
                batch = batch,
                currentItemIndex = 0,
                itemStates = mapOf(itemId to ItemTransferState(item, ItemPhase.Verifying, transferredBytes = 10_000_000L))
            )
        )

        nanoNow += 120_000_000L // 120ms verifying
        observer.onSavingStarted()
        nanoNow += 80_000_000L // 80ms saving
        observer.onSavingFinished(ExportUiStatus.Success("Download/Glide"))

        val terminal = SessionState(
            sessionId = sid,
            role = DeviceRole.Receiver,
            phase = SessionPhase.Completed,
            batch = batch,
            currentItemIndex = 0,
            itemStates = mapOf(itemId to ItemTransferState(item, ItemPhase.Verified, transferredBytes = 10_000_000L)),
            failure = TransferFailure.Unknown("glide://pair/v1#token=leak999")
        )
        observer.onTerminalState(this, terminal, ExportUiStatus.Success("Download/Glide"))
        advanceUntilIdle()

        val report = observer.latestReport.value
        assertNotNull(report)
        assertEquals(R3DiagnosticConstants.SCHEMA_VERSION, report!!.schemaVersion)
        assertEquals("Receive", report.testConfiguration.direction)
        assertEquals("Completed", report.outcome.status)
        assertEquals(10_000_000L, report.bytes.verifiedBytes)
        assertTrue((report.phaseDurationsMs.payloadTransferMs as R3Double.Available).value >= 1000.0)
        assertTrue((report.phaseDurationsMs.verificationMs as R3Double.Available).value >= 120.0)
        assertEquals(80.0, (report.phaseDurationsMs.savingMs as R3Double.Available).value, 0.1)
        assertFalse(report.outcome.terminalMessage.contains("leak999"))
        assertTrue(report.outcome.terminalMessage.contains("glide://pair/[REDACTED]"))

        val writtenReportFiles = diagDir.listFiles { f -> f.name.startsWith("r3-attempt-") && f.name.endsWith(".json") }
        assertNotNull(writtenReportFiles)
        assertEquals(1, writtenReportFiles!!.size)
        val jsonlFile = File(diagDir, "glide-diagnostics.jsonl")
        assertTrue(jsonlFile.exists())
        assertTrue(jsonlFile.readText().contains("glide-r3-sample-v1"))
    }

    @Test
    fun rotatesDiagnosticJsonlLogsAtConfiguredMaxFiles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val diagDir = tempFolder.newFolder("diagnostics-rotate")
        val observer = AndroidTransferDiagnosticsObserver(
            context = null,
            diagnosticsDir = diagDir,
            ioDispatcher = dispatcher,
            samplerDispatcher = dispatcher,
            maxLogFileBytes = 256L,
            maxLogFiles = 4
        )

        val item = TransferItem(
            ItemId("44444444-4444-4444-8444-444444444444"),
            "a.bin",
            "application/octet-stream",
            1024L
        )
        val batch = TransferBatch(BatchId("55555555-5555-4555-8555-555555555555"), listOf(item))
        repeat(6) {
            val sid = SessionId(UUID.randomUUID().toString().lowercase())
            observer.onSessionStarted(this, sid.value, DeviceRole.Sender)
            observer.onTerminalState(
                this,
                SessionState(
                    sessionId = sid,
                    role = DeviceRole.Sender,
                    phase = SessionPhase.Completed,
                    batch = batch,
                    itemStates = mapOf(item.id to ItemTransferState(item, ItemPhase.Verified, transferredBytes = 1024L))
                ),
                ExportUiStatus.Idle
            )
            advanceUntilIdle()
        }

        val logFiles = diagDir.listFiles { f -> f.name.startsWith("glide-diagnostics.jsonl") } ?: emptyArray()
        assertTrue("Expected rotated log files, found ${logFiles.size}", logFiles.size in 2..4)
        assertFalse("Log count must not exceed maxLogFiles=4", File(diagDir, "glide-diagnostics.jsonl.4").exists())
    }
}
