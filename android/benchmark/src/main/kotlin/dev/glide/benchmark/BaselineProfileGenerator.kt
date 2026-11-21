package dev.glide.benchmark

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates Baseline Profile rules for Glide using [BaselineProfileRule] (GLIDE-052).
 *
 * Traverses critical journeys:
 * 1. Cold startup to Home
 * 2. History navigation
 * 3. Settings navigation
 * 4. Simulated active transfer display journey (progress, verifying, saving, completed)
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    companion object {
        const val TARGET_PACKAGE = "dev.glide.app"
        const val TARGET_ACTIVITY = "dev.glide.app.MainActivity"
        const val UI_TIMEOUT_MS = 5000L
    }

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE
    ) {
        // Step 1: Cold start to interactive Home screen
        pressHome()
        startActivityAndWait()
        checkNotNull(device.wait(Until.findObject(By.text("Send files")), UI_TIMEOUT_MS)) {
            "Home screen did not render during baseline profile generation"
        }

        // Step 2: History journey
        val historyNav = checkNotNull(
            device.wait(Until.findObject(By.res("nav_history")), UI_TIMEOUT_MS)
                ?: device.wait(Until.findObject(By.descContains("Navigate to History")), 1000L)
        ) { "History navigation target not found" }
        historyNav.click()
        checkNotNull(device.wait(Until.findObject(By.text("Transfer history")), UI_TIMEOUT_MS)) {
            "History screen did not render"
        }

        // Step 3: Settings journey
        val settingsNav = checkNotNull(
            device.wait(Until.findObject(By.res("nav_settings")), UI_TIMEOUT_MS)
                ?: device.wait(Until.findObject(By.descContains("Navigate to Settings")), 1000L)
        ) { "Settings navigation target not found" }
        settingsNav.click()
        checkNotNull(device.wait(Until.findObject(By.desc("Navigate back from settings")), UI_TIMEOUT_MS)) {
            "Settings screen did not render"
        }

        // Step 4: Return to Home
        device.pressBack()
        checkNotNull(device.wait(Until.findObject(By.text("Send files")), UI_TIMEOUT_MS)) {
            "Failed to return to Home screen"
        }

        // Step 5: Simulated transfer display journey
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(TARGET_PACKAGE, TARGET_ACTIVITY)
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("benchmark_scenario", "simulated_transfer_journey")
        }
        startActivityAndWait(intent)
        checkNotNull(
            device.wait(Until.findObject(By.res("transfer_progress_card")), UI_TIMEOUT_MS)
                ?: device.wait(Until.findObject(By.textContains("simulated_transfer_benchmark.bin")), 1000L)
        ) { "TransferProgressCard did not render during baseline profile generation" }
        checkNotNull(device.wait(Until.findObject(By.text("Verifying file integrity")), UI_TIMEOUT_MS)) {
            "Verifying file integrity state did not render during baseline profile generation"
        }
        checkNotNull(device.wait(Until.findObject(By.textContains("Saving verified files")), UI_TIMEOUT_MS)) {
            "Saving verified files state did not render during baseline profile generation"
        }
        checkNotNull(device.wait(Until.findObject(By.text("Transfer complete")), UI_TIMEOUT_MS)) {
            "Transfer complete state did not render during baseline profile generation"
        }
        device.waitForIdle()
    }
}
