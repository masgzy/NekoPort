package io.github.masgzy.nekoport.runtime

import android.content.Context
import android.os.Build
import io.github.masgzy.nekoport.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/**
 * Runtime component catalogue.
 *
 * Every server shipped by NekoPort is the *upstream binary* built by the
 * Termux project for Android (Bionic libc, aarch64/arm/x86_64). Nothing here
 * is a re-implementation: the packages below are the actual lighttpd, nginx,
 * Apache httpd, PHP-FPM, MariaDB and Pure-FTPd programs, installed together
 * with their full dependency closure (openssl, pcre2, zlib, ...).
 */
enum class RuntimePackage(val packageName: String, val binary: String, val label: String, val versionArg: String = "-v") {
    LIGHTTPD("lighttpd", "lighttpd", "Lighttpd"),
    NGINX("nginx", "nginx", "Nginx"),
    APACHE("apache2", "httpd", "Apache"),
    PHP_FPM("php-fpm", "php-fpm", "PHP-FPM"),
    MARIADB("mariadb", "mariadbd", "MariaDB (MySQL)", versionArg = "--version"),
    PURE_FTPD("pure-ftpd", "pure-ftpd", "Pure-FTPd"),
    ;

    companion object {
        val ALL: List<RuntimePackage> = entries.toList()
    }
}

@Serializable
internal data class InstalledState(val packages: Map<String, String> = emptyMap())

/**
 * Installs/uninstalls the real server binaries into the app private directory
 * by pulling official Termux packages and resolving their dependency closure.
 *
 * The private directory is never reachable from HTTP requests and the binaries
 * run with the app sandbox uid, so the runtime stays outside the web root.
 */
object TermuxRuntime {

    private const val TAG = "Runtime"
    private const val USER_AGENT = "NekoPort/0.1"

    /**
     * Termux packages ship their payload under
     * `data/data/com.termux/files/usr/...`; we strip that prefix so the
     * payload lands directly inside our own prefix directory.
     */
    private const val TERMUX_PAYLOAD_PREFIX = "data/data/com.termux/files/usr/"
    /** Hard-baked Termux prefix found inside shell scripts; rewritten at install time. */
    private const val TERMUX_BAKED_PREFIX = "/data/data/com.termux/files/usr"

    fun runtimeRoot(context: Context): File = File(context.filesDir, "runtime")
    fun prefixDir(context: Context): File = File(runtimeRoot(context), "prefix")
    fun etcDir(context: Context): File = File(runtimeRoot(context), "etc").apply { mkdirs() }
    fun runDir(context: Context): File = File(runtimeRoot(context), "run").apply { mkdirs() }
    fun logsDir(context: Context): File = File(runtimeRoot(context), "logs").apply { mkdirs() }
    fun tmpDir(context: Context): File = File(runtimeRoot(context), "tmp").apply { mkdirs() }
    private fun stateFile(context: Context): File = File(runtimeRoot(context), "installed.json")
    private fun cacheDir(context: Context): File = File(context.cacheDir, "runtime-debs")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private class RepoException(message: String) : IOException(message)

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    @Synchronized
    private fun readInstalledState(context: Context): InstalledState = runCatching {
        val file = stateFile(context)
        if (!file.exists()) return InstalledState()
        json.decodeFromString(InstalledState.serializer(), file.readText())
    }.getOrElse { InstalledState() }

    @Synchronized
    private fun writeInstalledState(context: Context, state: InstalledState) {
        runCatching { stateFile(context).writeText(json.encodeToString(InstalledState.serializer(), state)) }
    }

    /** Detects the version of a bundled binary by running it with its version flag. */
    fun detectVersion(context: Context, pkg: RuntimePackage): String? {
        val exe = File(prefixDir(context), "bin/${pkg.binary}")
        if (!exe.exists()) return null
        return runCatching {
            exe.setExecutable(true, false)
            val proc = ProcessBuilder(exe.canonicalPath, pkg.versionArg)
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            Regex("""([0-9]+\.[0-9]+\.[0-9]+[0-9a-zA-Z.\-]*)""").find(text)?.groupValues?.get(1)
        }.getOrNull()
    }

