package dev.glide.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Acceptance test suite for GLIDE-036: Faithful Android brand resource variants.
 *
 * Validates:
 * 1. Adaptive foreground and monochrome icons fit strictly within the 66dp circular safe zone
 *    (ensuring zero clipping across circle, squircle, and rounded rectangle launcher masks).
 * 2. Adaptive monochrome icon is a pure white alpha silhouette for Android 13+ themed icons.
 * 3. Notification small icon is a pure white alpha silhouette with status-bar safe margins.
 * 4. Manifest application tag declares both ic_launcher and ic_launcher_round.
 * 5. Adaptive icon XML integrates background, foreground, and monochrome layers.
 * 6. API 31+ theme styles define native windowSplashScreen attributes.
 */
class BrandResourcesAcceptanceTest {

    private val resDir = File("src/main/res").let {
        if (it.exists()) it else File("app/src/main/res")
    }

    private val manifestFile = File("src/main/AndroidManifest.xml").let {
        if (it.exists()) it else File("android/app/src/main/AndroidManifest.xml")
    }

    @Test
    fun manifest_specifiesAdaptiveAndRoundIcons() {
        assertTrue("Manifest must exist", manifestFile.exists())
        val content = manifestFile.readText()
        assertTrue(
            "Manifest application must define android:icon='@mipmap/ic_launcher'",
            content.contains("android:icon=\"@mipmap/ic_launcher\"")
        )
        assertTrue(
            "Manifest application must define android:roundIcon='@mipmap/ic_launcher_round'",
            content.contains("android:roundIcon=\"@mipmap/ic_launcher_round\"")
        )
    }

    @Test
    fun adaptiveIconXml_referencesForegroundBackgroundAndMonochrome() {
        val launcherXml = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml")
        val roundXml = File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml")

        assertTrue("ic_launcher.xml must exist in mipmap-anydpi-v26", launcherXml.exists())
        assertTrue("ic_launcher_round.xml must exist in mipmap-anydpi-v26", roundXml.exists())

        for (file in listOf(launcherXml, roundXml)) {
            val xml = file.readText()
            assertTrue("${file.name} must specify <background>", xml.contains("<background android:drawable=\"@color/ic_launcher_background\""))
            assertTrue("${file.name} must specify <foreground>", xml.contains("<foreground android:drawable=\"@mipmap/ic_launcher_foreground\""))
            assertTrue("${file.name} must specify <monochrome>", xml.contains("<monochrome android:drawable=\"@mipmap/ic_launcher_monochrome\""))
        }
    }

    @Test
    fun adaptiveForeground_fitsStrictlyWithinSafeZoneAcrossDensities() {
        val densities = mapOf(
            "mdpi" to 1.0,
            "hdpi" to 1.5,
            "xhdpi" to 2.0,
            "xxhdpi" to 3.0,
            "xxxhdpi" to 4.0
        )

        // Android Adaptive icon standard: 108dp canvas, safe zone diameter 66dp (radius 33dp).
        val maxSafeRadiusDp = 33.0

        for ((density, scale) in densities) {
            val file = File(resDir, "mipmap-$density/ic_launcher_foreground.png")
            assertTrue("Foreground icon for $density must exist: ${file.path}", file.exists())

            val image = ImageIO.read(file)
            assertNotNull("Must decode image for $density", image)

            val expectedPx = (108 * scale).toInt()
            assertEquals("Canvas width for $density must be $expectedPx", expectedPx, image.width)
            assertEquals("Canvas height for $density must be $expectedPx", expectedPx, image.height)

            val centerX = image.width / 2.0
            val centerY = image.height / 2.0

            var maxMeasuredRadiusDp = 0.0

            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val pixel = image.getRGB(x, y)
                    val alpha = (pixel shr 24) and 0xFF
                    if (alpha > 15) { // Visible non-transparent pixel
                        val dx = (x - centerX) / scale
                        val dy = (y - centerY) / scale
                        val radiusDp = Math.sqrt(dx * dx + dy * dy)
                        if (radiusDp > maxMeasuredRadiusDp) {
                            maxMeasuredRadiusDp = radiusDp
                        }
                    }
                }
            }

