package com.connor.nearestplane

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.action.ActionParameters
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The plane on the widget, in full. It opens on that plane, wherever it is now,
 * rather than whatever happens to be nearest; its refresh updates that same
 * plane and the widget with it. Light or dark with the phone.
 */
class PlaneDetailActivity : ComponentActivity() {

    private sealed interface State {
        data object Loading : State
        data class Shown(val report: PlaneReport, val positionNote: String?, val wx: Map<String, AirportWx> = emptyMap()) : State
        data class Gone(val label: String) : State
        data class Problem(val message: String) : State
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hex = intent.getStringExtra(HEX_PARAM.name).orEmpty()
        setContent {
            val palette = if (isSystemInDarkTheme()) Palette.Dark else Palette.Light
            CompositionLocalProvider(LocalPalette provides palette) { Screen(hex) }
        }
    }

    @Composable
    private fun Screen(startHex: String) {
        val p = LocalPalette.current
        val scope = rememberCoroutineScope()
        var state by remember { mutableStateOf<State>(State.Loading) }
        var hex by remember { mutableStateOf(startHex) }
        var updatedAt by remember { mutableLongStateOf(0L) }
        var busy by remember { mutableStateOf(false) }

        /** [byYou]: you tapped refresh here, so get a fresh fix and update the widget too. */
        suspend fun load(byYou: Boolean) {
            busy = true
            try {
                val ctx = this@PlaneDetailActivity
                if (!LocationSource.isReady(ctx)) {
                    state = State.Problem("No location set. Open Overhead's settings to allow location or pin a place.")
                    return
                }
                val fix = LocationSource.current(ctx, fresh = byYou)
                if (fix == null) {
                    state = State.Problem("No position yet. Try again in a moment.")
                    return
                }
                val plane = if (hex.isNotBlank()) {
                    AdsbClient.byHex(hex, fix.lat, fix.lon)
                } else {
                    val filter = AppSettings.planeFilter(ctx)
                    AdsbClient.nearest(fix.lat, fix.lon, filter.radius.nm, maxAltitudeFt = filter.ceiling.maxFt)
                }
                if (plane == null) {
                    val last = (state as? State.Shown)?.report?.plane?.label
                    state = if (hex.isNotBlank()) State.Gone(last ?: hex.uppercase(Locale.US))
                    else State.Problem("Nothing overhead right now. Try a wider range in settings.")
                    return
                }
                hex = plane.hex
                // The saved AeroDataBox answer, if any, so this matches the widget without a lookup.
                val report = FlightTimes.merge(PlaneSummary.build(ctx, plane), FlightTimes.cached(ctx, plane))
                val wx = runCatching {
                    AirportWeather.forAirports(ctx, report.route?.origin, report.route?.destination)
                }.getOrDefault(emptyMap())
                state = State.Shown(report, fix.staleNote, wx)
                updatedAt = System.currentTimeMillis()
                // Refreshing here updates the widget too, so the two never disagree.
                if (byYou) RefreshWorker.showPlane(ctx, plane, report, fix)
            } catch (e: Exception) {
                state = State.Problem("Couldn't reach the flight data. Check your connection and try again.")
            } finally {
                busy = false
            }
        }

        LaunchedEffect(Unit) { load(byYou = false) }

        Box(modifier = Modifier.fillMaxSize().background(p.ground)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top bar
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundButton("‹", "Back") { finish() }
                    Text(
                        when {
                            busy -> "Updating…"
                            updatedAt == 0L -> ""
                            else -> "Updated ${Diagnostics.timeAgo(updatedAt)}"
                        },
                        style = body(13.sp), color = p.muted, modifier = Modifier.weight(1f)
                    )
                    RoundButton("↻", "Refresh this plane", enabled = !busy) { scope.launch { load(byYou = true) } }
                }

                when (val s = state) {
                    State.Loading -> Card { Text("Finding the plane…", style = body(14.sp), color = p.muted) }
                    is State.Problem -> Card { Text(s.message, style = body(14.sp), color = p.ink) }
                    is State.Gone -> Card {
                        Text(s.label, style = display(24.sp), color = p.ink)
                        Text(
                            "Isn't broadcasting right now. It may have landed or flown out of range.",
                            style = body(14.sp), color = p.muted
                        )
                    }
                    is State.Shown -> Shown(s.report, s.positionNote, s.wx)
                }
            }
        }
    }

    @Composable
    private fun Shown(r: PlaneReport, positionNote: String?, wx: Map<String, AirportWx>) {
        val p = LocalPalette.current
        val plane = r.plane
        val pass = BoardingPass.from(plane, r)
        val logo = r.route?.airlineIata?.let { AirlineLogos.bitmap(this, it) }

        // Boarding pass
        Card(padding = 20) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.badge),
                    contentAlignment = Alignment.Center
                ) {
                    if (logo != null) {
                        Image(logo.asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp))
                    } else if (pass.badge.isNotBlank()) {
                        Text(pass.badge, style = display(12.sp), color = p.ink)
                    } else {
                        Image(
                            painterResource(R.drawable.ic_plane), contentDescription = null,
                            modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(p.muted)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(plane.label, style = display(24.sp), color = p.ink)
                    val sub = listOf(r.operator, r.typeName.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = body(13.sp), color = p.muted)
                    FlightTimes.cached(this@PlaneDetailActivity, plane)?.flightNumber?.let {
                        Text("Flight $it", style = body(13.sp, FontWeight.Medium), color = p.ink)
                    }
                }
            }
            plane.emergency?.let {
                Text(
                    it, style = body(13.sp, FontWeight.Bold), color = p.warn,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(p.lateFill).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(pass.fromCode.ifBlank { "-" }, style = display(40.sp), color = p.ink)
                    Text(pass.fromCity.ifBlank { "-" }, style = body(12.sp), color = p.muted)
                    r.route?.origin?.let { wx[it.icao.uppercase()] }?.let { WeatherLine(it) }
                }
                RoutePath(pass.progress, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(pass.toCode.ifBlank { "-" }, style = display(40.sp), color = p.ink)
                    Text(pass.toCity.ifBlank { "-" }, style = body(12.sp), color = p.muted)
                    r.route?.destination?.let { wx[it.icao.uppercase()] }?.let { WeatherLine(it) }
                }
            }

            Rule()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val times = FlightTimes.cached(this@PlaneDetailActivity, plane)
                FlightEnd(if (times?.departure?.actual == true) "Departed" else "Departs", times?.departure, Alignment.Start)
                TimeBlock(
                    "Flown",
                    if (pass.progress >= 0) "${pass.progress}%" else "-",
                    pass.eta.ifBlank { " " },
                    Alignment.CenterHorizontally
                )
                FlightEnd(if (times?.arrival?.actual == true) "Landed" else "Arrives", times?.arrival, Alignment.End)
            }
        }

        // Right now
        Card {
            Text("Right now", style = display(17.sp), color = p.ink)
            Grid(
                "From you" to plane.positionLine.ifBlank { "-" },
                "Altitude" to (if (plane.onGround) "On the ground" else plane.altitudeFt?.let { "%,d ft".format(Locale.UK, it) } ?: "-"),
                "Ground speed" to (plane.groundSpeedKts?.let { "${it.toInt()} kt" } ?: "-"),
                "Heading" to (plane.trackDeg?.let { "%03d° %s".format(Locale.UK, it.toInt(), compass(it)) } ?: "-"),
                "Squawk" to (plane.squawk ?: "-"),
                "Vertical" to vertical(plane.verticalRateFpm)
            )
            positionNote?.let { Text("Measured from an old position of yours: $it.", style = body(12.sp), color = p.muted) }
        }

        // Aircraft
        Card {
            Text("Aircraft", style = display(17.sp), color = p.ink)
            Grid(
                "Registration" to (r.info?.registration ?: plane.registration ?: "-"),
                "Type code" to (r.info?.icaoType ?: plane.type ?: "-"),
                "Operator" to (r.info?.owner ?: r.operator.ifBlank { "-" }),
                "Mode-S hex" to plane.hex.uppercase(Locale.US)
            )
        }

        PlaneSummary.qualityNote(r.quality, r.routeLine.isNotBlank())?.let {
            Text(
                it, style = body(13.sp), color = p.late,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.lateFill).padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(p.card)
                .clickable(role = Role.Button) { openMap(plane.hex) }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Open on the live map", style = body(15.sp, FontWeight.SemiBold), color = p.ink, modifier = Modifier.weight(1f))
            Text("↗", style = body(16.sp), color = p.muted)
        }

        Text(
            "Position from adsb.fi · aircraft and route from adsbdb · times by AeroDataBox · weather from aviationweather.gov",
            style = body(11.sp), color = p.muted, modifier = Modifier.padding(horizontal = 4.dp)
        )
    }

    // ------------------------------------------------------------ components

    @Composable
    private fun Card(padding: Int = 18, content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(LocalPalette.current.card)
                .padding(padding.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) { content() }
    }

    @Composable
    private fun RoundButton(glyph: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
        val p = LocalPalette.current
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(p.badge)
                .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, style = body(20.sp, FontWeight.SemiBold), color = if (enabled) p.ink else p.muted)
        }
    }

    /** The widget's path: dashed track, solid line behind the plane, plane at the flight's progress. */
    @Composable
    private fun RoutePath(progress: Int, modifier: Modifier = Modifier) {
        val p = LocalPalette.current
        BoxWithConstraints(modifier = modifier.height(24.dp)) {
            val known = progress >= 0
            val fraction = if (known) progress.coerceIn(0, 100) / 100f else 0.5f
            val planeSize = 22.dp
            val x = (maxWidth * fraction).coerceIn(planeSize / 2, maxWidth - planeSize / 2)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cy = size.height / 2
                val px = x.toPx()
                val gap = planeSize.toPx() / 2 + 2.dp.toPx()
                drawLine(
                    p.track, Offset(px + gap, cy), Offset(size.width, cy), strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                )
                if (known) drawLine(p.accentFill, Offset(0f, cy), Offset(px - gap, cy), strokeWidth = 2.dp.toPx())
                else drawLine(
                    p.track, Offset(0f, cy), Offset(px - gap, cy), strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                )
            }
            Image(
                painterResource(R.drawable.ic_plane),
                contentDescription = if (known) "$progress% of the way" else "Route unknown",
                colorFilter = ColorFilter.tint(p.accentFill),
                modifier = Modifier.size(planeSize).offset(x = x - planeSize / 2, y = 1.dp)
            )
        }
    }

    @Composable
    private fun TimeBlock(
        label: String,
        value: String,
        note: String,
        align: Alignment.Horizontal,
        colour: androidx.compose.ui.graphics.Color? = null
    ) {
        val p = LocalPalette.current
        Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label.uppercase(Locale.UK), style = body(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp), color = p.muted)
            Text(value, style = display(22.sp), color = colour ?: p.ink)
            Text(note, style = body(11.sp), color = p.muted)
        }
    }

    /** Green within 15 minutes of schedule, amber when later, --:-- when there's no schedule. */
    @Composable
    private fun FlightEnd(label: String, t: FlightTime?, align: Alignment.Horizontal) {
        val p = LocalPalette.current
        if (t == null) {
            TimeBlock(label, "--:--", "No schedule", align, p.muted)
            return
        }
        val colour = when {
            t.delayMin == null -> p.ink
            t.late -> p.late
            else -> p.ok
        }
        val note = listOf(
            t.scheduled.takeIf { it.isNotBlank() }?.let { "Sched $it" },
            t.pill.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString(" · ").ifBlank { " " }
        TimeBlock(label, t.shown, note, align, colour)
    }

    @Composable
    private fun Grid(vararg pairs: Pair<String, String>) {
        val p = LocalPalette.current
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            pairs.toList().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { (label, value) ->
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = body(11.sp), color = p.muted)
                            Text(value, style = body(16.sp, FontWeight.SemiBold), color = p.ink)
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun WeatherLine(w: AirportWx) {
        val p = LocalPalette.current
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Image(
                painterResource(w.icon.drawable), contentDescription = null,
                modifier = Modifier.size(14.dp),
                colorFilter = ColorFilter.tint(if (w.icon == WxIcon.SUN || w.icon == WxIcon.PARTLY) p.late else p.muted)
            )
            Text(w.temp, style = body(12.sp, FontWeight.SemiBold), color = p.ink)
        }
    }

    @Composable
    private fun Rule() {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.rule))
    }

    private fun vertical(fpm: Int?): String = when {
        fpm == null -> "-"
        fpm > 300 -> "Climbing"
        fpm < -300 -> "Descending"
        else -> "Level"
    }

    /** adsb.fi's own map, the same feed the widget reads. */
    private fun openMap(hex: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://globe.adsb.fi/?icao=${hex.trim().lowercase(Locale.US)}")))
        }
    }

    companion object {
        /** The plane on the widget, passed in when you tap it. */
        val HEX_PARAM = ActionParameters.Key<String>("hex")
    }
}
