package dev.glide.core.protocol

import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.EffectiveLimits
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.isValidUuidV4
import java.nio.charset.StandardCharsets

object NegotiationConstants {
    const val PROTOCOL_MAJOR_VERSION: Int = 1
    const val CAPABILITY_STREAM_V1: String = "stream-v1"
    const val CAPABILITY_SHA256: String = "sha256"

    val REQUIRED_CAPABILITIES: Set<String> = setOf(CAPABILITY_STREAM_V1, CAPABILITY_SHA256)
}

data class NegotiatedSession(
    val selectedMajor: Int,
    val capabilities: Set<String>,
    val peerId: PeerId,
    val peerDisplayName: String,
    val effectiveLimits: EffectiveLimits
)

object NegotiationValidator {

    fun createHello(
        role: DeviceRole,
        ephemeralPeerId: String,
        displayName: String,
        optionalCapabilities: List<String> = emptyList()
    ): HelloBody {
        require(isValidUuidV4(ephemeralPeerId)) { "ephemeralPeerId must be UUIDv4, got: '$ephemeralPeerId'" }
        validateDisplayName(displayName)
        return HelloBody(
            supportedMajors = listOf(NegotiationConstants.PROTOCOL_MAJOR_VERSION),
            requiredCapabilities = NegotiationConstants.REQUIRED_CAPABILITIES.toList(),
            optionalCapabilities = optionalCapabilities,
            role = if (role == DeviceRole.Sender) "sender" else "receiver",
            ephemeralPeerId = ephemeralPeerId,
            displayName = displayName
        )
    }

    fun createHelloAck(
        hello: HelloBody,
        ephemeralPeerId: String,
        displayName: String,
        requestedLimits: EffectiveLimits = EffectiveLimits()
    ): HelloAckBody {
        validateHello(hello)
        require(isValidUuidV4(ephemeralPeerId)) { "ephemeralPeerId must be UUIDv4, got: '$ephemeralPeerId'" }
        validateDisplayName(displayName)

        if (NegotiationConstants.PROTOCOL_MAJOR_VERSION !in hello.supportedMajors) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "Major version ${NegotiationConstants.PROTOCOL_MAJOR_VERSION} not supported by sender: ${hello.supportedMajors}"
                )
            )
        }

        val helloReqCaps = hello.requiredCapabilities.toSet()
        if (!helloReqCaps.containsAll(NegotiationConstants.REQUIRED_CAPABILITIES)) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "Missing required capabilities in HELLO: ${NegotiationConstants.REQUIRED_CAPABILITIES - helloReqCaps}"
                )
            )
        }

        val limitsDto = LimitsDto(
            maxItems = requestedLimits.maxItems,
            maxItemBytes = DecimalByteCount.serialize(requestedLimits.maxItemBytes),
            maxBatchBytes = DecimalByteCount.serialize(requestedLimits.maxBatchBytes),
            maxControlBytes = requestedLimits.maxControlBytes
        )

        return HelloAckBody(
            selectedMajor = NegotiationConstants.PROTOCOL_MAJOR_VERSION,
            capabilities = hello.requiredCapabilities,
            ephemeralPeerId = ephemeralPeerId,
            displayName = displayName,
            limits = limitsDto
        )
    }

    fun validateHello(hello: HelloBody) {
        if (!isValidUuidV4(hello.ephemeralPeerId)) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("ephemeralPeerId must be UUIDv4, got: '${hello.ephemeralPeerId}'")
            )
        }
        validateDisplayName(hello.displayName)
        if (hello.role != "sender" && hello.role != "receiver") {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("Invalid role in HELLO: '${hello.role}'")
            )
        }
        if (NegotiationConstants.PROTOCOL_MAJOR_VERSION !in hello.supportedMajors) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "Supported majors ${hello.supportedMajors} does not contain version ${NegotiationConstants.PROTOCOL_MAJOR_VERSION}"
                )
            )
        }
        val reqCaps = hello.requiredCapabilities.toSet()
        if (!reqCaps.containsAll(NegotiationConstants.REQUIRED_CAPABILITIES)) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "HELLO missing required capabilities: ${NegotiationConstants.REQUIRED_CAPABILITIES - reqCaps}"
                )
            )
        }
    }

    fun validateHelloAck(hello: HelloBody, ack: HelloAckBody): NegotiatedSession {
        validateHello(hello)

        if (!isValidUuidV4(ack.ephemeralPeerId)) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage("ephemeralPeerId must be UUIDv4, got: '${ack.ephemeralPeerId}'")
            )
        }
        validateDisplayName(ack.displayName)

        if (ack.selectedMajor !in hello.supportedMajors) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "selectedMajor ${ack.selectedMajor} was not offered in supportedMajors ${hello.supportedMajors}"
                )
            )
        }
        if (ack.selectedMajor != NegotiationConstants.PROTOCOL_MAJOR_VERSION) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol("Unsupported selectedMajor: ${ack.selectedMajor}")
            )
        }

        val ackCaps = ack.capabilities.toSet()
        if (!ackCaps.containsAll(NegotiationConstants.REQUIRED_CAPABILITIES)) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "HELLO_ACK missing required capabilities: ${NegotiationConstants.REQUIRED_CAPABILITIES - ackCaps}"
                )
            )
        }

        val maxItems = ack.limits.maxItems
        if (maxItems !in 1..ProtocolLimits.MAX_ITEMS_PER_BATCH) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded("maxItems must be in 1..${ProtocolLimits.MAX_ITEMS_PER_BATCH}, got: $maxItems")
            )
        }

        val maxItemBytes = DecimalByteCount.parse(ack.limits.maxItemBytes)
        if (maxItemBytes <= 0 || maxItemBytes > ProtocolLimits.MAX_ITEM_BYTES) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded("maxItemBytes must be in 1..${ProtocolLimits.MAX_ITEM_BYTES}, got: $maxItemBytes")
            )
        }

        val maxBatchBytes = DecimalByteCount.parse(ack.limits.maxBatchBytes)
        if (maxBatchBytes <= 0 || maxBatchBytes > ProtocolLimits.MAX_BATCH_BYTES) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded("maxBatchBytes must be in 1..${ProtocolLimits.MAX_BATCH_BYTES}, got: $maxBatchBytes")
            )
        }

        val maxControlBytes = ack.limits.maxControlBytes
        if (maxControlBytes !in 1024..ProtocolLimits.MAX_CONTROL_PAYLOAD_BYTES) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "maxControlBytes must be in 1024..${ProtocolLimits.MAX_CONTROL_PAYLOAD_BYTES}, got: $maxControlBytes"
                )
            )
        }

        return NegotiatedSession(
            selectedMajor = ack.selectedMajor,
            capabilities = ackCaps,
            peerId = PeerId(ack.ephemeralPeerId),
            peerDisplayName = ack.displayName,
            effectiveLimits = EffectiveLimits(
                maxItems = maxItems,
                maxItemBytes = maxItemBytes,
                maxBatchBytes = maxBatchBytes,
                maxControlBytes = maxControlBytes
            )
        )
    }

    private fun validateDisplayName(displayName: String) {
        val codePoints = displayName.codePointCount(0, displayName.length)
        val bytes = displayName.toByteArray(StandardCharsets.UTF_8).size
        if (codePoints !in 1..64 || bytes > 256) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded(
                    "displayName must be 1..64 code points and <= 256 UTF-8 bytes, got $codePoints cps, $bytes bytes"
                )
            )
        }
    }
}
