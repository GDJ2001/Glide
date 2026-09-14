package dev.glide.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiIsolationTest {

    @Test
    fun isolate_wrapsTextInFsiAndPdi() {
        val filename = "photo_2026.jpg"
        val isolated = filename.isolateBidi()

        assertEquals("\u2068photo_2026.jpg\u2069", isolated)
        assertTrue(isolated.startsWith(BidiIsolation.FSI))
        assertTrue(isolated.endsWith(BidiIsolation.PDI))
    }

    @Test
    fun isolate_handlesEmptyStringGracefully() {
        val empty = ""
        assertEquals("", empty.isolateBidi())
    }

    @Test
    fun isolate_mixedScriptFilenamePreservesContent() {
        val hebrewFilename = "תמונה_01.png"
        val isolated = hebrewFilename.isolateBidi()

        assertEquals("\u2068תמונה_01.png\u2069", isolated)
        assertEquals(hebrewFilename, BidiIsolation.stripIsolates(isolated))
    }

    @Test
    fun isolateCode_wrapsDigitsInLriAndPdi() {
        val digits = "482916"
        val isolated = digits.isolateBidiCode()

        assertEquals("\u2066482916\u2069", isolated)
        assertTrue(isolated.startsWith(BidiIsolation.LRI))
        assertTrue(isolated.endsWith(BidiIsolation.PDI))
    }

    @Test
    fun progressThrottler_announcesOnlyAt10PercentMilestones() {
        val throttler = AccessibilityProgressThrottler()

        // Initial announcement at 0%
        val first = throttler.checkAnnouncement(percent = 0, phase = "Transferring", itemName = "doc.pdf")
        assertNotNull(first)
        assertEquals("Transferring for doc.pdf: 0% complete.", first)

        // Rapid updates between 1% and 9% must be throttled (return null)
        for (p in 1..9) {
            val throttled = throttler.checkAnnouncement(percent = p, phase = "Transferring", itemName = "doc.pdf")
            assertNull("Progress at $p% must be suppressed", throttled)
        }

        // Milestone at 10% must announce
        val ten = throttler.checkAnnouncement(percent = 10, phase = "Transferring", itemName = "doc.pdf")
        assertNotNull(ten)
        assertEquals("Transferring for doc.pdf: 10% complete.", ten)

        // 11% to 19% suppressed
        for (p in 11..19) {
            assertNull(throttler.checkAnnouncement(percent = p, phase = "Transferring", itemName = "doc.pdf"))
        }

        // Milestone at 20%
        val twenty = throttler.checkAnnouncement(percent = 20, phase = "Transferring", itemName = "doc.pdf")
        assertNotNull(twenty)
        assertEquals("Transferring for doc.pdf: 20% complete.", twenty)
    }

    @Test
    fun progressThrottler_announcesOnPhaseChangeImmediately() {
        val throttler = AccessibilityProgressThrottler()

        val sending = throttler.checkAnnouncement(percent = 45, phase = "Sending", itemName = "video.mp4")
        assertNotNull(sending)
        assertEquals("Sending for video.mp4: 40% complete.", sending)

        // Phase change to Verifying at same progress bucket must trigger an announcement
        val verifying = throttler.checkAnnouncement(percent = 45, phase = "Verifying", itemName = "video.mp4")
        assertNotNull(verifying)
        assertEquals("Verifying for video.mp4.", verifying)
    }

    @Test
    fun progressThrottler_resetClearsHistory() {
        val throttler = AccessibilityProgressThrottler()
        throttler.checkAnnouncement(percent = 50, phase = "Receiving")
        assertEquals(50, throttler.currentBucket)

        throttler.reset()
        assertNull(throttler.currentBucket)
        assertNull(throttler.currentPhase)

        // After reset, 50% should announce again
        val afterReset = throttler.checkAnnouncement(percent = 50, phase = "Receiving")
        assertNotNull(afterReset)
    }
}
