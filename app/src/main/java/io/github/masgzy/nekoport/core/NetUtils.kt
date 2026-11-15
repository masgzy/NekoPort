package io.github.masgzy.nekoport.core

import java.net.Inet4Address
import java.net.NetworkInterface
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

// The former UrlCodec "percent-decoding helpers" were dead code (zero
// call sites): HTTP is served by the real nginx/Apache/lighttpd binaries,
// which do their own decoding. Removing it so nobody assumes the app
// percent-decodes URLs anywhere (e.g. that %2e%2e%2f is handled here).
