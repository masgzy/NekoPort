package io.github.masgzy.nekoport.runtime

import android.content.Context
import android.os.Build
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.Engine
import io.github.masgzy.nekoport.core.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
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
internal data class InstalledPackage(val version: String, val files: List<String> = emptyList())

@Serializable
internal data class InstalledState(val packages: Map<String, InstalledPackage> = emptyMap())

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
        val text = file.readText()
        try {
            json.decodeFromString(InstalledState.serializer(), text)
        } catch (_: Exception) {
            // Legacy format: {"packages": {"name": "1.2.3"}} - values were
            // plain version strings. Convert so old installs keep working.
            val obj = json.parseToJsonElement(text)
            val packagesObj = obj.jsonObject["packages"]?.jsonObject ?: emptyMap()
            InstalledState(
                packages = packagesObj.mapValues { (_, v) ->
                    when (v) {
                        is JsonPrimitive -> InstalledPackage(v.content)
                        is JsonObject -> json.decodeFromJsonElement(InstalledPackage.serializer(), v)
                        else -> InstalledPackage("")
                    }
                },
            )
        }
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
                ?: recorded?.version?.let { it.substringAfter(':', it) }
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

    /**
     * Full, untruncated `binary --version` output (may be multi-line), used
     * by the "version details" row. Returns null when the binary is absent
     * or the probe fails. Must be called off the main thread.
     */
    fun rawVersionOutput(context: Context, pkg: RuntimePackage): String? {
        val exe = File(prefixDir(context), "bin/${pkg.binary}")
        if (!exe.exists()) return null
        return runCatching {
            exe.setExecutable(true, false)
            val proc = ProcessBuilder(exe.canonicalPath, pkg.versionArg)
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            text.ifBlank { null }
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Install / uninstall
    // ------------------------------------------------------------------

    sealed class Progress {
        data class Phase(val label: String) : Progress()
        data class Package(val name: String, val index: Int, val total: Int) : Progress()
        data class Download(val name: String, val percent: Int) : Progress()
    }

    /** Installs every runtime package (and dependencies) that is missing.
     *  When [roots] is given, only the closure of those components is fetched
     *  (per-component install); the default installs/updates everything.
     *  [force] re-downloads and re-extracts packages that are already
     *  installed - this is how "update" works (Termux packages always carry
     *  the newest upstream build, so re-extracting = updating). */
    suspend fun install(
        context: Context,
        mirror: String,
        roots: List<RuntimePackage>? = null,
        force: Boolean = false,
        onProgress: suspend (Progress) -> Unit,
    ): Result<Map<RuntimePackage, String>> = withContext(Dispatchers.IO) {
        try {
            val arch = termuxArch()
                ?: throw RepoException("unsupported device ABI: ${Build.SUPPORTED_ABIS.joinToString()}")

            val prefix = prefixDir(context)
            prefix.mkdirs()
            migrateLegacyLayout(prefix)
            cacheDir(context).mkdirs()

            val base = mirror.trimEnd('/')
            onProgress(Progress.Phase("index"))
            val index = fetchIndex(base, arch)
            AppLog.i(TAG, "index loaded: ${index.size} packages")

            val rootPackages = roots?.map { it.packageName } ?: RuntimePackage.ALL.map { it.packageName }
            onProgress(Progress.Phase("resolve"))
            val closure = resolveDependencies(index, rootPackages)
            if (closure.isEmpty()) throw RepoException("runtime packages not found in index")

            val alreadyInstalled = readInstalledState(context).packages.keys
            val missing = closure.filter { force || it.name !in alreadyInstalled }
            AppLog.i(TAG, "dependency closure: ${closure.joinToString { it.name }}")
            val total = missing.size
            val manifest = HashMap<String, List<String>>()
            missing.forEachIndexed { i, pkg ->
                onProgress(Progress.Package(pkg.name, i + 1, total))
                val debFile = download(context, base, pkg) { percent ->
                    onProgress(Progress.Download(pkg.name, percent))
                }
                onProgress(Progress.Phase("extract:${pkg.name}"))
                manifest[pkg.name] = extractDeb(debFile, prefix)
                debFile.delete()
            }

            markExecutables(context)

            // Record installed versions + per-package file manifests (used for
            // selective uninstall; shared dependencies are never double-owned).
            val state = readInstalledState(context).packages.toMutableMap()
            for (pkg in closure) {
                val existing = state[pkg.name]
                state[pkg.name] = InstalledPackage(
                    version = pkg.version,
                    files = manifest[pkg.name] ?: existing?.files ?: emptyList(),
                )
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

    /** Processes whose lifecycle belongs to [pkg]. */
    private fun processKeys(context: Context, pkg: RuntimePackage): List<String> = when (pkg) {
        RuntimePackage.LIGHTTPD -> engineSiteKeys(context, Engine.LIGHTTPD)
        RuntimePackage.NGINX -> engineSiteKeys(context, Engine.NGINX)
        RuntimePackage.APACHE -> engineSiteKeys(context, Engine.APACHE)
        RuntimePackage.PHP_FPM -> listOf("php-fpm")
        RuntimePackage.MARIADB -> listOf("maria")
        RuntimePackage.PURE_FTPD -> listOf("ftp")
    }

    private fun engineSiteKeys(context: Context, engine: Engine): List<String> =
        ServerManager.get(context).config.value.sites
            .filter { it.engine == engine }
            .map { "site-${it.id}-${engine.id}" }

    /**
     * Removes a single component: stops its processes, deletes the files it
     * owns (files also claimed by another installed package are kept) and
     * updates the manifest. MariaDB's data directory is preserved.
     *
     * Returns a readable failure reason when the component cannot be removed.
     */
    suspend fun uninstallPackage(context: Context, pkg: RuntimePackage): Result<Unit> = withContext(Dispatchers.IO) {
        val state = readInstalledState(context)
        val entry = state.packages[pkg.packageName]
        if (entry == null) {
            return@withContext Result.failure(IllegalStateException("package ${pkg.packageName} is not installed"))
        }
        // A web engine still referenced by a site must not disappear.
        val engine = when (pkg) {
            RuntimePackage.LIGHTTPD -> Engine.LIGHTTPD
            RuntimePackage.NGINX -> Engine.NGINX
            RuntimePackage.APACHE -> Engine.APACHE
            else -> null
        }
        if (engine != null) {
            val users = ServerManager.get(context).config.value.sites.filter { it.engine == engine }
            if (users.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("${engine.displayName}: ${users.joinToString { it.name }}"),
                )
            }
        }
        if (entry.files.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("no file manifest; reinstall first"))
        }

        // Stop the component's processes before deleting files under them.
        for (key in processKeys(context, pkg)) {
            ProcessController.stop(key)
        }

        // Reverse index: never delete files another installed package owns too.
        val ownedByOthers = state.packages
            .filterKeys { it != pkg.packageName }
            .values
            .flatMap { it.files }
            .toHashSet()
        val prefix = prefixDir(context)
        var removed = 0
        for (rel in entry.files) {
            if (rel in ownedByOthers) continue
            val f = File(prefix, rel)
            if (f.exists() && f.delete()) removed++
        }
        AppLog.i(TAG, "uninstalled ${pkg.packageName}: $removed files removed (${entry.files.size - removed} shared)")

        val newState = state.packages.toMutableMap()
        newState.remove(pkg.packageName)
        writeInstalledState(context, InstalledState(newState))
        Result.success(Unit)
    }

    /** Approximate on-disk size of an installed component (sum of its files). */
    fun packageSize(context: Context, pkg: RuntimePackage): Long {
        val entry = readInstalledState(context).packages[pkg.packageName] ?: return 0L
        val prefix = prefixDir(context)
        return entry.files.sumOf { rel -> runCatching { File(prefix, rel).length() }.getOrDefault(0L) }
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
     * v0.2.0 extracted packages with the full nested Termux path
     * (`prefix/data/data/com.termux/files/usr/...`). Hoists those payloads up
     * to the prefix root so the binaries land where we expect them.
     */
    private fun migrateLegacyLayout(prefix: File) {
        val legacy = File(prefix, "data/data/com.termux/files/usr")
        if (!legacy.isDirectory) return
        AppLog.i(TAG, "migrating legacy nested package layout")
        legacy.listFiles()?.forEach { child ->
            val target = File(prefix, child.name)
            if (!target.exists()) {
                runCatching { child.renameTo(target) }
                    .onFailure { AppLog.w(TAG, "migrate ${child.name} failed: ${it.message}") }
            }
        }
        File(prefix, "data").deleteRecursively()
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

    /** Extracts the `data.tar.*` payload of a .deb into [dest], path-traversal safe.
     *  Returns the relative paths of the regular files written. */
    private fun extractDeb(deb: File, dest: File): List<String> {
        val destCanonical = dest.canonicalFile
        val written = ArrayList<String>()
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
                                    written.add(rel)
                                }
                            }
                        }
                    }
                    return written
                }
            }
        }
        throw RepoException("no data.tar payload in ${deb.name}")
    }
}
