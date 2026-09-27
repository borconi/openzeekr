package com.openzeekr.app

import android.app.Activity
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Registers OpenZeekr as a navigation / location target. When the user picks OpenZeekr from a
 * map pin's share sheet, a `geo:` link, or a "navigate with" chooser, we parse the destination
 * and push it to the car's built-in nav (cloud [com.openzeekr.app.remote.NavRepository.sendToCar]).
 *
 * No UI: it parses, fires the (application-scoped) send so it survives this Activity finishing,
 * shows a toast, and closes immediately. Coordinates are raw WGS-84 — the server converts for the
 * car, so we never shift to GCJ02 here.
 */
class SendToCarActivity : Activity() {

    private data class Destination(val lat: Double, val lon: Double, val name: String, val address: String, val city: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deps = (application as App).deps

        // Parse first, then ALWAYS ask for confirmation before pushing anything to the car — this
        // activity is exported, so without a prompt any app could fire a POI at the car silently.
        deps.appScope.launch {
            val dest = withContext(Dispatchers.IO) { parseDestination(intent) }
            withContext(Dispatchers.Main) {
                if (dest == null) {
                    val preview = (intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: "").take(48)
                    Toast.makeText(
                        applicationContext,
                        if (preview.isBlank()) "Couldn't read a location to send" else "Couldn't read location from: $preview",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish(); return@withContext
                }
                if (isFinishing || isDestroyed) return@withContext
                android.app.AlertDialog.Builder(this@SendToCarActivity)
                    .setTitle("Send to your Zeekr?")
                    .setMessage("Send “${dest.name}” to the car's navigation?")
                    .setPositiveButton("Send") { _, _ ->
                        deps.appScope.launch {
                            when (val r = deps.nav.sendToCar(dest.lat, dest.lon, dest.name, dest.address, dest.city)) {
                                is CallResult.Ok -> toast("Sent to Zeekr ✓")
                                is CallResult.Err -> toast("Send failed: ${r.message}")
                            }
                        }
                        finish()
                    }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    private suspend fun toast(msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    /** Best-effort: pull WGS-84 lat/lon (+ optional label) out of whatever the picker handed us. */
    private fun parseDestination(intent: Intent): Destination? {
        // Candidate strings from the URI (geo:/google.navigation:) and/or the shared text (Maps
        // shares a place as multi-line text, often just a short link). Split text into lines/tokens
        // too, so a bare address line or an embedded URL can be picked out individually.
        // Gather every text blob the picker might carry: the URI (geo:/google.navigation:/https),
        // the shared EXTRA_TEXT, the EXTRA_SUBJECT (often the place name), and ClipData items — some
        // Maps/share flows put the payload in ClipData, not EXTRA_TEXT.
        val blobs = buildList {
            intent.data?.let { uri ->
                uri.getQueryParameter("q")?.let { add(it) }
                uri.getQueryParameter("destination")?.let { add(it) }
                add(uri.schemeSpecificPart ?: uri.toString())
            }
            intent.dataString?.let { add(it) }
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { add(it) }
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { add(it) }
            intent.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) cd.getItemAt(i)?.coerceToText(this@SendToCarActivity)?.toString()?.let { add(it) }
            }
        }
        Logx.d(
            "sendToCar",
            "recv action=${intent.action} type=${intent.type} " +
                "data=${intent.dataString?.take(120)} text=${intent.getStringExtra(Intent.EXTRA_TEXT)?.take(200)}"
        )
        // Explode each blob into whole lines (place-name line survives for labelling) + whitespace
        // tokens (an embedded URL gets isolated for expansion).
        val candidates = buildList {
            blobs.forEach { blob ->
                if (blob.isBlank()) return@forEach
                add(blob.trim())
                blob.split('\n').forEach { l -> if (l.isNotBlank()) add(l.trim()) }
                blob.split('\n', ' ', '\t').forEach { t -> if (t.isNotBlank()) add(t.trim()) }
            }
        }.distinct()

        // 1) Coordinates already present (geo:lat,lng, ?q=lat,lng, or @lat,lng inside a full maps URL).
        coordsIn(candidates)?.let { (lat, lon) -> return Destination(lat, lon, label(candidates), "", "") }

        // 2) A Google Maps link — usually a SHORT maps.app.goo.gl with no coords in the text. Follow
        //    its redirects and scan the resolved URL(s)/body for the place coordinates.
        candidates.firstOrNull {
            it.startsWith("http") && ("goo.gl" in it || "google." in it || "maps." in it || "/maps" in it)
        }?.let { link ->
            val expanded = expandUrl(link)
            Logx.d("sendToCar", "expanded ${link.take(90)} -> ${expanded.length} chars")
            coordsInText(expanded)?.let { (lat, lon) -> return Destination(lat, lon, label(candidates), "", "") }
        }

        // 3) Fall back to geocoding a plain address line (skip URLs and coordinate strings).
        val addr = candidates.firstOrNull { it.isNotBlank() && !it.startsWith("http") && !hasCoord(it) && it.length > 3 }
        if (addr != null && Geocoder.isPresent()) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(this).getFromLocationName(addr, 1)?.firstOrNull()
            }.getOrNull()?.let { a ->
                return Destination(
                    a.latitude, a.longitude, a.featureName ?: a.thoroughfare ?: addr,
                    a.getAddressLine(0) ?: addr, a.locality ?: a.subAdminArea ?: "",
                )
            }
        }
        Logx.w("sendToCar", "no location parsed; candidates=${candidates.take(8)}")
        return null
    }

    private fun coordsIn(candidates: List<String>): Pair<Double, Double>? {
        for (c in candidates) coordsInText(c)?.let { return it }
        return null
    }

    /** Scan [text] for a valid lat,lon — maps-specific markers first, then a generic decimal pair.
     *  Also tries a URL-decoded copy, since an EU consent redirect percent-encodes the real maps
     *  URL (with its `@lat,lng` / `!3d!4d`) inside a `continue=` param. */
    private fun coordsInText(text: String): Pair<Double, Double>? {
        scanCoords(text)?.let { return it }
        val decoded = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrNull()
        if (decoded != null && decoded != text) scanCoords(decoded)?.let { return it }
        return null
    }

    private fun scanCoords(text: String): Pair<Double, Double>? {
        for (rx in coordPatterns) {
            for (m in rx.findAll(text)) {
                val lat = m.groupValues[1].toDoubleOrNull()
                val lon = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0))
                    return lat to lon
            }
        }
        return null
    }

    private fun hasCoord(s: String) = coordsInText(s) != null

    /** Best label: a `(Label)` parenthetical, else the first line that isn't a URL or bare coords. */
    private fun label(candidates: List<String>): String {
        candidates.forEach { c ->
            Regex("\\(([^)]+)\\)").find(c)?.groupValues?.getOrNull(1)?.let {
                val d = Uri.decode(it); if (d.isNotBlank() && !hasCoord(d)) return d
            }
        }
        return candidates.firstOrNull { it.isNotBlank() && !it.startsWith("http") && !hasCoord(it) && it.length in 2..80 }
            ?: "Destination"
    }

    /** Follow a (short) maps link's redirects and return the URL chain + a slice of the final body,
     *  so [coordsInText] can find the place coordinates that the short link hides. */
    private fun expandUrl(start: String): String {
        val out = StringBuilder(start).append('\n')
        var url = start
        runCatching {
            var hop = 0
            while (hop < 6) {
                hop++
                // SSRF guard: this activity is exported and expands a caller-supplied URL BEFORE the
                // user confirms, so only follow http/https to PUBLIC hosts - never a private, loopback,
                // link-local or cloud-metadata target, checked on the initial URL and every redirect.
                if (!isSafeHttpUrl(url)) { out.append("[blocked: non-public URL]\n"); break }
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 6000; readTimeout = 6000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
                }
                val code = conn.responseCode
                out.append(conn.url).append('\n')
                val loc = conn.getHeaderField("Location")
                if (code in 300..399 && loc != null) {
                    out.append(loc).append('\n')
                    url = if (loc.startsWith("http")) loc else java.net.URL(java.net.URL(url), loc).toString()
                    conn.disconnect()
                } else {
                    runCatching { conn.inputStream.bufferedReader().use { it.readText() } }
                        .getOrNull()?.let { out.append(it.take(40000)) }
                    conn.disconnect()
                    break
                }
            }
        }
        return out.toString()
    }

    /** True only for an http/https URL whose host resolves entirely to PUBLIC addresses - blocks the
     *  SSRF vectors (localhost, 127/10/172.16-31/192.168 private ranges, 169.254 link-local incl. the
     *  cloud metadata IP, IPv6 loopback/ULA, multicast, wildcard). Any resolve failure = not safe. */
    private fun isSafeHttpUrl(raw: String): Boolean = runCatching {
        val u = java.net.URL(raw)
        val scheme = u.protocol?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = u.host?.trim('[', ']') ?: return false
        if (host.isBlank() || host.equals("localhost", ignoreCase = true)) return false
        java.net.InetAddress.getAllByName(host).isNotEmpty() &&
            java.net.InetAddress.getAllByName(host).all { a ->
                !a.isLoopbackAddress && !a.isSiteLocalAddress && !a.isLinkLocalAddress &&
                    !a.isAnyLocalAddress && !a.isMulticastAddress && a.hostAddress != "169.254.169.254"
            }
    }.getOrDefault(false)

    private companion object {
        // Ordered: the maps "data"/`@`/query markers point at the PLACE; the generic pair is a last
        // resort (≥2 decimals, so it ignores zoom levels / version-like numbers).
        private val coordPatterns = listOf(
            Regex("[!/]3d(-?\\d{1,3}\\.\\d+)[!/]4d(-?\\d{1,3}\\.\\d+)"),
            Regex("@(-?\\d{1,3}\\.\\d+),(-?\\d{1,3}\\.\\d+)"),
            Regex("[?&](?:q|ll|sll|daddr|destination|center)=(-?\\d{1,3}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)"),
            Regex("(-?\\d{1,3}\\.\\d{2,}),\\s*(-?\\d{1,3}\\.\\d{2,})"),
        )
    }
}
