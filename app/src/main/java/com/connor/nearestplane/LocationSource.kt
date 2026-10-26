package com.connor.nearestplane

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Which tier a position came from, best first. */
enum class PositionSource { FIXED, LIVE, LAST_KNOWN, CACHED }

data class Position(
    val lat: Double,
    val lon: Double,
    val source: PositionSource,
    val ageMinutes: Long?
) {
    /**
     * A short note for the tile when the position is old enough to matter, or
     * null when it isn't worth the space.
     *
     * This is the failure mode the three-tier fallback quietly introduced. A
     * tile reading "3.2 nm NNE" off an hour-old position isn't wrong about the
     * aircraft — it's wrong about *you*, and that's the harder error to spot,
     * because everything on the tile still looks live.
     */
    val staleNote: String?
        get() {
            // A pinned place isn't a fix and can't go stale.
            if (source == PositionSource.FIXED) return null
            val age = ageMinutes
                ?: return if (source == PositionSource.LIVE) null else "old location"
            if (age < 20) return null
            return "location ${ageText(age)} old"
        }

    /**
     * Where this position came from, for Diagnostics: the answer to "is it
     * actually updating my location?" without a debugger. A tile stuck at a
     * place you left reads here as "this app's last saved location, 26h old",
     * with coordinates rounded to about a kilometre so the place is recognisable.
     */
    val describe: String
        get() = buildString {
            append(
                when (source) {
                    PositionSource.FIXED -> "pinned place"
                    PositionSource.LIVE -> "live fix"
                    PositionSource.LAST_KNOWN -> "the phone's last known location"
                    PositionSource.CACHED -> "this app's last saved location"
                }
            )
            val age = ageMinutes
            if (source != PositionSource.FIXED && age != null && age >= 1) {
                append(", ${ageText(age)} old")
            }
            append(" (%.2f, %.2f)".format(Locale.US, lat, lon))
        }
}

/** "45m", "26h", "3d": minutes until two hours, hours until two days. */
internal fun ageText(minutes: Long): String = when {
    minutes < 120 -> "${minutes}m"
    minutes < 48 * 60 -> "${minutes / 60}h"
    else -> "${minutes / (24 * 60)}d"
}

/**
 * Whether a fresh fix is far enough from the last saved one that both tiles
 * are describing somewhere you no longer are. Two nautical miles clears GPS
 * jitter at home by a wide margin and is well inside the spacing of reporting
 * stations, so a real move always counts and standing still never does. With
 * nothing saved yet, any fix is news.
 */
internal fun movedFar(from: CachedPosition?, toLat: Double, toLon: Double): Boolean {
    if (from == null) return true
    return RouteMath.nmBetween(from.lat, from.lon, toLat, toLon) >= 2.0
}

object LocationSource {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether the position is available to work that runs with the app closed,
     * which is every refresh the widgets do, the refresh buttons included.
     *
     * "While using the app" is foreground-only. In the background Android
     * withholds both the live fix and the phone's last known location, so every
     * tier but this app's own saved fix comes back empty, and the tiles stay
     * wherever the app was last open. That is not a failure anything reports:
     * the refresh succeeds, against the wrong place. Before Android 10 a
     * foreground grant covered the background too.
     */
    fun hasBackgroundPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether the app can produce a position at all. A pinned place counts, and
     * needs no permission — which is the entire point of fixed mode.
     */
    suspend fun isReady(context: Context): Boolean =
        AppSettings.fixedLocation(context) != null || hasPermission(context)

    /**
     * The position to work from: a pinned place if there is one, otherwise the
     * device's own, through a three-tier fallback.
     */
    suspend fun current(context: Context, fresh: Boolean = false): Position? {
        AppSettings.fixedLocation(context)?.let {
            return Position(it.lat, it.lon, PositionSource.FIXED, null)
        }
        return deviceFix(context, fresh)
    }

    /**
     * The device's own position, ignoring any pin. Settings calls this directly
     * so "pin my current location" still works while a stale pin is in effect.
     *
     * Three tiers, in order: a current fix, the system's last known position,
     * then our own cached position from a previous successful run.
     *
     * The third tier is what removes the "no GPS" state. In Doze the fused
     * provider frequently returns null for both live tiers, and there is no
     * reason to blank a widget over that — your position an hour ago is a
     * perfectly good basis for "what's the nearest airport" and close enough
     * for "what's overhead".
     *
     * Every tier reports its own age, so the widget can say when it's working
     * from something old rather than presenting it as a live reading.
     */
    @SuppressLint("MissingPermission")
    suspend fun deviceFix(context: Context, fresh: Boolean = false): Position? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)

        // Fresh: the refresh button. You tapped it because something is overhead
        // right now, wherever you are, so a fix from ten minutes ago won't do.
        val request = CurrentLocationRequest.Builder()
            .setPriority(if (fresh) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(if (fresh) 30_000L else 10 * 60 * 1000L)
            .setDurationMillis(if (fresh) 15_000L else 10_000L)
            .build()

        await { client.getCurrentLocation(request, null) }?.let {
            return remember(context, it, PositionSource.LIVE)
        }

        // lastLocation has no age bound at all — it can be days old, which is
        // exactly why the age travels with the position from here on.
        await { client.lastLocation }?.let {
            return remember(context, it, PositionSource.LAST_KNOWN)
        }

        return AppSettings.lastPosition(context)?.let { cached ->
            Position(cached.lat, cached.lon, PositionSource.CACHED, cached.ageMinutes)
        }
    }

    private suspend fun await(start: () -> Task<Location>): Location? = runCatching {
        suspendCancellableCoroutine { cont ->
            start()
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
                // Without this a cancelled Task resumes nothing, and the
                // refresh hangs until WorkManager kills it ten minutes later.
                .addOnCanceledListener { cont.resume(null) }
        }
    }.getOrNull()

    private suspend fun remember(
        context: Context,
        location: Location,
        source: PositionSource
    ): Position {
        AppSettings.saveLastPosition(context, location.latitude, location.longitude, location.time)
        return Position(location.latitude, location.longitude, source, ageOf(location.time))
    }

    /** Location.time is the epoch millis of the fix itself, not of this read. */
    private fun ageOf(fixTimeMs: Long): Long? {
        if (fixTimeMs <= 0L) return null
        return ((System.currentTimeMillis() - fixTimeMs) / 60_000L).coerceAtLeast(0L)
    }
}
