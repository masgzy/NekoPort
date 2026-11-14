package io.github.masgzy.nekoport.server

import io.github.masgzy.nekoport.core.UrlCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BadRequestException(message: String) : IOException(message)

data class HttpRequest(
    val method: String,
    /** Decoded, guard-approved absolute path starting with "/" (no query). */
    val path: String,
    val rawPath: String,
    val query: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteAddr: String,
    val version: String,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    fun queryParam(name: String): String? {
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            if (k == name) {
                val v = if (idx >= 0) pair.substring(idx + 1) else ""
                return UrlCodec.decode(v)
            }
        }
        return null
    }
}

class HttpResponse(
    var status: Int = 200,
    var reason: String = "OK",
    val headers: LinkedHashMap<String, String> = LinkedHashMap(),
    var body: ByteArray = ByteArray(0),
)

/**
 * Reads and writes a single HTTP/1.1 connection. Supports keep-alive,
 * Content-Length and chunked request bodies, bounded header and body sizes.
 */
class HttpExchange(
    private val socket: Socket,
    private val maxBodySize: Long,
) {
    private val input = BufferedInputStream(socket.getInputStream(), 16384)
    private val output = BufferedOutputStream(socket.getOutputStream(), 32768)
    private var firstRequest = true

    val remoteAddr: String
        get() = socket.inetAddress?.hostAddress ?: "unknown"

    private fun readLine(maxLen: Int): String {
        val sb = StringBuilder(80)
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (sb.isEmpty()) throw EOFException()
                throw EOFException()
            }
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > maxLen) throw BadRequestException("Header line too long")
        }
    }

    /** Reads one request; returns null when the peer closed the connection cleanly. */
    fun readRequest(): HttpRequest? {
        if (firstRequest) {
            socket.soTimeout = 15_000
            firstRequest = false
        } else {
            socket.soTimeout = 75_000
        }
        val requestLine = try {
            readLine(8192)
        } catch (_: EOFException) {
            return null
        } catch (_: IOException) {
            return null
        }
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw BadRequestException("Malformed request line")
        val (rawMethod, rawTarget, version) = parts
        if (!version.startsWith("HTTP/1.")) throw BadRequestException("Unsupported HTTP version")
        val method = rawMethod.uppercase(Locale.US)
        if (method !in setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH")) {
            throw BadRequestException("Unsupported method")
        }

        // Headers
        val headers = HashMap<String, String>(32)
        var headerCount = 0
        while (true) {
            val line = readLine(16384)
            if (line.isEmpty()) break
            if (++headerCount > 200) throw BadRequestException("Too many headers")
            val idx = line.indexOf(':')
            if (idx <= 0) throw BadRequestException("Malformed header")
            val name = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            if (name.any { it.code < 0x20 || it.code > 0x7E }) throw BadRequestException("Bad header name")
            // First value wins for duplicated headers (anti-smuggling posture).
            headers.putIfAbsent(name, value)
        }

        // Anti request-smuggling: reject conflicting framing hints.
        if (headers.containsKey("content-length") && headers.containsKey("transfer-encoding")) {
            throw BadRequestException("Conflicting body framing")
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) != true &&
            headers.containsKey("transfer-encoding")
        ) {
            throw BadRequestException("Unsupported transfer encoding")
        }

        val body = readBody(headers)
        if (body.size > maxBodySize) throw BadRequestException("Body too large")

        val queryIdx = rawTarget.indexOf('?')
        val rawPath = if (queryIdx >= 0) rawTarget.substring(0, queryIdx) else rawTarget
        val query = if (queryIdx >= 0) rawTarget.substring(queryIdx + 1) else ""
        if (rawTarget.contains(' ') || rawTarget.contains('\t')) throw BadRequestException("Bad target")

        val decodedPath = UrlCodec.decode(rawPath) ?: throw BadRequestException("Bad path")
        val normalized = if (decodedPath.startsWith("/")) decodedPath else "/$decodedPath"

        return HttpRequest(
            method = method,
            path = normalized,
            rawPath = rawPath,
            query = query,
            headers = headers,
            body = body,
            remoteAddr = remoteAddr,
            version = version,
        )
    }

    private fun readBody(headers: Map<String, String>): ByteArray {
        val te = headers["transfer-encoding"]
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(1024).trim()
                val size = sizeLine.substringBefore(';').trim()
                val len = size.toLongOrNull(16) ?: throw BadRequestException("Bad chunk size")
                if (len < 0) throw BadRequestException("Bad chunk size")
                if (out.size() + len > maxBodySize) throw BadRequestException("Body too large")
                if (len == 0L) {
                    // Consume trailer lines until blank.
                    while (true) {
                        val t = readLine(8192)
                        if (t.isEmpty()) break
                    }
                    return out.toByteArray()
                }
                val buf = ByteArray(len.toInt())
                readFully(buf)
                out.write(buf)
                input.read() // CR
                input.read() // LF
            }
        }
        val cl = headers["content-length"]?.toLongOrNull()
            ?: return ByteArray(0)
        if (cl < 0 || cl > maxBodySize) throw BadRequestException("Body too large")
        val buf = ByteArray(cl.toInt())
        readFully(buf)
        return buf
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) throw EOFException()
            off += n
        }
    }

    fun writeResponse(response: HttpResponse, keepAlive: Boolean, isHead: Boolean) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        response.headers["Date"] = httpDate()
        response.headers.putIfAbsent("Server", "NekoPort")
        response.headers.putIfAbsent("Connection", if (keepAlive) "keep-alive" else "close")
        if (!isHead && response.status !in setOf(204, 304) && response.body.isNotEmpty()) {
            response.headers["Content-Length"] = response.body.size.toString()
        } else if (response.body.isEmpty()) {
            response.headers.putIfAbsent("Content-Length", "0")
        }
        for ((k, v) in response.headers) {
            if (v.contains('\r') || v.contains('\n')) continue // header injection guard
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!isHead && response.status !in setOf(204, 304)) {
            output.write(response.body)
        }
        output.flush()
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private val dayFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

        fun httpDate(): String {
            dayFmt.timeZone = TimeZone.getTimeZone("GMT")
            return dayFmt.format(Date())
        }
    }
}
