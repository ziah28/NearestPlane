package com.connor.nearestplane

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fetches live aircraft from a community ADS-B aggregator.
 *
 * If you later point this at your own Pi, dump1090's /data/aircraft.json is
 * close but has no `dst` field — you'd compute distance yourself with the
 * haversine in AviationWeatherClient.
 */
/**
 * A receiver of your own: sdr_windows, or anything serving dump1090's
 * aircraft.json. [url] is the full address of that file.
 */
data class Station(val url: String, val token: String)

/**
 * What a person types, as the address of an aircraft.json — or null when it
 * cannot be one. HTTPS only: Android refuses plain HTTP by default, and
 * Tailscale Serve gives a station a real certificate for exactly this.
 */
fun normalizeStationUrl(input: String): String? {
    val raw = input.trim().let { if ("://" in it) it else "https://$it" }
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    val path = uri.path.orEmpty().trimEnd('/')
    val file = if (path.endsWith(".json")) path else "$path/data/aircraft.json"
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "https://${uri.host}$port$file"
}

object AdsbClient {

    /**
     * Tried in order, first success wins.
     *
     * This is a list rather than a constant because airplanes.live closed its
     * public v2 API in September 2026 — it answers 403 with "please contact
     * us" to every request, key or no key — and took the widget down with it.
     * One hardcoded host is one press release away from a dead tile.
     *
     * adsb.fi leads because it carries `desc` and `ownOp`, which become the
     * full type name and the operator line. adsb.lol has neither, so on the
     * fallback the type name comes from adsbdb or the local ICAO table instead,
     * and the operator line may simply be absent. Everything else is the same
     * ADSBExchange v2 shape, except that adsb.fi names the list `aircraft`
     * where adsb.lol names it `ac`.
     */
    private val ENDPOINTS = listOf(
        "https://opendata.adsb.fi/api/v2/lat/%s/lon/%s/dist/%d",
        "https://api.adsb.lol/v2/point/%s/%s/%d"
    )

    private const val TIMEOUT_MS = 12_000

    /**
     * Returns the closest airborne aircraft within [radiusNm], or null if the
     * sky is empty. Throws on network or parse failure so the caller can
     * distinguish "nothing up there" from "couldn't reach the network".
     *
     * [maxAltitudeFt] drops high cruisers. Without it, anyone living under an
     * airway gets the same airliner at FL380 forty miles away every refresh,
     * which is technically the nearest aircraft and of no interest at all.
     *
     * [station], when set, is asked first: a receiver of your own sees what is
     * overhead seconds sooner than an aggregator, with nothing sent anywhere.
     * If it hears nothing in range the aggregators are asked anyway — a
     * receiver at a window in a valley hears little, and "nothing heard here"
     * is not "nothing there". Its failures go to [onStationError] rather than
     * failing the refresh, for the same reason.
     */
    suspend fun nearest(
        lat: Double,
        lon: Double,
        radiusNm: Int = 50,
        includeGround: Boolean = false,
        maxAltitudeFt: Int? = null,
        station: Station? = null,
        onStationError: suspend (String) -> Unit = {}
    ): Aircraft? = withContext(Dispatchers.IO) {
        if (station != null) {
            try {
                pick(get(station.url, station.token), lat, lon, radiusNm, includeGround, maxAltitudeFt)
                    ?.let { return@withContext it }
            } catch (e: Exception) {
                onStationError(e.message ?: e.javaClass.simpleName)
            }
        }

        var failure: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                // A successful fetch is authoritative, including when it finds
                // nothing: an empty sky is an answer, not a reason to ask again.
                val url = endpoint.format(
                    Locale.US,
                    "%.4f".format(Locale.US, lat),
                    "%.4f".format(Locale.US, lon),
                    radiusNm
                )
                return@withContext pick(get(url), lat, lon, radiusNm, includeGround, maxAltitudeFt)
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("No ADS-B endpoint configured")
    }

    private val HEX_ENDPOINTS = listOf(
        "https://opendata.adsb.fi/api/v2/hex/%s",
        "https://api.adsb.lol/v2/hex/%s"
    )

