package io.github.masgzy.nekoport.core

import kotlinx.serialization.Serializable

/**
 * HTTP engines bundled as real binaries (Termux builds of the upstream
 * servers). Each site picks one engine and listens on its own port.
 *
 * Order follows the classic KSWEB habit: Lighttpd first.
 */
enum class Engine(val id: String, val displayName: String) {
    LIGHTTPD("lighttpd", "Lighttpd"),
    NGINX("nginx", "Nginx"),
    APACHE("apache", "Apache"),
    ;

    companion object {
        fun byId(raw: String?): Engine =
            entries.firstOrNull { it.id == raw }
                ?: LIGHTTPD.also {
                    if (raw != null) AppLog.w("Config", "unknown engine '$raw', falling back to lighttpd")
                }
    }
}

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
    /** Which bundled real server binary serves this site. */
    val engine: Engine = Engine.LIGHTTPD,
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
     * Extensions routed to php-fpm. Anything else is served as a static file
     * (source stays visible but never executes) - this is the core of the
     * php2shell mitigation.
     */
    val phpExtensions: List<String> = listOf("php"),
    /**
     * Custom document root. Empty string = the default private directory
     * `filesDir/sites/<name>` (keeps old configs untouched); a non-empty
     * value is an absolute path such as `/sdcard/htdocs`.
     */
    val docRoot: String = "",
) {
    companion object {
        val NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
        fun isPortValid(port: Int): Boolean = port in 1024..65535
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

/** MariaDB (MySQL) server settings. */
@Serializable
data class MariaConfig(
    /** Start MariaDB together with "run all services". */
    val enabled: Boolean = false,
    val port: Int = 3306,
    /** Bind 0.0.0.0 instead of 127.0.0.1 (expose the database to the LAN). */
    val allowRemote: Boolean = false,
) {
    companion object {
        fun isPortValid(port: Int): Boolean = port in 1024..65535
    }
}

/** What a scheduled task does when its cron expression fires. */
enum class CronAction(val id: String) {
    /** Run a shell command inside the runtime environment. */
    COMMAND("command"),
    /** Restart every enabled service. */
    RESTART_SERVICES("restart"),
    /** Start all enabled services if nothing is running. */
    KEEP_ALIVE("keepalive"),
    ;

    companion object {
        fun byId(raw: String?): CronAction =
            entries.firstOrNull { it.id == raw }
                ?: COMMAND.also {
                    if (raw != null) AppLog.w("Config", "unknown cron action '$raw', falling back to command")
                }
    }
}

@Serializable
data class CronTask(
    val id: Long,
    val name: String,
    /** Standard 5-field cron expression: minute hour day-of-month month day-of-week. */
    val expr: String,
    val action: CronAction = CronAction.COMMAND,
    /** Shell command for [CronAction.COMMAND]. */
    val command: String = "",
    /** Append a line to the cron log for every run. */
    val logTask: Boolean = true,
    /** Append the command output to the cron log. */
    val logOutput: Boolean = false,
    val enabled: Boolean = true,
) {
    companion object {
        fun isNameValid(name: String): Boolean = name.isNotBlank() && name.length <= 64
    }
}

@Serializable
data class CronConfig(
    val enabled: Boolean = false,
    /** Write scheduler activity into runtime/logs/cron.log. */
    val logEnabled: Boolean = true,
    val tasks: List<CronTask> = emptyList(),
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val startOnBoot: Boolean = false,
    /** Termux repository base URL used to fetch the runtime packages. */
    val repoMirror: String = TermuxMirror.OFFICIAL.url,
    /** Code-editor font size in sp (range 10..20), persisted. */
    val editorFontSize: Int = 14,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Known-good PHP versions that contain the php2shell fix
 * (CVE-2026-109618 / CVE-2026-109619, fixed on 2026-10-21).
 * See https://github.com/kuwoo/php2shell-Lab
 */
object PhpMirror {
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

/**
 * Download mirrors for the Termux runtime repository. All URLs were probed
 * for reachability of `dists/stable/main/binary-<arch>/Packages`.
 * The first ten are pinned in the settings UI; the rest live under "more".
 */
data class TermuxMirror(
    val label: String,
    val url: String,
    val pinned: Boolean = false,
) {
    companion object {
        val OFFICIAL = TermuxMirror("官方源", "https://packages.termux.dev/apt/termux-main", pinned = true)
        val OFFICIAL_CF = TermuxMirror("官方CF源", "https://packages-cf.termux.dev/apt/termux-main", pinned = true)
        val CERNET = TermuxMirror("CERNET", "https://mirrors.cernet.edu.cn/termux/apt/termux-main", pinned = true)
        val TUNA = TermuxMirror("清华", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main", pinned = true)
        val PKU = TermuxMirror("北大", "https://mirrors.pku.edu.cn/termux/apt/termux-main", pinned = true)
        val BFSU = TermuxMirror("北外国语", "https://mirrors.bfsu.edu.cn/termux/apt/termux-main", pinned = true)
        val USTC = TermuxMirror("中科大", "https://mirrors.ustc.edu.cn/termux/apt/termux-main", pinned = true)
        val NJU = TermuxMirror("南大", "https://mirrors.nju.edu.cn/termux/apt/termux-main", pinned = true)
        val ALIYUN = TermuxMirror("阿里云", "https://mirrors.aliyun.com/termux/termux-main", pinned = true)
        val TENCENT = TermuxMirror("腾讯云", "https://mirrors.cloud.tencent.com/termux/apt/termux-main", pinned = true)

        val HUAWEI = TermuxMirror("华为云", "https://mirrors.huaweicloud.com/termux/apt/termux-main")
        val SJTUG = TermuxMirror("上海交大", "https://mirrors.sjtug.sjtu.edu.cn/termux/apt/termux-main")
        val ZJU = TermuxMirror("浙大", "https://mirrors.zju.edu.cn/termux/apt/termux-main")
        val HIT = TermuxMirror("哈工大", "https://mirrors.hit.edu.cn/termux/apt/termux-main")
        val SDU = TermuxMirror("山大", "https://mirrors.sdu.edu.cn/termux/apt/termux-main")
        val NYIST = TermuxMirror("南阳理工", "https://mirrors.nyist.edu.cn/termux/apt/termux-main")
        val SYSU = TermuxMirror("中山大学", "https://mirror.sysu.edu.cn/termux/apt/termux-main")

        /** Pinned mirrors shown directly in the settings spinner. */
        val PINNED: List<TermuxMirror> = listOf(OFFICIAL, OFFICIAL_CF, CERNET, TUNA, PKU, BFSU, USTC, NJU, ALIYUN, TENCENT)

        /** Additional mirrors hidden behind the "more" entry. */
        val MORE: List<TermuxMirror> = listOf(HUAWEI, SJTUG, ZJU, HIT, SDU, NYIST, SYSU)

        val ALL: List<TermuxMirror> = PINNED + MORE

        fun byUrl(url: String?): TermuxMirror? = ALL.firstOrNull { it.url == url?.trim() }
    }
}

@Serializable
data class GlobalConfig(
    val sites: List<SiteConfig> = mutableListOf(),
    val ftp: FtpConfig = FtpConfig(),
    val maria: MariaConfig = MariaConfig(),
    val cron: CronConfig = CronConfig(),
    val settings: AppSettings = AppSettings(),
)
