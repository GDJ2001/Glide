package dev.glide.app.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * GLIDE-061: Store Assets, Graphics & Legal/Privacy Listing Compliance Test Suite.
 *
 * Verifies:
 * 1. Google Play Store icon dimensions (512x512 px) and file size (<= 1024 KiB).
 * 2. Feature graphic dimensions (1024x500 px) and file size (<= 15 MiB).
 * 3. Curated phone screenshots (>= 4 items, valid aspect ratios between 320px and 3840px, no alpha).
 * 4. Curated tablet screenshots (>= 2 items, valid dimensions, no alpha).
 * 4b. Dedicated 7-inch tablet screenshots (>= 4 items, valid dimensions, no alpha).
 * 4c. Dedicated 10-inch tablet screenshots (>= 4 items, valid dimensions, no alpha).
 * 5. Store listing text constraints (Title <= 30 chars, Short Description <= 80 chars, Full Description <= 4000 chars).
 * 6. Policy documentation completeness: Privacy Policy, Data Safety, Foreground Service Declaration, and OSS Licenses.
 */
class StoreAssetsAndListingComplianceTest {

    private fun resolveProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            if (File(current, "doc").isDirectory || File(current, "docs").isDirectory) {
                return current
            }
            current = current.parentFile
        }
        return File(".").canonicalFile
    }

    // -------------------------------------------------------------------------
    // 1. App Icon (512x512 px)
    // -------------------------------------------------------------------------

    @Test
    fun test1_appIcon_dimensionsAndFileSize() {
        val root = resolveProjectRoot()
        val iconFile = File(root, "doc/release/store_assets/icon_512.png")
        assertTrue("icon_512.png must exist at ${iconFile.absolutePath}", iconFile.exists())
        assertTrue("Icon file size must be <= 1 MB (was ${iconFile.length()} bytes)", iconFile.length() <= 1024 * 1024)

        val image = ImageIO.read(iconFile)
        assertNotNull("icon_512.png must be a readable image format", image)
        assertEquals("Icon width must be exactly 512 px", 512, image.width)
        assertEquals("Icon height must be exactly 512 px", 512, image.height)
    }

    // -------------------------------------------------------------------------
    // 2. Feature Graphic (1024x500 px)
    // -------------------------------------------------------------------------

    @Test
    fun test2_featureGraphic_dimensionsAndFileSize() {
        val root = resolveProjectRoot()
        val fgFile = File(root, "doc/release/store_assets/feature_graphic_1024x500.png")
        assertTrue("feature_graphic_1024x500.png must exist at ${fgFile.absolutePath}", fgFile.exists())
        assertTrue("Feature graphic size must be <= 15 MB (was ${fgFile.length()} bytes)", fgFile.length() <= 15 * 1024 * 1024)

        val image = ImageIO.read(fgFile)
        assertNotNull("feature_graphic_1024x500.png must be a readable image format", image)
        assertEquals("Feature graphic width must be exactly 1024 px", 1024, image.width)
        assertEquals("Feature graphic height must be exactly 500 px", 500, image.height)
        assertFalse("Feature graphic must not have alpha channel: ${fgFile.name}", image.colorModel.hasAlpha())
    }

    // -------------------------------------------------------------------------
    // 3. Phone Screenshots (Min 2, Max 8, 320px to 3840px, No Alpha)
    // -------------------------------------------------------------------------

    @Test
    fun test3_phoneScreenshots_presenceAndAspectRatio() {
        val root = resolveProjectRoot()
        val phoneDir = File(root, "doc/release/store_assets/screenshots/phone")
        assertTrue("Phone screenshots directory must exist", phoneDir.isDirectory)

        val screenshots = phoneDir.listFiles { file -> file.extension.lowercase() == "png" } ?: emptyArray()
        assertTrue("Must have at least 4 curated phone screenshots, got: ${screenshots.size}", screenshots.size >= 4)
        assertTrue("Google Play allows maximum 8 screenshots per device type", screenshots.size <= 8)

        for (screen in screenshots) {
            val image = ImageIO.read(screen)
            assertNotNull("Screenshot must be readable: ${screen.name}", image)

            val minDim = minOf(image.width, image.height)
            val maxDim = maxOf(image.width, image.height)

            assertTrue("Min dimension must be >= 320 px for ${screen.name}", minDim >= 320)
            assertTrue("Max dimension must be <= 3840 px for ${screen.name}", maxDim <= 3840)
            assertTrue("Max dimension must be <= 2 * min dimension for ${screen.name}", maxDim <= 2 * minDim)
            assertFalse("Phone screenshot must not have alpha channel: ${screen.name}", image.colorModel.hasAlpha())
            assertTrue("Screenshot file size must be <= 8 MB for ${screen.name}", screen.length() <= 8 * 1024 * 1024)

            // Phone screenshots should be portrait orientation
            assertTrue("Phone screenshot should be in portrait orientation: ${screen.name}", image.height >= image.width)
        }
    }

    // -------------------------------------------------------------------------
    // 4. Tablet Screenshots (General, Min 2, Max 8)
    // -------------------------------------------------------------------------

    @Test
    fun test4_tabletScreenshots_presenceAndFormat() {
        val root = resolveProjectRoot()
        val tabletDir = File(root, "doc/release/store_assets/screenshots/tablet")
        assertTrue("Tablet screenshots directory must exist", tabletDir.isDirectory)

        val screenshots = tabletDir.listFiles { file -> file.extension.lowercase() == "png" } ?: emptyArray()
        assertTrue("Must have at least 2 curated tablet screenshots, got: ${screenshots.size}", screenshots.size >= 2)
        assertTrue("Google Play allows maximum 8 screenshots per device type", screenshots.size <= 8)

        for (screen in screenshots) {
            val image = ImageIO.read(screen)
            assertNotNull("Tablet screenshot must be readable: ${screen.name}", image)

            val minDim = minOf(image.width, image.height)
            val maxDim = maxOf(image.width, image.height)

            assertTrue("Min dimension must be >= 320 px for ${screen.name}", minDim >= 320)
            assertTrue("Max dimension must be <= 3840 px for ${screen.name}", maxDim <= 3840)
            assertTrue("Max dimension must be <= 2 * min dimension for ${screen.name}", maxDim <= 2 * minDim)
            assertFalse("Tablet screenshot must not have alpha channel: ${screen.name}", image.colorModel.hasAlpha())
            assertTrue("Tablet screenshot file size must be <= 8 MB for ${screen.name}", screen.length() <= 8 * 1024 * 1024)
        }
    }

    // -------------------------------------------------------------------------
    // 4b. 7-Inch Tablet Screenshots (Dedicated, Min 4, Max 8)
    // -------------------------------------------------------------------------

    @Test
    fun test4b_7inchTabletScreenshots_presenceAndFormat() {
        val root = resolveProjectRoot()
        val tablet7Dir = File(root, "doc/release/store_assets/screenshots/tablet_7inch")
        assertTrue("7-inch tablet screenshots directory must exist", tablet7Dir.isDirectory)

        val screenshots = tablet7Dir.listFiles { file -> file.extension.lowercase() == "png" } ?: emptyArray()
        assertTrue("Must have at least 4 curated 7-inch tablet screenshots, got: ${screenshots.size}", screenshots.size >= 4)
        assertTrue("Google Play allows maximum 8 screenshots per device type", screenshots.size <= 8)

        for (screen in screenshots) {
            val image = ImageIO.read(screen)
            assertNotNull("7-inch tablet screenshot must be readable: ${screen.name}", image)

            val minDim = minOf(image.width, image.height)
            val maxDim = maxOf(image.width, image.height)

            assertTrue("Min dimension must be >= 320 px for ${screen.name}", minDim >= 320)
            assertTrue("Max dimension must be <= 3840 px for ${screen.name}", maxDim <= 3840)
            assertTrue("Max dimension must be <= 2 * min dimension for ${screen.name}", maxDim <= 2 * minDim)
            assertFalse("7-inch tablet screenshot must not have alpha channel: ${screen.name}", image.colorModel.hasAlpha())
            assertTrue("7-inch tablet screenshot file size must be <= 8 MB for ${screen.name}", screen.length() <= 8 * 1024 * 1024)
        }
    }

    // -------------------------------------------------------------------------
    // 4c. 10-Inch Tablet Screenshots (Dedicated, Min 4, Max 8)
    // -------------------------------------------------------------------------

    @Test
    fun test4c_10inchTabletScreenshots_presenceAndFormat() {
        val root = resolveProjectRoot()
        val tablet10Dir = File(root, "doc/release/store_assets/screenshots/tablet_10inch")
        assertTrue("10-inch tablet screenshots directory must exist", tablet10Dir.isDirectory)

        val screenshots = tablet10Dir.listFiles { file -> file.extension.lowercase() == "png" } ?: emptyArray()
        assertTrue("Must have at least 4 curated 10-inch tablet screenshots, got: ${screenshots.size}", screenshots.size >= 4)
        assertTrue("Google Play allows maximum 8 screenshots per device type", screenshots.size <= 8)

        for (screen in screenshots) {
            val image = ImageIO.read(screen)
            assertNotNull("10-inch tablet screenshot must be readable: ${screen.name}", image)

            val minDim = minOf(image.width, image.height)
            val maxDim = maxOf(image.width, image.height)

            assertTrue("Min dimension must be >= 320 px for ${screen.name}", minDim >= 320)
            assertTrue("Max dimension must be <= 3840 px for ${screen.name}", maxDim <= 3840)
            assertTrue("Max dimension must be <= 2 * min dimension for ${screen.name}", maxDim <= 2 * minDim)
            assertFalse("10-inch tablet screenshot must not have alpha channel: ${screen.name}", image.colorModel.hasAlpha())
            assertTrue("10-inch tablet screenshot file size must be <= 8 MB for ${screen.name}", screen.length() <= 8 * 1024 * 1024)
        }
    }

    // -------------------------------------------------------------------------
    // 5. Store Listing Text Constraints
    // -------------------------------------------------------------------------

    @Test
    fun test5_storeListingMetadata_characterLimitCompliance() {
        val root = resolveProjectRoot()
        val metadataFile = File(root, "doc/release/store_assets/STORE_LISTING_METADATA.md")
        assertTrue("STORE_LISTING_METADATA.md must exist", metadataFile.exists())

        val content = metadataFile.readText()

        // Extract Title
        val titleMatch = Regex("""### App Title[^\n]*\n```text\n([^\n]+)\n```""").find(content)
        assertNotNull("App title block must be present", titleMatch)
        val title = titleMatch!!.groupValues[1].trim()
        assertTrue("App Title length must be in 1..30 characters (was ${title.length}: '$title')", title.length in 1..30)

        // Extract Short Description
        val shortDescMatch = Regex("""### Short Description[^\n]*\n```text\n([^\n]+)\n```""").find(content)
        assertNotNull("Short description block must be present", shortDescMatch)
        val shortDesc = shortDescMatch!!.groupValues[1].trim()
        assertTrue(
            "Short description length must be in 1..80 characters (was ${shortDesc.length}: '$shortDesc')",
            shortDesc.length in 1..80
        )

        // Extract Full Description
        val fullDescMatch = Regex("""### Full Description[^\n]*\n```text\n([\s\S]+?)\n```""").find(content)
        assertNotNull("Full description block must be present", fullDescMatch)
        val fullDesc = fullDescMatch!!.groupValues[1].trim()
        assertTrue(
            "Full description length must be in 1..4000 characters (was ${fullDesc.length})",
            fullDesc.length in 1..4000
        )

        // Truthful description assertions: must mention offline, peer-to-peer, and zero cloud
        assertTrue("Full description must disclose offline capability", fullDesc.contains("offline", ignoreCase = true))
        assertTrue("Full description must disclose peer-to-peer nature", fullDesc.contains("peer-to-peer", ignoreCase = true))
        assertTrue("Full description must disclose zero cloud storage", fullDesc.contains("cloud", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // 6. Policy & Legal Documentation Completeness
    // -------------------------------------------------------------------------

    @Test
    fun test6_policyDocuments_integrityAndCompleteness() {
        val root = resolveProjectRoot()

        // 1. Privacy Policy
        val privacyFile = File(root, "doc/release/PRIVACY_POLICY.md")
        assertTrue("PRIVACY_POLICY.md must exist", privacyFile.exists())
        assertTrue("Privacy policy must be comprehensive (> 2,000 bytes)", privacyFile.length() > 2000L)
        val privacyContent = privacyFile.readText()
        assertTrue(privacyContent.contains("No User Accounts"))
        assertTrue(privacyContent.contains("Zero Cloud Storage"))
        assertTrue(privacyContent.contains("NEARBY_WIFI_DEVICES"))
        assertTrue(privacyContent.contains("FOREGROUND_SERVICE"))

        // 2. Data Safety Declaration
        val dataSafetyFile = File(root, "doc/release/DATA_SAFETY_DECLARATION.md")
        assertTrue("DATA_SAFETY_DECLARATION.md must exist", dataSafetyFile.exists())
        assertTrue("Data safety doc must be comprehensive (> 1,500 bytes)", dataSafetyFile.length() > 1500L)
        val dataSafetyContent = dataSafetyFile.readText()
        assertTrue(dataSafetyContent.contains("Encryption in Transit"))
        assertTrue(dataSafetyContent.contains("TLS/DTLS"))

        // 3. Foreground Service Declaration
        val fgsFile = File(root, "doc/release/FOREGROUND_SERVICE_DECLARATION.md")
        assertTrue("FOREGROUND_SERVICE_DECLARATION.md must exist", fgsFile.exists())
        assertTrue("FGS declaration must be comprehensive (> 1,500 bytes)", fgsFile.length() > 1500L)
        val fgsContent = fgsFile.readText()
        assertTrue(fgsContent.contains("connectedDevice"))
        assertTrue(fgsContent.contains("TransferService"))
        assertTrue(fgsContent.contains("Demonstration Runbook"))

        // 4. Open Source Licenses
        val ossFile = File(root, "doc/release/OPEN_SOURCE_LICENSES.md")
        assertTrue("OPEN_SOURCE_LICENSES.md must exist", ossFile.exists())
        assertTrue("OSS licenses must be comprehensive (> 2,000 bytes)", ossFile.length() > 2000L)
        val ossContent = ossFile.readText()
        assertTrue(ossContent.contains("Apache License"))
        assertTrue(ossContent.contains("JetBrains"))
        assertTrue(ossContent.contains("Android Open Source Project"))
    }
}
