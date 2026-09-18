package dev.glide.core.protocol

import dev.glide.core.domain.isValidUuidV4
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Canonical signature transcripts for the versioned persistent-identity and true-resume
 * extensions.
 *
 * The signed value is binary, not JSON:
 * - domain ASCII + NUL
 * - one purpose byte
 * - every string as uint32 big-endian UTF-8 length + bytes
 * - integers as unsigned-range positive big-endian values
 *
 * This intentionally prevents Kotlin/.NET JSON serialization differences from changing a
 * cryptographic proof.
 */
object IdentityResumeTranscript {
    const val IDENTITY_PROFILE = "identity-v1"
    const val RESUME_PROFILE = "resume-v1"
    const val CHANNEL_BINDING_SCHEME = "tls-exporter-v1"
    const val TLS_EXPORTER_LABEL = "EXPORTER-GLIDE-IDENTITY-V1"

    private const val SIGN_DOMAIN = "GLIDE-SIGN-V1"
    private const val CAPABILITY_DOMAIN = "GLIDE-CAPS-V1"
    private const val PURPOSE_IDENTITY = 1
    private const val PURPOSE_RESUME = 2

    private val capabilityRegex = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    private val lowerHex64Regex = Regex("^[0-9a-f]{64}$")

    fun capabilityCanonicalBytes(
        capabilities: Collection<String>
    ): ByteArray {
        val normalized = capabilities.toSortedSet()
        require(normalized.all(capabilityRegex::matches)) {
            "Capabilities must be lowercase ASCII tokens of at most 64 characters"
        }

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            writeDomain(out, CAPABILITY_DOMAIN)
            out.writeInt(normalized.size)
            normalized.forEach { writeString(out, it) }
        }
        return bytes.toByteArray()
    }

    fun capabilityHashHex(
        capabilities: Collection<String>
    ): String =
        sha256Hex(capabilityCanonicalBytes(capabilities))

    fun identityProofBytes(
        activeSessionId: String,
        challengeMessageId: String,
        signerRole: String,
        verifierRole: String,
        signerFingerprintHex: String,
        verifierFingerprintHex: String,
        challengeNonceHex: String,
        channelBindingHex: String,
        capabilityHashHex: String
    ): ByteArray {
        requireUuid(activeSessionId, "activeSessionId")
        requireUuid(challengeMessageId, "challengeMessageId")
        requireOppositeRoles(signerRole, verifierRole)
        requireLowerHex64(signerFingerprintHex, "signerFingerprintHex")
        requireLowerHex64(verifierFingerprintHex, "verifierFingerprintHex")
        requireLowerHex64(challengeNonceHex, "challengeNonceHex")
        requireLowerHex64(channelBindingHex, "channelBindingHex")
        requireLowerHex64(capabilityHashHex, "capabilityHashHex")

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            writeDomain(out, SIGN_DOMAIN)
            out.writeByte(PURPOSE_IDENTITY)
            writeString(out, IDENTITY_PROFILE)
            writeString(out, CHANNEL_BINDING_SCHEME)
            writeString(out, activeSessionId)
            writeString(out, challengeMessageId)
            writeString(out, signerRole)
            writeString(out, verifierRole)
            writeString(out, signerFingerprintHex)
            writeString(out, verifierFingerprintHex)
            writeString(out, challengeNonceHex)
            writeString(out, channelBindingHex)
            writeString(out, capabilityHashHex)
        }
        return bytes.toByteArray()
    }

    fun resumeRequestBytes(
        activeSessionId: String,
        originalSessionId: String,
        itemId: String,
        requesterRole: String,
        responderRole: String,
        requesterFingerprintHex: String,
        responderFingerprintHex: String,
        sessionNonceHex: String,
        channelBindingHex: String,
        capabilityHashHex: String,
        chunkSize: Int,
        totalSizeBytes: Long,
        verifiedChunkCount: Long,
        contiguousByteOffset: Long,
        chunkRootHashHex: String
    ): ByteArray {
        requireUuid(activeSessionId, "activeSessionId")
        requireUuid(originalSessionId, "originalSessionId")
        requireUuid(itemId, "itemId")
        requireOppositeRoles(requesterRole, responderRole)
        require(requesterRole == "receiver" && responderRole == "sender") {
            "resume-v1 request roles must be receiver -> sender"
        }
        requireLowerHex64(requesterFingerprintHex, "requesterFingerprintHex")
        requireLowerHex64(responderFingerprintHex, "responderFingerprintHex")
        requireLowerHex64(sessionNonceHex, "sessionNonceHex")
        requireLowerHex64(channelBindingHex, "channelBindingHex")
        requireLowerHex64(capabilityHashHex, "capabilityHashHex")
        requireLowerHex64(chunkRootHashHex, "chunkRootHashHex")
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(totalSizeBytes >= 0) { "totalSizeBytes cannot be negative" }
        require(verifiedChunkCount >= 0) { "verifiedChunkCount cannot be negative" }
        require(contiguousByteOffset >= 0) { "contiguousByteOffset cannot be negative" }
        require(contiguousByteOffset <= totalSizeBytes) {
            "contiguousByteOffset cannot exceed totalSizeBytes"
        }
        require(
            contiguousByteOffset % chunkSize == 0L ||
                contiguousByteOffset == totalSizeBytes
        ) {
            "contiguousByteOffset must align with chunkSize or equal totalSizeBytes"
        }

        val expectedChunkCount =
            if (contiguousByteOffset == totalSizeBytes && totalSizeBytes % chunkSize != 0L) {
                (totalSizeBytes + chunkSize - 1L) / chunkSize
            } else {
                contiguousByteOffset / chunkSize
            }
        require(verifiedChunkCount == expectedChunkCount) {
            "verifiedChunkCount does not match contiguousByteOffset/chunkSize"
        }

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            writeDomain(out, SIGN_DOMAIN)
            out.writeByte(PURPOSE_RESUME)
            writeString(out, RESUME_PROFILE)
            writeString(out, CHANNEL_BINDING_SCHEME)
            writeString(out, activeSessionId)
            writeString(out, originalSessionId)
            writeString(out, itemId)
            writeString(out, requesterRole)
            writeString(out, responderRole)
            writeString(out, requesterFingerprintHex)
            writeString(out, responderFingerprintHex)
            writeString(out, sessionNonceHex)
            writeString(out, channelBindingHex)
            writeString(out, capabilityHashHex)
            out.writeInt(chunkSize)
            out.writeLong(totalSizeBytes)
            out.writeLong(verifiedChunkCount)
            out.writeLong(contiguousByteOffset)
            writeString(out, chunkRootHashHex)
        }
        return bytes.toByteArray()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun writeDomain(out: DataOutputStream, domain: String) {
        out.write(domain.toByteArray(StandardCharsets.US_ASCII))
        out.writeByte(0)
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(encoded.size)
        out.write(encoded)
    }

    private fun requireUuid(value: String, name: String) {
        require(isValidUuidV4(value)) { "$name must be a canonical lowercase UUIDv4" }
    }

    private fun requireOppositeRoles(first: String, second: String) {
        require(first in setOf("sender", "receiver")) { "Invalid role '$first'" }
        require(second in setOf("sender", "receiver")) { "Invalid role '$second'" }
        require(first != second) { "Identity/resume roles must be opposite" }
    }

    private fun requireLowerHex64(value: String, name: String) {
        require(lowerHex64Regex.matches(value)) {
            "$name must be exactly 64 lowercase hexadecimal characters"
        }
    }
}
