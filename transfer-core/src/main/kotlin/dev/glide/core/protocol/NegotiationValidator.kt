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
    const val CAPABILITY_FOLDER_V1: String = "folder-v1"
    const val CAPABILITY_PERSISTENT_IDENTITY_V1: String = "identity-v1"
    const val CAPABILITY_TRUE_RESUME_V1: String = "resume-v1"

    val REQUIRED_CAPABILITIES: Set<String> =
        setOf(CAPABILITY_STREAM_V1, CAPABILITY_SHA256)

    /**
     * Capabilities safe to advertise from today\'s production runtime.
     * identity-v1/resume-v1 remain opt-in until HARD-019..021 wire persistent keys,
     * channel binding and trusted-session enforcement.
     */
    val OPTIONAL_CAPABILITIES: Set<String> =
        setOf(CAPABILITY_FOLDER_V1)

    val TRUST_EXTENSION_CAPABILITIES: Set<String> =
        setOf(
            CAPABILITY_PERSISTENT_IDENTITY_V1,
            CAPABILITY_TRUE_RESUME_V1
        )

    val ALL_KNOWN_OPTIONAL_CAPABILITIES: Set<String> =
        OPTIONAL_CAPABILITIES + TRUST_EXTENSION_CAPABILITIES
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
        optionalCapabilities: List<String> = NegotiationConstants.OPTIONAL_CAPABILITIES.toList()
    ): HelloBody {
        require(isValidUuidV4(ephemeralPeerId)) { "ephemeralPeerId must be UUIDv4, got: '$ephemeralPeerId'" }
        validateDisplayName(displayName)
        validateCapabilityDependencies(optionalCapabilities.toSet())
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
        requestedLimits: EffectiveLimits = EffectiveLimits(),
        supportedOptionalCapabilities: Set<String> = NegotiationConstants.OPTIONAL_CAPABILITIES
    ): HelloAckBody {
        validateHello(hello)
        require(isValidUuidV4(ephemeralPeerId)) { "ephemeralPeerId must be UUIDv4, got: '$ephemeralPeerId'" }
        validateDisplayName(displayName)
        validateCapabilityDependencies(supportedOptionalCapabilities)

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

        val negotiatedOptional =
            hello.optionalCapabilities.toSet() intersect supportedOptionalCapabilities
        validateCapabilityDependencies(negotiatedOptional)
        val negotiatedCaps =
            (NegotiationConstants.REQUIRED_CAPABILITIES + negotiatedOptional)
                .sorted()

        val limitsDto = LimitsDto(
            maxItems = requestedLimits.maxItems,
            maxItemBytes = DecimalByteCount.serialize(requestedLimits.maxItemBytes),
            maxBatchBytes = DecimalByteCount.serialize(requestedLimits.maxBatchBytes),
            maxControlBytes = requestedLimits.maxControlBytes
        )

        return HelloAckBody(
            selectedMajor = NegotiationConstants.PROTOCOL_MAJOR_VERSION,
            capabilities = negotiatedCaps,
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

        validateCapabilityDependencies(hello.optionalCapabilities.toSet())
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

        val offeredCapabilities =
            NegotiationConstants.REQUIRED_CAPABILITIES +
                hello.optionalCapabilities.toSet()
        val unoffered = ackCaps - offeredCapabilities
        if (unoffered.isNotEmpty()) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "HELLO_ACK selected capabilities that were not offered: $unoffered"
                )
            )
        }
        validateCapabilityDependencies(ackCaps)

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

    private fun validateCapabilityDependencies(
        capabilities: Set<String>
    ) {
        if (NegotiationConstants.CAPABILITY_TRUE_RESUME_V1 in capabilities &&
            NegotiationConstants.CAPABILITY_PERSISTENT_IDENTITY_V1 !in capabilities
        ) {
            throw ProtocolViolationException(
                TransferFailure.UnsupportedProtocol(
                    "resume-v1 requires identity-v1 in the same negotiated capability set"
                )
            )
        }
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
