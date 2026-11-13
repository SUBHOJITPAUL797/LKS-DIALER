package com.example.ui.screens.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.data.model.ContactDto
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ──────────────────────────────────────────────────────────────────────────────
// Data Models for Rich WhatsApp Chat Payloads (Location, Contact, Poll)
// ──────────────────────────────────────────────────────────────────────────────

data class SharedLocationData(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val address: String,
    val accuracyMeters: Float = 10f,
    val isLiveLocation: Boolean = false,
    val liveDurationMinutes: Int = 0,
    val comment: String = ""
) {
    fun toMessageJson(): String {
        val obj = JSONObject()
        obj.put("whatsappType", "location")
        obj.put("lat", latitude)
        obj.put("lng", longitude)
        obj.put("title", title)
        obj.put("address", address)
        obj.put("accuracy", accuracyMeters.toDouble())
        obj.put("isLive", isLiveLocation)
        obj.put("liveDurationMin", liveDurationMinutes)
        obj.put("comment", comment)
        val mapsUrl = "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
        obj.put("url", mapsUrl)
        obj.put("text", "📍 $title\n$address\n$mapsUrl")
        return obj.toString()
    }
}

data class SharedContactData(
    val name: String,
    val phoneNumber: String
) {
    fun toMessageJson(): String {
        val obj = JSONObject()
        obj.put("whatsappType", "contact")
        obj.put("name", name)
        obj.put("phone", phoneNumber)
        obj.put("text", "👤 Contact: $name ($phoneNumber)")
        return obj.toString()
    }
}

data class SharedPollData(
    val question: String,
    val options: List<String>
) {
    fun toMessageJson(): String {
        val obj = JSONObject()
        obj.put("whatsappType", "poll")
        obj.put("question", question)
        val arr = JSONArray()
        options.forEach { arr.put(it) }
        obj.put("options", arr)
        obj.put("text", "📊 Poll: $question")
        return obj.toString()
    }
}

