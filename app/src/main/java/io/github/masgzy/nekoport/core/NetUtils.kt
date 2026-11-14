package io.github.masgzy.nekoport.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.Collections

object NetUtils {

    /** Enumerates active IPv4 addresses (Wi-Fi / hotspot / USB tethering). */
    fun localAddresses(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val nis = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in nis) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        result.add(addr.hostAddress ?: continue)
                    }
                }
            }
        }
        return result.distinct()
    }
}

/**
 * Percent-decoding helpers shared by the HTTP engine.
 */
object UrlCodec {

    /** Decodes percent-escapes; returns null when a stray NUL byte appears. */
    fun decode(raw: String): String? {
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()
            ?: return null
        if (decoded.indexOf('\u0000') >= 0) return null
        return decoded
    }
}
