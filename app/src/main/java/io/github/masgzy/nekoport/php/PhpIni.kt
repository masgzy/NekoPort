package io.github.masgzy.nekoport.php

import java.io.File

/**
 * Generates a hardened php.ini per site.
 *
 * Hardening targets the php2shell class of attacks (see
 * https://github.com/kuwoo/php2shell-Lab):
 * - `session.upload_progress.enabled = Off` kills the CVE-2026-109618/109619
 *   upload-progress filename injection primitive outright;
 * - `user_ini.filename = ` disables per-directory `.user.ini` files, so an
 *   attacker cannot smuggle auto_prepend_file directives through uploads;
 * - `cgi.fix_pathinfo = 0` stops `/upload.png/x.php` style PATH_INFO mapping;
 * - `open_basedir` confines every site to its own docroot + tmp dirs;
 * - `auto_prepend_file` / `auto_append_file` are forced empty.
 */
object PhpIni {

    fun generate(docRoot: File, tmpDir: File, sessionDir: File): String = buildString {
        appendLine("; NekoPort generated php.ini - hardened defaults, do not put secrets here")
        appendLine()
        appendLine("[PHP]")
        appendLine("engine = On")
        appendLine("short_open_tag = Off")
        appendLine("output_buffering = 4096")
        appendLine("expose_php = Off")
        appendLine("display_errors = On")
        appendLine("display_startup_errors = Off")
        appendLine("log_errors = On")
        appendLine("error_reporting = E_ALL & ~E_DEPRECATED & ~E_STRICT")
        appendLine("memory_limit = 256M")
        appendLine("max_execution_time = 120")
        appendLine("max_input_time = 120")
        appendLine("max_input_vars = 2000")
        appendLine("post_max_size = 64M")
        appendLine("upload_max_filesize = 64M")
        appendLine("default_charset = \"UTF-8\"")
        appendLine()
        appendLine("[CGI/FastCGI]")
        appendLine("cgi.fix_pathinfo = 0")
        appendLine("cgi.force_redirect = 0")
        appendLine("fastcgi.logging = 0")
        appendLine()
        appendLine("[Path security]")
        appendLine("open_basedir = \"${escape(docRoot.canonicalPath)}:${escape(tmpDir.canonicalPath)}\"")
        appendLine("user_ini.filename =")
        appendLine("user_ini.cache_ttl = 0")
        appendLine("auto_prepend_file =")
        appendLine("auto_append_file =")
        appendLine("enable_dl = Off")
        appendLine("allow_url_fopen = On")
        appendLine("allow_url_include = Off")
        appendLine()
        appendLine("[Session]")
        appendLine("session.use_only_cookies = 1")
        appendLine("session.use_strict_mode = 1")
        appendLine("session.upload_progress.enabled = Off")
        appendLine("session.save_handler = files")
        appendLine("session.save_path = \"${escape(sessionDir.canonicalPath)}\"")
        appendLine()
        appendLine("[File uploads]")
        appendLine("file_uploads = On")
        appendLine("upload_tmp_dir = \"${escape(tmpDir.canonicalPath)}\"")
        appendLine("sys_temp_dir = \"${escape(tmpDir.canonicalPath)}\"")
        appendLine("max_file_uploads = 20")
    }

    fun write(docRoot: File, tmpDir: File, sessionDir: File, iniFile: File): File {
        iniFile.parentFile?.mkdirs()
        iniFile.writeText(generate(docRoot, tmpDir, sessionDir))
        return iniFile
    }

    private fun escape(path: String): String = path.replace("\"", "\\\"")
}
