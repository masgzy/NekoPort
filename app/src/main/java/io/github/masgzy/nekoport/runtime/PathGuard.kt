package io.github.masgzy.nekoport.runtime

import io.github.masgzy.nekoport.core.AppLog
import java.io.File

/**
 * Guard rail against leftovers from Termux-built packages.
 *
 * The bundled binaries are Termux builds: they carry COMPILED-IN default
 * paths under `/data/data/com.termux/files/usr` (nginx temp dirs, the Apache
 * runtime dir, the pre-config error log, ...). Every such default is now
 * overridden explicitly in the generated configs, but user-editable config
 * files (copied from Termux, hand-written, or restored from a backup) can
 * still reference Termux paths this app can never access - the process then
 * dies with EACCES on startup ("Permission denied") and the site never opens
 * its port.
 *
 * Two complementary checks, per the audit request:
 *  1. regex REWRITE: any Termux prefix in a config is rewritten to the
 *     NekoPort equivalent before the server is started;
 *  2. full-text MATCH: after rewriting, the file must contain no Termux
 *     reference at all - if it does, the start is refused with a readable
 *     reason instead of a confusing "did not open port".
 */
object PathGuard {

    private const val TAG = "PathGuard"

    /** Marker that can never be part of a legitimate NekoPort path. */
    const val TERMUX_MARKER = "com.termux"

    /**
     * The Termux app data prefix, in BOTH spellings Android uses for the
     * same directory (`/data/data` and `/data/user/0` are bind-mounted
     * aliases of each other) plus any multi-user suffix.
     */
    private val termuxUsr = Regex("""/data/(?:data|user/\d+)/com\.termux/files/usr""")
    private val termuxFiles = Regex("""/data/(?:data|user/\d+)/com\.termux/files""")

    /**
     * Rewrites Termux prefix paths to their NekoPort equivalents:
     * `/data/.../com.termux/files/usr` -> the bundled prefix
     * (`.../files/runtime/prefix`), and any remaining
     * `/data/.../com.termux/files` -> the runtime root.
     * Returns the number of rewritten occurrences.
     */
    fun sanitize(text: String, prefix: String, runtimeRoot: String): Pair<String, Int> {
        var count = 0
        var out = termuxUsr.replace(text) { count++; prefix }
        out = termuxFiles.replace(out) { count++; runtimeRoot }
        return out to count
    }

    /** Full-text match: does the text still reference the Termux sandbox? */
    fun containsTermuxReference(text: String): Boolean = text.contains(TERMUX_MARKER)

    /** The first line (trimmed, capped) that references Termux, for error messages. */
    fun firstTermuxLine(text: String): String? =
        text.lineSequence().firstOrNull { it.contains(TERMUX_MARKER) }?.trim()?.take(160)

    /**
     * Belt-and-suspenders pre-start check for a config file: regex-rewrites
     * any Termux prefix to the NekoPort equivalent (writing the file back),
     * then full-text-matches for leftovers. Returns null when the file is
     * safe, or a readable failure reason when Termux references survive the
     * rewrite (only possible for obfuscated spellings) - the caller must
     * refuse to start the server in that case.
     */
    fun sanitizeAndVerify(conf: File, prefix: String, runtimeRoot: String): String? {
        val text = runCatching { conf.readText() }.getOrElse { return null } // unreadable: let the server fail on its own
        if (!containsTermuxReference(text)) return null
        val (rewritten, count) = sanitize(text, prefix, runtimeRoot)
        if (count > 0) {
            runCatching { conf.writeText(rewritten) }
            AppLog.w(
                TAG,
                "rewrote $count Termux path(s) in ${conf.name}: ${firstTermuxLine(text) ?: ""}",
            )
        }
        val residual = firstTermuxLine(rewritten)
        return residual?.let {
            "config ${conf.name} still references the Termux sandbox after rewriting (readable by Termux only): $it"
        }
    }

    /**
     * Final full-text gate in [ProcessController.launch]: refuses to spawn a
     * process whose executable or command line references the Termux
     * sandbox. The in-app paths are always app-sandbox ones, so a Termux
     * reference here can only be a config defect that would fail with
     * EACCES anyway - failing loudly with a pointer beats a silent crash
     * loop.
     */
    fun checkLaunchArgs(exe: File, args: List<String>): String? {
        if (containsTermuxReference(exe.canonicalPath)) {
            return "executable ${exe.name} lives inside the Termux sandbox - install the NekoPort runtime components"
        }
        val bad = args.firstOrNull { containsTermuxReference(it) }
        return bad?.let {
            "arguments reference the Termux sandbox (readable by Termux only): ${it.take(160)}"
        }
    }
}
