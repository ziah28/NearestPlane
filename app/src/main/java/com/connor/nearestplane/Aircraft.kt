package com.connor.nearestplane

import java.util.Locale
import kotlin.math.roundToInt

/**
 * One aircraft, trimmed to the fields the widget shows. Field names mirror the
 * ADSBExchange v2 schema that airplanes.live, adsb.fi and adsb.lol all follow.
 */
data class Aircraft(
    val hex: String,
    val callsign: String?,
    val registration: String?,
    val type: String?,          // ICAO type code, e.g. "B748"
    val description: String?,   // feed's own full name, e.g. "BOEING 747-8"
    val operator: String?,      // ownOp, when the feed has it
    val altitudeFt: Int?,
    val onGround: Boolean,
    val groundSpeedKts: Double?,
    val trackDeg: Double?,
    val distanceNm: Double?,
    val bearingDeg: Double?,
    val lat: Double?,
    val lon: Double?,
    val squawk: String?,
    /** Feet per minute, negative when descending. */
    val verticalRateFpm: Int? = null
) {
    /** Callsign, then tail number, then hex. */
    val label: String
        get() = callsign?.takeIf { it.isNotBlank() }
            ?: registration?.takeIf { it.isNotBlank() }
            ?: hex.uppercase()

    /** "Boeing 747-8" — feed description first, local table second, code last. */
    val typeName: String?
        get() = AircraftTypes.tidyDescription(description)
            ?: AircraftTypes.fullName(type)
            ?: type?.takeIf { it.isNotBlank() }

    /** 7500 hijack, 7600 radio failure, 7700 general emergency. */
    val emergency: String?
        get() = when (squawk) {
            "7500" -> "HIJACK"
            "7600" -> "NO RADIO"
            "7700" -> "EMERGENCY"
            else -> null
        }

    /** "3.2 nm NNE" */
    val positionLine: String
        get() = distanceNm?.let { "${fmt1(it)} nm ${compass(bearingDeg)}" }.orEmpty()

    /** "FL350 · 452 kts" or "on ground" */
    val detail: String
        get() = when {
            onGround -> "on ground"
            else -> buildList {
                altitudeFt?.let { add(formatAltitude(it)) }
                groundSpeedKts?.let { add("${it.roundToInt()} kts") }
            }.joinToString(" · ")
        }
}

/** Above the transition altitude pilots talk in flight levels; below it, feet. */
private fun formatAltitude(ft: Int): String =
    if (ft >= 18_000) "FL${(ft / 100).toString().padStart(3, '0')}"
    else "${"%,d".format(Locale.US, ft)} ft"

private fun fmt1(v: Double): String = "%.1f".format(Locale.US, v)

/** 16-point compass, because "NNE" reads faster than "28°" at a glance. */
fun compass(bearing: Double?): String {
    if (bearing == null) return ""
    val points = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )
    val normalized = ((bearing % 360) + 360) % 360
    return points[((normalized / 22.5) + 0.5).toInt() % 16]
}
