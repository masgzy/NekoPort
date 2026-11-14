package io.github.masgzy.nekoport.server

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal FastCGI (v1) client used to talk to php-cgi listening on localhost.
 * One TCP connection per request - simple, leak-free and fast enough for a
 * phone-scale web server.
 */
class FastCgiClient(
    private val port: Int,
    private val host: String = "127.0.0.1",
) {
    data class Result(
        val stdout: ByteArray,
        val stderr: String,
        val appStatus: Int,
    )

    class FastCgiException(message: String) : IOException(message)

    companion object {
        private const val FCGI_VERSION = 1
        private const val TYPE_BEGIN_REQUEST = 1
        private const val TYPE_END_REQUEST = 3
        private const val TYPE_PARAMS = 4
        private const val TYPE_STDIN = 5
        private const val TYPE_STDOUT = 6
        private const val TYPE_STDERR = 7
        private const val ROLE_RESPONDER = 1
    }

    fun execute(params: Map<String, String>, stdin: ByteArray, timeoutMs: Int = 30_000): Result {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true

            val out = DataOutputStream(socket.getOutputStream().buffered())
            val input = DataInputStream(socket.getInputStream().buffered())

            val requestId = 1

            // BEGIN_REQUEST
            val begin = ByteArray(8)
            begin[0] = (ROLE_RESPONDER shr 8).toByte()
            begin[1] = ROLE_RESPONDER.toByte()
            begin[2] = 0 // no keep-alive: single request per connection
            out.writeRecord(OUT_TYPE_BEGIN, requestId, begin)

            // PARAMS
            val paramsBuf = ByteArrayOutputStream()
            for ((k, v) in params) {
                writeNameValuePair(paramsBuf, k.toByteArray(Charsets.ISO_8859_1), v.toByteArray(Charsets.ISO_8859_1))
            }
            val paramsBytes = paramsBuf.toByteArray()
            var off = 0
            while (off < paramsBytes.size) {
                val len = minOf(8192, paramsBytes.size - off)
                out.writeRecord(TYPE_PARAMS, requestId, paramsBytes, off, len)
                off += len
            }
            out.writeRecord(TYPE_PARAMS, requestId, ByteArray(0)) // end stream

            // STDIN
            off = 0
            do {
                val len = minOf(8192, stdin.size - off)
                out.writeRecord(TYPE_STDIN, requestId, stdin, off, len)
                off += len
            } while (off < stdin.size)
            if (stdin.isEmpty()) out.writeRecord(TYPE_STDIN, requestId, ByteArray(0))
            out.writeRecord(TYPE_STDIN, requestId, ByteArray(0)) // end stream
            out.flush()

            // Read STDOUT / STDERR until END_REQUEST.
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var appStatus = -1
            var done = false
            while (!done) {
                val version = input.readUnsignedByte()
                if (version != FCGI_VERSION) throw FastCgiException("bad protocol version $version")
                val type = input.readUnsignedByte()
                input.readUnsignedShort() // request id
                val contentLength = input.readUnsignedShort()
                val paddingLength = input.readUnsignedByte()
                input.readUnsignedByte() // reserved
                val content = ByteArray(contentLength)
                input.readFully(content)
                if (paddingLength > 0) {
                    val pad = ByteArray(paddingLength)
                    input.readFully(pad)
                }
                when (type) {
                    TYPE_STDOUT -> stdout.write(content)
                    TYPE_STDERR -> stderr.write(content)
                    TYPE_END_REQUEST -> {
                        appStatus = ((content[0].toInt() and 0xFF) shl 24) or
                            ((content[1].toInt() and 0xFF) shl 16) or
                            ((content[2].toInt() and 0xFF) shl 8) or
                            (content[3].toInt() and 0xFF)
                        done = true
                    }
                    else -> {}
                }
            }
            return Result(stdout.toByteArray(), stderr.toString(Charsets.UTF_8), appStatus)
        }
    }

    private val OUT_TYPE_BEGIN = TYPE_BEGIN_REQUEST

    private fun DataOutputStream.writeRecord(type: Int, requestId: Int, content: ByteArray) {
        writeRecord(type, requestId, content, 0, content.size)
    }

    private fun DataOutputStream.writeRecord(type: Int, requestId: Int, content: ByteArray, off: Int, len: Int) {
        writeByte(FCGI_VERSION)
        writeByte(type)
        writeShort(requestId)
        writeShort(len)
        writeByte(0) // padding
        writeByte(0) // reserved
        if (len > 0) write(content, off, len)
    }

    private fun writeNameValuePair(out: ByteArrayOutputStream, name: ByteArray, value: ByteArray) {
        writeLen(out, name.size)
        writeLen(out, value.size)
        out.write(name)
        out.write(value)
    }

    private fun writeLen(out: ByteArrayOutputStream, len: Int) {
        if (len < 128) {
            out.write(len)
        } else {
            out.write((len ushr 24) or 0x80)
            out.write(len ushr 16)
            out.write(len ushr 8)
            out.write(len)
        }
    }
}
