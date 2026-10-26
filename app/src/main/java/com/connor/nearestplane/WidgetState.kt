package com.connor.nearestplane

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The worker writes these; the widget reads them. Glance persists the values
 * per widget instance, so tiles survive reboots and process death.
 */
object WidgetState {
    val TITLE = stringPreferencesKey("title")            // callsign / tail
    val AIRLINE = stringPreferencesKey("airline")
    val TYPE_NAME = stringPreferencesKey("type_name")    // "Boeing 747-8"
    val ROUTE = stringPreferencesKey("route")            // "KEWR → KLAX"
    val TIMING = stringPreferencesKey("timing")          // "~1h20m out · 2h14m to go"
    val POSITION = stringPreferencesKey("position")      // "3.2 nm NNE"
    val DETAIL = stringPreferencesKey("detail")          // "FL350 · 452 kts"
    val EMERGENCY = stringPreferencesKey("emergency")
    val STATUS = stringPreferencesKey("status")          // ok | empty | no_permission | error
    val UPDATED_AT = longPreferencesKey("updated_at")
    val STALE_NOTE = stringPreferencesKey("stale_note")  // set when a refresh failed
    // A pinned place never goes stale, so it never earns a note — which made a
    // forgotten pin look exactly like a tile that had stopped following you.
    val PINNED = booleanPreferencesKey("pinned")

    // Boarding-pass layout: the route as two airport codes with the plane on a
    // path between them. PROGRESS is 0..100, or -1 when there is no usable route.
    val BADGE = stringPreferencesKey("badge")            // "EZY", shown where the logo goes
    val FROM_CODE = stringPreferencesKey("from_code")    // "AGP"
    val FROM_CITY = stringPreferencesKey("from_city")    // "Málaga"
    val TO_CODE = stringPreferencesKey("to_code")
    val TO_CITY = stringPreferencesKey("to_city")
    val PROGRESS = intPreferencesKey("progress")
    val ETA = stringPreferencesKey("eta")                // "Lands ~4 min"
}
