package io.github.masgzy.nekoport.server

import android.content.Context
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.SiteConfig
import io.github.masgzy.nekoport.php.PhpEnvironment
import io.github.masgzy.nekoport.php.PhpIni
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns php-cgi child processes. Each PHP-enabled site gets its own
 * php-cgi process listening on a private FastCGI port, with a hardened
 * php.ini (see [PhpIni]) and a per-site `open_basedir`.
 */
class PhpCgiManager(private val context: Context) : PhpRequestHandler {

    private val phpRoot = File(context.filesDir, "php")
    private val tmpDir = File(phpRoot, "tmp").apply { mkdirs() }
    private val iniDir = File(phpRoot, "ini").apply { mkdirs() }
    private val sessionDir = File(phpRoot, "sessions").apply { mkdirs() }

    private val nextPort = AtomicInteger(10701)
    private val processes = ConcurrentHashMap<Long, Process>()
    private val ports = ConcurrentHashMap<Long, Int>()

    val binary: File
        get() = File(PhpEnvironment.prefixDir(context), "bin/php-cgi")

    fun isRuntimeInstalled(): Boolean = binary.exists() && binary.canExecute()

    /** Returns the detected version, e.g. "8.4.27", or null. */
    fun detectVersion(): String? {
        val exe = binary
        if (!exe.exists()) return null
        return runCatching {
            val proc = ProcessBuilder(exe.canonicalPath, "-n", "-v")
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            Regex("PHP ([0-9]+\\.[0-9]+\\.[0-9]+)").find(text)?.groupValues?.get(1)
        }.getOrNull()
    }

    /** Ensures a php-cgi process is alive for [site]; returns its FastCGI port. */
    @Synchronized
    fun ensureStarted(site: SiteConfig, docRoot: File): Int? {
        val exe = binary
        if (!exe.exists()) {
            AppLog.w(TAG, "php-cgi not installed; cannot start for site '${site.name}'")
            return null
        }
        if (!exe.setExecutable(true, false)) {
            AppLog.e(TAG, "cannot mark php-cgi executable (targetSdk/exec restriction?)")
            return null
        }

        val port = ports.getOrPut(site.id) { nextPort.getAndAdd(1) }
        val existing = processes[site.id]
        if (existing != null && existing.isAlive) return port

        existing?.destroyForcibly()

        val siteTmp = File(tmpDir, "site-${site.id}").apply { mkdirs() }
        val iniFile = PhpIni.write(docRoot, siteTmp, sessionDir, File(iniDir, "site-${site.id}.ini"))

        val proc = runCatching {
            ProcessBuilder(
                exe.canonicalPath,
                "-c", iniFile.canonicalPath,
                "-b", "127.0.0.1:$port",
                "-d", "error_log=${File(phpRoot, "error.log").canonicalPath}",
            ).apply {
                environment()["LD_LIBRARY_PATH"] = File(PhpEnvironment.prefixDir(context), "lib").canonicalPath
                environment()["TMPDIR"] = siteTmp.canonicalPath
                environment()["HOME"] = phpRoot.canonicalPath
            }.start()
        }.onFailure { AppLog.e(TAG, "spawn php-cgi failed: ${it.message}") }.getOrNull() ?: return null

        processes[site.id] = proc
        AppLog.i(TAG, "php-cgi started for site '${site.name}' on 127.0.0.1:$port")
        return port
    }

    @Synchronized
    fun stopSite(siteId: Long) {
        processes.remove(siteId)?.destroyForcibly()
        ports.remove(siteId)
        File(iniDir, "site-$siteId.ini").delete()
    }

    @Synchronized
    fun stopAll() {
        processes.keys.forEach { stopSite(it) }
    }

    // ---- request handling ----

    override fun handle(site: SiteConfig, docRoot: File, file: File, request: HttpRequest): HttpResponse? {
        val port = ensureStarted(site, docRoot) ?: return null
        val relative = file.canonicalPath
            .removePrefix(docRoot.canonicalPath)
            .replace(File.separatorChar, '/')
        val scriptName = "/" + relative.removePrefix("/")

        val params = buildMap {
            put("SCRIPT_FILENAME", file.canonicalPath)
            put("SCRIPT_NAME", scriptName)
            put("PHP_SELF", scriptName)
            put("DOCUMENT_ROOT", docRoot.canonicalPath)
            put("REQUEST_METHOD", request.method)
            put("REQUEST_URI", request.rawPath + if (request.query.isEmpty()) "" else "?${request.query}")
            put("QUERY_STRING", request.query)
            put("SERVER_PROTOCOL", request.version)
            put("SERVER_SOFTWARE", "NekoPort")
            put("SERVER_NAME", request.header("host") ?: "localhost")
            put("SERVER_ADDR", "0.0.0.0")
            put("SERVER_PORT", site.port.toString())
            put("REMOTE_ADDR", request.remoteAddr)
            put("REMOTE_PORT", "0")
            put("GATEWAY_INTERFACE", "CGI/1.1")
            request.header("content-type")?.let { put("CONTENT_TYPE", it) }
            put("CONTENT_LENGTH", request.body.size.toString())
            put("HTTPS", "off")
            put("REDIRECT_STATUS", "200")
            for ((k, v) in request.headers) {
                val upper = k.uppercase().replace('-', '_')
                if (upper !in setOf("CONTENT_TYPE", "CONTENT_LENGTH")) {
                    put("HTTP_$upper", v)
                }
            }
        }

        val result = try {
            FastCgiClient(port).execute(params, request.body)
        } catch (e: Exception) {
            AppLog.e(TAG, "fastcgi request failed for ${file.name}: ${e.message}")
            // Process may have died; drop it so the next request re-spawns.
            processes.remove(site.id)?.destroyForcibly()
            return null
        }

        return parseCgiResponse(result)
    }

    private fun parseCgiResponse(result: FastCgiClient.Result): HttpResponse {
        val raw = result.stdout
        val headerEnd = findHeaderEnd(raw)
        if (headerEnd < 0) {
            // No valid CGI header - treat output as an error page body.
            val resp = HttpResponse(500, "Internal Server Error")
            resp.headers["Content-Type"] = "text/html; charset=utf-8"
            val err = result.stderr.ifBlank { "php-cgi returned no HTTP header" }
            resp.body = StaticHandler.escape(err.take(4000)).toByteArray(Charsets.UTF_8)
            return resp
        }
        val headerBlock = String(raw, 0, headerEnd, Charsets.ISO_8859_1)
        val body = raw.copyOfRange(headerEnd, raw.size)

        val resp = HttpResponse(200, "OK")
        var status = 200
        var hasContentType = false
        for (line in headerBlock.split("\r\n", "\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (name.lowercase()) {
                "status" -> status = value.substringBefore(' ').toIntOrNull() ?: 200
                "content-type" -> {
                    hasContentType = true
                    resp.headers["Content-Type"] = value
                }
                "transfer-encoding", "content-length", "connection", "date", "server" -> {
                    // Framing is decided by NekoPort itself.
                }
                else -> resp.headers[name] = value
            }
        }
        if (!hasContentType) resp.headers["Content-Type"] = "text/html; charset=utf-8"
        resp.status = status
        resp.reason = HttpServer.statusText(status).takeIf { status != 200 } ?: "OK"
        resp.body = body
        return resp
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) return i + 4
            if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) return i + 2
        }
        return -1
    }

    companion object {
        private const val TAG = "PhpCgi"
    }
}