    /** Returns per-package versions for everything currently installed. */
    fun status(context: Context): Map<RuntimePackage, String> {
        val installed = readInstalledState(context).packages
        val result = LinkedHashMap<RuntimePackage, String>()
        for (pkg in RuntimePackage.ALL) {
            val recorded = installed[pkg.packageName]
            val version = detectVersion(context, pkg)
                // fallback: version recorded from the package index, strip epoch ("1:2.4.71")
                ?: recorded?.let { it.substringAfter(':', it) }
                ?: continue
            result[pkg] = version
        }
        return result
    }

    fun isInstalled(context: Context): Boolean {
        val state = readInstalledState(context).packages
        // Web engines: at least one of lighttpd / nginx / apache.
        val hasEngine = RuntimePackage.entries.any {
            it != RuntimePackage.PHP_FPM && it != RuntimePackage.MARIADB && state.containsKey(it.packageName)
        }
        return state.containsKey(RuntimePackage.PHP_FPM.packageName) &&
            state.containsKey(RuntimePackage.PURE_FTPD.packageName) &&
            hasEngine
    }

    /** True when the MariaDB server package is present. */
    fun isMariaDbInstalled(context: Context): Boolean {
        val exe = File(prefixDir(context), "bin/${RuntimePackage.MARIADB.binary}")
        return exe.exists()
    }

    // ------------------------------------------------------------------
    // Install / uninstall
    // ------------------------------------------------------------------

    sealed class Progress {
        data class Phase(val label: String) : Progress()
        data class Package(val name: String, val index: Int, val total: Int) : Progress()
        data class Download(val name: String, val percent: Int) : Progress()
    }

    /** Installs every runtime package (and dependencies) that is missing. */
    suspend fun install(
        context: Context,
        mirror: String,
        onProgress: suspend (Progress) -> Unit,
    ): Result<Map<RuntimePackage, String>> = withContext(Dispatchers.IO) {
        try {
            val arch = termuxArch()
                ?: throw RepoException("unsupported device ABI: ${Build.SUPPORTED_ABIS.joinToString()}")

            val prefix = prefixDir(context)
            prefix.mkdirs()
            cacheDir(context).mkdirs()

            val base = mirror.trimEnd('/')
            onProgress(Progress.Phase("index"))
            val index = fetchIndex(base, arch)
            AppLog.i(TAG, "index loaded: ${index.size} packages")

            val rootPackages = RuntimePackage.ALL.map { it.packageName }
            onProgress(Progress.Phase("resolve"))
            val closure = resolveDependencies(index, rootPackages)
            if (closure.isEmpty()) throw RepoException("runtime packages not found in index")

            val alreadyInstalled = readInstalledState(context).packages.keys
            val missing = closure.filter { it.name !in alreadyInstalled }
            AppLog.i(TAG, "dependency closure: ${closure.joinToString { it.name }}")
            val total = missing.size
            missing.forEachIndexed { i, pkg ->
                onProgress(Progress.Package(pkg.name, i + 1, total))
                val debFile = download(context, base, pkg) { percent ->
                    onProgress(Progress.Download(pkg.name, percent))
                }
                onProgress(Progress.Phase("extract:${pkg.name}"))
                extractDeb(debFile, prefix)
                debFile.delete()
            }

            markExecutables(context)

            // Record installed versions.
            val state = readInstalledState(context).packages.toMutableMap()
            for (pkg in closure) {
                state[pkg.name] = pkg.version
            }
            writeInstalledState(context, InstalledState(state))

            // Drop a termux-style layout so binaries feel at home.
            File(prefix, "tmp").mkdirs()
            File(prefix, "var/run").mkdirs()

            patchBakedPaths(context)

            val versions = status(context)
            AppLog.i(TAG, "runtime installed: ${versions.map { "${it.key.label} ${it.value}" }}")
            Result.success(versions)
        } catch (e: Throwable) {
            AppLog.e(TAG, "install failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun uninstall(context: Context): Boolean {
        val ok = prefixDir(context).deleteRecursively()
        stateFile(context).delete()
        etcDir(context).deleteRecursively()
        AppLog.i(TAG, "runtime uninstalled")
        return ok
    }

    /** Makes every extracted ELF runnable (data-dir exec is allowed: targetSdk 28). */
    private fun markExecutables(context: Context) {
        val prefix = prefixDir(context)
        File(prefix, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(prefix, "libexec/apache2").listFiles()?.forEach { it.setExecutable(true, false) }
        File(prefix, "lib/lighttpd").listFiles()?.forEach { it.setExecutable(true, false) }
        File(prefix, "sbin").listFiles()?.forEach { it.setExecutable(true, false) }
    }

    /**
     * Rewrites the hard-baked Termux prefix inside extracted shell scripts
     * (e.g. mariadb-install-db) so they point at our relocated prefix. ELF
     * binaries are left untouched: text decode simply fails for them.
     */
    private fun patchBakedPaths(context: Context) {
        val prefix = prefixDir(context)
        val newPrefix = prefix.canonicalPath
        val dirs = listOf(File(prefix, "bin"), File(prefix, "sbin"), File(prefix, "libexec/apache2"))
        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.length() == 0L || file.length() > 2 * 1024 * 1024) continue
                val text = runCatching {
                    if (!file.isFile) return@runCatching null
                    file.readText()
                }.getOrNull() ?: continue
                if (!text.contains(TERMUX_BAKED_PREFIX)) continue
                runCatching {
                    file.writeText(text.replace(TERMUX_BAKED_PREFIX, newPrefix))
                    AppLog.i(TAG, "patched baked termux prefix in ${file.name}")
                }
            }
        }
    }

