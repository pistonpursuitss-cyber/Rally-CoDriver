package com.rallycodriver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale
import kotlin.math.*

private val Night = Color(0xFF10151D)
private val Panel = Color(0xFF1B2531)
private val Accent = Color(0xFFFF6B35)
private val Mint = Color(0xFF7BE0C3)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var fused: FusedLocationProviderClient
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastLocation: Location? = null
    private var lastSpoken = ""
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) startGps()
    }
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                lastLocation = loc
                speedState.value = (loc.speed * 2.236936).toInt().coerceAtLeast(0)
                locationState.value = LatLng(loc.latitude, loc.longitude)
                headingState.value = if (loc.hasBearing()) loc.bearing.toInt() else 0
            }
        }
    }
    private val speedState = mutableIntStateOf(0)
    private val headingState = mutableIntStateOf(0)
    private val locationState = mutableStateOf<LatLng?>(null)
    private val alertsState = mutableStateListOf<ReportedAlert>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        setContent { RallyApp() }
        requestGpsIfNeeded()
    }
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        tts?.language = Locale.US
    }
    private fun requestGpsIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine) startGps() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun startGps() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
        try { fused.requestLocationUpdates(request, locationCallback, mainLooper) } catch (_: SecurityException) {}
    }
    private fun speak(text: String) {
        if (ttsReady && text != lastSpoken) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rally-note"); lastSpoken = text }
    }
    private fun openGoogleMaps() {
        val p = locationState.value
        val uri = if (p != null) Uri.parse("google.navigation:q=${p.latitude},${p.longitude}&mode=d") else Uri.parse("google.navigation:q=")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        try { startActivity(intent) } catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))) }
    }
    @Composable
    private fun RallyApp() {
        var rallyVoice by remember { mutableStateOf(true) }
        var alertsEnabled by remember { mutableStateOf(true) }
        var showReports by remember { mutableStateOf(false) }
        var activeTab by remember { mutableStateOf(0) }
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Night, surface = Panel)) {
            Scaffold(containerColor = Night, bottomBar = {
                NavigationBar(containerColor = Panel) {
                    NavigationBarItem(selected = activeTab == 0, onClick = { activeTab = 0 }, icon = { Icon(Icons.Default.Map, null) }, label = { Text("Drive") })
                    NavigationBarItem(selected = activeTab == 1, onClick = { activeTab = 1 }, icon = { Icon(Icons.Default.RecordVoiceOver, null) }, label = { Text("Co-driver") })
                    NavigationBarItem(selected = activeTab == 2, onClick = { activeTab = 2 }, icon = { Icon(Icons.Default.Warning, null) }, label = { Text("Alerts") })
                }
            }) { padding ->
                when (activeTab) {
                    0 -> DriveScreen(padding, rallyVoice, alertsEnabled, { rallyVoice = it }, { alertsEnabled = it }, { openGoogleMaps() }, { showReports = true })
                    1 -> CoDriverScreen(padding, rallyVoice, { rallyVoice = it }, { speak("Rally co-driver voice test. Caution, right three, tightens.") })
                    else -> AlertsScreen(padding, alertsState, { showReports = true })
                }
                if (showReports) ReportDialog(onDismiss = { showReports = false }, onAdd = { type -> alertsState.add(ReportedAlert(type, System.currentTimeMillis())); showReports = false; speak("$type reported ahead") })
            }
        }
    }
    @Composable
    private fun DriveScreen(padding: PaddingValues, rally: Boolean, alerts: Boolean, onRally: (Boolean)->Unit, onAlerts: (Boolean)->Unit, onGoogle: ()->Unit, onReport: ()->Unit) {
        Column(Modifier.fillMaxSize().padding(padding).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column { Text("RALLY CO-DRIVER", color = Mint, fontSize = 12.sp); Text("Live drive", color = Color.White, fontSize = 25.sp) }
                AssistChip(onClick = onGoogle, label = { Text("Google Maps") }, leadingIcon = { Icon(Icons.Default.NorthEast, null) })
            }
            MapPanel(locationState.value)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("GPS SPEED", "${speedState.intValue}", "MPH", Modifier.weight(1f))
                MetricCard("HEADING", "${headingState.intValue}°", "COMPASS", Modifier.weight(1f))
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DRIVING ASSIST", color = Mint)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Rally pace notes", Modifier.weight(1f), color = Color.White); Switch(rally, onRally) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Road alerts", Modifier.weight(1f), color = Color.White); Switch(alerts, onAlerts) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onReport, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Icon(Icons.Default.AddAlert, null); Spacer(Modifier.width(6.dp)); Text("Report") }
                OutlinedButton(onClick = { speak("Co-driver online. Drive safely.") }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(6.dp)); Text("Voice test") }
            }
            Text("Drive mode is a prototype. Obey posted limits and use alerts as supplemental information.", color = Color.Gray, fontSize = 11.sp)
        }
    }
    @Composable
    private fun MapPanel(point: LatLng?) {
        AndroidViewMap(point)
    }
    @Composable
    private fun AndroidViewMap(point: LatLng?) {
        val context = this
        val mapView = remember { MapView(context).apply { onCreate(Bundle()); getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri("https://demotiles.maplibre.org/style.json")) {
                map.cameraPosition = CameraPosition.Builder().target(point ?: LatLng(36.37, -94.21)).zoom(13.0).build()
            }
        } } }
        DisposableEffect(mapView) { mapView.onStart(); mapView.onResume(); onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() } }
        androidx.compose.ui.viewinterop.AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().height(260.dp))
        LaunchedEffect(point) { if (point != null) { /* Map follows GPS after map style loads in the next iteration. */ } }
    }
    @Composable
    private fun MetricCard(title: String, value: String, unit: String, modifier: Modifier) {
        Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) { Text(title, color = Mint, fontSize = 10.sp); Text(value, color = Color.White, fontSize = 32.sp); Text(unit, color = Color.Gray, fontSize = 10.sp) }
        }
    }
    @Composable
    private fun CoDriverScreen(padding: PaddingValues, rally: Boolean, onRally: (Boolean)->Unit, test: ()->Unit) {
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CO-DRIVER", color = Mint, fontSize = 12.sp); Text("Your voice navigator", color = Color.White, fontSize = 26.sp)
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("VOICE STYLE", color = Mint)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Rally pace notes", Modifier.weight(1f), color = Color.White); Switch(rally, onRally) }
                    Text(if (rally) "Example: “Caution, right 3, tightens to 2. 150 meters.”" else "Example: “Sharp right bend ahead. Reduce speed.”", color = Color.LightGray)
                    Button(onClick = test) { Icon(Icons.Default.PlayArrow, null); Text(" Test voice") }
                }
            }
            Text("PACE-NOTE SCALE", color = Mint)
            listOf("6 — Very fast / gentle", "5 — Fast", "4 — Medium-fast", "3 — Medium / caution", "2 — Tight", "1 — Very tight / hairpin").forEach { Text(it, color = Color.White) }
            Text("Notes are generated from road geometry when route-data integration is configured; this starter does not yet claim rally-grade corner calls.", color = Color.Gray, fontSize = 12.sp)
        }
    }
    @Composable
    private fun AlertsScreen(padding: PaddingValues, items: List<ReportedAlert>, report: ()->Unit) {
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ROAD INTELLIGENCE", color = Mint, fontSize = 12.sp); Text("Alerts & reports", color = Color.White, fontSize = 26.sp)
            listOf("Police sighting", "Speed camera", "Red-light camera", "Road hazard", "Crash / obstruction", "Weather hazard", "Radar hardware").forEach { name ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = Accent); Spacer(Modifier.width(10.dp)); Text(name, color = Color.White); Spacer(Modifier.weight(1f)); Text("Ready", color = Mint, fontSize = 11.sp) } }
            }
            Text("Community reports entered on this device: ${items.size}", color = Color.Gray)
            Button(onClick = report) { Icon(Icons.Default.Add, null); Text(" Report an alert") }
            Text("Police and camera notices depend on verified data and community reports. GPS cannot detect radar signals; radar support requires a compatible detector and documented Bluetooth/API integration.", color = Color.Gray, fontSize = 12.sp)
        }
    }
    @Composable
    private fun ReportDialog(onDismiss: ()->Unit, onAdd: (String)->Unit) {
        val choices = listOf("Police", "Speed camera", "Road hazard", "Crash", "Weather hazard")
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Report ahead") }, text = { Column { choices.forEach { TextButton(onClick = { onAdd(it) }) { Text(it) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }
    override fun onDestroy() { super.onDestroy(); fused.removeLocationUpdates(locationCallback); tts?.shutdown() }
}
data class ReportedAlert(val type: String, val createdAt: Long)
