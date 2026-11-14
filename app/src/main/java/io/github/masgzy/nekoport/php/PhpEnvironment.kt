package io.github.masgzy.nekoport.php

import android.content.Context
import android.os.Build
import io.github.masgzy.nekoport.core.AppLog
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/**
 * Installs the PHP runtime (php + php-cgi) into the app private directory by
 * pulling official Termux packages and their dependency closure.
 *
 * Termux builds PHP for Android with current releases (8.4/8.5 as of 2026),
 * which already contain the php2shell fix (CVE-2026-109618/109619). The
 * installed tree is never writable from HTTP requests and php-cgi is launched
 * with a hardened php.ini, so the runtime itself stays out of the web root.
 */
object PhpEnvironment {

    private const val TAG = "PhpEnv"

    fun prefixDir(context: Context): File = File(File(context.filesDir, "php"), "prefix")
    private fun installedDir(context: Context): File = File(File(context.filesDir, "php"), "installed")
    private fun cacheDir(context: Context): File = File(context.cacheDir, "php-debs")

    private fun termuxArch(): String? {
        return when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            else -> null
        }
    }

    sealed class Progress {
        data class Phase(val label: String) : Progress()
        data class Package(val name: String, val index: Int, val total: Int) : Progress()
        data class Download(val name: String, val percent: Int) : Progress()
    }

    data class Pkg(val name: String, val version: String, val depends: List<String>, val filename: String)

    private class RepoException(message: String) : IOException(message)

    /** Installs PHP; returns the detected version string on success. */
    suspend fun install(
        context: Context,
        mirror: String,
        onProgress: suspend (Progress) -> Unit,
    ): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val arch = termuxArch()
                ?: throw RepoException("unsupported device ABI: ${Build.SUPPORTED_ABIS.joinToString()}")

            val prefix = prefixDir(context)
            prefix.mkdirs()
            installedDir(context).mkdirs()
            cacheDir(context).mkdirs()

            val base = mirror.trimEnd('/')
            onProgress(Progress.Phase("index"))
            val index = fetchIndex(base, arch)
            AppLog.i(TAG, "index loaded: ${index.size} packages")

            onProgress(Progress.Phase("resolve"))
            val toInstall = resolveDependencies(index, "php")
            if (toInstall.isEmpty()) throw RepoException("package 'php' not found in index")
            AppLog.i(TAG, "dependency closure: ${toInstall.joinToString()}")

            val alreadyInstalled = installedDir(context).list()?.toSet() ?: emptySet()
            val missing = toInstall.filter { it.name !in alreadyInstalled }
            val total = missing.size
            missing.forEachIndexed { i, pkg ->
                onProgress(Progress.Package(pkg.name, i + 1, total))
                val debFile = download(context, base, pkg) { percent ->
                    onProgress(Progress.Download(pkg.name, percent))
                }
                onProgress(Progress.Phase("extract:${pkg.name}"))
                extractDeb(debFile, prefix)
                File(installedDir(context), pkg.name).writeText(pkg.version)
                debFile.delete()
            }

            // Make executables runnable (data dir is writable; exec is allowed
            // because NekoPort targets SDK 28, same approach as Termux).
            File(prefix, "bin").listFiles()?.forEach { it.setExecutable(true, false) }

            val version = detectVersion(prefix)
                ?: throw RepoException("php-cgi did not report a version")
            AppLog.i(TAG, "php installed: $version")
            Result.success(version)
        } catch (e: Throwable) {
            AppLog.e(TAG, "install failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun uninstall(context: Context): Boolean {
        val prefix = prefixDir(context)
        val ok = prefix.deleteRecursively()
        installedDir(context).deleteRecursively()
        File(File(context.filesDir, "php"), "error.log").delete()
        return ok
    }

    fun detectVersion(prefix: File): String? {
        val exe = File(prefix, "bin/php-cgi")
        if (!exe.exists()) return null
        return runCatching {
            exe.setExecutable(true, false)
            val proc = ProcessBuilder(exe.canonicalPath, "-n", "-v")
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            Regex("PHP ([0-9]+\\.[0-9]+\\.[0-9]+)").find(text)?.groupValues?.get(1)
        }.getOrNull()
    }

    // ---------- repo plumbing ----------

    private fun openConnection(url: String, connectTimeout: Int = 15000, readTimeout: Int = 30000): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeout
        conn.readTimeout = readTimeout
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "NekoPort/0.1")
        return conn
    }

    private fun fetchIndex(base: String, arch: String): Map<String, Pkg> {
        val url = "$base/dists/stable/main/binary-$arch/Packages"
        val text = runCatching {
            openConnection(url).let { conn ->
                conn.connect()
                if (conn.responseCode != 200) throw RepoException("index HTTP ${conn.responseCode}")
                conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            }
        }.getOrElse { throw RepoException("cannot fetch package index: ${it.message}") }

        val map = HashMap<String, Pkg>(512)
        for (block in text.split("\n\n")) {
            var name: String? = null
            var version = ""
            var depends = emptyList<String>()
            var filename = ""
            for (line in block.lines()) {
                when {
                    line.startsWith("Package: ") -> name = line.substring(9).trim()
                    line.startsWith("Version: ") -> version = line.substring(9).trim()
                    line.startsWith("Depends: ") -> depends = line.substring(9).split(',')
                    line.startsWith("Filename: ") -> filename = line.substring(10).trim()
                }
            }
            if (name != null && filename.isNotEmpty()) {
                map[name] = Pkg(name, version, depends, filename)
            }
        }
        return map
    }

    /** Breadth-first dependency closure rooted at [rootPackage]. */
    fun resolveDependencies(index: Map<String, Pkg>, rootPackage: String): List<Pkg> {
        val queue = ArrayDeque<String>()
        val seen = HashSet<String>()
        val result = LinkedHashMap<String, Pkg>()
        queue.add(rootPackage)
        seen.add(rootPackage)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            val pkg = index[name] ?: continue
            result[name] = pkg
            for (dep in pkg.depends) {
                val depName = dep.substringBefore('(')
                    .substringBefore('|')
                    .trim()
                if (depName.isEmpty() || !seen.add(depName)) continue
                queue.add(depName)
            }
        }
        return result.values.toList()
    }

    private suspend fun download(context: Context, base: String, pkg: Pkg, onPercent: suspend (Int) -> Unit): File {
        val url = "$base/${pkg.filename}"
        val target = File(cacheDir(context), pkg.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".deb")
        AppLog.i(TAG, "downloading ${pkg.name} from $url")
        val conn = openConnection(url, readTimeout = 60000)
        conn.connect()
        if (conn.responseCode !in 200..299) throw RepoException("HTTP ${conn.responseCode} for ${pkg.name}")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(65536)
                var received = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    received += n
                    if (total > 0) {
                        val percent = (received * 100 / total).toInt().coerceIn(0, 100)
                        if (percent % 10 == 0) onPercent(percent)
                    }
                }
            }
        }
        if (target.length() == 0L) throw RepoException("empty download for ${pkg.name}")
        return target
    }

    /** Extracts the `data.tar.*` payload of a .deb into [dest], path-traversal safe. */
    private fun extractDeb(deb: File, dest: File) {
        val destCanonical = dest.canonicalFile
        ArArchiveInputStream(FileInputStream(deb)).use { ar ->
            var dataEntry: org.apache.commons.compress.archivers.ArchiveEntry? = null
            while (run { dataEntry = ar.nextArEntry; dataEntry } != null) {
                val name = dataEntry!!.name ?: continue
                if (name.startsWith("data.tar.")) {
                    val decompressed = when {
                        name.endsWith(".xz") -> XZCompressorInputStream(ar)
                        name.endsWith(".zst") -> ZstdCompressorInputStream(ar)
                        name.endsWith(".gz") -> GzipCompressorInputStream(ar)
                        name == "data.tar" -> ar
                        else -> continue
                    }
                    TarArchiveInputStream(decompressed).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            val rel = entry.name.removePrefix("./").trimStart('/')
                            if (rel.isEmpty()) continue
                            val target = File(dest, rel)
                            if (!target.canonicalPath.startsWith(destCanonical.path + File.separator)) {
                                throw RepoException("blocked path traversal in package: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { tar.copyTo(it) }
                                if (entry.mode and 0x49 != 0) { // u+x, g+x, o+x
                                    target.setExecutable(true, false)
                                }
                            }
                        }
                    }
                    return
                }
            }
        }
        throw RepoException("no data.tar payload in ${deb.name}")
    }
}
