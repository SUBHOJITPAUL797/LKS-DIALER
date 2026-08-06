package com.example.ui.screens.dialer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallType
import com.example.data.model.CountryCode
import com.example.data.model.UserDto
import com.example.data.repository.FirebaseManager
import com.example.ui.components.CountryCodePickerModal
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import com.example.util.CountryCodes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen(
    firebaseManager: FirebaseManager,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var dialNumber by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf(CountryCodes.defaultCountry) }
    var showCountryPicker by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val matchedUser = remember(dialNumber) {
        if (dialNumber.length >= 7) {
            val fullNumber = CountryCodes.formatPhoneNumber(selectedCountry.dialCode, dialNumber)
            firebaseManager.lookupUserByNumber(fullNumber)
        } else null
    }

    if (showCountryPicker) {
        CountryCodePickerModal(
            selectedCountry = selectedCountry,
            onCountrySelected = { selectedCountry = it },
            onDismissRequest = { showCountryPicker = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LKS DIALER",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TealPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = GreenCall.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "VoIP Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = TealPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = {
                    val clipStr = clipboardManager.getText()?.text?.toString()
                    if (!clipStr.isNullOrBlank()) {
                        dialNumber = clipStr.filter { it.isDigit() }
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste number", tint = TealPrimary)
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TealPrimary)
                }
            }
        )

        // Number Input & Lookup Display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Country Code selector chip
            Surface(
                onClick = { showCountryPicker = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = selectedCountry.flagEmoji, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = selectedCountry.dialCode,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Large Phone Number Display
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (dialNumber.isEmpty()) "Enter number..." else dialNumber,
                    fontSize = if (dialNumber.length > 10) 28.sp else 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (dialNumber.isEmpty()) Color.LightGray else MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )

                if (dialNumber.isNotEmpty()) {
                    IconButton(
                        onClick = { if (dialNumber.isNotEmpty()) dialNumber = dialNumber.dropLast(1) },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.Default.Backspace, contentDescription = "Backspace", tint = Color.Gray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Matched User Real-time Lookup Card
            AnimatedVisibility(visible = matchedUser != null) {
                matchedUser?.let { user ->
                    Surface(
                        color = GreenCall.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = GreenCall,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = user.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TealPrimary
                                )
                                Text(
                                    text = user.statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.DarkGray
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3x4 Keypad Grid
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            KeypadRow("1", "", "2", "ABC", "3", "DEF") { dialNumber += it }
            KeypadRow("4", "GHI", "5", "JKL", "6", "MNO") { dialNumber += it }
            KeypadRow("7", "PQRS", "8", "TUV", "9", "WXYZ") { dialNumber += it }
            KeypadRow("*", "★", "0", "+", "#", "♯") { digit ->
                dialNumber += digit
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Call Actions Row (Audio & Video Buttons)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Add Contact Quick Button
            IconButton(
                onClick = {
                    if (dialNumber.isNotBlank()) {
                        firebaseManager.addContact("Contact $dialNumber", dialNumber)
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact", tint = TealPrimary)
            }

            // Green Audio Call Button
            Button(
                onClick = {
                    if (dialNumber.isNotBlank()) {
                        val fullNum = CountryCodes.formatPhoneNumber(selectedCountry.dialCode, dialNumber)
                        val calleeName = matchedUser?.displayName ?: fullNum
                        onStartCall(fullNum, calleeName, CallType.AUDIO)
                    }
                },
                enabled = dialNumber.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.size(68.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GreenCall)
            ) {
                Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = Color.White, modifier = Modifier.size(32.dp))
            }

            // Green Video Call Button
            Button(
                onClick = {
                    if (dialNumber.isNotBlank()) {
                        val fullNum = CountryCodes.formatPhoneNumber(selectedCountry.dialCode, dialNumber)
                        val calleeName = matchedUser?.displayName ?: fullNum
                        onStartCall(fullNum, calleeName, CallType.VIDEO)
                    }
                },
                enabled = dialNumber.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.size(68.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
            ) {
                Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun KeypadRow(
    d1: String, s1: String,
    d2: String, s2: String,
    d3: String, s3: String,
    onDigitClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        KeypadButton(d1, s1) { onDigitClick(d1) }
        KeypadButton(d2, s2) { onDigitClick(d2) }
        KeypadButton(d3, s3) { onDigitClick(d3) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadButton(
    digit: String,
    subText: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (digit == "0") onClick() // Inserts + on long press 0
                }
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = digit,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subText.isNotEmpty()) {
                Text(
                    text = subText,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


