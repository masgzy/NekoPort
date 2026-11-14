package io.github.masgzy.nekoport.core

object MimeTypes {

    private val map = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "shtml" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "map" to "application/json; charset=utf-8",
        "webmanifest" to "application/manifest+json",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/plain; charset=utf-8",
        "csv" to "text/csv; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "php" to "application/x-httpd-php",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "opus" to "audio/opus",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
        "ts" to "video/mp2t",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "tar" to "application/x-tar",
        "apk" to "application/vnd.android.package-archive",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "eot" to "application/vnd.ms-fontobject",
        "wasm" to "application/wasm",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "torrent" to "application/x-bittorrent",
    )

    fun of(extension: String?): String {
        val ext = extension?.lowercase()?.trim('.') ?: return "application/octet-stream"
        return map[ext] ?: "application/octet-stream"
    }

    fun isTextLike(mime: String): Boolean {
        return mime.startsWith("text/") ||
            mime.contains("json") ||
            mime.contains("xml") ||
            mime.contains("javascript") ||
            mime.contains("svg")
    }
}
