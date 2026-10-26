package com.connor.nearestplane.wx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Locale

/** An airport resolved from a code, used to pin a fixed location. */
data class AirportFix(val id: String, val name: String?, val lat: Double, val lon: Double)

/**
 * NOAA's Aviation Weather Center data API. Free, no key, no rate limit
 * published — but they ask that clients keep the load light, which is why
 * this refreshes on a 30-minute cadence. METARs only update hourly anyway.
 */
object AviationWeatherClient {

    private const val BASE = "https://aviationweather.gov/api/data"
    private const val TIMEOUT_MS = 12_000

    /**
     * Finds the closest reporting station by pulling every METAR in a box
     * around you, then sorting by great-circle distance.
     *
     * [boxDeg] of 0.75 is a 45 nm half-width — wide enough to catch a station
     * almost anywhere in the US, narrow enough that the response stays small.
     */
    suspend fun nearestMetar(
        lat: Double,
        lon: Double,
        boxDeg: Double = 0.75,
        preferForecast: Boolean = false
    ): Metar? = withContext(Dispatchers.IO) {
        val boxes = boxesAround(lat, lon, boxDeg)
        val reports = boxes
            .flatMap { bbox ->
                val arr = JSONArray(get("$BASE/metar?bbox=$bbox&format=json&hours=3"))
                (0 until arr.length()).mapNotNull { parseMetar(arr.getJSONObject(it), lat, lon) }
            }
            // Several hours of reports come back — keep the newest per station.
            .groupBy { it.stationId }
            .mapNotNull { (_, reports) -> reports.maxByOrNull { it.observedEpoch ?: 0L } }
        // Asked only when wanted, and a failure here costs the preference, not the tile.
        val forecasting = if (preferForecast) runCatching { tafStations(boxes) }.getOrNull() else null
        chooseStation(reports, forecasting)
    }

    /**
     * The nearest report — or, given the stations that issue a TAF, the nearest
     * of those. When none in range does, the nearest of all: a tile with today's
     * weather and no forecast beats a tile with nothing.
     */
    internal fun chooseStation(reports: List<Metar>, forecasting: Set<String>?): Metar? {
        val byDistance = reports.sortedBy { it.distanceNm ?: Double.MAX_VALUE }
        if (forecasting != null) byDistance.firstOrNull { it.stationId in forecasting }?.let { return it }
        return byDistance.firstOrNull()
    }

