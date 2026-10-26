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
    // Flight times: shown time, scheduled time, pill text, and 1 late / 0 on time / -1 unknown.
    val DEP_TIME = stringPreferencesKey("dep_time")
    val DEP_SCHED = stringPreferencesKey("dep_sched")
    val DEP_PILL = stringPreferencesKey("dep_pill")
    val DEP_LATE = intPreferencesKey("dep_late")
    val DEP_DONE = booleanPreferencesKey("dep_done")
    val ARR_TIME = stringPreferencesKey("arr_time")
    val ARR_SCHED = stringPreferencesKey("arr_sched")
    val ARR_PILL = stringPreferencesKey("arr_pill")
    val ARR_LATE = intPreferencesKey("arr_late")
    val ARR_DONE = booleanPreferencesKey("arr_done")
    val HEX = stringPreferencesKey("hex")                // the plane on the tile, for the detail screen
    val LOGO = stringPreferencesKey("logo")              // "U2" when that logo is saved on the phone
}
