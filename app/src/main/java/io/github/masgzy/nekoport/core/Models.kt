package io.github.masgzy.nekoport.core

import kotlinx.serialization.Serializable

/**
 * A single HTTP site (virtual host). Each site listens on its own port,
 * mirroring the "host" concept of classic Android web servers.
 */
@Serializable
data class SiteConfig(
    val id: Long,
    val name: String,
    val port: Int,
    val enabled: Boolean = true,
    val enablePhp: Boolean = false,
    val directoryListing: Boolean = true,
    /** Ordered index file candidates, e.g. ["index.html", "index.php"]. */
    val indexFiles: List<String> = listOf("index.html", "index.htm", "index.php"),
    /**
     * Relative directory names where PHP execution is refused (webshell protection
     * for upload directories). Path segments are matched case-insensitively.
     */
    val phpDenyDirs: List<String> = listOf("uploads", "upload", "static"),
    /**
     * Extensions routed to the php-cgi handler. Anything else is served as a
     * static file (source stays visible but never executes) - this is the core
     * of the php2shell mitigation.
     */
    val phpExtensions: List<String> = listOf("php"),
) {
    companion object {
        val NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
        fun isPortValid(port: Int): Boolean = port in 1..65535
    }
}

@Serializable
data class FtpConfig(
    val enabled: Boolean = false,
    val port: Int = 2121,
    val username: String = "neko",
    val password: String = "changeme-neko",
    /** Starting port of the passive range (start..start+32). */
    val passivePortStart: Int = 50000,
) {
    companion object {
        fun isPortValid(port: Int): Boolean = port in 1024..65535
    }
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accessLogEnabled: Boolean = true,
    val startOnBoot: Boolean = false,
    val phpMirror: String = PhpMirror.DEFAULT,
    val installedPhpVersion: String? = null,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Known-good PHP versions that contain the php2shell fix
 * (CVE-2026-109618 / CVE-2026-109619, fixed on 2026-10-21).
 * See https://github.com/kuwoo/php2shell-Lab
 */
object PhpMirror {
    const val DEFAULT = "https://packages.termux.dev/apt/termux-main"

    /** PHP releases containing the session.upload_progress hardening (php2shell fix). */
    val SAFE_VERSIONS = listOf("8.2.35", "8.3.36", "8.4.27", "8.5.12")
    const val RECOMMENDED = "8.4.27"

    /** Returns true when [version] (e.g. "8.4.27") is not older than the safe floor of its branch. */
    fun isSafe(version: String): Boolean {
        val parts = version.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size < 3) return false
        val major = parts[0]
        val minor = parts[1]
        val patch = parts[2]
        val floor = when {
            major == 8 && minor == 2 -> 8.2f to 35
            major == 8 && minor == 3 -> 8.3f to 36
            major == 8 && minor == 4 -> 8.4f to 27
            major == 8 && minor == 5 -> 8.5f to 12
            major > 8 -> return true
            else -> return false
        }
        return patch >= floor.second
    }
}

@Serializable
data class GlobalConfig(
    val sites: List<SiteConfig> = mutableListOf(),
    val ftp: FtpConfig = FtpConfig(),
    val settings: AppSettings = AppSettings(),
)
