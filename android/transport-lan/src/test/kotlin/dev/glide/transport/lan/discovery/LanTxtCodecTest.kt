package dev.glide.transport.lan.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class LanTxtCodecTest {

    private val validPresence = "0123456789abcdef0123456789abcdef"

    @Test
    fun testValidRoundTrip() {
        val record = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = "Alice's Phone",
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )

        val encoded = LanTxtCodec.encode(record)
        val decoded = LanTxtCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(record.v, decoded!!.v)
        assertEquals(record.tv, decoded.tv)
        assertEquals(record.q, decoded.q)
        assertEquals(record.p, decoded.p)
        assertEquals(record.dn, decoded.dn)
        assertEquals(record.dt, decoded.dt)
    }

    @Test
    fun testUnknownKeysSafelyIgnoredWithinBudget() {
        val record = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = "Desktop Node",
            dt = LanTxtRecord.DEVICE_TYPE_DESKTOP
        )

        val encoded = LanTxtCodec.encode(record).toMutableMap()
        encoded["future_feature"] = "ignored_value".toByteArray(StandardCharsets.UTF_8)
        encoded["vendor_tag"] = "custom".toByteArray(StandardCharsets.UTF_8)

        val decoded = LanTxtCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("Desktop Node", decoded!!.dn)
        assertEquals(LanTxtRecord.DEVICE_TYPE_DESKTOP, decoded.dt)
    }

    @Test
    fun testExceeding512ByteBudgetReturnsNull() {
        val record = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = "Phone",
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )

        val encoded = LanTxtCodec.encode(record).toMutableMap()
        // Inject large attribute to push past 512 bytes
        encoded["huge_bloat"] = ByteArray(500) { 0x41 }

        assertTrue(LanTxtCodec.calculateTotalTxtBytes(encoded) > LanTxtRecord.MAX_TXT_BUDGET)
        assertNull(LanTxtCodec.decode(encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidPresenceHexRejected() {
        LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = "not_32_hex_chars_too_short",
            dn = "Phone",
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBlankDisplayNameRejected() {
        LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = "   ",
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDisplayNameExceeding64CodePointsRejected() {
        val longName = "A".repeat(65)
        LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = longName,
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDisplayNameExceeding128Utf8BytesRejected() {
        // Multi-byte Unicode characters (e.g. 4-byte emojis: 33 * 4 = 132 bytes > 128 bytes)
        val emojiName = "\uD83D\uDE80".repeat(33) // 33 rocket emojis = 33 code points, 132 bytes
        LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = emojiName,
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidDeviceTypeRejected() {
        LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = "Phone",
            dt = "smart_fridge"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUnsupportedVersionRejected() {
        LanTxtRecord(
            v = 2,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = "Phone",
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )
    }

    @Test
    fun testMissingRequiredKeysInDecodeReturnsNull() {
        val incompleteMap = mapOf(
            "v" to "1".toByteArray(StandardCharsets.US_ASCII),
            "tv" to "1".toByteArray(StandardCharsets.US_ASCII)
            // missing q, p, dn
        )
        assertNull(LanTxtCodec.decode(incompleteMap))
    }

    @Test
    fun testPairReadyAndTrustedReconnectRoundTrip() {
        val record = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = validPresence,
            dn = "Office PC",
            dt = LanTxtRecord.DEVICE_TYPE_DESKTOP,
            isPairReady = true,
            supportsTrustedReconnect = true
        )

        val encoded = LanTxtCodec.encode(record)
        val decoded = LanTxtCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded!!.isPairReady)
        assertTrue(decoded.supportsTrustedReconnect)
    }

    @Test
    fun testForbiddenKeysRejectedInDecode() {
        val base = LanTxtCodec.encode(
            LanTxtRecord(
                v = 1,
                tv = 1,
                q = 1,
                p = validPresence,
                dn = "Office PC",
                dt = LanTxtRecord.DEVICE_TYPE_DESKTOP
            )
        )

        for (forbiddenKey in listOf("spki", "pin", "token", "trust", "fp")) {
            val mutated = base.toMutableMap()
            mutated[forbiddenKey] = "secret".toByteArray(StandardCharsets.US_ASCII)
            assertNull("Expected $forbiddenKey to be rejected", LanTxtCodec.decode(mutated))
        }
    }
}
