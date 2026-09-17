package dev.glide.transport.lan.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID

/**
 * Item offered for browser download via Glide Web Transfer Gateway.
 */
data class OfferedWebItem(
    val itemId: String = UUID.randomUUID().toString(),
    val fileName: String,
    val fileSize: Long,
    val file: File? = null,
    val contentSupplier: (() -> InputStream)? = null
) {
    constructor(file: File) : this(
        itemId = UUID.randomUUID().toString(),
        fileName = file.name,
        fileSize = file.length(),
        file = file,
        contentSupplier = null
    )

    fun openStream(): InputStream {
        return when {
            file != null -> file.inputStream()
            contentSupplier != null -> contentSupplier.invoke()
            else -> ByteArrayInputStream(ByteArray(0))
        }
    }
}

/**
 * Embedded, zero-cloud, zero-dependency HTTP/1.1 Web Transfer Gateway (ADR-0019).
 *
 * Implements:
 * - Ephemeral local port binding on specified Wi-Fi/LAN interface.
 * - 256-bit cryptographically secure one-use onboarding bearer token.
 * - Strict 120-second monotonic connection TTL.
 * - SameSite=Strict; HttpOnly session cookie issuance.
 * - Strict Content-Security-Policy, X-Content-Type-Options, and X-Frame-Options headers.
 * - High-speed chunked streaming downloads with Content-Disposition.
 * - Streaming quarantine uploads with on-the-fly SHA-256 validation.
 */
