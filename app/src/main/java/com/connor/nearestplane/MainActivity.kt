package com.connor.nearestplane

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.connor.nearestplane.wx.AviationWeatherClient
import kotlinx.coroutines.launch

/**
 * Overhead's settings screen: where you are, what counts as overhead, the
 * flight-times key, and diagnostics. Light or dark with the phone, in the same
 * colours as the widget.
 */
class MainActivity : ComponentActivity() {

    // Held on the activity, not remembered in the composition: both are changed
    // from outside the app, in system Settings, and the screen has to pick that
    // up when the user comes back. Remembered state never would.
    private var granted by mutableStateOf(false)
    private var optimized by mutableStateOf(false)
    private var background by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        readSystemState()
        // Planes refresh only from the widget's button.
        RefreshWorker.cancelPeriodic(this)
        catchUpToWhereYouAre()
    }

    /**
     * Opening the app used to move the widgets to wherever you were. The plane
     * widget now changes only when you tap its arrow, so there is nothing to
     * catch up; the hook stays so the app has one place to do it if that changes.
     */
    private fun catchUpToWhereYouAre() {
        return
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readSystemState()
        RefreshWorker.cancelPeriodic(this)
        // The weather widget has been removed; stop any schedule an older build left.
        runCatching { WorkManager.getInstance(this).cancelUniqueWork(WEATHER_PERIODIC) }
        setContent {
            val palette = if (isSystemInDarkTheme()) Palette.Dark else Palette.Light
            CompositionLocalProvider(LocalPalette provides palette) { Screen() }
        }
    }

    private fun readSystemState() {
        granted = LocationSource.hasPermission(this)
        optimized = Diagnostics.isBatteryOptimized(this)
        background = LocationSource.hasBackgroundPermission(this)
    }

    // ---------------------------------------------------------------- screen

    @Composable
    private fun Screen() {
        val p = LocalPalette.current
        val activity = this
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current

        val diag by Diagnostics.report(activity, "plane")
            .collectAsState(initial = Diagnostics.Report(0, 0, "", 0))
        val filter by AppSettings.planeFilterFlow(activity)
            .collectAsState(initial = AppSettings.PlaneFilter())
        val mode by AppSettings.locationModeFlow(activity)
            .collectAsState(initial = LocationMode.DEVICE)
        val pin by AppSettings.pinFlow(activity).collectAsState(initial = null)
        val savedKey by AppSettings.timesKeyFlow(activity).collectAsState(initial = null)

        var code by remember { mutableStateOf("") }
        var pinBusy by remember { mutableStateOf(false) }
        var pinError by remember { mutableStateOf<String?>(null) }
        var keyText by remember { mutableStateOf("") }
        var diagOpen by remember { mutableStateOf(false) }
        var update by remember { mutableStateOf<Release?>(null) }

        // A sideloaded app has no update channel, so nothing would otherwise
        // ever mention that a new version exists. Failure is silent by design.
        LaunchedEffect(Unit) {
            update = runCatching { UpdateCheck.latest() }.getOrNull()
                ?.takeIf { UpdateCheck.isNewer(it.version, BuildConfig.VERSION_NAME) }
        }

        // Settings that change what is fetched: refresh once, as if you'd tapped the arrow.
        fun refetch() = RefreshWorker.refreshNow(activity)

        val requestPermissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            granted = LocationSource.hasPermission(activity)
            background = LocationSource.hasBackgroundPermission(activity)
        }
        fun askForLocation() = requestPermissions.launch(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        )

        fun pinAirport() = scope.launch {
            pinBusy = true
            pinError = null
            try {
                val found = AviationWeatherClient.airport(code)
                if (found == null) {
                    pinError = "No airport found for \"${code.trim().uppercase()}\". Try its four-letter code, like EGCC."
                } else {
                    AppSettings.savePin(activity, found.id, found.lat, found.lon)
                    code = ""
                }
            } catch (e: Exception) {
                pinError = "Couldn't look that up. Check your connection and try again."
            } finally {
                pinBusy = false
            }
        }

        fun pinHere() = scope.launch {
            pinBusy = true
            pinError = null
            try {
                val fix = LocationSource.deviceFix(activity, fresh = true)
                if (fix == null) pinError = "No position yet. Try again in a moment."
                else AppSettings.savePin(activity, "Pinned position", fix.lat, fix.lon)
            } catch (e: Exception) {
                pinError = "Couldn't read your position."
            } finally {
                pinBusy = false
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(p.ground)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // The app icon, built from its own layers: the launcher's
                    // adaptive icon can't be drawn directly in the app.
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF12161D)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.requiredSize(66.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Overhead", style = display(24.sp), color = p.ink)
                        Text("Version ${BuildConfig.VERSION_NAME}", style = body(12.sp), color = p.muted)
                    }
                }

                update?.let { available ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(p.accentFill)
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Version ${available.version} is ready", style = display(16.sp), color = p.onAccent)
                            Text("From your releases on GitHub", style = body(12.sp), color = p.onAccent.copy(alpha = 0.8f))
                        }
                        Pill("Download", fill = p.onAccent, text = p.accentFill) { openUrl(available.apkUrl) }
                    }
                }

                // Where you are
                Section("Where you are", "The widget looks for the plane nearest this point.") {
                    Segmented(
                        options = listOf("Follow my phone", "Fixed place"),
                        selected = if (mode == LocationMode.DEVICE) 0 else 1,
                        onSelect = { i ->
                            scope.launch {
                                AppSettings.setLocationMode(activity, if (i == 0) LocationMode.DEVICE else LocationMode.FIXED)
                            }
                        }
                    )
                    if (mode == LocationMode.DEVICE) {
                        Column {
                            Rule()
                            when {
                                granted && background -> Status(true, "Location allowed all the time", "Needed so a tap on the widget can find you")
                                granted -> Status(false, "Location only while the app is open", "Set it to Allow all the time, or widget taps can't find you", "Fix") { openAppSettings() }
                                else -> Status(false, "Location is off for Overhead", "Allow it, then choose Allow all the time", "Allow") { askForLocation() }
                            }
                            Rule()
                            if (optimized) {
                                Status(false, "Battery is restricted", "Refreshes may be slow to start", "Fix") { requestBatteryExemption() }
                            } else {
                                Status(true, "Battery unrestricted", "Refreshes start straight away")
                            }
                        }
                    } else {
                        val current = pin
                        Text(
                            if (current == null) "Nowhere pinned yet." else "Pinned to ${current.label}",
                            style = body(14.sp, FontWeight.Medium), color = p.ink
                        )
                        Field(
                            value = code,
                            onValueChange = { code = it; pinError = null },
                            placeholder = "Airport code, like EGCC",
                            capitalise = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Pin airport", fill = p.accentFill, text = p.onAccent, enabled = !pinBusy && code.isNotBlank()) { pinAirport() }
                            Pill("Pin where I am", fill = p.badge, text = p.ink, enabled = !pinBusy) {
                                if (granted) pinHere() else askForLocation()
                            }
                        }
                        pinError?.let { Text(it, style = body(12.sp, FontWeight.Medium), color = p.warn) }
                    }
                }

                // What counts as overhead
                Section("What counts as overhead", "A low ceiling skips airliners cruising miles up.") {
                    Choice(
                        label = "Range", unit = "nautical miles",
                        options = SearchRadius.entries.map { it.nm.toString() },
                        selected = SearchRadius.entries.indexOf(filter.radius),
                        onSelect = { i -> scope.launch { AppSettings.setRadius(activity, SearchRadius.entries[i]); refetch() } }
                    )
                    Choice(
                        label = "Ceiling", unit = "feet",
                        options = AltitudeCeiling.entries.map { c -> c.maxFt?.let { "${it / 1000}k" } ?: "Any" },
                        selected = AltitudeCeiling.entries.indexOf(filter.ceiling),
                        onSelect = { i -> scope.launch { AppSettings.setCeiling(activity, AltitudeCeiling.entries[i]); refetch() } }
                    )
                }

                // Flight times
                Section("Flight times", "Departure, arrival and delays. About 200 lookups a month on the free plan.") {
                    Text("AeroDataBox key", style = body(13.sp, FontWeight.SemiBold), color = p.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            Field(
                                value = keyText,
                                onValueChange = { keyText = it },
                                placeholder = if (savedKey != null) "Saved · ends ${savedKey!!.takeLast(4)}" else "Paste your key",
                                secret = true
                            )
                        }
                        Pill("Paste", fill = p.badge, text = p.ink) {
                            clipboard.getText()?.text?.trim()?.takeIf { it.isNotBlank() }?.let { keyText = it }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Save key", fill = p.accentFill, text = p.onAccent, enabled = keyText.isNotBlank()) {
                            scope.launch { AppSettings.setTimesKey(activity, keyText); keyText = "" }
                        }
                        if (savedKey != null) {
                            Pill("Remove", fill = p.badge, text = p.ink) {
                                scope.launch { AppSettings.clearTimesKey(activity) }
                            }
                        }
                    }
                    Text(
                        if (savedKey != null) "Key saved on this phone" else "No key yet. Times show as --:-- without one.",
                        style = body(12.sp, FontWeight.Medium),
                        color = if (savedKey != null) p.ok else p.muted
                    )
                    Text("Times by AeroDataBox", style = body(11.sp), color = p.muted)
                }

                // Diagnostics
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(p.card)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { diagOpen = !diagOpen }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Diagnostics", style = display(17.sp), color = p.ink)
                            val result = when {
                                diag.lastRun == 0L -> "No refreshes yet"
                                diag.lastOk >= diag.lastRun -> "Last refresh ${Diagnostics.timeAgo(diag.lastRun)} · worked"
                                else -> "Last refresh ${Diagnostics.timeAgo(diag.lastRun)} · failed"
                            }
                            Text(result, style = body(12.sp), color = p.muted)
                        }
                        Text(if (diagOpen) "−" else "+", style = display(20.sp), color = p.muted)
                    }
                    if (diagOpen) {
                        Column(
                            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Rule()
                            Detail("Last refresh", Diagnostics.timeAgo(diag.lastRun))
                            Detail("Last success", Diagnostics.timeAgo(diag.lastOk))
                            Detail("Position used", diag.lastPosition.ifBlank { "—" })
                            Detail(
                                "Last error",
                                if (diag.lastError.isBlank()) "None"
                                else "${Diagnostics.clockTime(diag.lastErrorAt)} · ${diag.lastError}"
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ------------------------------------------------------------ components

    @Composable
    private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
        val p = LocalPalette.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(p.card)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = display(17.sp), color = p.ink)
                Text(subtitle, style = body(12.sp), color = p.muted)
            }
            content()
        }
    }

    @Composable
    private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val p = LocalPalette.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(p.badge)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (on) p.card else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = body(14.sp, if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) p.ink else p.muted)
                }
            }
        }
    }

    @Composable
    private fun Choice(label: String, unit: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val p = LocalPalette.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(label, style = body(13.sp, FontWeight.SemiBold), color = p.ink, modifier = Modifier.weight(1f))
                Text(unit, style = body(13.sp), color = p.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { i, option ->
                    val on = i == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) p.accentFill else p.card)
                            .border(1.dp, if (on) p.accentFill else p.rule, RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { onSelect(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(option, style = display(15.sp), color = if (on) p.onAccent else p.ink)
                    }
                }
            }
        }
    }

    @Composable
    private fun Status(ok: Boolean, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
        val p = LocalPalette.current
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (ok) p.okFill else p.lateFill),
                contentAlignment = Alignment.Center
            ) {
                Text(if (ok) "✓" else "!", style = body(12.sp, FontWeight.Bold), color = if (ok) p.ok else p.late)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = body(14.sp, FontWeight.Medium), color = p.ink)
                Text(detail, style = body(12.sp), color = p.muted)
            }
            if (action != null) {
                Box(
                    modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(action, style = body(14.sp, FontWeight.SemiBold), color = p.accentText)
                }
            }
        }
    }

    @Composable
    private fun Pill(label: String, fill: Color, text: Color, enabled: Boolean = true, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (enabled) fill else fill.copy(alpha = 0.4f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, style = body(14.sp, FontWeight.SemiBold), color = if (enabled) text else text.copy(alpha = 0.6f))
        }
    }

    @Composable
    private fun Field(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        secret: Boolean = false,
        capitalise: Boolean = false
    ) {
        val p = LocalPalette.current
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = body(14.sp).copy(color = p.ink),
            cursorBrush = SolidColor(p.accentText),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = if (capitalise) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                imeAction = ImeAction.Done
            ),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.ground)
                        .border(1.dp, p.rule, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) Text(placeholder, style = body(14.sp), color = p.muted)
                    inner()
                }
            }
        )
    }

    @Composable
    private fun Rule() {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.rule))
    }

    @Composable
    private fun Detail(label: String, value: String) {
        val p = LocalPalette.current
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = body(12.sp), color = p.muted, modifier = Modifier.width(104.dp))
            Text(value, style = body(13.sp), color = p.ink, modifier = Modifier.weight(1f))
        }
    }

    // ---------------------------------------------------------------- system

    /** Opens the system dialog that exempts this app from battery limits. */
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            // Some phone makers block the direct request; fall back to the list.
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private companion object {
        const val WEATHER_PERIODIC = "nearest-wx-periodic"
    }
}
