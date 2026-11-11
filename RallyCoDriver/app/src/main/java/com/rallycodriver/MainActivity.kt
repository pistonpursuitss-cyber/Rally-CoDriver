package com.rallycodriver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val Asphalt = Color(0xFF0A0D12)
private val Carbon = Color(0xFF111821)
private val Panel = Color(0xFF18222D)
private val PanelRaised = Color(0xFF202D3A)
private val Orange = Color(0xFFFF6B35)
private val Lime = Color(0xFFB7F36B)
private val Ice = Color(0xFFE8EEF5)
private val Muted = Color(0xFF96A4B4)
private val GoogleDarkMap = """[
  {"elementType":"geometry","stylers":[{"color":"#202c3a"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#a8b6c6"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#17212b"}]},
  {"featureType":"administrative","elementType":"geometry.stroke","stylers":[{"color":"#435365"}]},
  {"featureType":"landscape","elementType":"geometry","stylers":[{"color":"#151e28"}]},
  {"featureType":"poi","elementType":"geometry","stylers":[{"color":"#202c38"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#354252"}]},
  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#73513a"}]},
  {"featureType":"transit","elementType":"geometry","stylers":[{"color":"#273545"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#09131d"}]}
]"""

private enum class AppTab(val label: String) {
    DRIVE("Drive"), CODRIVER("Co-driver"), REPORTS("Reports"), SETTINGS("Garage")
}

private enum class SpeedUnit(val label: String, val divisor: Double) {
    MPH("MPH", 0.44704), KMH("KM/H", 1.0 / 3.6)
}

private data class RoadReport(val type: String, val createdAt: Long, val latitude: Double?, val longitude: Double?)

