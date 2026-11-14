package io.github.masgzy.nekoport.server

import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.SiteConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

interface PhpRequestHandler {
    /**
     * Executes [file] (must be inside [docRoot]) through php-cgi.
     * Returns null when the runtime is unavailable.
     */
    fun handle(site: SiteConfig, docRoot: File, file: File, request: HttpRequest): HttpResponse?
}

/**
 * Per-site HTTP/1.1 server. Binds to 0.0.0.0:[port], serves the site docroot,
 * static files and optionally PHP via FastCGI.
 */
class HttpServer(
    val site: SiteConfig,
    val docRoot: File,
    private val phpHandler: PhpRequestHandler?,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("http-${site.port}"))

    @Volatile
    var isRunning = false
        private set

    val requestCount = AtomicLong(0)

    fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(site.port), 64)
        serverSocket = ss
        isRunning = true
        AppLog.i(TAG, "site '${site.name}' listening on 0.0.0.0:${site.port} (root=$docRoot)")
        scope.launch {
            while (isActive && isRunning) {
                val sock = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                sock.tcpNoDelay = true
                scope.launch { handleConnection(sock) }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        AppLog.i(TAG, "site '${site.name}' stopped (served ${requestCount.get()} requests)")
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val exchange = HttpExchange(socket, MAX_BODY)
            while (isRunning) {
                val request = exchange.readRequest() ?: break
                requestCount.incrementAndGet()
                val keepAlive = request.version == "HTTP/1.1" &&
                    !request.header("connection").equals("close", ignoreCase = true)
                val response = try {
                    dispatch(request)
                } catch (e: BadRequestException) {
                    StaticHandler.errorPage(400, "Bad Request", e.message ?: "Bad request")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(TAG, "handler error: ${e.message}")
                    StaticHandler.errorPage(500, "Internal Server Error", "The server hit an internal error.")
                }
                exchange.writeResponse(response, keepAlive, request.method == "HEAD")
                AppLog.access(TAG, "${request.remoteAddr} \"${request.method} ${request.path}${if (request.query.isEmpty()) "" else "?${request.query}"}\" -> ${response.status}")
                if (!keepAlive) break
            }
        } catch (_: SocketTimeoutException) {
            // idle keep-alive connection, silently close
        } catch (_: Exception) {
            // broken pipe / reset, silently close
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Routes one request through the security gate. */
    private fun dispatch(request: HttpRequest): HttpResponse {
        if (request.method == "OPTIONS") {
            val resp = HttpResponse(200, "OK")
            resp.headers["Allow"] = "GET, HEAD, POST, PUT, DELETE, OPTIONS"
            resp.headers["Content-Length"] = "0"
            return resp
        }

        val docRootCanonical = docRoot.canonicalFile
        when (val decision = RequestGuard.resolve(docRootCanonical, request.path)) {
            is RequestGuard.Decision.Denied -> {
                return StaticHandler.errorPage(decision.status, statusText(decision.status), decision.reason)
            }
            is RequestGuard.Decision.Allowed -> {
                val file = decision.file
                if (file.isDirectory) {
                    // Try index files in order.
                    for (index in site.indexFiles) {
                        val candidate = File(file, index.trim())
                        if (candidate.isFile) {
                            return serveFile(candidate, request, docRootCanonical)
                        }
                    }
                    return if (site.directoryListing) {
                        val display = request.path.trimEnd('/') + "/"
                        StaticHandler.listing(file, display)
                    } else {
                        StaticHandler.errorPage(403, "Forbidden", "Directory listing is disabled.")
                    }
                }
                if (!file.exists()) {
                    return StaticHandler.errorPage(404, "Not Found", "The requested resource does not exist.")
                }
                return serveFile(file, request, docRootCanonical)
            }
        }
    }

    private fun serveFile(file: File, request: HttpRequest, docRootCanonical: File): HttpResponse {
        val name = file.name
        val ext = name.substringAfterLast('.', "").lowercase()

        // Dotfiles / metadata / risky sources are always blocked.
        when (val guard = RequestGuard.checkFile(file)) {
            is RequestGuard.Decision.Denied -> {
                AppLog.w(TAG, "blocked sensitive file request: ${file.name}")
                return StaticHandler.errorPage(guard.status, statusText(guard.status), guard.reason)
            }
            else -> {}
        }

        val whitelist = site.phpExtensions.map { it.trim('.').lowercase() }.filter { it.isNotEmpty() }
        val isPhpLike = ext.isNotEmpty() && ext in whitelist

        if (isPhpLike) {
            if (!site.enablePhp || phpHandler == null) {
                // Never disclose PHP source: refuse instead of serving raw.
                return StaticHandler.errorPage(403, "Forbidden", "PHP execution is disabled for this site.")
            }
            if (!RequestGuard.checkPhpExecution(site, docRootCanonical, file)) {
                AppLog.w(TAG, "blocked PHP execution in protected directory: ${file.path}")
                return StaticHandler.errorPage(
                    403, "Forbidden",
                    "PHP execution is not allowed in this directory (webshell protection)."
                )
            }
            val resp = phpHandler.handle(site, docRootCanonical, file, request)
                ?: return StaticHandler.errorPage(
                    503, "Service Unavailable",
                    "PHP runtime is unavailable. Install it from the Settings page."
                )
            return resp
        }

        return StaticHandler.serve(file, request)
    }

    companion object {
        private const val TAG = "HttpServer"
        const val MAX_BODY = 64L * 1024 * 1024

        fun statusText(status: Int): String = when (status) {
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }
    }
}
