package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallType
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import com.example.util.SimInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimCallPickerModal(
    phoneNumber: String,
    displayName: String,
    isRegisteredOnLks: Boolean,
    activeSims: List<SimInfo>,
    onDismissRequest: () -> Unit,
    onStartVoipCall: (CallType) -> Unit,
    onStartCellularCall: (SimInfo?) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Recipient Header
            Surface(
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                color = if (isRegisteredOnLks) GreenCall.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = (displayName.firstOrNull() ?: phoneNumber.firstOrNull() ?: '?').uppercase(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isRegisteredOnLks) GreenCall else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = displayName.ifBlank { phoneNumber },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (displayName.isNotBlank() && displayName != phoneNumber) {
                Text(
                    text = phoneNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // LKS Status Chip
            Surface(
                color = if (isRegisteredOnLks) GreenCall.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRegisteredOnLks) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isRegisteredOnLks) GreenCall else Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRegisteredOnLks) "Available on LKS (Free HD Call)" else "Not on LKS • Use Cellular SIM",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRegisteredOnLks) GreenCall else Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 1: LKS VoIP Calling ──────────────────────────────────────────────
            Text(
                text = "INTERNET CALL (LKS VoIP)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // Audio VoIP Call Option
            CallingOptionCard(
                icon = Icons.Default.Call,
                iconColor = Color.White,
                iconBg = GreenCall,
                title = "LKS HD Audio Call",
                subtitle = "Free • End-to-end encrypted • Zero data loss",
                badgeText = if (isRegisteredOnLks) "HD Voice" else null,
                badgeColor = GreenCall,
                onClick = {
                    onStartVoipCall(CallType.AUDIO)
                    onDismissRequest()
                }
            )

            if (isRegisteredOnLks) {
                Spacer(modifier = Modifier.height(8.dp))
                // Video VoIP Call Option
                CallingOptionCard(
                    icon = Icons.Default.Videocam,
                    iconColor = Color.White,
                    iconBg = TealPrimary,
                    title = "LKS HD Video Call",
                    subtitle = "Encrypted 1080p video • Low latency",
                    badgeText = "Video",
                    badgeColor = TealPrimary,
                    onClick = {
                        onStartVoipCall(CallType.VIDEO)
                        onDismissRequest()
                    }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Section 2: Cellular SIM Options ──────────────────────────────────────────
            Text(
                text = "CELLULAR CARRIER (NATIVE SIM)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            if (activeSims.isNotEmpty()) {
                activeSims.forEach { sim ->
                    CallingOptionCard(
                        icon = Icons.Default.SimCard,
                        iconColor = Color.White,
                        iconBg = if (sim.slotIndex == 0) Color(0xFF1E88E5) else Color(0xFF8E24AA),
                        title = "SIM ${sim.slotIndex + 1}: ${sim.displayName}",
                        subtitle = "Carrier phone call via ${sim.carrierName}${if (sim.countryIso.isNotBlank()) " (${sim.countryIso})" else ""}",
                        badgeText = "SIM ${sim.slotIndex + 1}",
                        badgeColor = if (sim.slotIndex == 0) Color(0xFF1E88E5) else Color(0xFF8E24AA),
                        onClick = {
                            onStartCellularCall(sim)
                            onDismissRequest()
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else {
                CallingOptionCard(
                    icon = Icons.Default.PhoneInTalk,
                    iconColor = Color.White,
                    iconBg = Color(0xFF546E7A),
                    title = "Cellular Call",
                    subtitle = "Call via device native cellular network",
                    badgeText = "SIM",
                    badgeColor = Color(0xFF546E7A),
                    onClick = {
                        onStartCellularCall(null)
                        onDismissRequest()
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cancel Button
            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Cancel", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CallingOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color = Color.Gray,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = iconBg
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (badgeText != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = badgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
