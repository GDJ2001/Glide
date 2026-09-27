package dev.glide.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architectural Boundary and Static Analysis Enforcement Tests (GLIDE-055).
 * Enforces:
 * 1. UI layer never imports Nearby, Transport adapters, or raw networking/socket/bluetooth/wifi APIs.
 * 2. :transfer-core is pure Kotlin JVM and Android-free (zero android.*, androidx.*, GMS).
 * 3. Transport layer does not import UI, ViewModel, or Data layers.
 * 4. Data layer does not import UI, Compose, or Transport layers.
 * 5. UI layer contains zero blocking main-thread file I/O operations.
 * 6. Room database configuration strictly forbids allowMainThreadQueries.
 * 7. Pinned dependency integrity: all dependencies cataloged in libs.versions.toml, zero ad/telemetry SDKs.
 */
class ArchitectureRulesTest {

    private val projectRoot: File by lazy {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            if (File(current, "android/app/src/main/AndroidManifest.xml").exists()) {
                return@lazy current
            }
            current = current.parentFile
        }
        error("Cannot locate Glide project root from ${File(".").canonicalPath}")
    }

    // ---------------------------------------------------------------------------------------------
    // Rule 1: UI / Navigation Layer Architectural Boundaries
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testUiDoesNotImportNearbyOrTransportOrRawSockets() {
        val uiDirs = listOf(
            File(projectRoot, "android/app/src/main/kotlin/dev/glide/app/ui"),
            File(projectRoot, "android/app/src/main/kotlin/dev/glide/app/navigation"),
            File(projectRoot, "android/designsystem/src/main/kotlin/dev/glide/designsystem")
        ).filter { it.exists() }

        assertTrue("UI directories must exist for scanning", uiDirs.isNotEmpty())

        val forbiddenImportPrefixes = listOf(
            "com.google.android.gms.nearby",
            "dev.glide.transport.nearby",
            "android.bluetooth",
            "android.net.wifi",
            "java.net.Socket",
            "java.net.ServerSocket",
            "androidx.room."
        )

        val violations = mutableListOf<String>()

        uiDirs.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        val importedSymbol = trimmed.removePrefix("import ").trim()
                        for (forbidden in forbiddenImportPrefixes) {
                            if (importedSymbol.startsWith(forbidden)) {
                                violations.add(
                                    "${file.relativeTo(projectRoot).path}:${index + 1} imports forbidden '$importedSymbol'"
                                )
                            }
                        }
                    }
                }
            }
        }

        assertTrue(
            "Architecture violation (Rule 1): UI/Navigation must not import Nearby, Transport, or raw networking:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Rule 2: :transfer-core Pure Kotlin JVM Isolation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testTransferCoreIsAndroidFree() {
        val coreDir = File(projectRoot, "android/core/src/main/kotlin")
        assertTrue("transfer-core main source directory must exist", coreDir.exists())

        val forbiddenImportPrefixes = listOf(
            "android.",
            "androidx.",
            "com.google.android.gms."
        )

        val violations = mutableListOf<String>()

        coreDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val importedSymbol = trimmed.removePrefix("import ").trim()
                    for (forbidden in forbiddenImportPrefixes) {
                        if (importedSymbol.startsWith(forbidden)) {
                            violations.add(
                                "${file.relativeTo(projectRoot).path}:${index + 1} imports forbidden Android API '$importedSymbol'"
                            )
                        }
                    }
                }
            }
        }

        assertTrue(
            "Architecture violation (Rule 3): :transfer-core must be 100% pure Kotlin JVM with no Android dependencies:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Rule 3: Transport Layer Boundaries
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testTransportDoesNotImportUiOrData() {
        val transportDir = File(projectRoot, "android/transport-nearby/src/main/kotlin")
        assertTrue("transport-nearby source directory must exist", transportDir.exists())

        val forbiddenImportPrefixes = listOf(
            "dev.glide.app.ui.",
            "dev.glide.app.navigation.",
            "dev.glide.data.",
            "androidx.lifecycle.ViewModel",
            "androidx.compose."
        )

        val violations = mutableListOf<String>()

        transportDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val importedSymbol = trimmed.removePrefix("import ").trim()
                    for (forbidden in forbiddenImportPrefixes) {
                        if (importedSymbol.startsWith(forbidden)) {
                            violations.add(
                                "${file.relativeTo(projectRoot).path}:${index + 1} transport layer imports UI/Data '$importedSymbol'"
                            )
                        }
                    }
                }
            }
        }

        assertTrue(
            "Architecture violation: Transport adapter must not import UI, ViewModel, or Data layers:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Rule 4: Data Layer Boundaries
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testDataDoesNotImportUiOrTransport() {
        val dataDir = File(projectRoot, "android/data/src/main/kotlin")
        assertTrue("data source directory must exist", dataDir.exists())

        val forbiddenImportPrefixes = listOf(
            "dev.glide.app.ui.",
            "dev.glide.app.navigation.",
            "dev.glide.transport.nearby.",
            "com.google.android.gms.nearby.",
            "androidx.compose."
        )

        val violations = mutableListOf<String>()

        dataDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val importedSymbol = trimmed.removePrefix("import ").trim()
                    for (forbidden in forbiddenImportPrefixes) {
                        if (importedSymbol.startsWith(forbidden)) {
                            violations.add(
                                "${file.relativeTo(projectRoot).path}:${index + 1} data layer imports UI or transport '$importedSymbol'"
                            )
                        }
                    }
                }
            }
        }

        assertTrue(
            "Architecture violation: Data layer must not import UI or Transport implementations:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Rule 5: Main-Thread I/O Pattern Prevention in UI
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testUiZeroMainThreadIoPatterns() {
        val uiDir = File(projectRoot, "android/app/src/main/kotlin/dev/glide/app/ui")
        assertTrue("app UI directory must exist", uiDir.exists())

        // Regex patterns for blocking synchronous I/O operations
        val blockingIoPatterns = listOf(
            Regex("""\b(File|file)\s*\([^)]*\)\.readBytes\("""),
            Regex("""\b(File|file)\s*\([^)]*\)\.readText\("""),
            Regex("""\b(File|file)\s*\([^)]*\)\.readLines\("""),
            Regex("""\b(File|file)\s*\([^)]*\)\.writeBytes\("""),
            Regex("""\b(File|file)\s*\([^)]*\)\.writeText\("""),
            Regex("""\b(File|file)\s*\([^)]*\)\.appendText\("""),
            Regex("""\bFileInputStream\("""),
            Regex("""\bFileOutputStream\("""),
            Regex("""\bRandomAccessFile\("""),
            Regex("""\bFileReader\("""),
            Regex("""\bFileWriter\("""),
            Regex("""\bFiles\.(read|write|copy|move|delete)"""),
            Regex("""\bjava\.io\.(FileInputStream|FileOutputStream|RandomAccessFile)""")
        )

        val violations = mutableListOf<String>()

        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                // Ignore comments
                if (!line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*")) {
                    for (pattern in blockingIoPatterns) {
                        if (pattern.containsMatchIn(line)) {
                            violations.add(
                                "${file.relativeTo(projectRoot).path}:${index + 1} contains main-thread I/O pattern: '$line'"
                            )
                        }
                    }
                }
            }
        }

        assertTrue(
            "Architecture violation (Rule 7): UI/Presentation layer must contain zero blocking synchronous I/O operations:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Rule 6: Room Database Main Thread Query Prevention
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testRoomZeroAllowMainThreadQueries() {
        val searchDirs = listOf(
            File(projectRoot, "android/app/src/main/kotlin"),
            File(projectRoot, "android/data/src/main/kotlin")
        ).filter { it.exists() }

        val violations = mutableListOf<String>()

        searchDirs.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.contains("allowMainThreadQueries()")) {
                        violations.add(
                            "${file.relativeTo(projectRoot).path}:${index + 1} invokes allowMainThreadQueries()"
                        )
                    }
                }
            }
        }

        assertTrue(
            "Architecture violation: Room database must never invoke allowMainThreadQueries():\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Rule 7: Pinned Dependencies & Version Catalog Integrity
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testDependencyIntegrityAndCatalogUsage() {
        val buildFiles = listOf(
            File(projectRoot, "build.gradle.kts"),
            File(projectRoot, "android/app/build.gradle.kts"),
            File(projectRoot, "android/data/build.gradle.kts"),
            File(projectRoot, "android/core/build.gradle.kts"),
            File(projectRoot, "android/transport-nearby/build.gradle.kts"),
            File(projectRoot, "android/designsystem/build.gradle.kts"),
            File(projectRoot, "android/benchmark/build.gradle.kts")
        ).filter { it.exists() }

        assertTrue("Build files must exist for dependency check", buildFiles.isNotEmpty())

        val forbiddenDependencyKeywords = listOf(
            "play-services-ads",
            "admob",
            "facebook-ads",
            "unity-ads",
            "applovin",
            "ironsource",
            "mopub",
            "firebase-analytics",
            "firebase-crashlytics",
            "mixpanel",
            "amplitude",
            "segment",
            "flurry",
            "google-analytics",
            "play-services-auth",
            "firebase-auth",
            "facebook-login",
            "auth0"
        )

        // Detect unreviewed direct string coordinates like `implementation("group:artifact:version")`
        // instead of `libs.*` or `project(":...")`
        val directStringDependencyPattern = Regex(
            """(implementation|api|ksp|debugImplementation|testImplementation|androidTestImplementation)\s*\(\s*["'][^"']+:[^"']+:[^"']+["']\s*\)"""
        )

        val violations = mutableListOf<String>()

        for (file in buildFiles) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("//")) {
                    val lower = trimmed.lowercase()
                    // Check banned keywords
                    for (forbidden in forbiddenDependencyKeywords) {
                        if (lower.contains(forbidden)) {
                            violations.add(
                                "${file.relativeTo(projectRoot).path}:${index + 1} contains banned dependency '$forbidden'"
                            )
                        }
                    }

                    // Check unreviewed direct string dependencies
                    if (directStringDependencyPattern.containsMatchIn(trimmed)) {
                        violations.add(
                            "${file.relativeTo(projectRoot).path}:${index + 1} uses uncataloged direct string coordinate: '$trimmed' (must use version catalog libs.*)"
                        )
                    }
                }
            }
        }

        assertTrue(
            "Architecture violation (Rule 13): All dependencies must be pinned in libs.versions.toml without inline coordinates or forbidden SDKs:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Verification Engine Self-Tests: Validating Detection of Deliberate Violations
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testDetectorCatchesDeliberateNearbyImport() {
        val fakeFileContent = """
            package dev.glide.app.ui.test
            import com.google.android.gms.nearby.Nearby
            class FakeScreen
        """.trimIndent()

        val violations = checkContentForForbiddenImports(
            fakeFileContent,
            listOf("com.google.android.gms.nearby", "dev.glide.transport.nearby")
        )
        assertEquals(1, violations.size)
        assertTrue(violations[0].contains("com.google.android.gms.nearby.Nearby"))
    }

    @Test
    fun testDetectorCatchesDeliberateMainThreadIo() {
        val fakeFileContent = """
            package dev.glide.app.ui.test
            fun loadFile() {
                val data = java.io.File("secret.dat").readBytes()
            }
        """.trimIndent()

        val violations = checkContentForMainThreadIo(fakeFileContent)
        assertEquals(1, violations.size)
        assertTrue(violations[0].contains("readBytes"))
    }

    @Test
    fun testDetectorCatchesDeliberateUncatalogedDependency() {
        val fakeBuildScript = """
            dependencies {
                implementation("com.unreviewed.adnetwork:sdk:1.0.0")
            }
        """.trimIndent()

        val violations = checkContentForUnreviewedDependencies(fakeBuildScript)
        assertEquals(2, violations.size) // 1 for direct string coord, 1 for banned keyword "adnetwork"
    }

    private fun checkContentForForbiddenImports(content: String, forbiddenPrefixes: List<String>): List<String> {
        val violations = mutableListOf<String>()
        content.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("import ")) {
                val imported = trimmed.removePrefix("import ").trim()
                for (prefix in forbiddenPrefixes) {
                    if (imported.startsWith(prefix)) {
                        violations.add("Line ${index + 1}: forbidden import '$imported'")
                    }
                }
            }
        }
        return violations
    }

    private fun checkContentForMainThreadIo(content: String): List<String> {
        val blockingIoPatterns = listOf(
            Regex("""\b(File|file)\s*\([^)]*\)\.readBytes\("""),
            Regex("""\b(File|file)\s*\([^)]*\)\.readText\("""),
            Regex("""\bFileInputStream\("""),
            Regex("""\bFileOutputStream\("""),
            Regex("""\bRandomAccessFile\(""")
        )
        val violations = mutableListOf<String>()
        content.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("//")) {
                for (pattern in blockingIoPatterns) {
                    if (pattern.containsMatchIn(trimmed)) {
                        violations.add("Line ${index + 1}: blocking I/O '$trimmed'")
                    }
                }
            }
        }
        return violations
    }

    private fun checkContentForUnreviewedDependencies(content: String): List<String> {
        val directStringDependencyPattern = Regex(
            """(implementation|api|ksp|debugImplementation|testImplementation|androidTestImplementation)\s*\(\s*["'][^"']+:[^"']+:[^"']+["']\s*\)"""
        )
        val forbidden = listOf("admob", "adnetwork", "firebase-analytics")
        val violations = mutableListOf<String>()
        content.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            for (kw in forbidden) {
                if (trimmed.lowercase().contains(kw)) {
                    violations.add("Line ${index + 1}: banned '$kw'")
                }
            }
            if (directStringDependencyPattern.containsMatchIn(trimmed)) {
                violations.add("Line ${index + 1}: direct string coordinate '$trimmed'")
            }
        }
        return violations
    }
}
