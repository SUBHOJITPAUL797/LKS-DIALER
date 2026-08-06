package com.example.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.repository.FirebaseManager
import com.example.ui.components.FirebaseSetupBanner
import com.example.ui.theme.TealPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    firebaseManager: FirebaseManager,
    onBackClick: (() -> Unit)? = null
) {
    val isFirebaseConnected by firebaseManager.isFirebaseConfigured.collectAsState()

    var isNoiseSuppressionOn by remember { mutableStateOf(true) }
    var isEchoCancellationOn by remember { mutableStateOf(true) }
    var isDataSaverOn by remember { mutableStateOf(false) }
    var isVibrateOn by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Firebase setup banner at top of settings
            FirebaseSetupBanner(isFirebaseConnected = isFirebaseConnected)

            Spacer(modifier = Modifier.height(16.dp))

            // Audio & Quality Settings Section
            SettingsSectionHeader("Audio & Quality (WebRTC)")
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsSwitchTile(
                        title = "Hardware Noise Suppression",
                        subtitle = "Reduces ambient background noise during calls",
                        icon = Icons.Default.GraphicEq,
                        checked = isNoiseSuppressionOn,
                        onCheckedChange = { isNoiseSuppressionOn = it }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    SettingsSwitchTile(
                        title = "Acoustic Echo Cancellation",
                        subtitle = "Prevents speaker echo during speakerphone calls",
                        icon = Icons.Default.Hearing,
                        checked = isEchoCancellationOn,
                        onCheckedChange = { isEchoCancellationOn = it }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    SettingsSwitchTile(
                        title = "Low Data Mode",
                        subtitle = "Limits Opus audio to 16kbps & video to 360p",
                        icon = Icons.Default.DataUsage,
                        checked = isDataSaverOn,
                        onCheckedChange = { isDataSaverOn = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Notifications & Ringing
            SettingsSectionHeader("Notifications & Ringtone")
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsSwitchTile(
                        title = "Vibrate on Incoming Call",
                        subtitle = "Vibrate device for incoming VoIP calls",
                        icon = Icons.Default.Vibration,
                        checked = isVibrateOn,
                        onCheckedChange = { isVibrateOn = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Security & Privacy
            SettingsSectionHeader("Security & Encryption")
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = TealPrimary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "End-to-End Encryption",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "All media packets are encrypted using DTLS-SRTP. Neither server nor relay can decrypt media.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = TealPrimary,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsSwitchTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = TealPrimary)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = TealPrimary)
        )
    }
}
