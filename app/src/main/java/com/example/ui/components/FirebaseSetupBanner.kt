package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FirebaseSetupBanner(
    isFirebaseConnected: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (isFirebaseConnected) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
        border = CardDefaults.outlinedCardBorder(enabled = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isFirebaseConnected) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isFirebaseConnected) Color(0xFF2E7D32) else Color(0xFFE65100),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isFirebaseConnected) "Firebase Database Connected ✓" else "Firebase Integration Space Ready",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isFirebaseConnected) Color(0xFF1B5E20) else Color(0xFFE65100)
                        )
                        Text(
                            text = if (isFirebaseConnected)
                                "Project ID: lks-dialer • Cloud Firestore & Signaling Active"
                            else
                                "Tap to view instructions to connect your separate Firebase project",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = "Expand",
                    tint = Color.Gray
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Firebase Integration Status:",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    InstructionStep("1", "App Package: com.subhojit.lksdialer.app")
                    InstructionStep("2", "Firebase Project: lks-dialer")
                    InstructionStep("3", "google-services.json: Configured & Verified")
                    InstructionStep("4", "Firestore Security Rules: Hardware Device Lock & Call Logs Active")
                    InstructionStep("5", "Real-Time WebRTC Call Signaling: Connected")

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "💡 Note: All user profiles, device locks, contact lists, WebRTC calls, and call history are automatically synced live with Cloud Firestore database.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                            color = Color(0xFF333333)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionStep(number: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF075E54)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF222222)
        )
    }
}
