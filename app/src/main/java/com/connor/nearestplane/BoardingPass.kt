package com.connor.nearestplane

/**
 * What the boarding-pass widget shows, worked out once from a plane and its
 * report so the widget itself only lays text out.
 */
data class BoardingPass(
    /** Airline prefix of the callsign ("EZY"), shown where the logo will go. */
    val badge: String,
    val fromCode: String,
    val fromCity: String,
    val toCode: String,
    val toCity: String,
    /** 0..100 along the route, or -1 when there is no route to draw. */
    val progress: Int,
    /** "Lands ~4 min" / "Departed ~12 min ago", or blank. */
    val eta: String
) {
    companion object {
        private val AIRLINE_CALLSIGN = Regex("^([A-Z]{3})\\d")

        fun from(plane: Aircraft, report: PlaneReport): BoardingPass {
            val callsign = plane.callsign?.trim()?.uppercase().orEmpty()
            val badge = AIRLINE_CALLSIGN.find(callsign)?.groupValues?.get(1).orEmpty()

            val origin = report.route?.origin
            val dest = report.route?.destination
            val lat = plane.lat
            val lon = plane.lon

            val progress = if (origin != null && dest != null && lat != null && lon != null) {
                RouteMath.progressPercent(lat, lon, origin, dest) ?: -1
            } else -1

            // Timing off a route we already doubt would be false precision.
            val eta = if (report.quality == RouteQuality.GOOD && progress >= 0 && lat != null && lon != null) {
                if (progress >= 50) {
                    dest?.let { RouteMath.minutesRemaining(lat, lon, it, plane.groundSpeedKts) }
                        ?.let { "Lands ~${duration(it)}" }
                } else {
                    origin?.let { RouteMath.minutesSinceDeparture(lat, lon, it, plane.groundSpeedKts) }
                        ?.let { "Departed ~${duration(it)} ago" }
                }
            } else null

            return BoardingPass(
                badge = badge,
                fromCode = origin?.let(::code).orEmpty(),
                fromCity = origin?.municipality.orEmpty(),
                toCode = dest?.let(::code).orEmpty(),
                toCity = dest?.municipality.orEmpty(),
                progress = progress,
                eta = eta.orEmpty()
            )
        }

        /** Three-letter IATA where known ("MAN"), else the ICAO code ("EGCC"). */
        private fun code(a: Airport): String = a.iata?.takeIf { it.isNotBlank() } ?: a.icao

        fun duration(minutes: Int): String =
            if (minutes < 60) "$minutes min"
            else "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
    }
}
