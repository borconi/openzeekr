package com.openzeekr.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MapsLinkGuardTest {

    // getByName on a literal never touches DNS. Note: Java turns ::ffff:a.b.c.d into an
    // Inet4Address, so the mapped cases are also exercised through raw bytes below.
    private fun pub(literal: String) = MapsLinkGuard.isPublicAddress(InetAddress.getByName(literal))

    private fun v6(vararg bytes: Int) = ByteArray(16).also { b -> bytes.forEachIndexed { i, v -> b[i] = v.toByte() } }

    @Test fun publicV4() {
        listOf("8.8.8.8", "142.250.72.14", "1.1.1.1", "100.63.255.255", "100.128.0.0",
            "172.15.255.255", "172.32.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255")
            .forEach { assertTrue(it, pub(it)) }
    }

    @Test fun nonPublicV4() {
        listOf("0.0.0.0", "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.255", "127.0.0.1",
            "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.1", "192.0.2.1",
            "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.19.255.255", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255")
            .forEach { assertFalse(it, pub(it)) }
    }

    @Test fun publicV6() {
        listOf("2001:4860:4860::8888", "2a00:1450:4001:80b::200e", "2607:f8b0:4004:c07::64")
            .forEach { assertTrue(it, pub(it)) }
    }

    @Test fun nonPublicV6() {
        listOf("::", "::1", "fc00::1", "fd12:3456:789a::1", "fe80::1", "fec0::1", "ff02::1",
            "2001:db8::1", "2001::1", "2001:10::1", "100::1", "64:ff9b:1::1", "3fff::1",
            "2002:0a00:0001::1", "2002:c0a8:0101::1", "64:ff9b::a9fe:a9fe", "64:ff9b::7f00:1",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1")
            .forEach { assertFalse(it, pub(it)) }
    }

    @Test fun embeddedV4InV6() {
        // NAT64 and 6to4 of a public IPv4 stay usable (IPv6-only carriers).
        assertTrue(pub("64:ff9b::808:808"))
        assertTrue(pub("2002:0808:0808::1"))
        // Raw IPv4-mapped bytes (InetAddress would normalise these to IPv4).
        assertFalse(MapsLinkGuard.isPublicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 169, 254, 169, 254)))
        assertFalse(MapsLinkGuard.isPublicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 192, 168, 0, 1)))
        assertTrue(MapsLinkGuard.isPublicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 8, 8, 8, 8)))
        // IPv4-compatible ::a.b.c.d is deprecated and always rejected.
        assertFalse(MapsLinkGuard.isPublicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)))
    }

    @Test fun badLength() {
        assertFalse(MapsLinkGuard.isPublicAddress(ByteArray(0)))
        assertFalse(MapsLinkGuard.isPublicAddress(ByteArray(5)))
    }

    @Test fun expandableLinks() {
        listOf(
            "https://maps.app.goo.gl/AbCdEf123",
            "https://goo.gl/maps/AbCdEf123",
            "https://maps.google.com/?q=Berlin",
            "https://maps.google.co.uk/maps?q=x",
            "https://www.google.com/maps/place/Somewhere/@52.5,13.4,17z",
            "https://google.de/maps?q=x",
            "https://www.google.com.au/maps",
            "http://maps.app.goo.gl/AbCdEf123",
        ).forEach { assertTrue(it, MapsLinkGuard.isExpandableMapsLink(it)) }
    }

    @Test fun notExpandableLinks() {
        listOf(
            "https://evil.example/maps/x",
            "https://maps.evil.example/x",
            "http://169.254.169.254/maps",
            "https://127.0.0.1/maps",
            "https://[::1]/maps",
            "https://www.google.com/search?q=x",
            "https://www.google.com/mapsfoo",
            "https://goo.gl/xyz",
            "https://consent.google.com/ml?continue=x",
            "https://google.com.evil.example/maps",
            "https://evilgoogle.com/maps",
            "https://maps.app.goo.gl@evil.example/x",
            "https://user:pw@maps.app.goo.gl/x",
            "https://maps.app.goo.gl:8443/x",
            "ftp://maps.app.goo.gl/x",
            "file:///etc/hosts",
            "not a url",
        ).forEach { assertFalse(it, MapsLinkGuard.isExpandableMapsLink(it)) }
    }

    @Test fun redirectHops() {
        // Redirects may go to any allowlisted Google host, incl. the EU consent page.
        assertEquals("https://consent.google.com/ml?continue=x",
            MapsLinkGuard.safeHop("https://consent.google.com/ml?continue=x").toString())
        assertEquals("https://www.google.com/search?q=x",
            MapsLinkGuard.safeHop("https://www.google.com/search?q=x").toString())
        // Plain http is upgraded, never sent in the clear.
        assertEquals("https://maps.google.com/?q=1", MapsLinkGuard.safeHop("http://maps.google.com/?q=1").toString())
        assertNull(MapsLinkGuard.safeHop("https://evil.example/"))
        assertNull(MapsLinkGuard.safeHop("http://10.0.0.1/"))
        assertNull(MapsLinkGuard.safeHop("http://maps.google.com:8080/"))
    }
}