private data class TripSummary(val elapsedSeconds: Long, val distanceMeters: Float, val maxMetersPerSecond: Float)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var lastAcceptedLocation: Location? = null

    private val currentLocation = mutableStateOf<LatLng?>(null)
    private val speedMetersPerSecond = mutableFloatStateOf(0f)
    private val headingDegrees = mutableIntStateOf(0)
    private val locationReady = mutableStateOf(false)
    private val permissionGranted = mutableStateOf(false)
    private val tripActive = mutableStateOf(false)
    private val tripStartedAt = mutableLongStateOf(0L)
    private val tripElapsed = mutableLongStateOf(0L)
    private val tripDistance = mutableFloatStateOf(0f)
    private val tripMaxSpeed = mutableFloatStateOf(0f)
    private val lastTrip = mutableStateOf<TripSummary?>(null)
    private val reports = mutableStateListOf<RoadReport>()
    private val selectedUnit = mutableStateOf(SpeedUnit.MPH)
    private val rallyVoice = mutableStateOf(true)
    private val mapFollow = mutableStateOf(true)
    private val alertsEnabled = mutableStateOf(true)
    private val voiceEnabled = mutableStateOf(true)

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted.value = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (permissionGranted.value) startLocationUpdates()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val previous = lastAcceptedLocation
            lastAcceptedLocation = location
            currentLocation.value = LatLng(location.latitude, location.longitude)
            speedMetersPerSecond.floatValue = location.speed.coerceAtLeast(0f)
            if (location.hasBearing() && location.speed > 0.7f) headingDegrees.intValue = location.bearing.toInt().mod(360)
            locationReady.value = true

            if (tripActive.value && previous != null && location.accuracy <= 50f && previous.accuracy <= 50f) {
                val gap = location.time - previous.time
                val delta = previous.distanceTo(location)
                if (gap in 0L..10_000L && delta in 1f..150f) tripDistance.floatValue += delta
            }
            if (tripActive.value) tripMaxSpeed.floatValue = maxOf(tripMaxSpeed.floatValue, location.speed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 13, 18)
        window.navigationBarColor = android.graphics.Color.rgb(10, 13, 18)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        textToSpeech = TextToSpeech(this, this)
        loadPreferences()
        loadReports()
        permissionGranted.value = hasLocationPermission()
        setContent { RallyCompanion() }
        if (permissionGranted.value) startLocationUpdates() else locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    override fun onStart() {
        super.onStart()
        if (::fusedLocation.isInitialized && hasLocationPermission()) startLocationUpdates()
    }

    override fun onStop() {
        if (::fusedLocation.isInitialized) fusedLocation.removeLocationUpdates(locationCallback)
        super.onStop()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) textToSpeech?.language = Locale.US
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        permissionGranted.value = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        try {
            fusedLocation.requestLocationUpdates(request, locationCallback, mainLooper)
            fusedLocation.lastLocation.addOnSuccessListener { location ->
                if (location != null && currentLocation.value == null) {
                    currentLocation.value = LatLng(location.latitude, location.longitude)
                    speedMetersPerSecond.floatValue = location.speed.coerceAtLeast(0f)
                    locationReady.value = true
                }
            }
        } catch (_: SecurityException) {
            permissionGranted.value = false
        }
    }

    private fun speak(text: String) {
        if (voiceEnabled.value && ttsReady) textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rally-codriver")
    }

    private fun toggleTrip() {
        if (tripActive.value) {
            tripElapsed.longValue = ((SystemClock.elapsedRealtime() - tripStartedAt.longValue) / 1000L).coerceAtLeast(0L)
            lastTrip.value = TripSummary(tripElapsed.longValue, tripDistance.floatValue, tripMaxSpeed.floatValue)
            tripActive.value = false
            speak("Drive recorded. ${formatDistance(tripDistance.floatValue)} covered.")
        } else {
            tripStartedAt.longValue = SystemClock.elapsedRealtime()
            tripElapsed.longValue = 0L
            tripDistance.floatValue = 0f
            tripMaxSpeed.floatValue = speedMetersPerSecond.floatValue
            lastTrip.value = null
            tripActive.value = true
            speak("Trip recording started. Eyes on the road.")
        }
    }

    private fun savePreferences() {
        getSharedPreferences("rally_companion", MODE_PRIVATE).edit()
            .putString("speed_unit", selectedUnit.value.name)
            .putBoolean("rally_voice", rallyVoice.value)
            .putBoolean("map_follow", mapFollow.value)
            .putBoolean("alerts_enabled", alertsEnabled.value)
            .putBoolean("voice_enabled", voiceEnabled.value)
            .apply()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("rally_companion", MODE_PRIVATE)
        selectedUnit.value = runCatching { SpeedUnit.valueOf(prefs.getString("speed_unit", "MPH") ?: "MPH") }.getOrDefault(SpeedUnit.MPH)
        rallyVoice.value = prefs.getBoolean("rally_voice", true)
        mapFollow.value = prefs.getBoolean("map_follow", true)
        alertsEnabled.value = prefs.getBoolean("alerts_enabled", true)
        voiceEnabled.value = prefs.getBoolean("voice_enabled", true)
    }

    private fun addReport(type: String) {
        val point = currentLocation.value
        reports.add(0, RoadReport(type, System.currentTimeMillis(), point?.latitude, point?.longitude))
        while (reports.size > 100) reports.removeAt(reports.lastIndex)
        saveReports()
        if (alertsEnabled.value) speak("$type reported at your location.")
    }

    private fun deleteReport(report: RoadReport) {
        reports.remove(report)
        saveReports()
    }

    private fun saveReports() {
        val array = JSONArray()
        reports.forEach { report ->
            array.put(JSONObject().apply {
                put("type", report.type)
                put("createdAt", report.createdAt)
                if (report.latitude != null) put("latitude", report.latitude)
                if (report.longitude != null) put("longitude", report.longitude)
            })
        }
        getSharedPreferences("rally_companion", MODE_PRIVATE).edit().putString("reports", array.toString()).apply()
    }

    private fun loadReports() {
        val raw = getSharedPreferences("rally_companion", MODE_PRIVATE).getString("reports", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                reports.add(RoadReport(item.getString("type"), item.getLong("createdAt"), item.optDouble("latitude").takeUnless { it.isNaN() }, item.optDouble("longitude").takeUnless { it.isNaN() }))
            }
        }
    }

    private fun openGoogleMapsNavigation(destination: LatLng) {
        val uri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        try {
            startActivity(intent)
        } catch (_: Exception) {
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${destination.latitude},${destination.longitude}&travelmode=driving")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    @Composable
    private fun RallyCompanion() {
        var tab by remember { mutableStateOf(AppTab.DRIVE) }
        var showReportDialog by remember { mutableStateOf(false) }
        val colorScheme = darkColorScheme(
            primary = Orange,
            onPrimary = Asphalt,
            secondary = Lime,
            background = Asphalt,
            surface = Panel,
            onSurface = Ice,
            onBackground = Ice,
            outline = Color(0xFF344251)
        )

        LaunchedEffect(tripActive.value) {
            while (tripActive.value) {
                tripElapsed.longValue = ((SystemClock.elapsedRealtime() - tripStartedAt.longValue) / 1000L).coerceAtLeast(0L)
                kotlinx.coroutines.delay(1000L)
            }
        }

        MaterialTheme(colorScheme = colorScheme) {
            Scaffold(
                containerColor = Asphalt,
                bottomBar = {
                    NavigationBar(containerColor = Carbon, tonalElevation = 0.dp) {
                        AppTab.entries.forEach { item ->
                            val icon = when (item) {
                                AppTab.DRIVE -> Icons.Default.Explore
                                AppTab.CODRIVER -> Icons.Default.RecordVoiceOver
                                AppTab.REPORTS -> Icons.Default.Campaign
                                AppTab.SETTINGS -> Icons.Default.Tune
                            }
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Orange,
                                    selectedTextColor = Orange,
                                    indicatorColor = PanelRaised,
                                    unselectedIconColor = Muted,
                                    unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                when (tab) {
                    AppTab.DRIVE -> DriveScreen(padding, { showReportDialog = true })
                    AppTab.CODRIVER -> CoDriverScreen(padding)
                    AppTab.REPORTS -> ReportsScreen(padding)
                    AppTab.SETTINGS -> GarageScreen(padding)
                }
                if (showReportDialog) {
                    ReportDialog(
                        onDismiss = { showReportDialog = false },
                        onSelect = { type -> addReport(type); showReportDialog = false }
                    )
                }
            }
        }
    }

    @Composable
    private fun ScreenColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }

    @Composable
    private fun ScreenHeader(eyebrow: String, title: String, trailing: @Composable (() -> Unit)? = null) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(eyebrow.uppercase(), color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Text(title, color = Ice, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
            }
            if (trailing != null) trailing()
        }
    }

    @Composable
    private fun DriveScreen(padding: PaddingValues, onReport: () -> Unit) {
        ScreenColumn(padding) {
            ScreenHeader("Rally Co-Driver", "Live stage", trailing = {
                StatusPill(if (locationReady.value) "GPS LOCK" else "GPS SEARCH", if (locationReady.value) Lime else Orange)
            })

            MapCard()

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TelemetryCard("SPEED", speedValue(speedMetersPerSecond.floatValue), selectedUnit.value.label, Icons.Default.Speed, Modifier.weight(1f))
                TelemetryCard("HEADING", "${headingDegrees.intValue}°", compassLabel(headingDegrees.intValue), Icons.Default.Explore, Modifier.weight(1f))
                TelemetryCard("TRIP", formatDistance(tripDistance.floatValue), if (tripActive.value) "RECORDING" else "DISTANCE", Icons.Default.Route, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { toggleTrip() },
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (tripActive.value) Color(0xFFBD3D34) else Orange)
                ) {
                    Icon(if (tripActive.value) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (tripActive.value) "END DRIVE" else "START DRIVE", fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                }
                OutlinedButton(
                    onClick = onReport,
                    modifier = Modifier.height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Orange.copy(alpha = 0.7f))
                ) {
                    Icon(Icons.Default.AddAlert, null, tint = Orange)
                    Spacer(Modifier.width(7.dp))
                    Text("REPORT", color = Ice, fontWeight = FontWeight.Bold)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timer, null, tint = Lime, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("DRIVE TIMER", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(tripElapsed.longValue), color = Ice, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SmallStat("MAX SPEED", speedValue(if (tripActive.value) tripMaxSpeed.floatValue else lastTrip.value?.maxMetersPerSecond ?: 0f) + " ${selectedUnit.value.label}")
                        SmallStat("REPORTS", "${reports.size} SAVED")
                        SmallStat("VOICE", if (voiceEnabled.value) "READY" else "MUTED")
                    }
                }
            }

            lastTrip.value?.let { summary ->
                Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, null, tint = Lime)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("LAST DRIVE", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("${formatDistance(summary.distanceMeters)} · ${formatDuration(summary.elapsedSeconds)}", color = Ice, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = { lastTrip.value = null }) { Icon(Icons.Default.Close, "Dismiss", tint = Muted) }
                    }
                }
            }

            Text("Set up before you drive. Pace notes are examples; this build does not generate turn-by-turn rally calls.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
        }
    }

    @Composable
    private fun MapCard() {
        val apiKeyAvailable = BuildConfig.MAPS_API_KEY.isNotBlank() && BuildConfig.MAPS_API_KEY != "MISSING_API_KEY"
        val defaultCenter = LatLng(39.5, -98.35)
        val coroutineScope = rememberCoroutineScope()
        val cameraPositionState = rememberCameraPositionState {
            position = com.google.android.gms.maps.model.CameraPosition.fromLatLngZoom(currentLocation.value ?: defaultCenter, 14f)
        }

        LaunchedEffect(currentLocation.value, mapFollow.value) {
            val point = currentLocation.value
            if (apiKeyAvailable && mapFollow.value && point != null) {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(point, 16f), 650)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                if (apiKeyAvailable) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)),
                        cameraPositionState = cameraPositionState,
                        properties = MapProperties(
                            isMyLocationEnabled = permissionGranted.value,
                            mapStyleOptions = MapStyleOptions(GoogleDarkMap)
                        ),
                        uiSettings = MapUiSettings(
                            compassEnabled = true,
                            mapToolbarEnabled = false,
                            myLocationButtonEnabled = true,
                            zoomControlsEnabled = false
                        )
                    ) {
                        reports.forEach { report ->
                            if (report.latitude != null && report.longitude != null) {
                                val point = LatLng(report.latitude, report.longitude)
                                Marker(
                                    state = rememberMarkerState(position = point),
                                    title = report.type,
                                    snippet = "Local report · ${formatClock(report.createdAt)}"
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().background(PanelRaised).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Map, null, tint = Orange, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Google Maps needs an API key", color = Ice, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("Add MAPS_API_KEY to the GitHub Actions secrets and rebuild. GPS and trip recording still work.", color = Muted, fontSize = 12.sp)
                    }
                }

                Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Asphalt.copy(alpha = 0.88f), shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (locationReady.value) Lime else Orange))
                            Spacer(Modifier.width(7.dp))
                            Text(if (locationReady.value) "LIVE POSITION" else "WAITING FOR GPS", color = Ice, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        }
                    }
                }

                if (apiKeyAvailable) {
                    IconButton(
                        onClick = {
                            currentLocation.value?.let { point ->
                                coroutineScope.launch { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(point, 16f)) }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(44.dp).clip(CircleShape).background(Asphalt.copy(alpha = 0.9f))
                    ) {
                        Icon(Icons.Default.MyLocation, "Center on my location", tint = Lime)
                    }
                }
            }
        }
    }

    @Composable
    private fun TelemetryCard(title: String, value: String, unit: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
        Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Lime, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(title, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1)
                }
                Text(value, color = Ice, fontSize = 23.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Clip)
                Text(unit, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }

    @Composable
    private fun CoDriverScreen(padding: PaddingValues) {
        ScreenColumn(padding) {
            ScreenHeader("Crew chief", "Co-driver")
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Orange.copy(alpha = 0.16f), shape = RoundedCornerShape(16.dp), modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.RecordVoiceOver, null, tint = Orange, modifier = Modifier.size(27.dp)) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("VOICE SYSTEM", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Text(if (voiceEnabled.value) "Co-driver online" else "Voice muted", color = Ice, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Switch(checked = voiceEnabled.value, onCheckedChange = { voiceEnabled.value = it; savePreferences() })
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Text("CALL STYLE", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        FilterChip(selected = rallyVoice.value, onClick = { rallyVoice.value = true; savePreferences() }, label = { Text("Rally notes") }, leadingIcon = { Icon(Icons.Default.Flag, null, modifier = Modifier.size(16.dp)) })
                        FilterChip(selected = !rallyVoice.value, onClick = { rallyVoice.value = false; savePreferences() }, label = { Text("Road guide") }, leadingIcon = { Icon(Icons.Default.TurnRight, null, modifier = Modifier.size(16.dp)) })
                    }
                    Text(
                        if (rallyVoice.value) "Example: “Caution, right three, tightens to two.”" else "Example: “Sharp right bend ahead. Ease off.”",
                        color = Ice,
                        fontSize = 15.sp
                    )
                    Button(
                        onClick = { speak(if (rallyVoice.value) "Rally co-driver voice check. Caution, right three, tightens to two." else "Road guide voice check. Sharp right bend ahead. Ease off.") },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("TEST THE VOICE", fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                    }
                }
            }

            Text("PACE NOTE SCALE", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            listOf(
                "6" to "Open and flowing",
                "5" to "Fast bend",
                "4" to "Medium-fast",
                "3" to "Caution, medium",
                "2" to "Tight turn",
                "1" to "Hairpin"
            ).forEach { (number, description) ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(number, color = Orange, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(36.dp))
                        Text(description, color = Ice, fontSize = 13.sp)
                    }
                }
            }
            InfoNote("This build has voice tests and trip telemetry. Automatic corner calls need route geometry and on-road validation.")
        }
    }

    @Composable
    private fun ReportsScreen(padding: PaddingValues) {
        ScreenColumn(padding) {
            ScreenHeader("Roadbook", "My reports", trailing = {
                Text("${reports.size}/100", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            })
            InfoNote("Reports are saved on this phone and pinned on your map. They are not shared with other drivers.")
            if (reports.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Default.Campaign, null, tint = Orange, modifier = Modifier.size(34.dp))
                        Text("Your roadbook is clear", color = Ice, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Add a report and it will appear here and on the map.", color = Muted, fontSize = 12.sp)
                    }
                }
            } else {
                reports.forEach { report ->
                    ReportCard(report, onDelete = { deleteReport(report) }, onNavigate = { point -> openGoogleMapsNavigation(point) })
                }
            }
            Spacer(Modifier.height(3.dp))
        }
    }

    @Composable
    private fun ReportCard(report: RoadReport, onDelete: () -> Unit, onNavigate: (LatLng) -> Unit) {
        val point = if (report.latitude != null && report.longitude != null) LatLng(report.latitude, report.longitude) else null
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Orange.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(reportIcon(report.type), null, tint = Orange, modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(report.type, color = Ice, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${formatClock(report.createdAt)}  ·  ${if (point == null) "No GPS fix" else String.format(Locale.US, "%.4f, %.4f", point.latitude, point.longitude)}", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (point != null) IconButton(onClick = { onNavigate(point) }) { Icon(Icons.Default.NearMe, "Open in Google Maps", tint = Lime) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Delete report", tint = Muted) }
            }
        }
    }

    @Composable
    private fun GarageScreen(padding: PaddingValues) {
        ScreenColumn(padding) {
            ScreenHeader("Setup & preferences", "Garage")
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Text("SPEED UNITS", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpeedUnit.entries.forEach { unit ->
                            FilterChip(selected = selectedUnit.value == unit, onClick = { selectedUnit.value = unit; savePreferences() }, label = { Text(unit.label) })
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    SettingRow("Follow my location", "Keep the map centered while driving", mapFollow.value) { mapFollow.value = it; savePreferences() }
                    SettingRow("Road report voice alerts", "Speak when you add a report", alertsEnabled.value) { alertsEnabled.value = it; savePreferences() }
                    SettingRow("Co-driver voice", "Enable voice tests and readouts", voiceEnabled.value) { voiceEnabled.value = it; savePreferences() }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Map, null, tint = Lime)
                        Spacer(Modifier.width(9.dp))
                        Text("GOOGLE MAPS", color = Ice, fontWeight = FontWeight.Bold)
                    }
                    Text(if (BuildConfig.MAPS_API_KEY == "MISSING_API_KEY") "No Maps API key configured in this build." else "Maps key is present in this build.", color = Muted, fontSize = 12.sp)
                    Text("Map SDK use requires a Google Cloud project, Maps SDK for Android enabled, billing, and an API key restricted to this app.", color = Muted, fontSize = 11.sp)
                }
            }
            InfoNote("GPS is used only while this app is open. Use Google Maps navigation for turn-by-turn directions; this app does not create a route or verified pace notes yet.")
        }
    }

    @Composable
    private fun SettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ice, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 10.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun ReportDialog(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
        val choices = listOf("Police sighting", "Speed camera", "Road hazard", "Crash / obstruction", "Weather hazard", "Road closed")
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Panel,
            title = { Text("Report ahead", color = Ice, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This report is saved on this phone at your current GPS position.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(3.dp))
                    choices.forEach { type ->
                        TextButton(onClick = { onSelect(type) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(reportIcon(type), null, tint = Orange)
                            Spacer(Modifier.width(10.dp))
                            Text(type, modifier = Modifier.weight(1f), color = Ice)
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
        )
    }

    @Composable
    private fun InfoNote(text: String) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Info, null, tint = Lime, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(9.dp))
            Text(text, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }

    @Composable
    private fun StatusPill(label: String, color: Color) {
        Surface(color = Panel, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, color = Ice, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
            }
        }
    }

    @Composable
    private fun SmallStat(label: String, value: String) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
            Text(value, color = Ice, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }

    private fun speedValue(metersPerSecond: Float): String =
        (metersPerSecond / selectedUnit.value.divisor).roundToInt().toString()

    private fun compassLabel(degrees: Int): String {
        val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return directions[((degrees + 22) % 360) / 45]
    }

    private fun formatDistance(meters: Float): String = when (selectedUnit.value) {
        SpeedUnit.MPH -> String.format(Locale.US, "%.2f mi", meters / 1609.344f)
        SpeedUnit.KMH -> String.format(Locale.US, "%.2f km", meters / 1000f)
    }

    private fun formatDuration(seconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(seconds)
        val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val secs = seconds % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        else String.format(Locale.US, "%02d:%02d", minutes, secs)
    }

    private fun formatClock(epochMillis: Long): String = SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(epochMillis))

    private fun reportIcon(type: String) = when {
        type.contains("police", ignoreCase = true) -> Icons.Default.LocalPolice
        type.contains("camera", ignoreCase = true) -> Icons.Default.PhotoCamera
        type.contains("crash", ignoreCase = true) -> Icons.Default.CarCrash
        type.contains("weather", ignoreCase = true) -> Icons.Default.Thunderstorm
        type.contains("closed", ignoreCase = true) -> Icons.Default.Block
        else -> Icons.Default.WarningAmber
    }

    override fun onDestroy() {
        if (::fusedLocation.isInitialized) fusedLocation.removeLocationUpdates(locationCallback)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}