    private fun termuxArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        else -> null
    }

    // ------------------------------------------------------------------
    // Repo plumbing
    // ------------------------------------------------------------------

    data class Pkg(val name: String, val version: String, val depends: List<String>, val filename: String)

    private fun openConnection(url: String, connectTimeout: Int = 15000, readTimeout: Int = 30000): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeout
        conn.readTimeout = readTimeout
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    private fun fetchIndex(base: String, arch: String): Map<String, Pkg> {
        val url = "$base/dists/stable/main/binary-$arch/Packages"
        val text = runCatching {
            openConnection(url).let { conn ->
                conn.connect()
                if (conn.responseCode != 200) throw RepoException("index HTTP ${conn.responseCode}")
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }.getOrElse { throw RepoException("cannot fetch package index: ${it.message}") }

        val map = HashMap<String, Pkg>(1024)
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

    /** Breadth-first dependency closure over several roots. */
    fun resolveDependencies(index: Map<String, Pkg>, roots: List<String>): List<Pkg> {
        val queue = ArrayDeque<String>()
        val seen = HashSet<String>()
        val result = LinkedHashMap<String, Pkg>()
        for (root in roots) {
            if (seen.add(root)) queue.add(root)
        }
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            val pkg = index[name] ?: continue
            result[name] = pkg
            for (dep in pkg.depends) {
                val depName = dep.substringBefore('(').substringBefore('|').trim()
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
                            val rel0 = entry.name.removePrefix("./").trimStart('/')
                            val rel = if (rel0.startsWith(TERMUX_PAYLOAD_PREFIX)) {
                                rel0.substring(TERMUX_PAYLOAD_PREFIX.length)
                            } else {
                                rel0
                            }
                            if (rel.isEmpty()) continue
                            val target = File(dest, rel)
                            if (!target.canonicalPath.startsWith(destCanonical.path + File.separator)) {
                                throw RepoException("blocked path traversal in package: ${entry.name}")
                            }
                            when {
                                entry.isDirectory -> target.mkdirs()
                                entry.isSymbolicLink -> {
                                    target.parentFile?.mkdirs()
                                    runCatching {
                                        target.delete()
                                        val linkTarget = File(target.parentFile, entry.linkName)
                                        java.nio.file.Files.createSymbolicLink(
                                            target.toPath(),
                                            linkTarget.toPath(),
                                        )
                                    }.onFailure {
                                        AppLog.w(TAG, "symlink failed for ${entry.name}: ${it.message}")
                                    }
                                }
                                entry.isLink -> {
                                    // Hard link: copy the already-extracted target.
                                    val source = File(dest, entry.linkName.trimStart('/'))
                                    if (source.exists()) {
                                        target.parentFile?.mkdirs()
                                        source.copyTo(target, overwrite = true)
                                    }
                                }
                                else -> {
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { tar.copyTo(it) }
                                    if (entry.mode and 0x49 != 0) { // u+x, g+x, o+x
                                        target.setExecutable(true, false)
                                    }
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
