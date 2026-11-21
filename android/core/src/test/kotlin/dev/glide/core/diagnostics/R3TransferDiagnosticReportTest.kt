package dev.glide.core.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R3TransferDiagnosticReportTest {

    @Test
    fun unavailableSentinelsSerializeAsUnavailableStringNeverNegativeOneOrNull() {
        val report = R3TransferDiagnosticReport(
            attemptId = "attempt-001",
            timestampUtc = "2026-11-19T10:00:00Z",
            appVersion = "1.0.0",
            platform = R3PlatformInfo(
                osFamily = "android",
                osVersion = "Android 17 (API 37)",
                architecture = "x86_64",
                executionEnvironment = "android-emulator",
                deviceModel = "Pixel_10a"
            ),
            runtime = R3RuntimeInfo(
                runtimeName = "ART",
                runtimeVersion = "Android 37 ART",
                buildConfiguration = "Release"
            ),
            testConfiguration = R3TestConfiguration(
                role = "receiver",
                direction = "Receive"
            ),
            outcome = R3OutcomeInfo(
                status = "Completed",
                success = true,
                rejected = false,
                cancelled = false
            )
        )

        val jsonText = report.toJson(pretty = true)
        val root = Json.parseToJsonElement(jsonText).jsonObject

        assertEquals(R3DiagnosticConstants.SCHEMA_VERSION, root["schemaVersion"]?.jsonPrimitive?.content)
        assertEquals("unavailable", root["sourceCommit"]?.jsonPrimitive?.content)
        assertEquals("unavailable", root["artifactSha256"]?.jsonPrimitive?.content)

        val phase = root["phaseDurationsMs"]!!.jsonObject
        assertEquals("unavailable", phase["discoveryMs"]?.jsonPrimitive?.content)
        assertEquals("unavailable", phase["payloadTransferMs"]?.jsonPrimitive?.content)

        val androidCond = root["androidConditions"]!!.jsonObject
        assertEquals("unavailable", androidCond["thermalStatusStart"]?.jsonPrimitive?.content)
        assertEquals("unavailable", androidCond["thermalHeadroom"]?.jsonPrimitive?.content)
        assertEquals("unavailable", androidCond["batteryLevelPercentStart"]?.jsonPrimitive?.content)
        assertEquals("unavailable", androidCond["isCharging"]?.jsonPrimitive?.content)

        val linuxCond = root["linuxConditions"]!!.jsonObject
        assertEquals("unavailable", linuxCond["kernelRelease"]?.jsonPrimitive?.content)
        assertEquals("unavailable", linuxCond["cgroupMemoryLimitBytes"]?.jsonPrimitive?.content)

        assertFalse("Serialized JSON must not emit null for missing metrics", jsonText.contains(": null"))
    }

    @Test
    fun sanitizerRedactsInvitationUrisTokensPinsAndUserPathsAndBoundsLength() {
        val raw = "Failed while handling glide://pair/v1#host=10.0.2.2&port=8443&token=secret123&spki=abcd " +
            "with pin=654321 password=hunter2 privatekey=deadbeef at /home/alice/secret C:\\Users\\Bob\\Docs " +
            "x".repeat(700)

        val sanitized = R3DiagnosticSanitizer.sanitize(raw)

        assertFalse(sanitized.contains("secret123"))
        assertFalse(sanitized.contains("654321"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("deadbeef"))
        assertFalse(sanitized.contains("/home/alice"))
        assertFalse(sanitized.contains("C:\\Users\\Bob"))
        assertTrue(sanitized.contains("glide://pair/[REDACTED]"))
        assertTrue(sanitized.contains("pin=[REDACTED]"))
        assertTrue(sanitized.contains("password=[REDACTED]"))
        assertTrue(sanitized.contains("privatekey=[REDACTED]"))
        assertTrue(sanitized.length <= R3DiagnosticConstants.MAX_TERMINAL_MESSAGE_LENGTH)
    }

    @Test
    fun sanitizerCapsOneSecondRatesAndFiveSecondWindows() {
        val rates = List(R3DiagnosticConstants.MAX_ONE_SECOND_SAMPLES + 500) { 10.0 }
        val windows = List(R3DiagnosticConstants.MAX_FIVE_SECOND_WINDOWS + 100) { idx ->
            R3FiveSecondRateWindow(idx, idx * 5.0, (idx + 1) * 5.0, 50_000_000L, 10.0)
        }

        val report = R3TransferDiagnosticReport(
            attemptId = "bounded-001",
            timestampUtc = "2026-11-19T10:00:00Z",
            appVersion = "1.0.0",
            platform = R3PlatformInfo("android", "Android 17", "x86_64", "android-emulator", "Pixel_10a"),
            runtime = R3RuntimeInfo("ART", "37", "Release"),
            testConfiguration = R3TestConfiguration("sender", "Send"),
            outcome = R3OutcomeInfo("Completed", success = true, rejected = false, cancelled = false),
            throughput = R3ThroughputInfo(
                oneSecondRatesMBps = rates,
                fiveSecondRateWindowsMBps = windows
            )
        ).sanitized()

        assertEquals(R3DiagnosticConstants.MAX_ONE_SECOND_SAMPLES, report.throughput.oneSecondRatesMBps.size)
        assertEquals(R3DiagnosticConstants.MAX_FIVE_SECOND_WINDOWS, report.throughput.fiveSecondRateWindowsMBps.size)
    }

    @Test
    fun throughputCalculatorComputesFiveSecondWindowsAndSteadyQuarters() {
        val oneSecBytes = List(10) { 100_000_000L } // 100 MB/s each second
        val windows = R3ThroughputCalculator.computeFiveSecondWindows(oneSecBytes)
        assertEquals(2, windows.size)
        assertEquals(100.0, windows[0].rateMBps, 0.01)
        assertEquals(500_000_000L, windows[1].bytes)

        val rates = listOf(100.0, 100.0, 95.0, 95.0, 90.0, 90.0, 90.0, 90.0)
        val (firstQ, finalQ, ratio) = R3ThroughputCalculator.computeSteadyQuarters(rates)
        assertEquals(100.0, (firstQ as R3Double.Available).value, 0.01)
        assertEquals(90.0, (finalQ as R3Double.Available).value, 0.01)
        assertEquals(0.90, (ratio as R3Double.Available).value, 0.001)
    }

    @Test
    fun sampleJsonlEmitsSchemaVersionAndSanitizesSecrets() {
        val sample = R3DiagnosticSample(
            timestampUtc = "2026-11-19T10:00:01Z",
            attemptId = "sample-001",
            direction = "Receive",
            phase = "TransferringItem",
            elapsedSeconds = 1.0,
            terminalStatus = "token=abc123 glide://pair/v1#secret=xyz"
        )
        val line = sample.toJsonLine()
        assertTrue(line.contains("\"schemaVersion\":\"glide-r3-sample-v1\""))
        assertFalse(line.contains("abc123"))
        assertFalse(line.contains("xyz"))
        assertTrue(line.contains("glide://pair/[REDACTED]"))
    }
}