    /**
     * One aircraft by its Mode-S hex, measured from where you are: how the
     * detail screen follows the plane on the widget rather than whatever is
     * nearest now. Null when it isn't broadcasting any more.
     */
    suspend fun byHex(hex: String, fromLat: Double, fromLon: Double): Aircraft? = withContext(Dispatchers.IO) {
        val clean = hex.trim().lowercase(Locale.US).filter { it.isLetterOrDigit() }
        if (clean.isEmpty()) return@withContext null
        var failure: Exception? = null
        for (endpoint in HEX_ENDPOINTS) {
            try {
                val root = JSONObject(get(endpoint.format(Locale.US, clean)))
                val list = root.optJSONArray("ac") ?: root.optJSONArray("aircraft")
                    ?: return@withContext null
                val o = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .firstOrNull { it.optString("hex", "").trim().lowercase(Locale.US) == clean }
                    ?: return@withContext null
                // The hex lookup has no "distance from you", so work it out.
                val a = parseAircraft(o)
                val lat = a.lat
                val lon = a.lon
                return@withContext if (lat == null || lon == null) a else a.copy(
                    distanceNm = RouteMath.nmBetween(fromLat, fromLon, lat, lon),
                    bearingDeg = RouteMath.bearingTo(fromLat, fromLon, lat, lon)
                )
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("No ADS-B endpoint configured")
    }

    private fun get(address: String, token: String? = null): String {
        val url = URL(address)
        return (url.openConnection() as HttpURLConnection).run {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Aggregators ask that clients identify themselves.
            setRequestProperty("User-Agent", "NearestPlaneWidget/1.0")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            // A station that refuses a token may redirect to its sign-in page;
            // following that would read an HTML form as an empty sky. Only the
            // station: an aggregator that moves an endpoint should still work.
            if (token != null) instanceFollowRedirects = false
            try {
                if (responseCode !in 200..299) {
                    // Name the host: "HTTP 403" alone doesn't say who refused.
                    val hint = if (token != null && responseCode in listOf(301, 302, 401, 403))
                        " (check the station token)" else ""
                    throw IllegalStateException("${url.host} returned HTTP $responseCode$hint")
                }
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }

    /**
     * The nearest qualifying aircraft in an ADSBExchange-v2 or dump1090 body.
     *
     * Aggregators measure distance and bearing from the point asked about
     * (`dst`, `dir`). dump1090 — and so a receiver of your own — does not, so
     * where those are absent they are worked out from the aircraft's position.
     */
    internal fun pick(
        body: String,
        fromLat: Double,
        fromLon: Double,
        radiusNm: Int,
        includeGround: Boolean,
        maxAltitudeFt: Int?
    ): Aircraft? {
        val root = JSONObject(body)
        val list = root.optJSONArray("ac") ?: root.optJSONArray("aircraft") ?: return null

        return (0 until list.length())
            .mapNotNull { list.optJSONObject(it) }
            .map { measured(parseAircraft(it), fromLat, fromLon, it) }
            .filter { includeGround || !it.onGround }
            // An unreported altitude is not evidence of a high one, and
            // dropping those would lose exactly the close GA traffic the
            // ceiling exists to surface.
            .filter { maxAltitudeFt == null || (it.altitudeFt ?: 0) <= maxAltitudeFt }
            .filter { it.distanceNm != null && it.distanceNm <= radiusNm }
            .minByOrNull { it.distanceNm!! }
    }

    /**
     * Fill in distance and bearing where the feed did not. A position older
     * than a minute is not used: dump1090 keeps an aircraft's last position
     * long after it was heard, and "0.4 nm overhead" from three minutes ago is
     * a different place at 250 knots.
     */
    private fun measured(a: Aircraft, fromLat: Double, fromLon: Double, o: JSONObject): Aircraft {
        if (a.distanceNm != null) return a
        val lat = a.lat ?: return a
        val lon = a.lon ?: return a
        if ((o.optDoubleOrNull("seen_pos") ?: 0.0) > 60.0) return a
        return a.copy(
            distanceNm = RouteMath.nmBetween(fromLat, fromLon, lat, lon),
            bearingDeg = RouteMath.bearingTo(fromLat, fromLon, lat, lon)
        )
    }

    private fun parseAircraft(o: JSONObject): Aircraft {
        // alt_baro is an Int in flight but the string "ground" on the tarmac.
        val rawAlt = o.opt("alt_baro")
        val onGround = rawAlt is String && rawAlt.equals("ground", ignoreCase = true)

        return Aircraft(
            hex = o.optString("hex", "?"),
            callsign = o.optString("flight", "").trim().ifBlank { null },
            registration = o.optString("r", "").trim().ifBlank { null },
            type = o.optString("t", "").trim().ifBlank { null },
            description = o.optString("desc", "").trim().ifBlank { null },
            operator = o.optString("ownOp", "").trim().ifBlank { null },
            altitudeFt = (rawAlt as? Number)?.toInt(),
            onGround = onGround,
            groundSpeedKts = o.optDoubleOrNull("gs"),
            trackDeg = o.optDoubleOrNull("track"),
            distanceNm = o.optDoubleOrNull("dst"),
            bearingDeg = o.optDoubleOrNull("dir"),
            lat = o.optDoubleOrNull("lat"),
            lon = o.optDoubleOrNull("lon"),
            squawk = o.optString("squawk", "").trim().ifBlank { null },
            verticalRateFpm = ((o.opt("baro_rate") ?: o.opt("geom_rate")) as? Number)?.toInt()
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
}
