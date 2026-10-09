package com.example.ui.screens.settings

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LinkedDeviceDto
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.GreenCall
import com.example.ui.theme.LocalThemeColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(
    firebaseManager: FirebaseManager,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val currentTheme = LocalThemeColor.current
    val currentUser by firebaseManager.currentUser.collectAsState()
    val linkedDevices by firebaseManager.linkedDevices.collectAsState()

    var isScanning by remember { mutableStateOf(false) }
    var verifyingSession by remember { mutableStateOf(false) }

    // Dialog state for confirming link
    var pendingSessionId by remember { mutableStateOf<String?>(null) }
    var pendingNonce by remember { mutableStateOf<String?>(null) }
    var pendingDeviceName by remember { mutableStateOf<String?>(null) }

    // Dialog state for confirming logout of a linked device
    var deviceToLogout by remember { mutableStateOf<LinkedDeviceDto?>(null) }

    if (isScanning) {
        QrScannerView(
            onQrScanned = { rawQrText ->
                isScanning = false
                verifyingSession = true
                firebaseManager.verifyScannedQrCode(
                    rawQrContent = rawQrText,
                    onValidSession = { sessionId, nonce, deviceName ->
                        verifyingSession = false
                        pendingSessionId = sessionId
                        pendingNonce = nonce
                        pendingDeviceName = deviceName
                    },
                    onError = { error ->
                        verifyingSession = false
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                )
            },
            onClose = { isScanning = false }
        )
        return
    }

    // Confirmation dialog to approve the link
    pendingSessionId?.let { sessionId ->
        val nonce = pendingNonce ?: ""
        val deviceName = pendingDeviceName ?: "Web Browser"

        AlertDialog(
            onDismissRequest = {
                pendingSessionId = null
                pendingNonce = null
                pendingDeviceName = null
            },
            icon = {
                Icon(
                    Icons.Default.Laptop,
                    contentDescription = null,
                    tint = currentTheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Link this device?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = currentTheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = currentTheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "This will log in your LKS Dialer account (${currentUser?.phoneNumber}) on this web browser with full call and encrypted chat access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        firebaseManager.approveQrSession(
                            sessionId = sessionId,
                            nonce = nonce,
                            deviceName = deviceName,
                            onSuccess = {
                                Toast.makeText(context, "Device linked successfully!", Toast.LENGTH_SHORT).show()
                                pendingSessionId = null
                                pendingNonce = null
                                pendingDeviceName = null
                            },
                            onError = { err ->
                                Toast.makeText(context, "Failed to link: $err", Toast.LENGTH_LONG).show()
                                pendingSessionId = null
                                pendingNonce = null
                                pendingDeviceName = null
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Link Device", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingSessionId = null
                        pendingNonce = null
                        pendingDeviceName = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation dialog to log out of a linked device
    deviceToLogout?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToLogout = null },
            icon = {
                Icon(
                    Icons.Default.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Log out from device?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to log out of \"${device.deviceName.ifBlank { "Web Browser" }}\"? This will immediately terminate the session on that computer.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        firebaseManager.unlinkDevice(device.sessionId) { success ->
                            if (success) {
                                Toast.makeText(context, "Logged out from device", Toast.LENGTH_SHORT).show()
                            }
                        }
                        deviceToLogout = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Log Out", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToLogout = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Linked Devices",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Illustration & Link Button Banner
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = currentTheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = currentTheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Use LKS Dialer on Web",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Link your computer or browser by scanning the QR code on the website to call and chat anytime.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = { isScanning = true },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Link a Device",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // Device Status Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "DEVICE STATUS",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Tap to log out",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Linked Devices List
            if (linkedDevices.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.LaptopChromebook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No devices linked yet",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Open web.lksdialer.pages.dev on your computer and tap Link a Device above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(linkedDevices, key = { it.sessionId }) { device ->
                    LinkedDeviceItem(
                        device = device,
                        onLogoutClick = { deviceToLogout = device }
                    )
                }
            }

            // Security footnote
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Your personal messages and calls are end-to-end encrypted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkedDeviceItem(
    device: LinkedDeviceDto,
    onLogoutClick: () -> Unit
) {
    val currentTheme = LocalThemeColor.current
    val formattedTime = remember(device.linkedAt) {
        if (device.linkedAt > 0) {
            DateUtils.getRelativeTimeSpanString(
                device.linkedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
        } else {
            "Active"
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLogoutClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = currentTheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (device.deviceName.contains("Safari", ignoreCase = true) || 
                            device.deviceName.contains("Apple", ignoreCase = true) ||
                            device.deviceName.contains("Mac", ignoreCase = true)) {
                            Icons.Default.LaptopMac
                        } else if (device.deviceName.contains("Windows", ignoreCase = true) ||
                            device.deviceName.contains("Chrome", ignoreCase = true) ||
                            device.deviceName.contains("Edge", ignoreCase = true)) {
                            Icons.Default.LaptopWindows
                        } else {
                            Icons.Default.Language
                        },
                        contentDescription = null,
                        tint = currentTheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName.ifBlank { "Web Browser" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(GreenCall)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Linked $formattedTime",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onLogoutClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Logout,
                    contentDescription = "Log Out",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
