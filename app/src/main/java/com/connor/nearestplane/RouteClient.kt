package com.connor.nearestplane

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Airport(
    val icao: String,
    val iata: String?,
    val municipality: String?,
    val lat: Double,
    val lon: Double
) {
    val display: String get() = icao.ifBlank { iata.orEmpty() }
}

data class FlightRoute(
    val airline: String?,
    val origin: Airport?,
    val destination: Airport?,
    /** Two-letter airline code ("U2"), used to fetch the airline's logo. */
    val airlineIata: String? = null
)

/** Airframe facts, keyed by Mode-S hex. These never change for a given hex. */
data class AircraftInfo(
    val manufacturer: String?,
    val model: String?,        // "680A Citation Latitude"
    val icaoType: String?,
    val registration: String?,
    val owner: String?         // "NetJets"
) {
    /** "Cessna 680A Citation Latitude" */
    val fullName: String?
        get() = listOfNotNull(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }
}

data class AdsbdbResult(val aircraft: AircraftInfo?, val route: FlightRoute?)

/**
 * adsbdb gives us two things the radio signal doesn't carry: what the airframe
 * actually is, and where the flight is scheduled to go.
 *
 * The aircraft half is reliable — a Mode-S hex maps to exactly one airframe
 * forever. The route half is a static callsign lookup, and that's a much weaker
 * claim: see RouteQuality below.
 */
object AdsbdbClient {

    private const val COMBINED = "https://api.adsbdb.com/v0/aircraft/%s?callsign=%s"
    private const val AIRCRAFT_ONLY = "https://api.adsbdb.com/v0/aircraft/%s"
    private const val TIMEOUT_MS = 10_000
    private const val NONE = "__none__"

    private const val AC = "ac:"
    private const val RT = "rt:"

    /** Airframes are permanent; routes go stale, so they carry a timestamp. */
    private const val ROUTE_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * A *miss* expires even for an airframe. adsbdb's database grows, so a hex
     * looked up the week before it was catalogued would otherwise stay blank
     * for the life of the install.
     */
    private const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000L

    /**
     * The cache is a preferences file, and DataStore loads all of it on every
     * read. You see a different aircraft most refreshes, so left alone this
     * grows without limit for as long as the app is installed — slowly, and
     * then noticeably. Evicting the oldest entries costs one re-fetch each.
     */
    private const val MAX_ENTRIES = 500
    private const val EVICT_TO = 400

    suspend fun lookup(context: Context, hex: String, callsign: String?): AdsbdbResult {
        val hexKey = hex.trim().lowercase()
        val signKey = callsign?.trim()?.uppercase().orEmpty()

        val cachedAircraft = readAircraft(context, hexKey)
        val cachedRoute = if (signKey.isNotBlank()) readRoute(context, signKey) else null

        // Both already known — no request at all.
        if (cachedAircraft != null && (signKey.isBlank() || cachedRoute != null)) {
            return AdsbdbResult(
                cachedAircraft.takeIf { it.icaoType != NONE },
                cachedRoute?.takeIf { it.origin != null || it.destination != null }
            )
        }

        val fetched = try {
            fetch(hexKey, signKey)
        } catch (_: Exception) {
            // Network trouble must not poison the cache or fail the refresh.
            return AdsbdbResult(cachedAircraft?.takeIf { it.icaoType != NONE }, cachedRoute)
        }

        writeAircraft(context, hexKey, fetched.aircraft)
        if (signKey.isNotBlank()) writeRoute(context, signKey, fetched.route)
        return fetched
    }

    private suspend fun fetch(hex: String, callsign: String): AdsbdbResult =
        withContext(Dispatchers.IO) {
            val url = if (callsign.isBlank()) AIRCRAFT_ONLY.format(Locale.US, hex)
            else COMBINED.format(Locale.US, hex, callsign)

            val conn = URL(url).openConnection() as HttpURLConnection
            val body = conn.run {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "NearestPlaneWidget/1.0")
                try {
                    // 404 is a real answer — adsbdb has never heard of this hex —
                    // and caching it saves a request next time. Any other non-2xx
                    // is a transient server problem, so throw instead: the airframe
                    // cache never expires, and writing "unknown" over a rate-limit
                    // response would hide that aircraft's type name forever.
                    if (responseCode == 404) return@withContext AdsbdbResult(null, null)
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("adsbdb returned HTTP $responseCode")
                    }
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }

            val response = JSONObject(body).optJSONObject("response")
                ?: return@withContext AdsbdbResult(null, null)

