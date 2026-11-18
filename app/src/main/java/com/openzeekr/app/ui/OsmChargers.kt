package com.openzeekr.app.ui

import android.content.Context
import android.location.Geocoder
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import java.util.concurrent.TimeUnit

data class OsmCharger(
    val id: Long,
    val name: String,
    val operator: String,
    val powerKw: String,
    val lat: Double,
    val lng: Double,
)

data class RoutePreview(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

data class WeatherInfo(
    val tempC: Double,
    val emoji: String,
)

object OsmChargers {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchNearby(
        lat: Double,
        lng: Double,
        radiusMeters: Int = 5000,
        ocmKey: String = "",
    ): List<OsmCharger> = coroutineScope {
        val osmDef = async { fetchOverpass(lat, lng, radiusMeters) }
        val ocmDef = async {
            if (ocmKey.isNotBlank()) fetchOpenChargeMap(lat, lng, radiusMeters / 1000, ocmKey.trim())
            else emptyList()
        }
        val ocm = ocmDef.await()
        val osm = osmDef.await()
        if (ocm.isEmpty()) return@coroutineScope osm
        if (osm.isEmpty()) return@coroutineScope ocm

        // Prefer Open Charge Map entries (richer kW/operator metadata), append OSM entries >40m away
        val merged = ocm.toMutableList()
        val dist = FloatArray(1)
        for (item in osm) {
            val dup = merged.any {
                Location.distanceBetween(it.lat, it.lng, item.lat, item.lng, dist)
                dist[0] < 40f
            }
            if (!dup) merged.add(item)
        }
        merged
    }

    private suspend fun fetchOverpass(lat: Double, lng: Double, radiusMeters: Int): List<OsmCharger> =
        withContext(Dispatchers.IO) {
            runCatching {
                val query = "[out:json][timeout:10];node[\"amenity\"=\"charging_station\"](around:$radiusMeters,$lat,$lng);out body 40;"
                val req = Request.Builder()
                    .url("https://overpass-api.de/api/interpreter")
                    .post("data=$query".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .header("User-Agent", "OpenZeekr/1.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching emptyList()
                    val body = resp.body?.string() ?: return@runCatching emptyList()
                    val elements = JSONObject(body).optJSONArray("elements") ?: return@runCatching emptyList()
                    buildList {
                        for (i in 0 until elements.length()) {
                            val el = elements.optJSONObject(i) ?: continue
                            val elat = el.optDouble("lat", Double.NaN)
                            val elng = el.optDouble("lon", Double.NaN)
                            if (elat.isNaN() || elng.isNaN()) continue
                            val tags = el.optJSONObject("tags")
                            val name = tags?.optString("name").orEmpty()
                            val op = tags?.optString("operator").orEmpty()
                            val power = tags?.optString("socket:type2:output")
                                ?.ifBlank { tags.optString("socket:ccs:output") }
                                ?.ifBlank { tags.optString("capacity") }
                                .orEmpty()
                            add(
                                OsmCharger(
                                    id = el.optLong("id"),
                                    name = name.ifBlank { op }.ifBlank { "EV Charger" },
                                    operator = op,
                                    powerKw = power,
                                    lat = elat,
                                    lng = elng,
                                ),
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    private suspend fun fetchOpenChargeMap(
        lat: Double,
        lng: Double,
        distanceKm: Int,
        apiKey: String,
    ): List<OsmCharger> = withContext(Dispatchers.IO) {
        runCatching {
            val km = distanceKm.coerceAtLeast(5)
            val url = "https://api.openchargemap.io/v3/poi/?output=json&latitude=$lat&longitude=$lng&distance=$km&distanceunit=KM&maxresults=40&compact=false&verbose=false"
            val req = Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey)
                .header("User-Agent", "OpenZeekr/1.0")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string() ?: return@runCatching emptyList()
                val arr = JSONArray(body)
                buildList {
                    for (i in 0 until arr.length()) {
                        val poi = arr.optJSONObject(i) ?: continue
                        val addr = poi.optJSONObject("AddressInfo") ?: continue
                        val elat = addr.optDouble("Latitude", Double.NaN)
                        val elng = addr.optDouble("Longitude", Double.NaN)
                        if (elat.isNaN() || elng.isNaN()) continue
                        val title = addr.optString("Title").orEmpty()
                        val op = poi.optJSONObject("OperatorInfo")?.optString("Title").orEmpty()
                        val conns = poi.optJSONArray("Connections")
                        var maxKw = 0.0
                        if (conns != null) {
                            for (j in 0 until conns.length()) {
                                val kw = conns.optJSONObject(j)?.optDouble("PowerKW", 0.0) ?: 0.0
                                if (!kw.isNaN() && kw > maxKw) maxKw = kw
                            }
                        }
                        val kwStr = if (maxKw > 0.0) "${maxKw.toInt()} kW" else ""
                        add(
                            OsmCharger(
                                id = poi.optLong("ID"),
                                name = title.ifBlank { op }.ifBlank { "OCM Charger" },
                                operator = op,
                                powerKw = kwStr,
                                lat = elat,
                                lng = elng,
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Free open-source street routing via OSRM (no API key). */
    suspend fun fetchRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): RoutePreview? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://router.project-osrm.org/route/v1/driving/$fromLng,$fromLat;$toLng,$toLat?overview=full&geometries=geojson"
                val req = Request.Builder().url(url).header("User-Agent", "OpenZeekr/1.0").get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val body = resp.body?.string() ?: return@runCatching null
                    val route = JSONObject(body).optJSONArray("routes")?.optJSONObject(0) ?: return@runCatching null
                    val coords = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@runCatching null
                    val pts = buildList {
                        for (i in 0 until coords.length()) {
                            val c = coords.optJSONArray(i) ?: continue
                            add(LatLng(c.getDouble(1), c.getDouble(0)))
                        }
                    }
                    RoutePreview(
                        points = pts,
                        distanceMeters = route.optDouble("distance", 0.0),
                        durationSeconds = route.optDouble("duration", 0.0),
                    )
                }
            }.getOrNull()
        }

    @Suppress("DEPRECATION")
    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val list = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
                val a = list?.firstOrNull() ?: return@runCatching ""
                listOfNotNull(
                    a.thoroughfare?.let { st -> a.subThoroughfare?.let { "$st, $it" } ?: st },
                    a.subLocality ?: a.locality,
                    a.adminArea,
                ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            }.getOrDefault("")
        }

    /** Free open-source current weather via Open-Meteo (no API key). */
    suspend fun fetchWeather(lat: Double, lng: Double): WeatherInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=temperature_2m,weather_code"
                val req = Request.Builder().url(url).header("User-Agent", "OpenZeekr/1.0").get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val body = resp.body?.string() ?: return@runCatching null
                    val cur = JSONObject(body).optJSONObject("current") ?: return@runCatching null
                    val temp = cur.optDouble("temperature_2m", Double.NaN)
                    if (temp.isNaN()) return@runCatching null
                    val code = cur.optInt("weather_code", -1)
                    val emoji = when (code) {
                        0 -> "☀️"
                        1, 2 -> "⛅"
                        3 -> "☁️"
                        45, 48 -> "🌫️"
                        in 51..67, in 80..82 -> "🌧️"
                        in 71..77, 85, 86 -> "❄️"
                        in 95..99 -> "⛈️"
                        else -> "🌡️"
                    }
                    WeatherInfo(tempC = temp, emoji = emoji)
                }
            }.getOrNull()
        }
}
