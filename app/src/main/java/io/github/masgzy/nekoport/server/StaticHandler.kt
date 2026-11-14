package io.github.masgzy.nekoport.server

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serves static files (with ETag / Last-Modified / Range support) and renders
 * directory listings with proper HTML escaping.
 */
object StaticHandler {

    data class FileMeta(
        val size: Long,
        val lastModified: Long,
        val mime: String,
    )

    fun meta(file: File): FileMeta {
        val ext = file.name.substringAfterLast('.', "")
        return FileMeta(file.length(), file.lastModified(), MimeTypes.of(ext))
    }

    fun etag(meta: FileMeta): String {
        return "\"n-${meta.size.toString(16)}-${meta.lastModified.toString(16)}\""
    }

    private fun parseHttpDate(value: String): Long = runCatching {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        fmt.parse(value)?.time
    }.getOrNull() ?: -1L

    /**
     * Builds a response for [file] honouring conditional and range headers.
     * Returns null when the caller should fall through (e.g. zero-size).
     */
    fun serve(file: File, request: HttpRequest): HttpResponse {
        val meta = meta(file)
        val etag = etag(meta)
        val lastModified = HttpExchange.httpDate().let { _ ->
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.format(Date(meta.lastModified))
        }

        val notModified = request.header("if-none-match")?.contains(etag) == true ||
            (request.header("if-none-match") == null &&
                parseHttpDate(request.header("if-modified-since") ?: "") >= meta.lastModified &&
                meta.lastModified > 0)
        if (notModified) {
            val resp = HttpResponse(304, "Not Modified")
            resp.headers["ETag"] = etag
            resp.headers["Last-Modified"] = lastModified
            resp.headers["Cache-Control"] = "public, max-age=60"
            return resp
        }

        val contentType = meta.mime
        val ext = file.name.substringAfterLast('.', "")
        val forceDownload = MimeTypes.of(ext) == "application/octet-stream"

        // Single byte-range support.
        val rangeHeader = request.header("range")
        if (rangeHeader != null && !forceDownload) {
            val match = Regex("^bytes=(\\d*)-(\\d*)$").find(rangeHeader.trim())
            if (match != null) {
                val (startStr, endStr) = match.destructured
                val size = meta.size
                val start = startStr.toLongOrNull()
                val end = endStr.toLongOrNull()
                val resolvedStart: Long
                val resolvedEnd: Long
                if (start == null && end != null) { // suffix range
                    resolvedStart = (size - end).coerceAtLeast(0)
                    resolvedEnd = size - 1
                } else if (start != null) {
                    resolvedStart = start
                    resolvedEnd = (end ?: (size - 1)).coerceAtMost(size - 1)
                } else {
                    return HttpResponse(416, "Range Not Satisfiable")
                }
                if (resolvedStart > resolvedEnd || resolvedStart >= size) {
                    val resp = HttpResponse(416, "Range Not Satisfiable")
                    resp.headers["Content-Range"] = "bytes */$size"
                    return resp
                }
                val bytes = file.inputStream().use { input ->
                    input.skip(resolvedStart)
                    ByteArray((resolvedEnd - resolvedStart + 1).toInt()).also { input.read(it) }
                }
                val resp = HttpResponse(206, "Partial Content")
                resp.body = bytes
                resp.headers["Content-Type"] = contentType
                resp.headers["Content-Range"] = "bytes $resolvedStart-$resolvedEnd/$size"
                resp.headers["Accept-Ranges"] = "bytes"
                resp.headers["ETag"] = etag
                resp.headers["Last-Modified"] = lastModified
                return resp
            }
        }

        val bytes = file.readBytes()
        val resp = HttpResponse(200, "OK")
        resp.body = bytes
        resp.headers["Content-Type"] = contentType
        resp.headers["Accept-Ranges"] = "bytes"
        resp.headers["ETag"] = etag
        resp.headers["Last-Modified"] = lastModified
        resp.headers["Cache-Control"] = "public, max-age=60"
        if (forceDownload) resp.headers["Content-Disposition"] = "attachment; filename=\"${sanitizeFileName(file.name)}\""
        return resp
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
    }

    fun errorPage(status: Int, reason: String, message: String): HttpResponse {
        val resp = HttpResponse(status, reason)
        resp.headers["Content-Type"] = "text/html; charset=utf-8"
        resp.body = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>").append(status).append(' ').append(escape(reason)).append("</title>")
            append("<style>body{font-family:system-ui,-apple-system,sans-serif;background:#f4f4f9;color:#1e1e2e;")
            append("display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}")
            append(".card{background:#fff;border-radius:16px;padding:32px 40px;box-shadow:0 4px 24px rgba(0,0,0,.08);max-width:420px}")
            append("h1{font-size:56px;margin:0;color:#5b6cff}p{color:#666;line-height:1.6}</style></head><body>")
            append("<div class=\"card\"><h1>").append(status).append("</h1><p>").append(escape(message))
            append("</p><p style=\"opacity:.6;font-size:12px\">NekoPort</p></div></body></html>")
        }.toByteArray(Charsets.UTF_8)
        return resp
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                '&' -> sb.append("&amp;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Renders a lightweight directory listing (XSS-safe). */
    fun listing(dir: File, displayPath: String): HttpResponse {
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val sb = StringBuilder(files.size * 96 + 512)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>Index of ").append(escape(displayPath)).append("</title><style>")
        sb.append("body{font-family:system-ui,sans-serif;max-width:860px;margin:0 auto;padding:24px;color:#1e1e2e;background:#f4f4f9}")
        sb.append("h1{font-size:20px}table{width:100%;border-collapse:collapse;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,.06)}")
        sb.append("th{background:#5b6cff;color:#fff;text-align:left;padding:10px 14px;font-size:13px}")
        sb.append("td{padding:8px 14px;border-top:1px solid #eee;font-size:14px}")
        sb.append("a{color:#3b4bd8;text-decoration:none}a:hover{text-decoration:underline}")
        sb.append(".size{color:#888;white-space:nowrap}.time{color:#888;white-space:nowrap}</style></head><body>")
        sb.append("<h1>Index of ").append(escape(displayPath)).append("</h1><table>")
        sb.append("<tr><th>Name</th><th>Size</th><th>Modified</th></tr>")
        if (displayPath != "/") {
            sb.append("<tr><td colspan=\"3\"><a href=\"../\">../</a></td></tr>")
        }
        for (f in files) {
            val name = escape(f.name)
            val href = escape(f.name) + if (f.isDirectory) "/" else ""
            val size = if (f.isDirectory) "-" else humanSize(f.length())
            val time = fmt.format(Date(f.lastModified()))
            sb.append("<tr><td><a href=\"").append(href).append("\">")
            if (f.isDirectory) sb.append("&#128193; ")
            sb.append(name).append("</a></td><td class=\"size\">").append(size)
            sb.append("</td><td class=\"time\">").append(time).append("</td></tr>")
        }
        sb.append("</table><p style=\"opacity:.5;font-size:12px\">NekoPort directory listing</p></body></html>")
        val resp = HttpResponse(200, "OK")
        resp.headers["Content-Type"] = "text/html; charset=utf-8"
        resp.body = sb.toString().toByteArray(Charsets.UTF_8)
        return resp
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        for (u in units) {
            v /= 1024
            if (v < 1024) return String.format(Locale.US, "%.1f %s", v, u)
        }
        return String.format(Locale.US, "%.1f PB", v / 1024)
    }
}
