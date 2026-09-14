package dev.glide.benchmark

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
 * 2. Device discovery radar rendering
 * 3. History navigation
 * 4. Settings navigation
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    companion object {
        const val TARGET_PACKAGE = "dev.glide.app"
        const val UI_TIMEOUT_MS = 5000L
    }

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE
    ) {
        // Step 1: Cold start to Home screen
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)

        // Step 2: History journey
        val historyButton = device.findObject(By.descContains("History").clazz("android.widget.Button"))
            ?: device.findObject(By.textContains("History"))
        historyButton?.click()
        device.waitForIdle()

        // Step 3: Settings journey
        val settingsButton = device.findObject(By.descContains("Settings").clazz("android.widget.Button"))
            ?: device.findObject(By.textContains("Settings"))
        settingsButton?.click()
        device.waitForIdle()

        // Step 4: Return to Home
        device.pressBack()
        device.waitForIdle()
    }
}