            AdsbdbResult(
                aircraft = response.optJSONObject("aircraft")?.let {
                    AircraftInfo(
                        manufacturer = it.optString("manufacturer", "").ifBlank { null },
                        model = it.optString("type", "").ifBlank { null },
                        icaoType = it.optString("icao_type", "").ifBlank { null },
                        registration = it.optString("registration", "").ifBlank { null },
                        owner = it.optString("registered_owner", "").ifBlank { null }
                    )
                },
                route = response.optJSONObject("flightroute")?.let {
                    FlightRoute(
                        airline = it.optJSONObject("airline")?.optString("name")?.ifBlank { null },
                        origin = airport(it.optJSONObject("origin")),
                        destination = airport(it.optJSONObject("destination")),
                        airlineIata = it.optJSONObject("airline")?.optString("iata")?.ifBlank { null }
                    )
                }
            )
        }

    private fun airport(o: JSONObject?): Airport? {
        if (o == null) return null
        val lat = (o.opt("latitude") as? Number)?.toDouble() ?: return null
        val lon = (o.opt("longitude") as? Number)?.toDouble() ?: return null
        return Airport(
            icao = o.optString("icao_code", ""),
            iata = o.optString("iata_code", "").ifBlank { null },
            municipality = o.optString("municipality", "").ifBlank { null },
            lat = lat,
            lon = lon
        )
    }

    // ---- cache: pipe-delimited, to avoid a serialization dependency ----
    //
    // Every value now starts with the epoch millis it was written at, so the
    // eviction pass can age any entry without knowing which kind it is.

    private fun key(prefix: String, id: String) = stringPreferencesKey("$prefix$id")

    private fun stampOf(raw: String?): Long = raw?.substringBefore("|")?.toLongOrNull() ?: 0L

    private suspend fun readAircraft(context: Context, hex: String): AircraftInfo? = runCatching {
        val raw = context.cacheStore.data.first()[key(AC, hex)] ?: return null
        val f = raw.split("|")
        // Entries written before the timestamp existed parse as unreadable,
        // which re-fetches them once and rewrites them in the new shape.
        val stamp = f.getOrNull(0)?.toLongOrNull() ?: return null
        if (f.getOrNull(1) == NONE) {
            return if (System.currentTimeMillis() - stamp > MISS_TTL_MS) null
            else AircraftInfo(null, null, NONE, null, null)
        }
        if (f.size < 6) return null
        AircraftInfo(
            f[1].ifBlank { null }, f[2].ifBlank { null }, f[3].ifBlank { null },
            f[4].ifBlank { null }, f[5].ifBlank { null }
        )
    }.getOrNull()

    private suspend fun writeAircraft(context: Context, hex: String, a: AircraftInfo?) {
        val now = System.currentTimeMillis()
        put(
            context, key(AC, hex),
            a?.let { v ->
                listOf(
                    now.toString(), v.manufacturer.orEmpty(), v.model.orEmpty(),
                    v.icaoType.orEmpty(), v.registration.orEmpty(), v.owner.orEmpty()
                ).joinToString("|")
            } ?: "$now|$NONE"
        )
    }

    private suspend fun readRoute(context: Context, callsign: String): FlightRoute? = runCatching {
        val raw = context.cacheStore.data.first()[key(RT, callsign)] ?: return null
        val f = raw.split("|")
        val stamp = f.getOrNull(0)?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - stamp > ROUTE_TTL_MS) return null   // expired
        if (f.getOrNull(1) == NONE) return FlightRoute(null, null, null)
        fun ap(s: String?): Airport? {
            if (s.isNullOrBlank()) return null
            val g = s.split(";")
            if (g.size < 5) return null
            val lat = g[3].toDoubleOrNull() ?: return null
            val lon = g[4].toDoubleOrNull() ?: return null
            return Airport(g[0], g[1].ifBlank { null }, g[2].ifBlank { null }, lat, lon)
        }
        // The airline code was added later; older entries simply have none.
        FlightRoute(
            f.getOrNull(1)?.ifBlank { null }, ap(f.getOrNull(2)), ap(f.getOrNull(3)),
            f.getOrNull(4)?.ifBlank { null }
        )
    }.getOrNull()

    private suspend fun writeRoute(context: Context, callsign: String, r: FlightRoute?) {
        fun enc(a: Airport?) = a?.let {
            "${it.icao};${it.iata.orEmpty()};${it.municipality.orEmpty()};${it.lat};${it.lon}"
        }.orEmpty()
        val now = System.currentTimeMillis()
        put(
            context, key(RT, callsign),
            if (r == null) "$now|$NONE"
            else "$now|${r.airline.orEmpty()}|${enc(r.origin)}|${enc(r.destination)}|${r.airlineIata.orEmpty()}"
        )
    }

    /**
     * One write, plus an eviction pass when the file has grown past its cap.
     * Both happen inside a single edit, so the cache can never be left holding
     * more than it is allowed to.
     */
    private suspend fun put(context: Context, k: Preferences.Key<String>, value: String) {
        runCatching {
            context.cacheStore.edit { prefs ->
                prefs[k] = value

                val cached = prefs.asMap().keys
                    .map { it.name }
                    .filter { it.startsWith(AC) || it.startsWith(RT) }
                if (cached.size <= MAX_ENTRIES) return@edit

                // Oldest first, dropped in a batch rather than one per write:
                // this then runs once every hundred new aircraft, not always.
                cached
                    .sortedBy { stampOf(prefs[stringPreferencesKey(it)]) }
                    .take(cached.size - EVICT_TO)
                    .forEach { prefs.remove(stringPreferencesKey(it)) }
            }
        }
    }
}

