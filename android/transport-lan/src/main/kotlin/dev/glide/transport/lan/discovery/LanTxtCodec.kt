package dev.glide.transport.lan.discovery

import java.nio.charset.StandardCharsets

/**
 * Validated domain model for Glide LAN DNS-SD TXT record (LAN-014, Section 11).
 *
 * TXT Contract:
 * - v=1 (Glide protocol version)
 * - tv=1 (LAN record profile)
 * - q=1 (invitation pairing required)
 * - p=<random 128-bit availability-presence ID, 32 lowercase hex>
 * - dn=<bounded display name, at most 64 Unicode code points and 128 UTF-8 bytes>
 * - dt=phone|tablet|desktop|unknown
 *
 * Invariants:
 * - Total TXT budget <= 512 bytes
 * - Unknown keys within budget are ignored
 * - No tokens, file names, or private key fingerprints permitted
 */
data class LanTxtRecord(
    val v: Int = 1,
    val tv: Int = 1,
    val q: Int = 1,
    val p: String,
    val dn: String,
    val dt: String = DEVICE_TYPE_PHONE,
    val isPairReady: Boolean = false,
    val supportsTrustedReconnect: Boolean = false
) {
    init {
        require(v == 1) { "Unsupported protocol version: $v, must be 1" }
        require(tv == 1) { "Unsupported record profile: $tv, must be 1" }
        require(q == 1) { "q must be 1 (pairing required), got: $q" }
        require(PRESENCE_REGEX.matches(p)) { "p must be 32 lowercase hex chars, got: '$p'" }
        require(dn.isNotBlank()) { "dn must not be blank" }
        require(dn.codePointCount(0, dn.length) <= MAX_DN_CODE_POINTS) {
            "dn exceeds $MAX_DN_CODE_POINTS code points: ${dn.codePointCount(0, dn.length)}"
        }
        require(dn.toByteArray(StandardCharsets.UTF_8).size <= MAX_DN_UTF8_BYTES) {
            "dn exceeds $MAX_DN_UTF8_BYTES UTF-8 bytes: ${dn.toByteArray(StandardCharsets.UTF_8).size}"
        }
        require(dt in VALID_DEVICE_TYPES) {
            "Invalid device type dt '$dt', must be one of $VALID_DEVICE_TYPES"
        }
    }

    companion object {
        const val MAX_TXT_BUDGET = 512
        const val MAX_DN_CODE_POINTS = 64
        const val MAX_DN_UTF8_BYTES = 128

        const val DEVICE_TYPE_PHONE = "phone"
        const val DEVICE_TYPE_TABLET = "tablet"
        const val DEVICE_TYPE_DESKTOP = "desktop"
        const val DEVICE_TYPE_UNKNOWN = "unknown"

        val VALID_DEVICE_TYPES = setOf(DEVICE_TYPE_PHONE, DEVICE_TYPE_TABLET, DEVICE_TYPE_DESKTOP, DEVICE_TYPE_UNKNOWN)
        val FORBIDDEN_KEYS = setOf("spki", "pin", "token", "trust", "fp")
        val PRESENCE_REGEX = Regex("^[0-9a-f]{32}$")
    }
}

/**
 * Strict canonical codec for DNS-SD TXT records enforcing Section 11 constraints and bounds.
 */
object LanTxtCodec {

    /**
     * Encodes a [LanTxtRecord] into a map of byte arrays suitable for [android.net.nsd.NsdServiceInfo].
     */
    fun encode(record: LanTxtRecord): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        map["v"] = record.v.toString().toByteArray(StandardCharsets.US_ASCII)
        map["tv"] = record.tv.toString().toByteArray(StandardCharsets.US_ASCII)
        map["q"] = record.q.toString().toByteArray(StandardCharsets.US_ASCII)
        map["p"] = record.p.lowercase().toByteArray(StandardCharsets.US_ASCII)
        map["dn"] = record.dn.toByteArray(StandardCharsets.UTF_8)
        map["dt"] = record.dt.toByteArray(StandardCharsets.US_ASCII)
        if (record.isPairReady) {
            map["pair"] = "1".toByteArray(StandardCharsets.US_ASCII)
        }
        if (record.supportsTrustedReconnect) {
            map["tr"] = "1".toByteArray(StandardCharsets.US_ASCII)
        }

        val totalBytes = calculateTotalTxtBytes(map)
        require(totalBytes <= LanTxtRecord.MAX_TXT_BUDGET) {
            "Encoded TXT record exceeds budget of ${LanTxtRecord.MAX_TXT_BUDGET} bytes: $totalBytes"
        }

        return map
    }

    /**
     * Decodes and validates attributes into a [LanTxtRecord].
     * Returns null if required keys are missing, corrupt, out-of-bounds, or if forbidden keys are present.
     * Unknown keys within the 512-byte budget are safely ignored.
     */
    fun decode(attributes: Map<String, ByteArray?>): LanTxtRecord? {
        val totalBytes = calculateTotalTxtBytes(attributes)
        if (totalBytes > LanTxtRecord.MAX_TXT_BUDGET) {
            return null
        }

        for (key in attributes.keys) {
            if (key.lowercase() in LanTxtRecord.FORBIDDEN_KEYS) {
                return null
            }
        }

        return try {
            val vStr = attributes["v"]?.let { String(it, StandardCharsets.US_ASCII) } ?: return null
            val v = vStr.toIntOrNull() ?: return null

            val tvStr = attributes["tv"]?.let { String(it, StandardCharsets.US_ASCII) } ?: return null
            val tv = tvStr.toIntOrNull() ?: return null

            val qStr = attributes["q"]?.let { String(it, StandardCharsets.US_ASCII) } ?: return null
            val q = qStr.toIntOrNull() ?: return null

            val p = attributes["p"]?.let { String(it, StandardCharsets.US_ASCII) } ?: return null
            val dnBytes = attributes["dn"] ?: return null
            val dn = String(dnBytes, StandardCharsets.UTF_8)

            val dt = attributes["dt"]?.let { String(it, StandardCharsets.US_ASCII) } ?: LanTxtRecord.DEVICE_TYPE_UNKNOWN

            val isPairReady = if (attributes.containsKey("pair")) {
                val pairVal = attributes["pair"]?.let { String(it, StandardCharsets.US_ASCII) } ?: return null
                if (pairVal != "1") return null
                true
            } else {
                false
            }

            val supportsTrustedReconnect = if (attributes.containsKey("tr")) {
                val trVal = attributes["tr"]?.let { String(it, StandardCharsets.US_ASCII) } ?: return null
                if (trVal != "1") return null
                true
            } else {
                false
            }

            LanTxtRecord(
                v = v,
                tv = tv,
                q = q,
                p = p,
                dn = dn,
                dt = dt,
                isPairReady = isPairReady,
                supportsTrustedReconnect = supportsTrustedReconnect
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Computes RFC 6763 wire size: each attribute is encoded as length (1 byte) + key + '=' + value.
     */
    fun calculateTotalTxtBytes(attributes: Map<String, ByteArray?>): Int {
        var total = 0
        for ((key, value) in attributes) {
            val keyBytes = key.toByteArray(StandardCharsets.US_ASCII)
            val valBytes = value ?: ByteArray(0)
            // 1 byte length prefix + key + '=' + value
            total += 1 + keyBytes.size + 1 + valBytes.size
        }
        return total
    }
}