    /** Stations in these boxes that currently issue a TAF. One request per box, like the METARs. */
    private fun tafStations(boxes: List<String>): Set<String> = boxes.flatMap { bbox ->
        val body = get("$BASE/taf?bbox=$bbox&format=json")
        if (body.isBlank()) emptyList() else {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("icaoId", "").ifBlank { null } }
        }
    }.toSet()

    /**
     * The API wants minLat,minLon,maxLat,maxLon. Three things make that more
     * than subtraction:
     *
     *  - **A degree of longitude shrinks as you go north.** At 45°N, 0.75° of
     *    longitude is 32 nm, not 45; in Fairbanks it's 22; in Utqiagvik it's
     *    15. The box has to widen to stay square in nautical miles, which is
     *    what "45 nm" was supposed to mean. Without this the search quietly
     *    narrows the further from the equator you are — exactly where stations
     *    are sparsest and you most need the reach.
     *  - **Latitude has to stop at the poles.**
     *  - **Longitude wraps.** A box crossing the antimeridian is two boxes.
     *    Sent unwrapped, minLon > maxLon asks for the whole planet the long
     *    way round, and the response is either empty or enormous.
     */
    private fun boxesAround(lat: Double, lon: Double, boxDeg: Double): List<String> {
        val minLat = (lat - boxDeg).coerceAtLeast(-90.0)
        val maxLat = (lat + boxDeg).coerceAtMost(90.0)

        // 1/cos runs away at the poles, so the widening is capped at ~6.7x.
        val lonHalf = boxDeg / cos(Math.toRadians(lat)).coerceAtLeast(0.15)
        if (lonHalf >= 180.0) return listOf(box(minLat, -180.0, maxLat, 180.0))

        val minLon = lon - lonHalf
        val maxLon = lon + lonHalf
        return when {
            minLon < -180.0 -> listOf(
                box(minLat, minLon + 360.0, maxLat, 180.0),
                box(minLat, -180.0, maxLat, maxLon)
            )
            maxLon > 180.0 -> listOf(
                box(minLat, minLon, maxLat, 180.0),
                box(minLat, -180.0, maxLat, maxLon - 360.0)
            )
            else -> listOf(box(minLat, minLon, maxLat, maxLon))
        }
    }

    private fun box(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String =
        "%.3f,%.3f,%.3f,%.3f".format(Locale.US, minLat, minLon, maxLat, maxLon)

    /**
     * Resolves an airport code to a position, for pinning a fixed location.
     * Accepts ICAO, IATA or FAA identifiers and works outside the US — EGLL and
     * YSSY both resolve — because it's the same station database the METARs
     * come from, not an FAA-only list.
     */
    suspend fun airport(code: String): AirportFix? = withContext(Dispatchers.IO) {
        val clean = code.trim().uppercase().filter { it.isLetterOrDigit() }
        if (clean.isEmpty()) return@withContext null

        // An unknown code is answered with 204 and an empty body, not an error,
        // so a blank response here means "no such airport" rather than trouble.
        val body = get("$BASE/airport?ids=$clean&format=json")
        if (body.isBlank()) return@withContext null

        val arr = JSONArray(body)
        if (arr.length() == 0) return@withContext null
        val o = arr.getJSONObject(0)
        val lat = o.optDoubleOrNull("lat") ?: return@withContext null
        val lon = o.optDoubleOrNull("lon") ?: return@withContext null
        AirportFix(
            id = o.optString("icaoId", "").ifBlank { clean },
            name = o.optString("name", "").trim().ifBlank { null },
            lat = lat,
            lon = lon
        )
    }

    /**
     * The latest report for each of [stationIds] (ICAO codes), in one request.
     * Used for the weather at both ends of a flight on the Overhead widget.
     */
    suspend fun metars(stationIds: List<String>): Map<String, Metar> = withContext(Dispatchers.IO) {
        val ids = stationIds.map { it.trim().uppercase() }.filter { it.matches(Regex("[A-Z0-9]{3,4}")) }.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()
        val arr = JSONArray(get("$BASE/metar?ids=${ids.joinToString(",")}&format=json"))
        (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .mapNotNull { parseMetar(it, 0.0, 0.0) }
            .groupBy { it.stationId.uppercase() }
            .mapValues { (_, reports) -> reports.maxByOrNull { it.observedEpoch ?: 0L }!! }
    }

    suspend fun taf(stationId: String): Taf? = withContext(Dispatchers.IO) {
        val body = get("$BASE/taf?ids=$stationId&format=json")
        val arr = JSONArray(body)
        if (arr.length() == 0) return@withContext null
        parseTaf(arr.getJSONObject(0))
    }

    // ---- HTTP ----

    private fun get(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "NearestPlaneWidget/1.0")
            setRequestProperty("Accept", "application/json")
            try {
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Aviation Weather API returned HTTP $responseCode")
                }
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }

    // ---- parsing ----

    private fun parseMetar(o: JSONObject, fromLat: Double, fromLon: Double): Metar? {
        val id = o.optString("icaoId", "").ifBlank { return null }
        val raw = o.optString("rawOb", "").ifBlank { return null }
        val sLat = o.optDoubleOrNull("lat")
        val sLon = o.optDoubleOrNull("lon")

        return Metar(
            stationId = id,
            siteName = o.optString("name", "").ifBlank { null },
            lat = sLat,
            lon = sLon,
            distanceNm = if (sLat != null && sLon != null)
                haversineNm(fromLat, fromLon, sLat, sLon) else null,
            observedEpoch = o.optLongOrNull("obsTime"),
            raw = raw,
            tempC = o.optDoubleOrNull("temp"),
            dewpointC = o.optDoubleOrNull("dewp"),
            windDirDeg = o.optWindDir(),
            windKts = o.optIntOrNull("wspd"),
            gustKts = o.optIntOrNull("wgst"),
            visibilitySm = o.optVisibility(),
            altimeterInHg = o.optDoubleOrNull("altim")?.let { mbToInHg(it) },
            weather = o.optString("wxString", "").ifBlank { null },
            clouds = parseClouds(o.optJSONArray("clouds"))
        )
    }

    private fun parseTaf(o: JSONObject): Taf {
        val fcsts = o.optJSONArray("fcsts")
        val periods = (0 until (fcsts?.length() ?: 0)).map { i ->
            val f = fcsts!!.getJSONObject(i)
            TafPeriod(
                fromEpoch = f.optLongOrNull("timeFrom"),
                toEpoch = f.optLongOrNull("timeTo"),
                changeType = f.optString("fcstChange", "").ifBlank { null },
                windDirDeg = f.optWindDir(),
                windKts = f.optIntOrNull("wspd"),
                gustKts = f.optIntOrNull("wgst"),
                visibilitySm = f.optVisibility(),
                weather = f.optString("wxString", "").ifBlank { null },
                clouds = parseClouds(f.optJSONArray("clouds"))
            )
        }
        return Taf(
            stationId = o.optString("icaoId", "?"),
            raw = o.optString("rawTAF", ""),
            issuedEpoch = o.optLongOrNull("issueTime"),
            periods = periods
        )
    }

    private fun parseClouds(arr: JSONArray?): List<Cloud> =
        (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val c = arr!!.getJSONObject(i)
            val cover = c.optString("cover", "").ifBlank { return@mapNotNull null }
            Cloud(cover, c.optIntOrNull("base"))
        }

    // ---- field quirks ----

    /** "VRB" for variable wind arrives where an int normally would. */
    private fun JSONObject.optWindDir(): Int? {
        val v = opt("wdir") ?: return null
        return (v as? Number)?.toInt()
    }

    /** visib is a number, or a string like "10+" or "1 1/2". */
    private fun JSONObject.optVisibility(): Double? {
        val v = opt("visib") ?: return null
        (v as? Number)?.let { return it.toDouble() }
        val s = v.toString().trim().removeSuffix("+")
        s.toDoubleOrNull()?.let { return it }
        // Fractional forms: "1 1/2" or "1/2"
        val parts = s.split(" ")
        return try {
            parts.sumOf { part ->
                if (part.contains("/")) {
                    val (n, d) = part.split("/")
                    n.toDouble() / d.toDouble()
                } else part.toDouble()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) (opt(key) as? Number)?.toDouble() else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) (opt(key) as? Number)?.toInt() else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) (opt(key) as? Number)?.toLong() else null

    private fun mbToInHg(mb: Double): Double = mb * 0.0295299830714

    private fun haversineNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3440.065 // earth radius in nautical miles
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
