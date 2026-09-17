package dev.glide.transport.lan.web

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Desired-security regressions for the transitional Android/Kotlin HTTP gateway.
 * These cases are intentionally red until the secure browser HARD-025..030 work lands.
 */
class Hard003WebGatewaySecurityRegressionTest {

    private lateinit var quarantineDir: File
    private lateinit var gateway: GlideWebGateway

    @Before
    fun setUp() {
        quarantineDir = File(
            System.getProperty("java.io.tmpdir"),
            "glide_hard003_web_${UUID.randomUUID()}"
        )
        quarantineDir.mkdirs()
        gateway = GlideWebGateway(
            host = "127.0.0.1",
            port = 0,
            offeredItems = emptyList(),
            quarantineDir = quarantineDir,
            usableSpaceProvider = { 10L * 1024 * 1024 * 1024 }
        )
        gateway.start()
    }

    @After
    fun tearDown() {
        gateway.close()
        quarantineDir.deleteRecursively()
    }

    @Test
    fun onboardingUrl_mustUseEncryptedTransport() {
        assertTrue(
            "Browser onboarding must not expose pairing/session credentials over plaintext HTTP",
            gateway.onboardingUrl.startsWith("https://")
        )
    }

    @Test
    fun redeemedOnboardingToken_mustNotRemainValidAsBearerCredential() {
        val redeem = URL(gateway.onboardingUrl).openConnection() as HttpURLConnection
        try {
            assertEquals(200, redeem.responseCode)
        } finally {
            redeem.disconnect()
        }

        val replay = URL("http://127.0.0.1:${gateway.boundPort}/api/v1/manifest").openConnection() as HttpURLConnection
        try {
            replay.setRequestProperty("Authorization", "Bearer ${gateway.token}")
            assertEquals(403, replay.responseCode)
        } finally {
            replay.disconnect()
        }
    }

    @Test
    fun upload_mustRequireAcceptedProposalUploadId() {
        val payload = "proposal binding regression".toByteArray()
        val digest = sha256(payload)
        val conn = URL("http://127.0.0.1:${gateway.boundPort}/api/v1/upload?token=${gateway.token}")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("X-File-Name", "unproposed.txt")
            conn.setRequestProperty("X-File-Size", payload.size.toString())
            conn.setRequestProperty("X-Declared-Sha256", digest)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            assertEquals(428, conn.responseCode)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun upload_mustRejectMissingDeclaredDigest_evenAfterProposal() {
        val payload = "digest-required regression".toByteArray()
        val cookie = redeemSessionCookie()
        val uploadId = createProposal(cookie, "digest-required.txt", payload)

        val conn = URL("http://127.0.0.1:${gateway.boundPort}/api/v1/upload").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("X-Upload-Id", uploadId)
            conn.setRequestProperty("X-File-Name", "digest-required.txt")
            conn.setRequestProperty("X-File-Size", payload.size.toString())
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            assertNotEquals(200, conn.responseCode)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun proposalUploadId_mustBeConsumedExactlyOnce() {
        val payload = "single-use upload proposal".toByteArray()
        val digest = sha256(payload)
        val cookie = redeemSessionCookie()
        val uploadId = createProposal(cookie, "single-use.txt", payload, digest)

        val firstStatus = sendUpload(cookie, uploadId, "single-use.txt", payload, digest)
        assertEquals(200, firstStatus)

        val replayStatus = sendUpload(cookie, uploadId, "single-use.txt", payload, digest)
        assertNotEquals(200, replayStatus)
    }

    private fun redeemSessionCookie(): String {
        val conn = URL(gateway.onboardingUrl).openConnection() as HttpURLConnection
        try {
            assertEquals(200, conn.responseCode)
            val setCookie = conn.getHeaderField("Set-Cookie")
            require(!setCookie.isNullOrBlank()) { "Onboarding response did not issue a session cookie" }
            return setCookie.substringBefore(';')
        } finally {
            conn.disconnect()
        }
    }

    private fun createProposal(
        cookie: String,
        fileName: String,
        payload: ByteArray,
        digest: String = sha256(payload)
    ): String {
        val json = "{\"fileName\":\"$fileName\",\"fileSize\":${payload.size},\"mimeType\":\"application/octet-stream\",\"sha256\":\"$digest\"}"
        val bytes = json.toByteArray()
        val conn = URL("http://127.0.0.1:${gateway.boundPort}/api/v1/upload/proposal")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            return Regex("\"uploadId\":\"([^\"]+)\"")
                .find(body)?.groupValues?.get(1)
                ?: error("Proposal response did not contain uploadId: $body")
        } finally {
            conn.disconnect()
        }
    }

    private fun sendUpload(
        cookie: String,
        uploadId: String,
        fileName: String,
        payload: ByteArray,
        digest: String
    ): Int {
        val conn = URL("http://127.0.0.1:${gateway.boundPort}/api/v1/upload")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("X-Upload-Id", uploadId)
            conn.setRequestProperty("X-File-Name", fileName)
            conn.setRequestProperty("X-File-Size", payload.size.toString())
            conn.setRequestProperty("X-Declared-Sha256", digest)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
}