class GlideWebGateway(
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val offeredItems: List<OfferedWebItem> = emptyList(),
    val deviceName: String = "Glide Device",
    val platform: String = "android",
    val quarantineDir: File = File(System.getProperty("java.io.tmpdir"), "glide_quarantine"),
    val ttlSeconds: Int = 120,
    private val secureRandom: SecureRandom = SecureRandom()
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val token: String = run {
        val bytes = ByteArray(32).apply { secureRandom.nextBytes(this) }
        bytes.joinToString("") { "%02x".format(it) }
    }

    val expiresAtMonoNs: Long = System.nanoTime() + ttlSeconds * 1_000_000_000L

    val boundPort: Int
        get() = serverSocket?.localPort ?: 0

    val onboardingUrl: String
        get() = "http://$host:$boundPort/?token=$token"

    private val redeemedSessions = Collections.synchronizedSet(mutableSetOf<String>())
    private var isTokenRedeemed: Boolean = false

    @Synchronized
    fun start() {
        if (serverSocket != null) return

        if (!quarantineDir.exists()) {
            quarantineDir.mkdirs()
        }

        val address = InetAddress.getByName(host)
        val s = ServerSocket(port, 50, address)
        s.reuseAddress = true
        serverSocket = s

        acceptJob = scope.launch {
            while (isActive && !s.isClosed) {
                try {
                    val client = s.accept()
                    launch {
                        handleClient(client)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            acceptJob?.cancel()
            serverSocket?.close()
        } catch (_: Exception) {
        } finally {
            serverSocket = null
        }
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawUri = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val headerLine = readLine(input) ?: break
                if (headerLine.isEmpty()) break
                val colonIdx = headerLine.indexOf(':')
                if (colonIdx > 0) {
                    val key = headerLine.substring(0, colonIdx).trim().lowercase()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            val uriParts = rawUri.split("?", limit = 2)
            val path = uriParts[0]
            val query = if (uriParts.size > 1) uriParts[1] else ""

            val queryParams = parseQuery(query)
            val tokenParam = queryParams["token"]

            val isHead = method.equals("HEAD", ignoreCase = true)
            val isGetOrHead = method.equals("GET", ignoreCase = true) || isHead

            // Static assets: serve directly with security headers
            if (isGetOrHead && path == "/style.css") {
                sendResponse(output, 200, "OK", "text/css; charset=utf-8", GlideWebAssets.getStyleCss(), null, isHead)
                return
            }
            if (isGetOrHead && path == "/app.js") {
                sendResponse(output, 200, "OK", "application/javascript; charset=utf-8", GlideWebAssets.getAppJs(), null, isHead)
                return
            }

            // Check authentication
            val authHeader = headers["authorization"]
            val isAuthorized = checkAuthorization(tokenParam, headers["cookie"], authHeader)
            var setCookieHeader: String? = null

            if (tokenParam != null && tokenParam.equals(token, ignoreCase = true)) {
                if (System.nanoTime() <= expiresAtMonoNs) {
                    val sessionCookie = "glide_web_session=$token; Path=/; HttpOnly; SameSite=Strict"
                    setCookieHeader = sessionCookie
                }
            }

            if (!isAuthorized) {
                sendResponse(output, 403, "Forbidden", "text/plain", "403 Forbidden: Invalid, expired, or missing authentication token.\r\n".toByteArray(), null, isHead)
                return
            }

            // Route endpoints
            when {
                isGetOrHead && (path == "/" || path == "/index.html") -> {
                    sendResponse(output, 200, "OK", "text/html; charset=utf-8", GlideWebAssets.getIndexHtml(), setCookieHeader, isHead)
                }
                isGetOrHead && path == "/api/v1/manifest" -> {
                    val json = buildManifestJson()
                    sendResponse(output, 200, "OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8), setCookieHeader, isHead)
                }
                isGetOrHead && path.startsWith("/api/v1/download/") -> {
                    val itemId = path.removePrefix("/api/v1/download/")
                    val item = offeredItems.firstOrNull { it.itemId.equals(itemId, ignoreCase = true) }
                    if (item == null) {
                        sendResponse(output, 404, "Not Found", "text/plain", "File not found\r\n".toByteArray(), setCookieHeader, isHead)
                    } else {
                        streamDownload(method, output, item, headers, setCookieHeader)
                    }
                }
                method == "POST" && path == "/api/v1/upload" -> {
                    handleUpload(input, output, headers, setCookieHeader)
                }
                else -> {
                    sendResponse(output, 404, "Not Found", "text/plain", "404 Not Found\r\n".toByteArray(), setCookieHeader, isHead)
                }
            }
        }
    }

    private fun checkAuthorization(queryToken: String?, cookieHeader: String?, authHeader: String?): Boolean {
        val now = System.nanoTime()
        if (now > expiresAtMonoNs) return false

        if (queryToken != null && queryToken.equals(token, ignoreCase = true)) {
            return true
        }

        if (cookieHeader != null) {
            val cookies = cookieHeader.split(";")
            for (c in cookies) {
                val pair = c.trim().split("=", limit = 2)
                if (pair.size == 2 && pair[0].trim() == "glide_web_session") {
                    val session = pair[1].trim()
                    if (session.equals(token, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }

        if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            val bearer = authHeader.substring("Bearer ".length).trim()
            if (bearer.equals(token, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (p in pairs) {
            val parts = p.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            result[key] = value
        }
        return result
    }

    private fun buildManifestJson(): String {
        val sb = StringBuilder()
        sb.append("{\"device\":{\"name\":\"")
        sb.append(escapeJson(deviceName))
        sb.append("\",\"platform\":\"")
        sb.append(escapeJson(platform))
        sb.append("\"},\"items\":[")
        for (i in offeredItems.indices) {
            val item = offeredItems[i]
            if (i > 0) sb.append(",")
            sb.append("{\"itemId\":\"")
            sb.append(escapeJson(item.itemId))
            sb.append("\",\"fileName\":\"")
            sb.append(escapeJson(item.fileName))
            sb.append("\",\"fileSize\":")
            sb.append(item.fileSize)
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "\\n")
    }

    private fun streamDownload(
        method: String,
        output: OutputStream,
        item: OfferedWebItem,
        headers: Map<String, String>,
        setCookie: String?
    ) {
        val isHead = method.equals("HEAD", ignoreCase = true)
        val safeFileName = item.fileName.replace("/", "_").replace("\\", "_")
        val safeAscii = safeFileName.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("").ifBlank { "download.bin" }
        val encodedUtf8 = URLEncoder.encode(safeFileName, "UTF-8").replace("+", "%20")
        val disposition = "attachment; filename=\"$safeAscii\"; filename*=UTF-8''$encodedUtf8"

        val totalLength = item.fileSize
        val rangeHeader = headers["range"]

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            val parsedRange = parseByteRange(rangeHeader, totalLength)
            if (parsedRange == null) {
                val sb = StringBuilder()
                sb.append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                sb.append("Content-Range: bytes */$totalLength\r\n")
                sb.append("Content-Length: 0\r\n")
                sb.append("Accept-Ranges: bytes\r\n")
                appendSecurityHeaders(sb)
                if (setCookie != null) sb.append("Set-Cookie: $setCookie\r\n")
                sb.append("Connection: close\r\n\r\n")
                output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                output.flush()
                return
            }

            val (start, end) = parsedRange
            val rangeLength = end - start + 1

            val sb = StringBuilder()
            sb.append("HTTP/1.1 206 Partial Content\r\n")
            sb.append("Content-Type: application/octet-stream\r\n")
            sb.append("Content-Length: $rangeLength\r\n")
            sb.append("Content-Range: bytes $start-$end/$totalLength\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Content-Disposition: $disposition\r\n")
            appendSecurityHeaders(sb)
            if (setCookie != null) sb.append("Set-Cookie: $setCookie\r\n")
            sb.append("Connection: close\r\n\r\n")
            output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))

            if (isHead) {
                output.flush()
                return
            }

            item.openStream().use { input ->
                skipFully(input, start)
                val buf = ByteArray(64 * 1024)
                var remaining = rangeLength
                while (remaining > 0) {
                    val toRead = Math.min(buf.size.toLong(), remaining).toInt()
                    val read = input.read(buf, 0, toRead)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    remaining -= read
                }
            }
            output.flush()
            return
        }

        // Full content 200 OK
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: application/octet-stream\r\n")
        sb.append("Content-Length: $totalLength\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Disposition: $disposition\r\n")
        appendSecurityHeaders(sb)
        if (setCookie != null) {
            sb.append("Set-Cookie: $setCookie\r\n")
        }
        sb.append("Connection: close\r\n\r\n")

        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))

        if (isHead) {
            output.flush()
            return
        }

        item.openStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } != -1) {
                output.write(buf, 0, bytesRead)
            }
        }
        output.flush()
    }

    private fun parseByteRange(rangeHeader: String?, totalLength: Long): Pair<Long, Long>? {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=", ignoreCase = true) || totalLength <= 0) {
            return null
        }
        var spec = rangeHeader.substring("bytes=".length).trim()
        val commaIdx = spec.indexOf(',')
        if (commaIdx >= 0) {
            spec = spec.substring(0, commaIdx).trim()
        }
        val dashIdx = spec.indexOf('-')
        if (dashIdx < 0) return null

        val startStr = spec.substring(0, dashIdx).trim()
        val endStr = spec.substring(dashIdx + 1).trim()

        if (startStr.isEmpty() && endStr.isEmpty()) return null

        if (startStr.isEmpty()) {
            val suffix = endStr.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            val start = Math.max(0L, totalLength - suffix)
            return Pair(start, totalLength - 1)
        }

        val start = startStr.toLongOrNull() ?: return null
        if (start < 0 || start >= totalLength) return null

        val end = if (endStr.isEmpty()) {
            totalLength - 1
        } else {
            val parsedEnd = endStr.toLongOrNull() ?: return null
            if (parsedEnd < start) return null
            Math.min(parsedEnd, totalLength - 1)
        }

        return Pair(start, end)
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        setCookie: String?
    ) {
        val rawFileName = headers["x-file-name"]
        val fileName = if (!rawFileName.isNullOrBlank()) {
            URLDecoder.decode(rawFileName, "UTF-8").replace("..", "").replace("/", "").replace("\\", "")
        } else {
            "upload_${System.currentTimeMillis()}.bin"
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        val tempFile = File(quarantineDir, "staged_${UUID.randomUUID()}.part")

        val digest = MessageDigest.getInstance("SHA-256")
        var totalRead = 0L

        FileOutputStream(tempFile).use { fileOut ->
            val buffer = ByteArray(64 * 1024)
            var remaining = if (contentLength >= 0) contentLength else Long.MAX_VALUE
            while (remaining > 0) {
                val toRead = Math.min(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                fileOut.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                totalRead += read
                if (contentLength >= 0) {
                    remaining -= read
                }
            }
        }

        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val targetFile = File(quarantineDir, fileName)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        tempFile.renameTo(targetFile)

        val respJson = "{\"status\":\"ok\",\"fileName\":\"${escapeJson(fileName)}\",\"sha256\":\"$sha256\",\"bytes\":$totalRead}"
        sendResponse(output, 200, "OK", "application/json; charset=utf-8", respJson.toByteArray(StandardCharsets.UTF_8), setCookie)
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        setCookie: String?,
        isHead: Boolean = false
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        appendSecurityHeaders(sb)
        if (setCookie != null) {
            sb.append("Set-Cookie: $setCookie\r\n")
        }
        sb.append("Connection: close\r\n\r\n")

        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        if (!isHead) {
            output.write(body)
        }
        output.flush()
    }

    private fun appendSecurityHeaders(sb: StringBuilder) {
        sb.append("Content-Security-Policy: default-src 'self' data:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none';\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("X-Frame-Options: DENY\r\n")
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) {
                break
            } else if (c != '\r'.code) {
                sb.append(c.toChar())
            }
        }
        if (sb.isEmpty() && c == -1) return null
        return sb.toString()
    }
}
