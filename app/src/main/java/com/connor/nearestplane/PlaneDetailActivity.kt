package com.connor.nearestplane

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Opened by tapping the plane tile. Fetches fresh rather than reading the
 * widget's cached state — if you've bothered to open it, you want current data.
 *
 * The tile is one line of everything; this is the room to say what the tile is
 * hedging about, which mostly means the route.
 */
class PlaneDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var report by remember { mutableStateOf<PlaneReport?>(null) }
        var positionNote by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        suspend fun load() {
            loading = true
            error = null
            try {
                if (!LocationSource.isReady(this@PlaneDetailActivity)) {
                    error = "No location set. Grant location access, or pin a fixed " +
                        "place, in the main app screen."
                    return
                }
                val fix = LocationSource.current(this@PlaneDetailActivity)
                if (fix == null) {
                    error = "No position yet. Step outside, or try again in a moment."
                    return
                }
                positionNote = fix.staleNote

                val filter = AppSettings.planeFilter(this@PlaneDetailActivity)
                val plane = AdsbClient.nearest(
                    lat = fix.lat,
                    lon = fix.lon,
                    radiusNm = filter.radius.nm,
                    maxAltitudeFt = filter.ceiling.maxFt,
                    station = AppSettings.station(this@PlaneDetailActivity)
                )
                if (plane == null) {
                    error = buildString {
                        append("Nothing flying within ${filter.radius.nm} nm")
                        filter.ceiling.maxFt?.let { append(" ${filter.ceiling.label.lowercase()}") }
                        append(". Widen the search in the main app screen.")
                    }
                    return
                }
                report = PlaneSummary.build(this@PlaneDetailActivity, plane)
            } catch (e: Exception) {
                error = e.message ?: "Request failed."
            } finally {
                loading = false
            }
        }

        LaunchedEffect(Unit) { load() }

        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val message = error
                val r = report
                when {
                    loading -> CircularProgressIndicator()
                    message != null -> {
                        Text(message, style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = { scope.launch { load() } }) { Text("Try again") }
                    }
                    r == null -> {
                        Text("No aircraft loaded.", style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = { scope.launch { load() } }) { Text("Try again") }
                    }
                    else -> Detail(r, positionNote) { scope.launch { load() } }
                }
            }
        }
    }

    @Composable
    private fun Detail(r: PlaneReport, positionNote: String?, onRefresh: () -> Unit) {
        val plane = r.plane

        Text(
            plane.label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        plane.emergency?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828)
            )
        }
        if (r.operator.isNotBlank()) {
            Text(r.operator, style = MaterialTheme.typography.bodyLarge)
        }

        Section("Aircraft") {
            Rows(
                "Type" to r.typeName,
                "ICAO type" to (r.info?.icaoType ?: plane.type),
                "Registration" to (r.info?.registration ?: plane.registration),
                "Owner" to r.info?.owner,
                "Mode-S hex" to plane.hex.uppercase()
            )
        }

        Section("Position") {
            Rows(
                "Distance" to plane.positionLine.ifBlank { null },
                "Altitude" to plane.altitudeFt?.let { "${"%,d".format(Locale.US, it)} ft" },
                "Ground speed" to plane.groundSpeedKts?.let { "${it.toInt()} kts" },
                "Track" to plane.trackDeg?.let { "${it.toInt()}° (${compass(it)})" },
                "Squawk" to plane.squawk,
                "On ground" to if (plane.onGround) "yes" else null
            )
            positionNote?.let {
                Text(
                    "Measured from an old position of yours — $it.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Section("Route") {
            if (r.routeLine.isBlank()) {
                Text(
                    "No route. adsbdb has none for this callsign — normal for GA and " +
                        "military — or the aircraft is broadcasting no callsign at all.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    r.routeLine,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Rows(
                    "From" to r.route?.origin?.let { airportLine(it) },
                    "To" to r.route?.destination?.let { airportLine(it) },
                    "Estimates" to r.timingLine.ifBlank { null }
                )
                if (r.timingLine.isNotBlank()) {
                    Text(
                        "Times are geometry, not schedule: distance over current ground " +
                            "speed, blind to routing, holds and wind. The percentage is " +
                            "the most trustworthy of the three.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            PlaneSummary.qualityNote(r.quality, r.routeLine.isNotBlank())?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            OutlinedButton(onClick = { openGlobe(plane.hex) }) { Text("View on airplanes.live") }
        }
    }

    private fun airportLine(a: Airport): String =
        listOfNotNull(
            a.display.ifBlank { null },
            a.municipality?.takeIf { it.isNotBlank() }
        ).joinToString(" — ")

    /** The same feed the tile reads, on a map, for when the tile isn't enough. */
    private fun openGlobe(hex: String) {
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://globe.airplanes.live/?icao=${hex.trim().lowercase()}")
                )
            )
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) { content() }
            }
        }
    }

    /** Label/value pairs, skipping every pair whose value we don't have. */
    @Composable
    private fun Rows(vararg pairs: Pair<String, String?>) {
        pairs.forEach { (label, value) ->
            if (!value.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth(0.38f)
                    )
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
