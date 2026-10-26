package com.connor.nearestplane

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One end of a flight: the time to show, local to that airport, the scheduled
 * time it is judged against, and how late it is.
 */
data class FlightTime(
    /** "14:27", local to the airport. */
    val shown: String,
    /** "14:05", or blank when unknown. */
    val scheduled: String,
    /** Minutes late, negative when early, null when there's nothing to compare. */
    val delayMin: Int?,
    /** True when [shown] has actually happened (departed, landed), not an estimate. */
    val actual: Boolean
) {
    /** The industry's line: within 15 minutes of schedule counts as on time. */
    val late: Boolean get() = (delayMin ?: 0) > 15

    val pill: String
        get() = when {
            delayMin == null -> ""
            delayMin > 15 -> "+$delayMin min"
            delayMin < -1 -> "${-delayMin} min early"
            else -> "On time"
        }
}

/**
 * Everything one AeroDataBox lookup says about a flight. Looked up by the
 * aircraft's own transponder ID, so the route is today's, not a guess from the
 * callsign; any part it doesn't have stays null and the free sources fill in.
 */
data class FlightSchedule(
    val departure: FlightTime?,
    val arrival: FlightTime?,
    val origin: Airport? = null,
    val destination: Airport? = null,
    val airlineName: String? = null,
    val airlineIata: String? = null,
    /** "FR 4527", what you'd see on a departures board. */
    val flightNumber: String? = null,
    val model: String? = null,
    val registration: String? = null
)

/**
 * Departure and arrival times from AeroDataBox (via RapidAPI), with your key.
 *
 * Thrifty by design, because the free plan is about 200 lookups a month:
 * - one lookup per flight, then the answer is reused for 12 hours, so tapping
 *   refresh on the same plane, or opening its detail screen, costs nothing;
 * - only airline-style callsigns are looked up (EZY18VM, not G-ABCD), since
 *   private and light aircraft have no schedule to find;
 * - a flight with no schedule is remembered too, so it isn't asked about twice.
 */
object FlightTimes {

    private const val HOST = "aerodatabox.p.rapidapi.com"
    private const val TIMEOUT_MS = 10_000
    private const val TTL_MS = 12 * 60 * 60 * 1000L

    private val AIRLINE_CALLSIGN = Regex("^[A-Z]{3}\\d")

    enum class KeyStatus { UNKNOWN, WORKS, REJECTED, LIMIT }

    private fun prefs(context: Context) = context.getSharedPreferences("flight_times", Context.MODE_PRIVATE)

    private fun cacheKey(plane: Aircraft) =
        // "t2": answers saved before the leg-matching fix may hold the wrong leg, so they're not reused.
        "t2:${plane.hex.lowercase(Locale.US)}:${plane.callsign?.trim()?.uppercase(Locale.US).orEmpty()}"

