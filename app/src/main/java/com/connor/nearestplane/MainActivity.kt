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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.connor.nearestplane.wx.AviationWeatherClient
import com.connor.nearestplane.wx.WeatherWidget
import com.connor.nearestplane.wx.WxRefreshWorker
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The app is a setup screen — the widgets are the product.
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
        // Planes refresh only from the widget's button; weather keeps its schedule.
        RefreshWorker.cancelPeriodic(this)
        WxRefreshWorker.schedulePeriodic(this)
        catchUpToWhereYouAre()
    }

    /**
     * The one moment the app is certain to be given your location: now, while
     * it is open. Every widget refresh runs in the background, where Android
     * withholds location unless it is set to "Allow all the time" — so without
     * this, a tile followed you only as far as wherever the app was last
     * opened, and opening it again did not move it either.
     *
     * Asking costs one location request. Refreshing costs a fetch per tile, so
     * that only happens when you have actually moved.
     */
    private fun catchUpToWhereYouAre() {
        if (!LocationSource.hasPermission(this)) return
        lifecycleScope.launch {
            val context = this@MainActivity
            // A pin is a choice to stay put, not a position to correct.
            if (AppSettings.fixedLocation(context) != null) return@launch
            val before = AppSettings.lastPosition(context)
            val now = LocationSource.deviceFix(context) ?: return@launch
            // Our own saved fix is what we'd be comparing against; it tells
            // us nothing new about where you are.
            if (now.source == PositionSource.CACHED) return@launch
            // Weather only: the plane tile keeps the last plane until you tap refresh.
            if (movedFar(before, now.lat, now.lon)) {
                WxRefreshWorker.refreshNow(context)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readSystemState()
        RefreshWorker.cancelPeriodic(this)
        WxRefreshWorker.schedulePeriodic(this)
        setContent {
            // Follows the phone: dark screens in dark mode, light otherwise.
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Screen() }
        }
    }

    private fun readSystemState() {
        granted = LocationSource.hasPermission(this)
        optimized = Diagnostics.isBatteryOptimized(this)
        background = LocationSource.hasBackgroundPermission(this)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val activity = this
        val scope = rememberCoroutineScope()
        val planeDiag by Diagnostics.report(activity, "plane")
            .collectAsState(initial = Diagnostics.Report(0, 0, "", 0))
        val wxDiag by Diagnostics.report(activity, "weather")
            .collectAsState(initial = Diagnostics.Report(0, 0, "", 0))
        val look by AppSettings.appearanceFlow(activity)
            .collectAsState(initial = AppSettings.Appearance())
        val filter by AppSettings.planeFilterFlow(activity)
            .collectAsState(initial = AppSettings.PlaneFilter())
        val mode by AppSettings.locationModeFlow(activity)
            .collectAsState(initial = LocationMode.DEVICE)
        val pin by AppSettings.pinFlow(activity).collectAsState(initial = null)
        val station by AppSettings.stationFlow(activity).collectAsState(initial = null)
        val preferForecast by AppSettings.preferForecastFlow(activity).collectAsState(initial = false)
        var stationUrl by remember { mutableStateOf("") }
        var stationToken by remember { mutableStateOf("") }
        var stationError by remember { mutableStateOf<String?>(null) }
        // Show the saved address once it loads, without fighting the user's typing.
        LaunchedEffect(station?.url) { station?.url?.let { if (stationUrl.isBlank()) stationUrl = it } }

        var code by remember { mutableStateOf("") }
        var pinBusy by remember { mutableStateOf(false) }
        var pinError by remember { mutableStateOf<String?>(null) }

        var update by remember { mutableStateOf<Release?>(null) }
        var updateChecked by remember { mutableStateOf(false) }

        // A sideloaded app has no update channel, so nothing would otherwise
        // ever mention that a new version exists. Failure here is silent by
        // design: not knowing about an update is not worth an error message.
        suspend fun checkForUpdate() {
            update = runCatching { UpdateCheck.latest() }.getOrNull()
                ?.takeIf { UpdateCheck.isNewer(it.version, BuildConfig.VERSION_NAME) }
            updateChecked = true
        }

        LaunchedEffect(Unit) { checkForUpdate() }

        fun redrawWidgets() = scope.launch {
            NearestPlaneWidget().updateAll(activity)
            WeatherWidget().updateAll(activity)
        }

        // Settings that change *what is fetched* need a refetch, not a redraw.
        fun refetch() {
            RefreshWorker.refreshNow(activity)
            WxRefreshWorker.refreshNow(activity)
        }

        val requestPermissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            granted = LocationSource.hasPermission(activity)
            if (granted) refetch()
        }

        fun pinAirport() = scope.launch {
            pinBusy = true
            pinError = null
            try {
                val found = AviationWeatherClient.airport(code)
                if (found == null) {
                    pinError = "No airport found for \"${code.trim().uppercase()}\"."
                } else {
                    AppSettings.savePin(activity, found.id, found.lat, found.lon)
                    code = ""
                    refetch()
                }
            } catch (e: Exception) {
                pinError = e.message ?: "Lookup failed."
            } finally {
                pinBusy = false
            }
        }

        fun pinHere() = scope.launch {
            pinBusy = true
            pinError = null
            try {
                // deviceFix, not current: a pin is already in effect, and asking
                // for "the current position" would just hand back the pin.
                val fix = LocationSource.deviceFix(activity)
                if (fix == null) {
                    pinError = "No position available yet. Try again in a moment."
                } else {
                    AppSettings.savePin(activity, "Pinned position", fix.lat, fix.lon)
                    refetch()
                }
            } catch (e: Exception) {
                pinError = e.message ?: "Couldn't read your position."
            } finally {
                pinBusy = false
            }
        }

        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text("Nearest Plane", style = MaterialTheme.typography.headlineMedium)

                update?.let { available ->
                    SectionCard("Version ${available.version} is available") {
                        Text(
                            "You have ${BuildConfig.VERSION_NAME}. Downloading opens your " +
                                "browser; tap the file when it lands to install over the top.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                openUrl(available.apkUrl)
                            }) { Text("Download") }
                            OutlinedButton(onClick = { openUrl(available.pageUrl) }) {
                                Text("What changed")
                            }
                        }
                    }
                }

                SectionCard("Location") {
                    Text(
                        "Both widgets need a position to work out what's closest.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LocationMode.entries.forEach { option ->
                        WideOption(
                            title = option.label,
                            subtitle = option.blurb,
                            selected = mode == option,
                            onSelect = {
                                scope.launch {
                                    AppSettings.setLocationMode(activity, option)
                                    refetch()
                                }
                            }
                        )
                    }

                    HorizontalDivider()

                    if (mode == LocationMode.DEVICE) {
                        if (granted && background) {
                            Text("Location is allowed all the time. The widgets follow you.")
                        } else if (granted) {
                            // Checked, not assumed. This used to give the same
                            // advice whether or not it had been taken, so there
                            // was no way to tell which state you were in.
                            Text(
                                "Location is allowed only while the app is open.",
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "The widgets refresh with the app closed, and Android won't " +
                                    "tell them where you are then. They stay wherever the app " +
                                    "was last open, and the refresh buttons can't move them " +
                                    "either. Set location to \"Allow all the time\" in app " +
                                    "settings; Android won't let an app ask for that from a dialog.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = { openAppSettings() }) {
                                Text("Open app settings")
                            }
                            Text(
                                "Until then, opening this screen catches the widgets up to " +
                                    "where you are.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text("This mode needs location access.")
                            Button(onClick = {
                                requestPermissions.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                )
                            }) { Text("Grant location access") }
                        }
                    } else {
                        val current = pin
                        if (current == null) {
                            Text("Nowhere pinned yet — the widgets have nothing to work from.")
                        } else {
                            Text("Pinned to ${current.label}", fontWeight = FontWeight.Medium)
                            Text(
                                "%.4f, %.4f".format(Locale.US, current.lat, current.lon),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it; pinError = null },
                            label = { Text("Airport code") },
                            placeholder = { Text("KJFK, EGLL, YSSY…") },
                            singleLine = true,
                            enabled = !pinBusy,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { pinAirport() },
                                enabled = !pinBusy && code.isNotBlank()
                            ) { Text("Pin airport") }
                            OutlinedButton(
                                onClick = {
                                    if (granted) {
                                        pinHere()
                                    } else {
                                        requestPermissions.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                                Manifest.permission.ACCESS_FINE_LOCATION
                                            )
                                        )
                                    }
                                },
                                enabled = !pinBusy
                            ) { Text("Pin where I am") }
                        }
                        pinError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            "\"Pin where I am\" needs location access once, and then never " +
                                "again — no background permission, and nothing to grant in " +
                                "Settings by hand.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                SectionCard("Plane search") {
                    Text(
                        "Applies to the Nearest Plane tile.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Range", fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchRadius.entries.forEach { option ->
                            FilterChip(
                                selected = filter.radius == option,
                                onClick = {
                                    scope.launch {
                                        AppSettings.setRadius(activity, option)
                                        refetch()
                                    }
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Text("Ceiling", fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AltitudeCeiling.entries.forEach { option ->
                            FilterChip(
                                selected = filter.ceiling == option,
                                onClick = {
                                    scope.launch {
                                        AppSettings.setCeiling(activity, option)
                                        refetch()
                                    }
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Text(
                        "A ceiling is what stops the tile showing the same airliner at " +
                            "FL380 forty miles away every time. If you live under an airway, " +
                            "it's the difference between a widget and wallpaper.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                SectionCard("Your own receiver") {
                    Text(
                        "If you run sdr_windows, or anything that serves dump1090's " +
                            "aircraft.json, the plane tile asks it first. When it hears nothing " +
                            "in range, adsb.fi is asked as usual.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    station?.let {
                        Text("Asking ${it.url}", fontWeight = FontWeight.Medium)
                    }
                    OutlinedTextField(
                        value = stationUrl,
                        onValueChange = { stationUrl = it; stationError = null },
                        label = { Text("Station address") },
                        placeholder = { Text("https://my-pc.tailnet.ts.net") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = stationToken,
                        onValueChange = { stationToken = it },
                        label = {
                            Text(if (station?.token.isNullOrBlank()) "Dashboard token" else "Dashboard token (saved)")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val url = normalizeStationUrl(stationUrl)
                                if (url == null) {
                                    stationError = "That needs to be an https:// address, like the one " +
                                        "tailscale serve prints."
                                } else {
                                    scope.launch {
                                        AppSettings.setStation(activity, url, stationToken.trim())
                                        stationUrl = url
                                        stationToken = ""
                                        refetch()
                                    }
                                }
                            },
                            enabled = stationUrl.isNotBlank()
                        ) { Text("Save") }
                        if (station != null) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    AppSettings.clearStation(activity)
                                    stationUrl = ""
                                    stationToken = ""
                                    refetch()
                                }
                            }) { Text("Stop using it") }
                        }
                    }
                    stationError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        "HTTPS only: Tailscale Serve gives the station a real certificate. The " +
                            "token stays on this phone and is left out of Android's backups. " +
                            "If the station cannot be reached, Diagnostics says why.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                SectionCard("Background") {
                    Text("Applies to both widgets.", style = MaterialTheme.typography.bodySmall)
                    WidgetBackground.entries.forEach { option ->
                        WideOption(
                            title = option.label,
                            subtitle = option.blurb,
                            selected = look.background == option,
                            onSelect = {
                                scope.launch {
                                    AppSettings.setBackground(activity, option)
                                    redrawWidgets()
                                }
                            }
                        )
                    }
                }

                SectionCard("Text colour") {
                    Text(
                        "Automatic follows your theme on Material You, and goes white on " +
                            "Scrim or Transparent. Pick a fixed colour if your wallpaper " +
                            "fights with it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetTextColor.entries.forEach { option ->
                            FilterChip(
                                selected = look.textColor == option,
                                onClick = {
                                    scope.launch {
                                        AppSettings.setTextColor(activity, option)
                                        redrawWidgets()
                                    }
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }

                SectionCard("Text size and weight") {
                    Text("Size", fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetTextScale.entries.forEach { option ->
                            FilterChip(
                                selected = look.textScale == option,
                                onClick = {
                                    scope.launch {
                                        AppSettings.setTextScale(activity, option)
                                        redrawWidgets()
                                    }
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Text("Weight", fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetTextWeight.entries.forEach { option ->
                            FilterChip(
                                selected = look.textWeight == option,
                                onClick = {
                                    scope.launch {
                                        AppSettings.setTextWeight(activity, option)
                                        redrawWidgets()
                                    }
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Text(
                        "Bold is the default, and it's the other half of legibility over a " +
                            "photo — Glance can't draw a text shadow, so weight and colour " +
                            "are the only levers there are.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Larger text may clip on a small tile — drag the widget's edge to " +
                            "resize it if that happens.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                SectionCard("Weather station") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to "Nearest airport", true to "Nearest with a forecast")
                            .forEach { (option, label) ->
                                FilterChip(
                                    selected = preferForecast == option,
                                    onClick = {
                                        scope.launch {
                                            AppSettings.setPreferForecast(activity, option)
                                            WxRefreshWorker.refreshNow(activity)
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                    }
                    Text(
                        "Only larger airports issue a TAF, so the nearest station often has " +
                            "today's weather but no forecast. \"Nearest with a forecast\" skips " +
                            "those for the nearest one that has both, still within about 45 nm. " +
                            "If none in range does, the tile uses the nearest airport anyway.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                SectionCard("Temperature") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TemperatureUnit.entries.forEach { option ->
                            FilterChip(
                                selected = look.temperature == option,
                                onClick = {
                                    scope.launch {
                                        AppSettings.setTemperature(activity, option)
                                        // The summary line is built when the METAR
                                        // is fetched, so this needs a refetch.
                                        WxRefreshWorker.refreshNow(activity)
                                    }
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Text(
                        "The METAR tile only. Raw METARs are always in Celsius, and the " +
                            "detail screen shows both regardless.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (optimized) {
                    SectionCard("Background refresh is restricted") {
                        Text(
                            "Android is holding this app under battery optimisation. That " +
                                "defers the widgets' scheduled updates indefinitely, which is " +
                                "why they only refresh when you open the app.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = { requestBatteryExemption() }) {
                            Text("Allow background updates")
                        }
                        Text(
                            "On Samsung and Xiaomi you may also need Settings → Apps → " +
                                "Nearest Plane → Battery → Unrestricted.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                SectionCard("Diagnostics") {
                    Text(
                        "Planes: last run ${Diagnostics.timeAgo(planeDiag.lastRun)}, " +
                            "last success ${Diagnostics.timeAgo(planeDiag.lastOk)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (planeDiag.lastPosition.isNotBlank()) {
                        Text(
                            "Planes used: ${planeDiag.lastPosition}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (planeDiag.lastError.isNotBlank()) {
                        Text(
                            "Planes error at ${Diagnostics.clockTime(planeDiag.lastErrorAt)}: " +
                                planeDiag.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        "Weather: last run ${Diagnostics.timeAgo(wxDiag.lastRun)}, " +
                            "last success ${Diagnostics.timeAgo(wxDiag.lastOk)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (wxDiag.lastPosition.isNotBlank()) {
                        Text(
                            "Weather used: ${wxDiag.lastPosition}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (wxDiag.lastError.isNotBlank()) {
                        Text(
                            "Weather error at ${Diagnostics.clockTime(wxDiag.lastErrorAt)}: " +
                                wxDiag.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        "If \"last run\" is hours old, the system isn't running the job at all " +
                            "— that's battery optimisation, not a bug in the fetch.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "If \"used\" says this app's last saved location and it's hours old, " +
                            "the refreshes are running but Android isn't giving them your " +
                            "location — see Location above.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                SectionCard("Refresh now") {
                    Text(
                        "Background updates run every 15 min (planes) and 30 min (weather).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { RefreshWorker.refreshNow(activity) }) {
                            Text("Planes")
                        }
                        OutlinedButton(onClick = { WxRefreshWorker.refreshNow(activity) }) {
                            Text("Weather")
                        }
                    }
                }

                SectionCard("Version") {
                    Text(
                        "Nearest Plane ${BuildConfig.VERSION_NAME}",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (update != null) "An update is waiting, above."
                        else if (updateChecked) "This is the latest release."
                        else "Checking for a newer release…",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            updateChecked = false
                            scope.launch { checkForUpdate() }
                        }) { Text("Check again") }
                        OutlinedButton(onClick = {
                            openUrl("https://github.com/ziah28/NearestPlane")
                        }) { Text("Source") }
                    }
                }

                Text(
                    "Add the tiles by long-pressing your home screen, tapping Widgets, then " +
                        "finding Nearest Plane. Two tiles are listed.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                content()
            }
        }
    }

    @Composable
    private fun WideOption(
        title: String,
        subtitle: String,
        selected: Boolean,
        onSelect: () -> Unit
    ) {
        val modifier = Modifier.fillMaxWidth()
        val body: @Composable () -> Unit = {
            Column {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (selected) Button(onClick = onSelect, modifier = modifier) { body() }
        else OutlinedButton(onClick = onSelect, modifier = modifier) { body() }
    }

    /**
     * Opens the system dialog that exempts this app from Doze. Without the
     * exemption, WorkManager's scheduled jobs are deferred for hours.
     */
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            // Some OEM builds block the direct request; fall back to the list.
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}
