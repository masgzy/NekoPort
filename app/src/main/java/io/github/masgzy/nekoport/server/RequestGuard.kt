package io.github.masgzy.nekoport.server

import io.github.masgzy.nekoport.core.SiteConfig
import io.github.masgzy.nekoport.core.UrlCodec
import java.io.File

/**
 * Central request-safety gate. Every path that reaches the filesystem goes
 * through [resolve], and every file that reaches the php-cgi handler goes
 * through [checkPhpExecution]. This is where traversal, dotfile disclosure
 * and php2shell-style abuse are neutralised.
 */
object RequestGuard {

    sealed class Decision {
        /** Canonical file inside the docroot that may be served. */
        data class Allowed(val file: File) : Decision()
        data class Denied(val status: Int, val reason: String) : Decision()
    }

    /** Files that must never be served, regardless of location. */
    private val FORBIDDEN_NAMES = setOf(
        "web.config", "composer.json", "composer.lock", "package.json",
        "package-lock.json", "yarn.lock", "database.yml", ".htpasswd",
    )

    private val FORBIDDEN_EXTENSIONS = setOf(
        "bak", "old", "orig", "save", "swp", "swo", "inc", "conf", "config",
        "ini", "env", "sql", "sqlite", "db", "mdb", "gitignore", "gitattributes",
        "editorconfig", "bashrc", "sh", "pl", "py", "cgi", "phar", "phtml",
    )

    /**
     * Decodes and resolves [rawPath] (already split from the query string)
     * against [docRoot]. Guarantees the canonical path stays inside the root:
     * - percent-decoded `..`/`.` segments are collapsed in-memory first,
     * - backslash separators are treated as separators (windows-style tricks),
     * - symlinks are resolved with [File.getCanonicalPath],
     * - NUL bytes and empty escapes are rejected.
     */
    fun resolve(docRoot: File, rawPath: String): Decision {
        val rootCanonical = docRoot.canonicalFile

        if (rawPath.indexOf('\u0000') >= 0) return Decision.Denied(400, "Bad request")

        val decoded = UrlCodec.decode(rawPath)
            ?: return Decision.Denied(400, "Bad request")

        // Treat both separators, split into segments.
        val segments = decoded.split('/', '\\')
        val stack = ArrayDeque<String>()
        for (seg in segments) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (stack.isEmpty()) return Decision.Denied(403, "Forbidden")
                    stack.removeLast()
                }
                else -> {
                    // Control characters are never legitimate in public paths.
                    if (seg.any { it.code in 0x00..0x1F }) return Decision.Denied(400, "Bad request")
                    stack.addLast(seg)
                }
            }
        }

        var file = rootCanonical
        for (seg in stack) {
            file = File(file, seg)
        }

        val canonical = file.canonicalFile
        if (canonical != rootCanonical && !canonical.path.startsWith(rootCanonical.path + File.separator)) {
            return Decision.Denied(403, "Forbidden")
        }
        return Decision.Allowed(canonical)
    }

    /**
     * Evaluates whether [file] may be served at all (static or dynamic).
     * Dotfiles, sensitive metadata and risky source files are blocked.
     */
    fun checkFile(file: File): Decision {
        val name = file.name
        if (name.startsWith(".")) return Decision.Denied(403, "Forbidden")
        if (name in FORBIDDEN_NAMES) return Decision.Denied(403, "Forbidden")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in FORBIDDEN_EXTENSIONS) return Decision.Denied(403, "Forbidden")
        return Decision.Allowed(file)
    }

    /**
     * Checks whether a request for [file] may execute PHP:
     * - the extension must be on the site whitelist (default: only `php`),
     * - no directory between [docRoot] and the file may appear in
     *   [SiteConfig.phpDenyDirs] (upload-directory webshell protection).
     */
    fun checkPhpExecution(site: SiteConfig, docRoot: File, file: File): Boolean {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return false
        val whitelist = site.phpExtensions.map { it.trim('.').lowercase() }.filter { it.isNotEmpty() }
        if (ext !in whitelist) return false

        val rootCanonical = docRoot.canonicalFile
        var parent = file.canonicalFile.parentFile
        while (parent != null && parent.path.startsWith(rootCanonical.path + File.separator)) {
            val dirName = parent.name.lowercase()
            if (site.phpDenyDirs.any { it.trim().lowercase() == dirName && dirName.isNotEmpty() }) {
                return false
            }
            parent = parent.parentFile ?: break
        }
        return true
    }
}
