package io.github.masgzy.nekoport.ftp

import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.FtpConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * One FTP control connection. Implements a safe subset of RFC 959 with
 * passive transfers only (friendlier to mobile networks and firewalls).
 */
class FtpSession(
    private val socket: Socket,
    private val config: FtpConfig,
    private val root: File,
    private val pasvPortStart: Int,
    private val pasvCounter: AtomicInteger,
) {
    private val reader = BufferedInputStream(socket.getInputStream(), 8192)
    private val writer = PrintWriter(BufferedOutputStream(socket.getOutputStream(), 8192), true)

    private var dataServer: ServerSocket? = null
    private var cwd: File = root.canonicalFile
    private var renameFrom: File? = null
    private var binaryMode = true
    private var authenticated = false
    private var pendingUser: String? = null

    fun run() {
        socket.soTimeout = 120_000
        try {
            reply(220, "NekoPort FTP ready")
            while (true) {
                val line = readLine() ?: break
                if (line.isEmpty()) continue
                AppLog.access("FTP", "${socket.inetAddress?.hostAddress}: $line")
                val space = line.indexOf(' ')
                val cmd = (if (space < 0) line else line.substring(0, space)).uppercase(Locale.US)
                val arg = if (space < 0) "" else line.substring(space + 1).trim()
                if (!dispatch(cmd, arg)) break
            }
        } catch (_: IOException) {
            // client went away
        } finally {
            runCatching { dataServer?.close() }
            runCatching { socket.close() }
        }
    }

    private fun readLine(): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = reader.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > 4096) return null
        }
    }

    private fun reply(code: Int, text: String) {
        writer.println("$code $text")
        writer.flush()
    }

    /** Returns false when the session should end. */
    private fun dispatch(cmd: String, arg: String): Boolean {
        // Commands allowed before authentication.
        if (!authenticated && cmd !in setOf("USER", "PASS", "QUIT", "FEAT", "SYST", "NOOP", "HELP", "OPTS")) {
            reply(530, "Please login with USER and PASS")
            return true
        }
        when (cmd) {
            "USER" -> {
                pendingUser = arg
                reply(331, "Password required")
            }
            "PASS" -> {
                authenticated = pendingUser == config.username && arg == config.password
                pendingUser = null
                if (authenticated) reply(230, "Login successful") else reply(530, "Login incorrect")
            }
            "QUIT" -> {
                reply(221, "Goodbye")
                return false
            }
            "SYST" -> reply(215, "UNIX Type: L8")
            "FEAT" -> {
                reply(211, "Features:")
                writer.println(" UTF8")
                writer.println(" SIZE")
                writer.println(" MDTM")
                writer.println(" REST STREAM")
                reply(211, "End")
            }
            "OPTS" -> reply(200, "OK")
            "NOOP" -> reply(200, "NOOP ok")
            "HELP" -> reply(214, "Commands: USER PASS QUIT SYST FEAT OPTS NOOP PWD CWD CDUP TYPE PASV LIST NLST RETR STOR DELE MKD RMD RNFR RNTO SIZE MDTM")
            "PWD" -> reply(257, "\"${relativePath(cwd)}\" is the current directory")
            "CWD" -> {
                val target = resolve(arg)
                if (target?.isDirectory == true) {
                    cwd = target
                    reply(250, "Directory changed")
                } else reply(550, "Directory not found")
            }
            "CDUP" -> {
                if (cwd != root.canonicalFile) {
                    cwd = cwd.parentFile?.canonicalFile ?: cwd
                    if (!cwd.path.startsWith(root.canonicalFile.path)) cwd = root.canonicalFile
                }
                reply(250, "OK")
            }
            "TYPE" -> {
                binaryMode = arg.uppercase(Locale.US).startsWith("I")
                reply(200, "Type set to ${if (binaryMode) "binary" else "ascii"}")
            }
            "PASV" -> {
                runCatching { dataServer?.close() }
                val ss = ServerSocket()
                ss.reuseAddress = true
                val tries = 16
                var bound = false
                for (i in 0 until tries) {
                    try {
                        ss.bind(InetSocketAddress(pasvPortStart + (pasvCounter.getAndIncrement() % 32)))
                        bound = true
                        break
                    } catch (_: IOException) {
                    }
                }
                if (!bound) {
                    reply(425, "Cannot open passive port")
                    return true
                }
                dataServer = ss
                val ip = FtpServer.localIpForPasv() ?: socket.getLocalAddress()
                val addr = ip.address
                val port = ss.localPort
                reply(227, "Entering Passive Mode (${addr[0].toInt() and 0xFF},${addr[1].toInt() and 0xFF},${addr[2].toInt() and 0xFF},${addr[3].toInt() and 0xFF},${port shr 8},${port and 0xFF})")
            }
            "LIST", "NLST" -> {
                val ds = acceptData() ?: run {
                    reply(425, "No data connection")
                    return true
                }
                try {
                    val dir = resolve(arg)?.takeIf { it.isDirectory } ?: cwd
                    val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                    ds.getOutputStream().use { out ->
                        val pw = PrintWriter(BufferedOutputStream(out), true)
                        for (f in files) {
                            if (cmd == "NLST") {
                                pw.println(f.name)
                            } else {
                                val perms = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                                val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US).apply {
                                    timeZone = TimeZone.getDefault()
                                }
                                pw.println("$perms 1 neko neko ${f.length()} ${fmt.format(Date(f.lastModified()))} ${f.name}")
                            }
                        }
                        pw.flush()
                    }
                    reply(226, "Transfer complete")
                } catch (_: Exception) {
                    reply(426, "Transfer aborted")
                } finally {
                    runCatching { ds.close() }
                }
            }
            "RETR" -> {
                val f = resolve(arg)
                if (f?.isFile != true) {
                    reply(550, "File not found")
                    return true
                }
                val ds = acceptData() ?: run {
                    reply(425, "No data connection")
                    return true
                }
                try {
                    ds.getOutputStream().use { out ->
                        FileInputStream(f).use { input ->
                            if (binaryMode) input.copyTo(out) else copyAscii(input, out)
                        }
                    }
                    reply(226, "Transfer complete")
                } catch (_: Exception) {
                    reply(426, "Transfer aborted")
                } finally {
                    runCatching { ds.close() }
                }
            }
            "STOR" -> {
                val f = resolve(arg)
                if (f == null || !canWriteTarget(f)) {
                    reply(550, "Permission denied")
                    return true
                }
                val ds = acceptData() ?: run {
                    reply(425, "No data connection")
                    return true
                }
                try {
                    ds.getInputStream().use { input ->
                        FileOutputStream(f).use { out ->
                            if (binaryMode) input.copyTo(out) else copyAscii(input, out)
                        }
                    }
                    reply(226, "Transfer complete")
                } catch (_: Exception) {
                    reply(426, "Transfer aborted")
                } finally {
                    runCatching { ds.close() }
                }
            }
            "DELE" -> {
                val f = resolve(arg)
                if (f?.isFile == true && f.delete()) reply(250, "File deleted") else reply(550, "Delete failed")
            }
            "MKD" -> {
                val f = resolve(arg)
                if (f != null && (f.mkdirs() || f.isDirectory)) reply(257, "\"${relativePath(f)}\" created") else reply(550, "Create failed")
            }
            "RMD" -> {
                val f = resolve(arg)
                if (f != null && f.isDirectory && f.list()?.isEmpty() == true && f.delete()) reply(250, "Removed") else reply(550, "Remove failed (dir must be empty)")
            }
            "RNFR" -> {
                val f = resolve(arg)
                if (f?.exists() == true) {
                    renameFrom = f
                    reply(350, "Ready for RNTO")
                } else reply(550, "File not found")
            }
            "RNTO" -> {
                val from = renameFrom
                val to = resolve(arg)
                if (from != null && to != null && canWriteTarget(to) && from.renameTo(to)) {
                    reply(250, "Rename successful")
                } else reply(550, "Rename failed")
                renameFrom = null
            }
            "SIZE" -> {
                val f = resolve(arg)
                if (f?.isFile == true) reply(213, f.length().toString()) else reply(550, "Not found")
            }
            "MDTM" -> {
                val f = resolve(arg)
                if (f?.isFile == true) {
                    val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                    reply(213, fmt.format(Date(f.lastModified())))
                } else reply(550, "Not found")
            }
            else -> reply(502, "Command not implemented")
        }
        return true
    }

    private fun copyAscii(input: InputStream, out: OutputStream) {
        val br = input.bufferedReader()
        val bw = out.bufferedWriter()
        br.forEachLine { line ->
            bw.write(line)
            bw.write("\r\n")
        }
        bw.flush()
    }

    private fun acceptData(): Socket? {
        val ss = dataServer ?: return null
        dataServer = null
        return runCatching {
            ss.soTimeout = 15000
            val s = ss.accept()
            ss.close()
            s
        }.getOrElse { runCatching { ss.close() }; null }
    }

    private fun canWriteTarget(f: File): Boolean {
        val rootCanonical = root.canonicalFile
        return f.canonicalPath.startsWith(rootCanonical.path + File.separator)
    }

    /** Resolves an FTP path argument inside the jail. Null when escaping the root. */
    private fun resolve(arg: String): File? {
        val clean = arg.trim()
        if (clean.isEmpty()) return cwd
        val base = if (clean.startsWith("/")) root.canonicalFile else cwd
        var file = base
        for (seg in clean.split('/', '\\')) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    file = file.parentFile ?: return if (base == root.canonicalFile) root.canonicalFile else null
                    if (!file.canonicalPath.startsWith(root.canonicalFile.path)) file = root.canonicalFile
                }
                else -> file = File(file, seg)
            }
        }
        val canonical = file.canonicalFile
        if (canonical != root.canonicalFile && !canonical.path.startsWith(root.canonicalFile.path + File.separator)) {
            return null
        }
        return canonical
    }

    private fun relativePath(f: File): String {
        val rootPath = root.canonicalFile.path
        val p = f.canonicalPath
        return if (p == rootPath) "/" else "/" + p.removePrefix(rootPath + File.separator)
    }

    companion object {
        private const val TAG = "FtpServer"
    }
}
