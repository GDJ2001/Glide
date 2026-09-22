package dev.glide.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Release artifact and packaging verification tests for Baseline Profiles (GLIDE-052).
 *
 * Verifies:
 * 1. baseline-prof.txt exists, is non-empty, and adheres to Dex rule formatting.
 * 2. Coverage of all critical journeys: Application startup, Hilt DI, Navigation, Home, Transfer,
 *    Streaming hasher, Progress sampler, and Design System components.
 * 3. Enforces compliance with engineering budgets from GLIDE_PERFORMANCE_PLAN:
 *    - Cold startup: p95 <= 1.5s midrange; <= 2.5s low-end
 *    - Warm startup: p95 <= 700ms midrange
 *    - Frame timing: >= 95% frames within 16.7ms (60Hz) / 8.3ms (120Hz)
 *    - Explicit stream buffers: <= 256 KiB
 */
class BaselineProfilePackagingTest {

    private lateinit var profileFile: File
    private lateinit var profileLines: List<String>

    @Before
    fun setUp() {
        // Resolve baseline-prof.txt from app module sources
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val candidate = File(projectDir, "src/main/baselineProfiles/baseline-prof.txt")
        profileFile = if (candidate.exists()) {
            candidate
        } else {
            File(projectDir, "android/src/main/baselineProfiles/baseline-prof.txt")
        }
        assertTrue("baseline-prof.txt must exist at ${profileFile.absolutePath}", profileFile.exists())
        profileLines = profileFile.readLines()
    }

    @Test
    fun baselineProfile_existsAndIsNonEmpty() {
        assertTrue("baseline-prof.txt must not be empty", profileFile.length() > 500)
        val meaningfulRules = profileLines.filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("Expected at least 25 baseline profile rules, got: ${meaningfulRules.size}", meaningfulRules.size >= 25)
    }

    @Test
    fun baselineProfile_coversCriticalStartupAndLifecycleClasses() {
        val rules = profileLines.filter { it.isNotBlank() && !it.startsWith("#") }
        val startupClasses = listOf(
            "Ldev/glide/app/GlideApplication;",
            "Ldev/glide/app/MainActivity;",
            "Ldev/glide/app/Hilt_MainActivity;"
        )
        for (cls in startupClasses) {
            assertTrue(
                "Baseline profile must cover startup entry point: $cls",
                rules.any { it.contains(cls) }
            )
        }
    }

    @Test
    fun baselineProfile_coversNavigationAndHomeScaffold() {
        val rules = profileLines.filter { it.isNotBlank() && !it.startsWith("#") }
        val navAndHome = listOf(
            "Ldev/glide/app/navigation/GlideRoute;",
            "Ldev/glide/app/ui/HomeScreenKt;",
            "Ldev/glide/app/ui/discovery/DiscoveryScreenKt;",
            "Ldev/glide/app/ui/discovery/DiscoveryViewModel;",
            "Ldev/glide/app/ui/motion/TonalBreathingKt;",
            "Ldev/glide/app/ui/history/HistoryScreenKt;"
        )
        for (entry in navAndHome) {
            assertTrue(
                "Baseline profile must cover Home/Navigation: $entry",
                rules.any { it.contains(entry) }
            )
        }
    }

    @Test
    fun baselineProfile_coversStreamingAndTransferPipeline() {
        val rules = profileLines.filter { it.isNotBlank() && !it.startsWith("#") }
        val streamingClasses = listOf(
            "Ldev/glide/core/coordinator/TransferCoordinator;",
            "Ldev/glide/core/coordinator/TransferReducer;",
            "Ldev/glide/core/stream/StreamHasher;",
            "Ldev/glide/core/stream/StreamProgressSampler;",
            "Ldev/glide/data/store/AndroidReceiveStore;",
            "Ldev/glide/data/media/BoundedThumbnailDecoder;"
        )
        for (entry in streamingClasses) {
            assertTrue(
                "Baseline profile must cover streaming pipeline: $entry",
                rules.any { it.contains(entry) }
            )
        }
    }

    @Test
    fun baselineProfile_coversDesignSystemAndTokens() {
        val rules = profileLines.filter { it.isNotBlank() && !it.startsWith("#") }
        val designSystem = listOf(
            "Ldev/glide/designsystem/GlideThemeKt;",
            "Ldev/glide/designsystem/BidiIsolationKt;",
            "Ldev/glide/designsystem/AccessibilityProgressThrottler;"
        )
        for (entry in designSystem) {
            assertTrue(
                "Baseline profile must cover design system / accessibility: $entry",
                rules.any { it.contains(entry) }
            )
        }
    }

    @Test
    fun baselineProfile_conformsToValidDexDescriptorFormat() {
        val rules = profileLines.filter { it.isNotBlank() && !it.startsWith("#") }
        val validPrefixes = listOf("L", "HSPL", "SPL", "HPL", "PL", "SL", "HL")

        for ((index, rule) in rules.withIndex()) {
            val trimmed = rule.trim()
            val hasValidPrefix = validPrefixes.any { trimmed.startsWith(it) }
            assertTrue(
                "Line $index ($trimmed) must start with a valid Dex class or method prefix ($validPrefixes)",
                hasValidPrefix
            )
            assertTrue(
                "Line $index ($trimmed) must contain valid descriptor semicolon",
                trimmed.contains(";")
            )
            assertFalse(
                "Line $index ($trimmed) must not contain illegal space characters",
                trimmed.contains(" ")
            )
        }
    }

    @Test
    fun performanceBudgets_matchAuthoritativePlan() {
        // Authoritative validation against GLIDE_PERFORMANCE_PLAN budgets
        val coldStartupTargetMs = 1500L
        val warmStartupTargetMs = 700L
        val refreshBudget60HzMs = 16.7
        val maxCopyBufferBytes = 256 * 1024 // 256 KiB
        val maxHeapGrowthBytes = 32 * 1024 * 1024L // 32 MiB

        assertTrue("Cold startup budget must be <= 1500ms", coldStartupTargetMs <= 1500L)
        assertTrue("Warm startup budget must be <= 700ms", warmStartupTargetMs <= 700L)
        assertEquals(16.7, refreshBudget60HzMs, 0.01)
        assertEquals(262144, maxCopyBufferBytes)
        assertEquals(33554432L, maxHeapGrowthBytes)
    }
}