            assertTrue(
                "Max visible radius in $density was $maxMeasuredRadiusDp dp, exceeding safe zone radius $maxSafeRadiusDp dp",
                maxMeasuredRadiusDp <= maxSafeRadiusDp
            )
        }
    }

    @Test
    fun adaptiveMonochrome_isPureWhiteAlphaSilhouetteAndFitsSafeZone() {
        val file = File(resDir, "mipmap-xxhdpi/ic_launcher_monochrome.png")
        assertTrue("Monochrome icon must exist", file.exists())

        val image = ImageIO.read(file)
        val scale = 3.0 // xxhdpi
        val centerX = image.width / 2.0
        val centerY = image.height / 2.0
        val maxSafeRadiusDp = 33.0

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                val alpha = (pixel shr 24) and 0xFF
                if (alpha > 0) {
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    assertEquals("Monochrome RGB must be pure white (255) for pixel ($x, $y)", 255, red)
                    assertEquals("Monochrome RGB must be pure white (255) for pixel ($x, $y)", 255, green)
                    assertEquals("Monochrome RGB must be pure white (255) for pixel ($x, $y)", 255, blue)

                    if (alpha > 15) {
                        val dx = (x - centerX) / scale
                        val dy = (y - centerY) / scale
                        val radiusDp = Math.sqrt(dx * dx + dy * dy)
                        assertTrue(
                            "Monochrome pixel ($x, $y) radius $radiusDp dp exceeds safe zone $maxSafeRadiusDp dp",
                            radiusDp <= maxSafeRadiusDp
                        )
                    }
                }
            }
        }
    }

    @Test
    fun notificationSmallIcon_isAlphaSilhouetteAndPadded() {
        val densities = mapOf(
            "mdpi" to 24,
            "hdpi" to 36,
            "xhdpi" to 48,
            "xxhdpi" to 72,
            "xxxhdpi" to 96
        )

        for ((density, expectedPx) in densities) {
            val file = File(resDir, "drawable-$density/ic_notification.png")
            assertTrue("Notification icon for $density must exist", file.exists())

            val image = ImageIO.read(file)
            assertEquals("Canvas width for $density", expectedPx, image.width)
            assertEquals("Canvas height for $density", expectedPx, image.height)

            var visiblePixelCount = 0

            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val pixel = image.getRGB(x, y)
                    val alpha = (pixel shr 24) and 0xFF
                    if (alpha > 0) {
                        visiblePixelCount++
                        val red = (pixel shr 16) and 0xFF
                        val green = (pixel shr 8) and 0xFF
                        val blue = pixel and 0xFF
                        assertEquals("Notification RGB must be pure white (255) for pixel ($x, $y) in $density", 255, red)
                        assertEquals("Notification RGB must be pure white (255) for pixel ($x, $y) in $density", 255, green)
                        assertEquals("Notification RGB must be pure white (255) for pixel ($x, $y) in $density", 255, blue)
                    }
                }
            }

            assertTrue("Notification icon in $density must contain visible pixels", visiblePixelCount > 0)

            // Verify outer borders (0, 0) and edges are fully transparent to ensure safe margins
            assertEquals("Top-left pixel must be transparent", 0, (image.getRGB(0, 0) shr 24) and 0xFF)
            assertEquals("Top-right pixel must be transparent", 0, (image.getRGB(image.width - 1, 0) shr 24) and 0xFF)
            assertEquals("Bottom-left pixel must be transparent", 0, (image.getRGB(0, image.height - 1) shr 24) and 0xFF)
            assertEquals("Bottom-right pixel must be transparent", 0, (image.getRGB(image.width - 1, image.height - 1) shr 24) and 0xFF)
        }
    }

    @Test
    fun splashScreenTheme_configuresNativeAttributes() {
        val v31ThemeFile = File(resDir, "values-v31/themes.xml")
        assertTrue("values-v31/themes.xml must exist", v31ThemeFile.exists())

        val xml = v31ThemeFile.readText()
        assertTrue(
            "Must define android:windowSplashScreenAnimatedIcon",
            xml.contains("name=\"android:windowSplashScreenAnimatedIcon\"")
        )
        assertTrue(
            "Must define android:windowSplashScreenBackground",
            xml.contains("name=\"android:windowSplashScreenBackground\"")
        )
    }
}