private val Context.cacheStore: DataStore<Preferences> by preferencesDataStore("adsbdb_cache")

/**
 * How much to trust a route.
 *
 * The underlying data is a static callsign-to-route table. That works for
 * scheduled airlines, where UAL123 flies the same city pair daily for a whole
 * season. It fails badly for fractional and charter operators — NetJets will
 * use EJA888 for Teterboro today and Midway tomorrow, and the table only holds
 * one of them.
 */
enum class RouteQuality { GOOD, UNCERTAIN, CONTRADICTED }

object RouteMath {

    /**
     * ICAO prefixes of operators that recycle callsigns across unrelated
     * flights. A route for one of these is a guess, and gets marked as such.
     */
    private val CALLSIGN_REUSERS = setOf(
        "EJA",  // NetJets
        "LXJ",  // Flexjet
        "XOJ",  // XO
        "JTL",  // Jet Linx
        "VJT",  // VistaJet
        "GAJ",  // Gama Aviation
        "TWY",  // Airshare
        "OPT",  // Flight Options
        "EEA",  // ExecuJet
        "PSP",  // PlaneSense
        "AJI"   // Ameriflight charter
    )

    /** A bare tail number as a callsign is always a one-off flight. */
    private val BARE_TAIL = Regex("^N\\d")

    fun assessRoute(
        callsign: String?,
        planeLat: Double?,
        planeLon: Double?,
        trackDeg: Double?,
        groundSpeedKts: Double?,
        altitudeFt: Int?,
        destination: Airport?
    ): RouteQuality {
        val reuser = callsign?.uppercase()?.let { cs ->
            CALLSIGN_REUSERS.any { cs.startsWith(it) } || BARE_TAIL.containsMatchIn(cs)
        } ?: false

        // Can only sanity-check a destination we have, for an aircraft that's
        // established en route. In the climb, descent or pattern, track tells
        // you nothing about where the flight is ultimately going.
        val enRoute = (groundSpeedKts ?: 0.0) > 150 && (altitudeFt ?: 0) > 12_000
        if (destination == null || planeLat == null || planeLon == null ||
            trackDeg == null || !enRoute
        ) {
            return if (reuser) RouteQuality.UNCERTAIN else RouteQuality.GOOD
        }

        val bearing = bearingTo(planeLat, planeLon, destination.lat, destination.lon)
        val off = angularDifference(trackDeg, bearing)

        return when {
            // Flying away from the claimed destination — the route is wrong.
            off > 90 -> RouteQuality.CONTRADICTED
            off > 45 || reuser -> RouteQuality.UNCERTAIN
            else -> RouteQuality.GOOD
        }
    }

    /** Smallest angle between two headings, 0..180. */
    fun angularDifference(a: Double, b: Double): Double {
        var d = abs(a - b) % 360
        if (d > 180) d = 360 - d
        return d
    }

    fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun nmBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3440.065
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun progressPercent(
        planeLat: Double, planeLon: Double, origin: Airport, destination: Airport
    ): Int? {
        val flown = nmBetween(origin.lat, origin.lon, planeLat, planeLon)
        val remaining = nmBetween(planeLat, planeLon, destination.lat, destination.lon)
        val total = flown + remaining
        if (total < 1) return null
        return ((flown / total) * 100).roundToInt().coerceIn(0, 100)
    }

    /**
     * Straight-line distance to destination over current ground speed.
     * Reasonable at cruise, optimistic in descent, blind to routing and wind.
     */
    fun minutesRemaining(
        planeLat: Double, planeLon: Double, destination: Airport, groundSpeedKts: Double?
    ): Int? {
        if (groundSpeedKts == null || groundSpeedKts < 60) return null
        val remaining = nmBetween(planeLat, planeLon, destination.lat, destination.lon)
        return ((remaining / groundSpeedKts) * 60).roundToInt().takeIf { it in 0..1440 }
    }

    /** Worse than the above: assumes the whole leg was flown at current speed. */
    fun minutesSinceDeparture(
        planeLat: Double, planeLon: Double, origin: Airport, groundSpeedKts: Double?
    ): Int? {
        if (groundSpeedKts == null || groundSpeedKts < 60) return null
        val flown = nmBetween(origin.lat, origin.lon, planeLat, planeLon)
        return ((flown / groundSpeedKts) * 60).roundToInt().takeIf { it in 0..1440 }
    }

    fun formatDuration(minutes: Int): String =
        if (minutes < 60) "${minutes}m"
        else "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}m"
}