object WhatsAppPayloadParser {
    fun parseLocation(rawText: String): SharedLocationData? {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{")) {
            try {
                val obj = JSONObject(trimmed)
                if (obj.optString("whatsappType") == "location") {
                    return SharedLocationData(
                        latitude = obj.optDouble("lat", 0.0),
                        longitude = obj.optDouble("lng", 0.0),
                        title = obj.optString("title", "Pinned Location"),
                        address = obj.optString("address", ""),
                        accuracyMeters = obj.optDouble("accuracy", 10.0).toFloat(),
                        isLiveLocation = obj.optBoolean("isLive", false),
                        liveDurationMinutes = obj.optInt("liveDurationMin", 0),
                        comment = obj.optString("comment", "")
                    )
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun parseContact(rawText: String): SharedContactData? {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{")) {
            try {
                val obj = JSONObject(trimmed)
                if (obj.optString("whatsappType") == "contact") {
                    val name = obj.optString("name", "")
                    val phone = obj.optString("phone", "")
                    if (name.isNotBlank() || phone.isNotBlank()) {
                        return SharedContactData(name.ifBlank { phone }, phone)
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun parsePoll(rawText: String): SharedPollData? {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{")) {
            try {
                val obj = JSONObject(trimmed)
                if (obj.optString("whatsappType") == "poll") {
                    val q = obj.optString("question", "")
                    val arr = obj.optJSONArray("options")
                    val opts = mutableListOf<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optString(i)
                            if (item.isNotBlank()) opts.add(item)
                        }
                    }
                    if (q.isNotBlank() && opts.isNotEmpty()) {
                        return SharedPollData(q, opts)
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 1. WhatsApp Floating Attachment Card Popup (120fps Spring Reveal)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun WhatsAppFloatingAttachmentSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDocumentClick: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onAudioClick: () -> Unit,
    onLocationClick: () -> Unit,
    onContactClick: () -> Unit,
    onPollClick: () -> Unit,
    onScheduleClick: () -> Unit = {}
) {
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(140)) + scaleIn(
            initialScale = 0.82f,
            transformOrigin = TransformOrigin(0.78f, 1.0f),
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            )
        ),
        exit = fadeOut(animationSpec = tween(110)) + scaleOut(
            targetScale = 0.86f,
            transformOrigin = TransformOrigin(0.78f, 1.0f),
            animationSpec = tween(110)
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF1F2C34) else Color.White,
            tonalElevation = 10.dp,
            shadowElevation = 14.dp,
            border = BorderStroke(
                width = 1.dp,
                color = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Row 1: Document, Camera, Gallery, Audio
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WhatsAppAttachmentTile(
                        icon = Icons.Default.Description,
                        label = "Document",
                        gradientColors = listOf(Color(0xFF7F66FF), Color(0xFF5151C6)),
                        onClick = {
                            onDismiss()
                            onDocumentClick()
                        }
                    )
                    WhatsAppAttachmentTile(
                        icon = Icons.Default.PhotoCamera,
                        label = "Camera",
                        gradientColors = listOf(Color(0xFFFF4B82), Color(0xFFD3396D)),
                        onClick = {
                            onDismiss()
                            onCameraClick()
                        }
                    )
                    WhatsAppAttachmentTile(
                        icon = Icons.Default.PhotoLibrary,
                        label = "Gallery",
                        gradientColors = listOf(Color(0xFFD861FF), Color(0xFFAC44CF)),
                        onClick = {
                            onDismiss()
                            onGalleryClick()
                        }
                    )
                    WhatsAppAttachmentTile(
                        icon = Icons.Default.Headphones,
                        label = "Audio",
                        gradientColors = listOf(Color(0xFFFF9800), Color(0xFFE65100)),
                        onClick = {
                            onDismiss()
                            onAudioClick()
                        }
                    )
                }

                // Row 2: Location (Google Maps), Contact, Poll, Schedule
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WhatsAppAttachmentTile(
                        icon = Icons.Default.LocationOn,
                        label = "Location",
                        gradientColors = listOf(Color(0xFF25D366), Color(0xFF1FA855)),
                        onClick = {
                            onDismiss()
                            onLocationClick()
                        }
                    )
                    WhatsAppAttachmentTile(
                        icon = Icons.Default.Person,
                        label = "Contact",
                        gradientColors = listOf(Color(0xFF00B4D8), Color(0xFF0077B6)),
                        onClick = {
                            onDismiss()
                            onContactClick()
                        }
                    )
                    WhatsAppAttachmentTile(
                        icon = Icons.Default.Poll,
                        label = "Poll",
                        gradientColors = listOf(Color(0xFF00C49A), Color(0xFF008069)),
                        onClick = {
                            onDismiss()
                            onPollClick()
                        }
                    )
                    WhatsAppAttachmentTile(
                        icon = Icons.Default.ScheduleSend,
                        label = "Schedule",
                        gradientColors = listOf(Color(0xFF6366F1), Color(0xFF4338CA)),
                        onClick = {
                            onDismiss()
                            onScheduleClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsAppAttachmentTile(
    icon: ImageVector,
    label: String,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.verticalGradient(gradientColors)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isDark) Color(0xFFD1D7DB) else Color(0xFF3B4A54),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 2. WhatsApp Google Maps Location Sharing Dialog (Live GPS + Interactive Map)
// ──────────────────────────────────────────────────────────────────────────────

data class NearbyPlaceItem(
    val name: String,
    val address: String,
    val categoryIcon: ImageVector,
    val latOffset: Double,
    val lngOffset: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppLocationSharingDialog(
    onDismiss: () -> Unit,
    onSendLocation: (SharedLocationData) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current

    // Default fallback coordinates if GPS is disabled/warming up
    var currentLat by remember { mutableDoubleStateOf(22.5726) }
    var currentLng by remember { mutableDoubleStateOf(88.3639) }
    var accuracyMeters by remember { mutableFloatStateOf(12f) }
    var hasRealGpsFix by remember { mutableStateOf(false) }
    var isLocating by remember { mutableStateOf(true) }

    // Interactive map pin offset (in degrees) when user pans the map
    var mapPanLatOffset by remember { mutableDoubleStateOf(0.0) }
    var mapPanLngOffset by remember { mutableDoubleStateOf(0.0) }
    var mapZoomLevel by remember { mutableFloatStateOf(1f) }
    var isSatelliteStyle by remember { mutableStateOf(false) }

    val activePinLat = currentLat + mapPanLatOffset
    val activePinLng = currentLng + mapPanLngOffset

    var currentAddressText by remember { mutableStateOf("Fetching street address…") }
    var currentCityName by remember { mutableStateOf("Current Location") }

    // Search bar state
    var searchQuery by remember { mutableStateOf("") }
    var isSearchingPlaces by remember { mutableStateOf(false) }
    var searchedPlaces by remember { mutableStateOf<List<Address>>(emptyList()) }

    // Live Location sheet state
    var showLiveLocationOptions by remember { mutableStateOf(false) }
    var selectedLiveDurationMin by remember { mutableIntStateOf(60) } // 15, 60, 480
    var liveLocationComment by remember { mutableStateOf("") }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    @SuppressLint("MissingPermission")
    fun startFetchingDeviceLocation() {
        if (!hasLocationPermission) {
            isLocating = false
            return
        }
        isLocating = true
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var bestLastKnown: Location? = null
            for (provider in providers) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        val loc = locationManager.getLastKnownLocation(provider)
                        if (loc != null && (bestLastKnown == null || loc.accuracy < bestLastKnown.accuracy)) {
                            bestLastKnown = loc
                        }
                    }
                } catch (_: Exception) {}
            }
            if (bestLastKnown != null) {
                currentLat = bestLastKnown.latitude
                currentLng = bestLastKnown.longitude
                accuracyMeters = bestLastKnown.accuracy.coerceAtLeast(4f)
                hasRealGpsFix = true
                isLocating = false
            }

            // Request fresh fix on API 30+ or via single update listener
            val activeProvider = providers.firstOrNull {
                try { locationManager.isProviderEnabled(it) } catch (_: Exception) { false }
            }
            if (activeProvider != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.getCurrentLocation(
                        activeProvider,
                        CancellationSignal(),
                        ContextCompat.getMainExecutor(context)
                    ) { loc ->
                        if (loc != null) {
                            currentLat = loc.latitude
                            currentLng = loc.longitude
                            accuracyMeters = loc.accuracy.coerceAtLeast(3f)
                            mapPanLatOffset = 0.0
                            mapPanLngOffset = 0.0
                            hasRealGpsFix = true
                        }
                        isLocating = false
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            currentLat = loc.latitude
                            currentLng = loc.longitude
                            accuracyMeters = loc.accuracy.coerceAtLeast(3f)
                            mapPanLatOffset = 0.0
                            mapPanLngOffset = 0.0
                            hasRealGpsFix = true
                            isLocating = false
                            try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    locationManager.requestSingleUpdate(activeProvider, listener, android.os.Looper.getMainLooper())
                }
            } else {
                isLocating = false
            }
        } catch (_: Exception) {
            isLocating = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        if (granted) {
            startFetchingDeviceLocation()
        } else {
            Toast.makeText(context, "You can still pan the map or search any place to share", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (hasLocationPermission) {
            startFetchingDeviceLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Reverse geocode active pin coordinates whenever they change
    LaunchedEffect(activePinLat, activePinLng) {
        withContext(Dispatchers.IO) {
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val results = geocoder.getFromLocation(activePinLat, activePinLng, 1)
                    val first = results?.firstOrNull()
                    if (first != null) {
                        val line = first.getAddressLine(0) ?: ""
                        val locality = first.subLocality ?: first.locality ?: first.adminArea ?: "Pinned Location"
                        withContext(Dispatchers.Main) {
                            currentCityName = locality
                            currentAddressText = line.ifBlank {
                                String.format(Locale.US, "%.5f, %.5f", activePinLat, activePinLng)
                            }
                        }
                        return@withContext
                    }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                currentCityName = if (mapPanLatOffset == 0.0 && mapPanLngOffset == 0.0) "Current Location" else "Pinned Map Location"
                currentAddressText = String.format(Locale.US, "Lat %.5f, Lng %.5f", activePinLat, activePinLng)
            }
        }
    }

    // Forward geocode when user searches a place name
    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length < 2) {
            searchedPlaces = emptyList()
            isSearchingPlaces = false
            return@LaunchedEffect
        }
        isSearchingPlaces = true
        kotlinx.coroutines.delay(300)
        withContext(Dispatchers.IO) {
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocationName(q, 5) ?: emptyList()
                    withContext(Dispatchers.Main) {
                        searchedPlaces = list
                        isSearchingPlaces = false
                    }
                    return@withContext
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                searchedPlaces = emptyList()
                isSearchingPlaces = false
            }
        }
    }

    val nearbySuggestions = remember(currentCityName) {
        listOf(
            NearbyPlaceItem(
                name = "$currentCityName Center",
                address = "Main Road, $currentCityName",
                categoryIcon = Icons.Default.Place,
                latOffset = 0.0012,
                lngOffset = 0.0015
            ),
            NearbyPlaceItem(
                name = "Metro / Transit Station • $currentCityName",
                address = "Transit Hub, $currentCityName",
                categoryIcon = Icons.Default.DirectionsTransit,
                latOffset = -0.0018,
                lngOffset = 0.0021
            ),
            NearbyPlaceItem(
                name = "City Cafe & Restaurant",
                address = "Central Avenue, $currentCityName",
                categoryIcon = Icons.Default.Restaurant,
                latOffset = 0.0024,
                lngOffset = -0.0014
            ),
            NearbyPlaceItem(
                name = "Shopping Mall & Plaza",
                address = "Market Square, $currentCityName",
                categoryIcon = Icons.Default.ShoppingBag,
                latOffset = -0.0025,
                lngOffset = -0.0019
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Send location",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (hasRealGpsFix) "GPS Active • Accurate to ${accuracyMeters.roundToInt()}m" else "Drag map or search to pin location",
                                style = MaterialTheme.typography.labelSmall,
                                color = GreenCall
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Open in external Google Maps app button
                        IconButton(
                            onClick = {
                                openInGoogleMaps(context, activePinLat, activePinLng, currentCityName)
                            }
                        ) {
                            Icon(
                                Icons.Default.Map,
                                contentDescription = "Open in Google Maps",
                                tint = GreenCall
                            )
                        }
                        IconButton(
                            onClick = {
                                mapPanLatOffset = 0.0
                                mapPanLngOffset = 0.0
                                startFetchingDeviceLocation()
                            }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh GPS"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Search Place / Address Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search Google Maps places or address…", fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TealPrimary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = GreenCall
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )

                // Interactive Google Maps Preview Viewport (Pan, Zoom, Satellite Toggle, My Location)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .pointerInput(mapZoomLevel) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val scaleFactor = 0.000025 / mapZoomLevel.coerceAtLeast(0.5f)
                                mapPanLatOffset += dragAmount.y * scaleFactor
                                mapPanLngOffset -= dragAmount.x * scaleFactor
                            }
                        }
                ) {
                    InteractiveGoogleMapsCanvas(
                        latitude = activePinLat,
                        longitude = activePinLng,
                        zoomLevel = mapZoomLevel,
                        isSatellite = isSatelliteStyle,
                        isDark = isDark,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top-Start: Google Maps Live Coordinates Pill
                    Surface(
                        color = Color.Black.copy(alpha = 0.68f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                tint = Color(0xFFEA4335),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = String.format(Locale.US, "%.5f, %.5f", activePinLat, activePinLng),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Top-End: Satellite / Standard Map Toggle
                    Surface(
                        onClick = { isSatelliteStyle = !isSatelliteStyle },
                        color = Color.Black.copy(alpha = 0.68f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = "Toggle Map Layer",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isSatelliteStyle) "Satellite" else "Map",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Bottom-Start: Zoom + / - Controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = { mapZoomLevel = (mapZoomLevel * 1.35f).coerceAtMost(4f) },
                            containerColor = Color.White,
                            contentColor = Color(0xFF1E293B),
                            shape = CircleShape,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(20.dp))
                        }
                        SmallFloatingActionButton(
                            onClick = { mapZoomLevel = (mapZoomLevel / 1.35f).coerceAtLeast(0.5f) },
                            containerColor = Color.White,
                            contentColor = Color(0xFF1E293B),
                            shape = CircleShape,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(20.dp))
                        }
                    }

                    // Bottom-End: Recenter on My GPS Location FAB
                    FloatingActionButton(
                        onClick = {
                            mapPanLatOffset = 0.0
                            mapPanLngOffset = 0.0
                            if (hasLocationPermission) {
                                startFetchingDeviceLocation()
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        },
                        containerColor = Color.White,
                        contentColor = Color(0xFF0284C7),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(46.dp)
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "My Location",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Scrollable WhatsApp Location Options List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // 1. Share Live Location Row (WhatsApp signature feature)
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showLiveLocationOptions = !showLiveLocationOptions }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(GreenCall.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.ShareLocation,
                                        contentDescription = null,
                                        tint = GreenCall,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Share live location",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        ),
                                        color = GreenCall
                                    )
                                    Text(
                                        text = "Real-time GPS updates for 15 min, 1 hour, or 8 hours",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            AnimatedVisibility(visible = showLiveLocationOptions) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    border = BorderStroke(1.dp, GreenCall.copy(alpha = 0.4f))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "Select duration",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            listOf(
                                                15 to "15 minutes",
                                                60 to "1 hour",
                                                480 to "8 hours"
                                            ).forEach { (mins, label) ->
                                                val selected = selectedLiveDurationMin == mins
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = { selectedLiveDurationMin = mins },
                                                    label = {
                                                        Text(
                                                            text = label,
                                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                            fontSize = 12.sp
                                                        )
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = GreenCall,
                                                        selectedLabelColor = Color.White
                                                    )
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(
                                                value = liveLocationComment,
                                                onValueChange = { liveLocationComment = it },
                                                placeholder = { Text("Add a comment…", fontSize = 13.sp) },
                                                singleLine = true,
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            FloatingActionButton(
                                                onClick = {
                                                    val durLabel = when (selectedLiveDurationMin) {
                                                        15 -> "15 min"
                                                        60 -> "1 hour"
                                                        else -> "8 hours"
                                                    }
                                                    onSendLocation(
                                                        SharedLocationData(
                                                            latitude = activePinLat,
                                                            longitude = activePinLng,
                                                            title = "Live Location ($durLabel)",
                                                            address = currentAddressText,
                                                            accuracyMeters = accuracyMeters,
                                                            isLiveLocation = true,
                                                            liveDurationMinutes = selectedLiveDurationMin,
                                                            comment = liveLocationComment.trim()
                                                        )
                                                    )
                                                },
                                                containerColor = GreenCall,
                                                contentColor = Color.White,
                                                shape = CircleShape,
                                                modifier = Modifier.size(46.dp)
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Live Location")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }

                    // 2. Send Your Current / Pinned Location Immediately
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSendLocation(
                                        SharedLocationData(
                                            latitude = activePinLat,
                                            longitude = activePinLng,
                                            title = currentCityName,
                                            address = currentAddressText,
                                            accuracyMeters = accuracyMeters,
                                            isLiveLocation = false
                                        )
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(GreenCall),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MyLocation,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (mapPanLatOffset == 0.0 && mapPanLngOffset == 0.0) {
                                        "Send your current location"
                                    } else {
                                        "Send this pinned location"
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "$currentAddressText • Accurate to ${accuracyMeters.roundToInt()} m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = GreenCall,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 3. Nearby Places or Search Results Header
                    item {
                        Text(
                            text = if (searchedPlaces.isNotEmpty()) "SEARCH RESULTS" else "NEARBY PLACES",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    if (searchedPlaces.isNotEmpty()) {
                        items(searchedPlaces) { addr ->
                            val placeTitle = addr.featureName ?: addr.locality ?: searchQuery
                            val fullLine = addr.getAddressLine(0) ?: "${addr.latitude}, ${addr.longitude}"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSendLocation(
                                            SharedLocationData(
                                                latitude = addr.latitude,
                                                longitude = addr.longitude,
                                                title = placeTitle,
                                                address = fullLine,
                                                accuracyMeters = 5f
                                            )
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Place,
                                            contentDescription = null,
                                            tint = Color(0xFFEA4335),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = placeTitle,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = fullLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        items(nearbySuggestions) { place ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSendLocation(
                                            SharedLocationData(
                                                latitude = activePinLat + place.latOffset,
                                                longitude = activePinLng + place.lngOffset,
                                                title = place.name,
                                                address = place.address,
                                                accuracyMeters = 8f
                                            )
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            place.categoryIcon,
                                            contentDescription = null,
                                            tint = TealPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = place.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = place.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Google Maps Styled Canvas Renderer (Used in Picker & Chat Bubble Card)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun InteractiveGoogleMapsCanvas(
    latitude: Double,
    longitude: Double,
    zoomLevel: Float = 1f,
    isSatellite: Boolean = false,
    isDark: Boolean = false,
    isLiveLocation: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gps_pulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        // Base Map Background
        val bgColor = when {
            isSatellite -> Color(0xFF0F241D)
            isDark -> Color(0xFF17263C)
            else -> Color(0xFFE8EAED)
        }
        drawRect(color = bgColor)

        // Compute deterministic offset from lat/lng so panning moves the roads/parks smoothly!
        val shiftX = ((longitude * 12000.0 * zoomLevel) % 240.0).toFloat()
        val shiftY = ((latitude * 12000.0 * zoomLevel) % 240.0).toFloat()

        // Park / Green Zones
        val parkColor = when {
            isSatellite -> Color(0xFF193829)
            isDark -> Color(0xFF1B3B2B)
            else -> Color(0xFFCEEAD6)
        }
        drawRoundRect(
            color = parkColor,
            topLeft = Offset(cx - 190f + shiftX * 0.4f, cy - 130f + shiftY * 0.4f),
            size = Size(130f * zoomLevel.coerceIn(0.7f, 1.5f), 95f * zoomLevel.coerceIn(0.7f, 1.5f)),
            cornerRadius = CornerRadius(18f, 18f)
        )
        drawRoundRect(
            color = parkColor,
            topLeft = Offset(cx + 55f + shiftX * 0.4f, cy + 25f + shiftY * 0.4f),
            size = Size(150f * zoomLevel.coerceIn(0.7f, 1.5f), 85f * zoomLevel.coerceIn(0.7f, 1.5f)),
            cornerRadius = CornerRadius(18f, 18f)
        )

        // Water / River Channel
        val waterColor = when {
            isSatellite -> Color(0xFF0A3147)
            isDark -> Color(0xFF0F3456)
            else -> Color(0xFFA0C3FF)
        }
        val riverPath = Path().apply {
            moveTo(0f, h * 0.78f + shiftY * 0.25f)
            cubicTo(
                w * 0.35f, h * 0.62f + shiftY * 0.25f,
                w * 0.65f, h * 0.88f + shiftY * 0.25f,
                w, h * 0.68f + shiftY * 0.25f
            )
        }
        drawPath(
            path = riverPath,
            color = waterColor,
            style = Stroke(width = 24f * zoomLevel.coerceIn(0.8f, 1.4f))
        )

        // Secondary Street Grid
        val minorRoadColor = when {
            isSatellite -> Color.White.copy(alpha = 0.16f)
            isDark -> Color(0xFF283C5A)
            else -> Color.White
        }
        val step = (64f * zoomLevel.coerceIn(0.65f, 1.6f)).coerceAtLeast(36f)
        var gx = (shiftX % step) - step
        while (gx < w + step) {
            drawLine(
                color = minorRoadColor,
                start = Offset(gx, 0f),
                end = Offset(gx + 22f, h),
                strokeWidth = 6f
            )
            gx += step
        }
        var gy = (shiftY % step) - step
        while (gy < h + step) {
            drawLine(
                color = minorRoadColor,
                start = Offset(0f, gy),
                end = Offset(w, gy - 16f),
                strokeWidth = 6f
            )
            gy += step
        }

        // Major Arterial Google Maps Yellow/Orange Highway
        val highwayColor = when {
            isSatellite -> Color(0xFFFBBF24).copy(alpha = 0.7f)
            isDark -> Color(0xFF6B5322)
            else -> Color(0xFFFDE293)
        }
        drawLine(
            color = highwayColor,
            start = Offset(0f, cy - 28f + shiftY * 0.35f),
            end = Offset(w, cy + 34f + shiftY * 0.35f),
            strokeWidth = 13f
        )
        drawLine(
            color = highwayColor,
            start = Offset(cx - 42f + shiftX * 0.35f, 0f),
            end = Offset(cx + 38f + shiftX * 0.35f, h),
            strokeWidth = 11f
        )

        // Pulsing GPS Accuracy Halo
        val maxHaloRadius = if (isLiveLocation) 58f else 42f
        val haloColor = if (isLiveLocation) Color(0xFF25D366) else Color(0xFF4285F4)
        drawCircle(
            color = haloColor.copy(alpha = (1f - pulseRadius) * 0.35f),
            radius = maxHaloRadius * pulseRadius,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = haloColor.copy(alpha = 0.16f),
            radius = 24f,
            center = Offset(cx, cy)
        )

        // Pin Drop Shadow
        drawOval(
            color = Color.Black.copy(alpha = 0.28f),
            topLeft = Offset(cx - 9f, cy - 3f),
            size = Size(18f, 7f)
        )

        // Google Maps Iconic Red Pin
        val pinBodyPath = Path().apply {
            moveTo(cx, cy)
            cubicTo(cx - 16f, cy - 18f, cx - 18f, cy - 38f, cx, cy - 38f)
            cubicTo(cx + 18f, cy - 38f, cx + 16f, cy - 18f, cx, cy)
            close()
        }
        drawPath(path = pinBodyPath, color = Color(0xFFEA4335))
        drawCircle(
            color = Color(0xFFB31412),
            radius = 5.5f,
            center = Offset(cx, cy - 24f)
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 3. Rich WhatsApp Location Message Bubble Card (Inside ChatConversationScreen)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun WhatsAppLocationBubbleCard(
    locationData: SharedLocationData,
    isOutgoing: Boolean,
    onOpenMap: () -> Unit,
    onDirections: () -> Unit
) {
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
    val cardBg = when {
        isDark && isOutgoing -> Color(0xFF121B22)
        isDark && !isOutgoing -> Color(0xFF182229)
        isOutgoing -> Color.White
        else -> Color(0xFFF8FAFC)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFE2E8F0)
    val titleColor = if (isDark) Color.White else Color(0xFF0F172A)
    val subtitleColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpenMap() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Map Preview Thumbnail with Pin & Badges
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                InteractiveGoogleMapsCanvas(
                    latitude = locationData.latitude,
                    longitude = locationData.longitude,
                    zoomLevel = 1.15f,
                    isSatellite = false,
                    isDark = isDark,
                    isLiveLocation = locationData.isLiveLocation,
                    modifier = Modifier.fillMaxSize()
                )

                // Top-Start: Google Maps Chip
                Surface(
                    color = if (isDark) Color(0xFF0F172A).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.5.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFCBD5E1)),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Google Maps",
                            color = if (isDark) Color.White else Color(0xFF0F172A),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Top-End: LIVE badge if Live Location
                if (locationData.isLiveLocation) {
                    Surface(
                        color = Color(0xFFEF4444),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Sensors,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "LIVE",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Location Details & Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = locationData.title.ifBlank { "Pinned Location" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = locationData.address.ifBlank {
                        String.format(Locale.US, "%.5f, %.5f", locationData.latitude, locationData.longitude)
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = subtitleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (locationData.comment.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "“${locationData.comment}”",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFF4ADE80) else Color(0xFF059669)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val mapBtnBg = if (isDark) Color(0xFF1E293B) else Color(0xFFECFDF5)
                    val mapBtnTint = if (isDark) Color(0xFF4ADE80) else Color(0xFF059669)
                    Surface(
                        onClick = onOpenMap,
                        shape = RoundedCornerShape(10.dp),
                        color = mapBtnBg,
                        border = BorderStroke(0.8.dp, mapBtnTint.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = mapBtnTint,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "View on Map",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = mapBtnTint
                            )
                        }
                    }

                    val dirBtnBg = if (isDark) Color(0xFF1E293B) else Color(0xFFF0F9FF)
                    val dirBtnTint = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
                    Surface(
                        onClick = onDirections,
                        shape = RoundedCornerShape(10.dp),
                        color = dirBtnBg,
                        border = BorderStroke(0.8.dp, dirBtnTint.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Directions,
                                contentDescription = null,
                                tint = dirBtnTint,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Directions",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = dirBtnTint
                            )
                        }
                    }
                }
            }
        }
    }
}

fun openInGoogleMaps(
    context: Context,
    latitude: Double,
    longitude: Double,
    label: String,
    navigationMode: Boolean = false
) {
    try {
        val encodedLabel = Uri.encode(label.ifBlank { "Shared Location" })
        val uriString = if (navigationMode) {
            "google.navigation:q=$latitude,$longitude"
        } else {
            "geo:$latitude,$longitude?q=$latitude,$longitude($encodedLabel)"
        }
        val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapIntent)
            return
        }
    } catch (_: Exception) {}

    // Universal fallback to browser/system maps handler
    try {
        val webUrl = "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open Maps", Toast.LENGTH_SHORT).show()
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 4. WhatsApp Contact Card & Poll Card inside MessageBubble + Picker Dialogs
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun WhatsAppContactBubbleCard(
    contactData: SharedContactData,
    isOutgoing: Boolean,
    onCallClick: (String, String) -> Unit
) {
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
    val cardBg = when {
        isDark && isOutgoing -> Color(0xFF121B22)
        isDark && !isOutgoing -> Color(0xFF182229)
        isOutgoing -> Color.White
        else -> Color(0xFFF8FAFC)
    }
    val titleColor = if (isDark) Color.White else Color(0xFF0F172A)
    val subtitleColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
    val accentGreen = if (isDark) Color(0xFF4ADE80) else Color(0xFF059669)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFE2E8F0)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF009DE2),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = contactData.name.take(1).uppercase().ifBlank { "👤" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contactData.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = contactData.phoneNumber,
                        fontSize = 12.sp,
                        color = subtitleColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = if (isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFE2E8F0))
            TextButton(
                onClick = { onCallClick(contactData.phoneNumber, contactData.name) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = null,
                    tint = accentGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Call Contact",
                    fontWeight = FontWeight.Bold,
                    color = accentGreen
                )
            }
        }
    }
}

@Composable
fun WhatsAppPollBubbleCard(
    pollData: SharedPollData,
    isOutgoing: Boolean
) {
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
    var selectedOptionIndex by remember { mutableIntStateOf(-1) }
    val cardBg = when {
        isDark && isOutgoing -> Color(0xFF121B22)
        isDark && !isOutgoing -> Color(0xFF182229)
        isOutgoing -> Color.White
        else -> Color(0xFFF8FAFC)
    }
    val titleColor = if (isDark) Color.White else Color(0xFF0F172A)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val accentGreen = if (isDark) Color(0xFF4ADE80) else Color(0xFF059669)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.1f) else Color(0xFFE2E8F0)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Poll,
                    contentDescription = null,
                    tint = accentGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = pollData.question,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = titleColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select one option",
                fontSize = 11.sp,
                color = subtitleColor
            )
            Spacer(modifier = Modifier.height(8.dp))

            pollData.options.forEachIndexed { idx, opt ->
                val isSelected = selectedOptionIndex == idx
                Surface(
                    onClick = { selectedOptionIndex = if (isSelected) -1 else idx },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) GreenCall.copy(alpha = 0.18f) else (if (isDark) Color.White.copy(alpha = 0.06f) else Color(0xFFF1F5F9)),
                    border = if (isSelected) BorderStroke(1.2.dp, GreenCall) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) GreenCall else subtitleColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = opt,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = titleColor,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Text(
                                text = "1 vote",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentGreen
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WhatsAppShareContactDialog(
    contacts: List<ContactDto>,
    onDismiss: () -> Unit,
    onSendContact: (SharedContactData) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customPhone by remember { mutableStateOf("") }

    val filtered = remember(contacts, search) {
        if (search.isBlank()) contacts.take(30)
        else contacts.filter {
            it.name.contains(search, ignoreCase = true) || it.phoneNumber.contains(search)
        }.take(30)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send Contact", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (contacts.isNotEmpty()) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search contacts…") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(filtered) { c ->
                            ListItem(
                                headlineContent = { Text(c.name, fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text(c.phoneNumber) },
                                leadingContent = {
                                    Surface(
                                        shape = CircleShape,
                                        color = TealPrimary.copy(alpha = 0.2f),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = c.name.take(1).uppercase(),
                                                fontWeight = FontWeight.Bold,
                                                color = TealPrimary
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable {
                                    onSendContact(SharedContactData(c.name, c.phoneNumber))
                                }
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Contact Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customPhone,
                        onValueChange = { customPhone = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (contacts.isEmpty()) {
                Button(
                    onClick = {
                        if (customPhone.isNotBlank()) {
                            onSendContact(SharedContactData(customName.ifBlank { customPhone }, customPhone))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenCall)
                ) {
                    Text("Send", color = Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun WhatsAppCreatePollDialog(
    onDismiss: () -> Unit,
    onSendPoll: (SharedPollData) -> Unit
) {
    var question by remember { mutableStateOf("") }
    var option1 by remember { mutableStateOf("") }
    var option2 by remember { mutableStateOf("") }
    var option3 by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Poll", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Question") },
                    placeholder = { Text("Ask a question…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = option1,
                    onValueChange = { option1 = it },
                    label = { Text("Option 1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = option2,
                    onValueChange = { option2 = it },
                    label = { Text("Option 2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = option3,
                    onValueChange = { option3 = it },
                    label = { Text("Option 3 (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val opts = listOf(option1.trim(), option2.trim(), option3.trim()).filter { it.isNotBlank() }
                    if (question.isNotBlank() && opts.size >= 2) {
                        onSendPoll(SharedPollData(question.trim(), opts))
                    }
                },
                enabled = question.isNotBlank() && option1.isNotBlank() && option2.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = GreenCall)
            ) {
                Text("Send Poll", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// 5. Sleek WhatsApp / Telegram 3-Dots Dropdown Menu Item & Quick Wallpaper Sheet
//    (Adapts to Light Mode by default, or Dark Mode if user enables it!)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun WhatsAppStyledMenuItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    iconTint: Color,
    isDestructive: Boolean = false,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
    val textColor = when {
        isDestructive -> Color(0xFFEF4444)
        isDark -> Color(0xFFF1F5F9)
        else -> Color(0xFF0F172A)
    }
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val badgeBg = if (isDestructive) Color(0xFFEF4444).copy(alpha = 0.12f) else iconTint.copy(alpha = 0.14f)

    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circular / squircle tinted icon badge for 100% consistent alignment
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isDestructive) Color(0xFFEF4444) else iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!badgeText.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = iconTint.copy(alpha = 0.14f),
                    border = BorderStroke(0.8.dp, iconTint.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = iconTint,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun WhatsAppQuickWallpaperDialog(
    wallpaperManager: com.example.util.ChatWallpaperManager,
    onDismiss: () -> Unit
) {
    val isDark = com.example.ui.theme.LocalIsDarkTheme.current
    val currentConfig by wallpaperManager.config.collectAsState()
    val presets = remember { com.example.util.ChatWallpaperManager.PRESETS }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (isDark) Color(0xFF161F28) else Color.White,
        titleContentColor = if (isDark) Color.White else Color(0xFF0F172A),
        textContentColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Wallpaper,
                    contentDescription = null,
                    tint = Color(0xFF0284C7),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Chat Wallpaper",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = if (isDark) Color.White else Color(0xFF0F172A)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Choose a signature WhatsApp / RB Dialer chat background:",
                    fontSize = 12.sp,
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(presets) { preset ->
                        val isSelected = currentConfig.type == com.example.util.WallpaperType.PRESET &&
                                currentConfig.presetId == preset.id
                        Surface(
                            onClick = {
                                wallpaperManager.setPreset(preset.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDark) Color(0xFF1F2C34) else Color(0xFFF8FAFC),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 0.8.dp,
                                color = if (isSelected) Color(0xFF10B981) else (if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0))
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Brush.verticalGradient(preset.gradientColors))
                                        .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(10.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = if (isDark) Color.White else Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = if (preset.isDoodle) "Classic WhatsApp chat canvas" else "Smooth gradient theme",
                                        fontSize = 11.sp,
                                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    wallpaperManager.resetToDefault()
                    onDismiss()
                }
            ) {
                Text("Reset Default", color = Color(0xFF0284C7), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = if (isDark) Color.White else Color(0xFF0F172A))
            }
        }
    )
}