    /** Saved times for this flight, if any. Never costs a lookup. */
    fun cached(context: Context, plane: Aircraft): FlightSchedule? {
        val raw = prefs(context).getString(cacheKey(plane), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.optLong("at") > TTL_MS) return null
            FlightSchedule(
                departure = readTime(o.optJSONObject("dep")),
                arrival = readTime(o.optJSONObject("arr")),
                origin = readAirport(o.optJSONObject("orig")),
                destination = readAirport(o.optJSONObject("dest")),
                airlineName = o.optString("aname").ifBlank { null },
                airlineIata = o.optString("aiata").ifBlank { null },
                flightNumber = o.optString("fno").ifBlank { null },
                model = o.optString("model").ifBlank { null },
                registration = o.optString("reg").ifBlank { null }
            )
        }.getOrNull()
    }

    /**
     * Times for [plane]: saved ones if we have them, otherwise one lookup.
     * Never throws; no key, no network or no schedule all come back as null.
     */
    suspend fun forPlane(context: Context, plane: Aircraft): FlightSchedule? {
        cached(context, plane)?.let { return it }
        val callsign = plane.callsign?.trim()?.uppercase(Locale.US).orEmpty()
        if (!AIRLINE_CALLSIGN.containsMatchIn(callsign)) return null
        val key = AppSettings.timesKey(context) ?: return null
        if (keyStatus(context) == KeyStatus.LIMIT && sameMonth(prefs(context).getLong("limit_at", 0L))) return null

        val result = runCatching { fetch(key, plane.hex) }
        result.onFailure { return null }
        val (code, body) = result.getOrThrow()
        countLookup(context)

        when (code) {
            401, 403 -> { setStatus(context, KeyStatus.REJECTED); return null }
            429 -> { setStatus(context, KeyStatus.LIMIT); return null }
        }
        if (code !in 200..299 && code != 204 && code != 404) return null
        setStatus(context, KeyStatus.WORKS)

        val schedule = if (code == 200) runCatching { pick(body, plane) }.getOrNull() else null
        // Remember the answer, including "no schedule", so this flight is never asked about twice.
        val saved = JSONObject().put("at", System.currentTimeMillis())
        schedule?.departure?.let { saved.put("dep", writeTime(it)) }
        schedule?.arrival?.let { saved.put("arr", writeTime(it)) }
        schedule?.origin?.let { saved.put("orig", writeAirport(it)) }
        schedule?.destination?.let { saved.put("dest", writeAirport(it)) }
        schedule?.airlineName?.let { saved.put("aname", it) }
        schedule?.airlineIata?.let { saved.put("aiata", it) }
        schedule?.flightNumber?.let { saved.put("fno", it) }
        schedule?.model?.let { saved.put("model", it) }
        schedule?.registration?.let { saved.put("reg", it) }
        prefs(context).edit().putString(cacheKey(plane), saved.toString()).apply()
        trimCache(context)
        return schedule
    }

    // ---- the key and the monthly count, for the settings screen ----

    fun keyStatus(context: Context): KeyStatus =
        runCatching { KeyStatus.valueOf(prefs(context).getString("status", null) ?: "UNKNOWN") }
            .getOrDefault(KeyStatus.UNKNOWN)

    /** Lookups made this calendar month. */
    fun lookupsThisMonth(context: Context): Int {
        val p = prefs(context)
        return if (p.getString("month", "") == monthNow()) p.getInt("count", 0) else 0
    }

    /** A new key starts with a clean status. */
    fun resetStatus(context: Context) {
        prefs(context).edit().remove("status").remove("limit_at").apply()
    }

    private fun setStatus(context: Context, s: KeyStatus) {
        val e = prefs(context).edit().putString("status", s.name)
        if (s == KeyStatus.LIMIT) e.putLong("limit_at", System.currentTimeMillis())
        e.apply()
    }

    private fun countLookup(context: Context) {
        val p = prefs(context)
        val month = monthNow()
        val count = if (p.getString("month", "") == month) p.getInt("count", 0) else 0
        p.edit().putString("month", month).putInt("count", count + 1).apply()
    }

    private fun monthNow() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    private fun sameMonth(at: Long) = at > 0 && SimpleDateFormat("yyyy-MM", Locale.US).format(Date(at)) == monthNow()

    private fun trimCache(context: Context) {
        val p = prefs(context)
        // Old-format entries ("t:") are dropped here too.
        p.all.keys.filter { it.startsWith("t:") }.takeIf { it.isNotEmpty() }?.let { old ->
            p.edit().apply { old.forEach { remove(it) } }.apply()
        }
        val entries = p.all.filterKeys { it.startsWith("t2:") }
        if (entries.size <= 300) return
        val e = p.edit()
        entries.keys.sortedBy { runCatching { JSONObject(entries[it] as String).optLong("at") }.getOrDefault(0L) }
            .take(entries.size - 200)
            .forEach { e.remove(it) }
        e.apply()
    }

    // ---- the request ----

    private suspend fun fetch(key: String, hex: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val url = URL("https://$HOST/flights/Icao24/${hex.trim().lowercase(Locale.US)}/$date?dateLocalRole=Both&withAircraftImage=false&withLocation=false")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("X-RapidAPI-Key", key)
            conn.setRequestProperty("X-RapidAPI-Host", HOST)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The flight in the air now, out of the day's flights for this aircraft:
     * the one that has departed but not landed, else the one scheduled
     * closest to now.
     */
    private fun pick(body: String, plane: Aircraft): FlightSchedule? {
        val trimmed = body.trim()
        val flights: List<JSONObject> = when {
            trimmed.startsWith("[") -> JSONArray(trimmed).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            trimmed.startsWith("{") -> listOf(JSONObject(trimmed))
            else -> emptyList()
        }
        if (flights.isEmpty()) return null
        val now = System.currentTimeMillis()

        fun utcOf(end: JSONObject?, field: String): Long? =
            end?.optJSONObject(field)?.optString("utc")?.let(::parseUtc)

        // An aircraft flies several legs a day, and an airport doesn't always
        // report the earlier ones as landed. So first: the leg whose callsign
        // matches, and whose route the plane is actually sitting on.
        val callsign = plane.callsign?.replace(" ", "")?.uppercase(Locale.US).orEmpty()
        val sameCallsign = flights.filter { it.optString("callSign").replace(" ", "").uppercase(Locale.US) == callsign }
        val pool = sameCallsign.ifEmpty { flights }
        val lat = plane.lat
        val lon = plane.lon
        val onTheLine = if (lat != null && lon != null) {
            pool.mapNotNull { f ->
                val o = airport(f.optJSONObject("departure")?.optJSONObject("airport")) ?: return@mapNotNull null
                val d = airport(f.optJSONObject("arrival")?.optJSONObject("airport")) ?: return@mapNotNull null
                val direct = RouteMath.nmBetween(o.lat, o.lon, d.lat, d.lon).coerceAtLeast(1.0)
                val toDest = RouteMath.nmBetween(lat, lon, d.lat, d.lon)
                val via = RouteMath.nmBetween(o.lat, o.lon, lat, lon) + toDest
                // The outbound and return legs share a line, so the heading decides:
                // the plane should be pointing roughly at its destination, unless
                // it's close enough to be turning onto the runway.
                val track = plane.trackDeg
                val heading = track == null || toDest < 20 || run {
                    val off = kotlin.math.abs(((RouteMath.bearingTo(lat, lon, d.lat, d.lon) - track + 540) % 360) - 180)
                    off < 100
                }
                Triple(f, via / direct, heading)
            }
                // Within a quarter of the straight line: real flights bend a little, wrong legs a lot.
                .filter { it.second < 1.25 }
                .let { fits -> fits.filter { it.third }.ifEmpty { fits } }
                .minByOrNull { it.second }?.first
        } else null

        val airborne = onTheLine ?: pool.firstOrNull { f ->
            val dep = f.optJSONObject("departure")
            val arr = f.optJSONObject("arrival")
            val left = utcOf(dep, "runwayTime") ?: utcOf(dep, "revisedTime")
            left != null && left <= now && utcOf(arr, "runwayTime") == null
        } ?: pool.firstOrNull { f ->
            f.optString("status").let { it.contains("Route", true) || it.contains("Departed", true) || it.contains("Approaching", true) }
        }
        val chosen = airborne ?: pool.minByOrNull { f ->
            val s = utcOf(f.optJSONObject("departure"), "scheduledTime") ?: return@minByOrNull Long.MAX_VALUE
            kotlin.math.abs(s - now)
        } ?: return null

        val airline = chosen.optJSONObject("airline")
        val aircraft = chosen.optJSONObject("aircraft")
        return FlightSchedule(
            departure = end(chosen.optJSONObject("departure"), arriving = false),
            arrival = end(chosen.optJSONObject("arrival"), arriving = true),
            origin = airport(chosen.optJSONObject("departure")?.optJSONObject("airport")),
            destination = airport(chosen.optJSONObject("arrival")?.optJSONObject("airport")),
            airlineName = airline?.optString("name")?.ifBlank { null },
            airlineIata = airline?.optString("iata")?.ifBlank { null },
            flightNumber = chosen.optString("number").ifBlank { null },
            model = aircraft?.optString("model")?.ifBlank { null },
            registration = aircraft?.optString("reg")?.ifBlank { null }
        ).takeIf { it.departure != null || it.arrival != null || (it.origin != null && it.destination != null) }
    }

    private fun end(o: JSONObject?, arriving: Boolean): FlightTime? {
        if (o == null) return null
        val scheduled = o.optJSONObject("scheduledTime")
        val runway = o.optJSONObject("runwayTime")
        val revised = o.optJSONObject("revisedTime")
        val predicted = o.optJSONObject("predictedTime")
        // What actually happened first, then the latest estimate, then the plan.
        val best = runway ?: revised ?: (if (arriving) predicted else null) ?: scheduled ?: return null
        val shown = clock(best.optString("local")) ?: return null
        val sched = scheduled?.optString("local")?.let(::clock).orEmpty()
        val bestUtc = parseUtc(best.optString("utc"))
        val schedUtc = scheduled?.optString("utc")?.let(::parseUtc)
        // Only a live time (actual or revised) says anything about delay; the plan alone doesn't.
        val delay = if (bestUtc != null && schedUtc != null && best !== scheduled) ((bestUtc - schedUtc) / 60_000L).toInt() else null
        return FlightTime(
            shown = shown,
            scheduled = if (sched == shown) "" else sched,
            delayMin = delay,
            actual = best === runway || (!arriving && best === revised && bestUtc != null && bestUtc <= System.currentTimeMillis())
        )
    }

    /** "2026-10-26 14:05+01:00" → "14:05". */
    private fun clock(local: String?): String? =
        local?.let { Regex("(\\d{2}):(\\d{2})").find(it.substringAfter(' ', it))?.value }

    /** "2026-10-26 13:05Z" → epoch millis. */
    private fun parseUtc(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})").find(s) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0)
        }.timeInMillis
    }

    /** An airport from AeroDataBox; only usable with a position, which the route path needs. */
    private fun airport(o: JSONObject?): Airport? {
        if (o == null) return null
        val loc = o.optJSONObject("location") ?: return null
        val lat = (loc.opt("lat") as? Number)?.toDouble() ?: return null
        val lon = (loc.opt("lon") as? Number)?.toDouble() ?: return null
        val icao = o.optString("icao").ifBlank { return null }
        return Airport(
            icao = icao,
            iata = o.optString("iata").ifBlank { null },
            municipality = o.optString("municipalityName").ifBlank { o.optString("shortName").ifBlank { null } },
            lat = lat,
            lon = lon
        )
    }

    private fun writeAirport(a: Airport) = JSONObject()
        .put("icao", a.icao).put("iata", a.iata.orEmpty()).put("city", a.municipality.orEmpty())
        .put("lat", a.lat).put("lon", a.lon)

    private fun readAirport(o: JSONObject?): Airport? {
        if (o == null || !o.has("lat") || !o.has("lon")) return null
        return Airport(
            icao = o.optString("icao").ifBlank { return null },
            iata = o.optString("iata").ifBlank { null },
            municipality = o.optString("city").ifBlank { null },
            lat = o.optDouble("lat"),
            lon = o.optDouble("lon")
        )
    }

    /**
     * The free sources' report, with AeroDataBox's answer laid over it: its
     * route, airline and aircraft win wherever it has them, because they come
     * from today's flight rather than a callsign table.
     */
    fun merge(report: PlaneReport, s: FlightSchedule?): PlaneReport {
        if (s == null) return report
        var r = report
        if (s.origin != null && s.destination != null) {
            val route = FlightRoute(
                airline = s.airlineName ?: report.route?.airline,
                origin = s.origin,
                destination = s.destination,
                airlineIata = s.airlineIata ?: report.route?.airlineIata
            )
            r = r.copy(
                route = route,
                quality = RouteQuality.GOOD,
                routeLine = runCatching { PlaneSummary.routeLine(route, RouteQuality.GOOD) }.getOrDefault(r.routeLine),
                timingLine = runCatching { PlaneSummary.timingLine(report.plane, route) }.getOrDefault("")
            )
        } else if (s.airlineIata != null && r.route != null) {
            r = r.copy(route = r.route!!.copy(airlineIata = s.airlineIata))
        }
        s.airlineName?.let { r = r.copy(operator = it) }
        s.model?.let { r = r.copy(typeName = it) }
        s.registration?.let { reg ->
            r = r.copy(info = r.info?.copy(registration = reg) ?: AircraftInfo(null, null, null, reg, null))
        }
        return r
    }

    private fun writeTime(t: FlightTime) = JSONObject()
        .put("shown", t.shown).put("sched", t.scheduled).put("actual", t.actual)
        .apply { t.delayMin?.let { put("delay", it) } }

    private fun readTime(o: JSONObject?): FlightTime? {
        if (o == null) return null
        return FlightTime(
            shown = o.optString("shown").ifBlank { return null },
            scheduled = o.optString("sched"),
            delayMin = if (o.has("delay")) o.optInt("delay") else null,
            actual = o.optBoolean("actual")
        )
    }
}
