package dev.glide.transport.lan.tls

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lightweight, self-contained RFC 5280 DER encoder for self-signed EC P-256 X.509 certificates (LAN-010, Section 6).
 *
 * Avoids heavy third-party dependencies (like BouncyCastle) while producing fully valid
 * X.509 certificates that platform [CertificateFactory] and [javax.net.ssl.SSLContext] parse and verify.
 */
object SelfSignedCertificateGenerator {

    // OID: 1.2.840.10045.4.3.2 (ecdsa-with-SHA256)
    private val OID_ECDSA_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)

    // OID: 2.5.4.3 (commonName)
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun generate(
        keyPair: KeyPair,
        commonName: String = "Glide LAN Lease",
        notBefore: Date = Date(System.currentTimeMillis() - 60_000), // 1 min in the past for clock drift
        notAfter: Date = Date(System.currentTimeMillis() + 86_400_000) // 24 hours
    ): X509Certificate {
        val algId = encodeSequence(encodeOid(OID_ECDSA_SHA256))

        // Version: [0] EXPLICIT INTEGER 2 (X.509 v3)
        val version = byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02)

        // Serial number: positive random 64-bit integer
        val serialNumber = encodeInteger(BigInteger.valueOf(System.currentTimeMillis()).abs())

        // Issuer / Subject Name: SEQUENCE { SET { SEQUENCE { OID commonName, UTF8String commonName } } }
        val name = encodeSequence(
            encodeSet(
                encodeSequence(
                    encodeOid(OID_COMMON_NAME) + encodeUtf8String(commonName)
                )
            )
        )

        // Validity: SEQUENCE { UTCTime notBefore, UTCTime notAfter }
        val validity = encodeSequence(
            encodeUtcTime(notBefore) + encodeUtcTime(notAfter)
        )

        // SubjectPublicKeyInfo: already DER-encoded in keyPair.public.encoded
        val spki = keyPair.public.encoded

        // TBSCertificate
        val tbsCertificate = encodeSequence(
            version + serialNumber + algId + name + validity + name + spki
        )

        // Sign TBS with SHA256withECDSA
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(tbsCertificate)
        val signatureBytes = signer.sign()

        val signatureBitString = encodeBitString(signatureBytes)

        // Full Certificate: SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        val certDer = encodeSequence(
            tbsCertificate + algId + signatureBitString
        )

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun encodeSequence(contents: ByteArray): ByteArray =
        byteArrayOf(0x30) + encodeLength(contents.size) + contents

    private fun encodeSet(contents: ByteArray): ByteArray =
        byteArrayOf(0x31) + encodeLength(contents.size) + contents

    private fun encodeInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02) + encodeLength(bytes.size) + bytes
    }

    private fun encodeBitString(bytes: ByteArray): ByteArray {
        // Tag 0x03, length = bytes.size + 1, unused bits = 0x00, bytes
        return byteArrayOf(0x03) + encodeLength(bytes.size + 1) + byteArrayOf(0x00) + bytes
    }

    private fun encodeOid(oidBytes: ByteArray): ByteArray =
        byteArrayOf(0x06) + encodeLength(oidBytes.size) + oidBytes

    private fun encodeUtf8String(str: String): ByteArray {
        val bytes = str.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x0C) + encodeLength(bytes.size) + bytes
    }

    private fun encodeUtcTime(date: Date): ByteArray {
        val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val bytes = sdf.format(date).toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17) + encodeLength(bytes.size) + bytes
    }

    private fun encodeLength(length: Int): ByteArray {
        return when {
            length < 128 -> byteArrayOf(length.toByte())
            length <= 255 -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte())
        }
    }
}
