package com.connor.nearestplane

import android.content.Context
import com.connor.nearestplane.wx.AviationWeatherClient
import com.connor.nearestplane.wx.Metar
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The weather at one airport, as the widget shows it: "11°C" and an icon. */
data class AirportWx(val temp: String, val icon: WxIcon)

enum class WxIcon(val drawable: Int) {
    SUN(R.drawable.ic_wx_sun),
    MOON(R.drawable.ic_wx_moon),
    PARTLY(R.drawable.ic_wx_partly),
    CLOUD(R.drawable.ic_wx_cloud),
    RAIN(R.drawable.ic_wx_rain),
    SNOW(R.drawable.ic_wx_snow),
    STORM(R.drawable.ic_wx_storm),
    FOG(R.drawable.ic_wx_fog)
}

/**
 * Weather at both ends of a flight, from the airports' own official reports
 * (METARs, via aviationweather.gov). Free, no key; reports are refreshed about
 * hourly, so each airport's is kept for 30 minutes and reused.
 */
object AirportWeather {

    private const val TTL_MS = 30 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences("airport_weather", Context.MODE_PRIVATE)

    /** Weather for each ICAO code that has a report. Never throws. */
    suspend fun forAirports(context: Context, vararg airports: Airport?): Map<String, AirportWx> {
        val wanted = airports.filterNotNull().filter { it.icao.isNotBlank() }
        if (wanted.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, AirportWx>()
        val missing = mutableListOf<Airport>()
        for (a in wanted) {
            val hit = cached(context, a.icao)
            if (hit != null) out[a.icao.uppercase()] = hit else missing += a
        }
        if (missing.isNotEmpty()) {
            val fetched = runCatching { AviationWeatherClient.metars(missing.map { it.icao }) }.getOrNull().orEmpty()
            for (a in missing) {
                val m = fetched[a.icao.uppercase()] ?: continue
                val wx = describe(m, a.lat, a.lon) ?: continue
                out[a.icao.uppercase()] = wx
                save(context, a.icao, wx)
            }
        }
        return out
    }

    private fun cached(context: Context, icao: String): AirportWx? = runCatching {
        val o = JSONObject(prefs(context).getString(icao.uppercase(), null) ?: return null)
        if (System.currentTimeMillis() - o.optLong("at") > TTL_MS) return null
        AirportWx(o.getString("temp"), WxIcon.valueOf(o.getString("icon")))
    }.getOrNull()

    private fun save(context: Context, icao: String, wx: AirportWx) {
        val o = JSONObject().put("at", System.currentTimeMillis()).put("temp", wx.temp).put("icon", wx.icon.name)
        prefs(context).edit().putString(icao.uppercase(), o.toString()).apply()
    }

    /** One report as a temperature and the icon for its conditions. */
    internal fun describe(m: Metar, lat: Double, lon: Double): AirportWx? {
        val temp = m.tempC ?: return null
        val wx = m.weather.orEmpty().uppercase()
        val covers = m.clouds.map { it.cover.uppercase() }
        val icon = when {
            "TS" in wx -> WxIcon.STORM
            "SN" in wx || "SG" in wx || "PL" in wx -> WxIcon.SNOW
            "RA" in wx || "DZ" in wx || "SH" in wx -> WxIcon.RAIN
            "FG" in wx || "BR" in wx || "HZ" in wx || ((m.visibilitySm ?: 10.0) < 3.0) -> WxIcon.FOG
            covers.any { it == "OVC" || it == "OVX" || it == "BKN" } -> WxIcon.CLOUD
            covers.any { it == "SCT" || it == "FEW" } -> if (daylight(lat, lon)) WxIcon.PARTLY else WxIcon.CLOUD
            else -> if (daylight(lat, lon)) WxIcon.SUN else WxIcon.MOON
        }
        return AirportWx("${temp.roundToInt()}°C", icon)
    }

    /** Is the sun up at this airport right now? A plain solar-elevation estimate. */
    private fun daylight(lat: Double, lon: Double): Boolean {
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val day = now.get(Calendar.DAY_OF_YEAR)
        val hours = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0
        val decl = Math.toRadians(23.44) * sin(Math.toRadians(360.0 / 365.0 * (day - 81)))
        val solarHour = hours + lon / 15.0
        val hourAngle = Math.toRadians(15.0 * (solarHour - 12.0))
        val latR = Math.toRadians(lat)
        val elevation = asin(sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(hourAngle))
        return Math.toDegrees(elevation) > -0.83
    }
}
