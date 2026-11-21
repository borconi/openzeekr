package com.openzeekr.app.net

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * SSRF guard for the exported send-to-car activity, which expands a caller-supplied maps link
 * BEFORE the user confirms. Three layers:
 *  1. [isExpandableMapsLink] / [safeHop]: only https to an allowlist of Google Maps hosts (http is
 *     upgraded, never sent in the clear), checked on the first URL and on every redirect hop.
 *  2. [PublicOnlyDns]: OkHttp resolves each host exactly once through this [Dns] and connects to the
 *     addresses it returns, so there is no gap between "check" and "connect" for DNS rebinding.
 *  3. [isPublicAddress]: pure classifier over the raw address bytes; any non-public address in the
 *     answer rejects the whole host.
 */
object MapsLinkGuard {

    // google.<tld>, google.co.<cc>, google.com.<cc>, optionally under www./maps./consent.
    private val googleHost = Regex("^(?:(?:www|maps|consent)\\.)?google\\.(?:[a-z]{2,3}|co\\.[a-z]{2}|com\\.[a-z]{2})$")

    /** Host may be fetched at all (first link or redirect target). */
    fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "maps.app.goo.gl" || h == "goo.gl" || googleHost.matches(h)
    }

    /** Normalises one hop: http(s) only, upgraded to https, default port, no userinfo, allowlisted
     *  host. Returns null when the URL must not be fetched. */
    fun safeHop(raw: String): HttpUrl? {
        val u = raw.trim().toHttpUrlOrNull() ?: return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        val defaultPort = if (u.isHttps) 443 else 80
        if (u.port != defaultPort) return null
        if (!isAllowedHost(u.host)) return null
        return if (u.isHttps) u else u.newBuilder().scheme("https").port(443).build()
    }

    /** The initial shared link qualifies for expansion: a maps short link, a maps.google.* URL, or a
     *  google.* URL with a /maps path. (Redirect hops only need [safeHop].) */
    fun isExpandableMapsLink(raw: String): Boolean {
        val u = safeHop(raw) ?: return false
        val h = u.host
        val path = u.encodedPath
        return when {
            h == "maps.app.goo.gl" -> true
            h == "goo.gl" -> path.startsWith("/maps")
            h.startsWith("maps.") -> true
            h.startsWith("consent.") -> false
            else -> path == "/maps" || path.startsWith("/maps/")
        }
    }

    /** Resolves once and returns the answer only if EVERY address is public; OkHttp then connects to
     *  exactly these addresses. */
    object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            if (all.isEmpty() || !all.all { isPublicAddress(it) }) {
                throw UnknownHostException("blocked non-public address for $hostname")
            }
            return all
        }
    }

    fun isPublicAddress(a: InetAddress): Boolean = isPublicAddress(a.address)

    /** True only for globally routable unicast addresses. Handles IPv4 and IPv6 (incl. IPv4-mapped,
     *  NAT64 64:ff9b::/96 and 6to4 2002::/16 by classifying the embedded IPv4). */
    fun isPublicAddress(b: ByteArray): Boolean = when (b.size) {
        4 -> isPublicV4(b)
        16 -> isPublicV6(b)
        else -> false
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    private fun isPublicV4(b: ByteArray): Boolean {
        val a0 = u(b, 0); val a1 = u(b, 1); val a2 = u(b, 2)
        return when {
            a0 == 0 -> false                                  // 0.0.0.0/8 "this network"
            a0 == 10 -> false                                 // 10/8 private
            a0 == 100 && a1 in 64..127 -> false               // 100.64/10 CGNAT
            a0 == 127 -> false                                // loopback
            a0 == 169 && a1 == 254 -> false                   // link-local (incl. cloud metadata)
            a0 == 172 && a1 in 16..31 -> false                // 172.16/12 private
            a0 == 192 && a1 == 0 && a2 == 0 -> false          // 192.0.0/24 IETF protocol assignments
            a0 == 192 && a1 == 0 && a2 == 2 -> false          // TEST-NET-1
            a0 == 192 && a1 == 88 && a2 == 99 -> false        // 6to4 relay anycast
            a0 == 192 && a1 == 168 -> false                   // 192.168/16 private
            a0 == 198 && a1 in 18..19 -> false                // 198.18/15 benchmarking
            a0 == 198 && a1 == 51 && a2 == 100 -> false       // TEST-NET-2
            a0 == 203 && a1 == 0 && a2 == 113 -> false        // TEST-NET-3
            a0 >= 224 -> false                                // multicast 224/4, reserved 240/4, broadcast
            else -> true
        }
    }

    private fun isPublicV6(b: ByteArray): Boolean {
        val first10Zero = (0 until 10).all { b[it].toInt() == 0 }
        // ::ffff:a.b.c.d (IPv4-mapped): classify the IPv4.
        if (first10Zero && u(b, 10) == 0xff && u(b, 11) == 0xff) return isPublicV4(b.copyOfRange(12, 16))
        // ::/96 (unspecified, loopback, deprecated IPv4-compatible).
        if (first10Zero && u(b, 10) == 0 && u(b, 11) == 0) return false
        // 64:ff9b::/96 well-known NAT64 prefix: classify the IPv4.
        if (u(b, 0) == 0x00 && u(b, 1) == 0x64 && u(b, 2) == 0xff && u(b, 3) == 0x9b &&
            (4 until 12).all { b[it].toInt() == 0 }) return isPublicV4(b.copyOfRange(12, 16))
        // Everything outside 2000::/3 global unicast (ULA fc00::/7, link-local fe80::/10, site-local
        // fec0::/10, multicast ff00::/8, 64:ff9b:1::/48 local NAT64, 100::/64 discard, ...).
        if ((u(b, 0) and 0xe0) != 0x20) return false
        // 2001::/23 IETF protocol assignments (incl. Teredo 2001::/32, ORCHID).
        if (u(b, 0) == 0x20 && u(b, 1) == 0x01 && u(b, 2) <= 0x01) return false
        // 2001:db8::/32 documentation.
        if (u(b, 0) == 0x20 && u(b, 1) == 0x01 && u(b, 2) == 0x0d && u(b, 3) == 0xb8) return false
        // 2002::/16 6to4: classify the embedded IPv4.
        if (u(b, 0) == 0x20 && u(b, 1) == 0x02) return isPublicV4(b.copyOfRange(2, 6))
        // 3fff::/20 documentation.
        if (u(b, 0) == 0x3f && u(b, 1) == 0xff && (u(b, 2) and 0xf0) == 0) return false
        return true
    }
}
