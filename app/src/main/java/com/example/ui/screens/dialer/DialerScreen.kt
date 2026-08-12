package com.example.ui.screens.dialer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp

    // Adaptive sizing based on screen height
    val keySize = if (screenHeight < 700) 56.dp else if (screenHeight < 800) 60.dp else 64.dp
    val keyFontSize = if (screenHeight < 700) 22.sp else 24.sp
    val keySubFontSize = if (screenHeight < 700) 8.sp else 9.sp
    val keyRowPadding = if (screenHeight < 700) 1.dp else 2.dp
    val callButtonSize = if (screenHeight < 700) 58.dp else 64.dp

    val registeredUsers by firebaseManager.registeredUsers.collectAsState()

    val matchedUser: UserDto? by remember(dialNumber, registeredUsers) {
        derivedStateOf {
            if (dialNumber.length >= 7) {
                val fullNumber = CountryCodes.formatPhoneNumber(selectedCountry.dialCode, dialNumber)
                firebaseManager.lookupUserByNumber(fullNumber)
            } else null
        }
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

        // Number Input Section - takes remaining space above keypad
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            // Country Code selector chip
            Surface(
                onClick = { showCountryPicker = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = selectedCountry.flagEmoji, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = selectedCountry.dialCode,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Large Phone Number Display (no backspace here anymore)
            Text(
                text = if (dialNumber.isEmpty()) "Enter number..." else dialNumber,
                fontSize = when {
                    dialNumber.length > 13 -> 24.sp
                    dialNumber.length > 10 -> 28.sp
                    else -> 34.sp
                },
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (dialNumber.isEmpty()) Color.LightGray else MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // Matched User Inline Badge - compact, sits right below number
            AnimatedVisibility(
                visible = matchedUser != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                matchedUser?.let { user ->
                    Surface(
                        color = GreenCall.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = GreenCall,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = user.displayName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = TealPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "• Available",
                                style = MaterialTheme.typography.labelSmall,
                                color = GreenCall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // 4x4 Keypad Grid - tighter spacing
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            KeypadRow("1", "", "2", "ABC", "3", "DEF", keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize, rowPadding = keyRowPadding) { dialNumber += it }
            KeypadRow("4", "GHI", "5", "JKL", "6", "MNO", keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize, rowPadding = keyRowPadding) { dialNumber += it }
            KeypadRow("7", "PQRS", "8", "TUV", "9", "WXYZ", keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize, rowPadding = keyRowPadding) { dialNumber += it }
            // Bottom row: *, 0, #, Backspace
            Box(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                // The *, 0, # keys perfectly aligned with the grid
                KeypadRow("*", "★", "0", "+", "#", "♯", keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize, rowPadding = keyRowPadding) { digit ->
                    if (digit == "+") dialNumber += "+" else dialNumber += digit
                }
                
                // Backspace button positioned on the right side without disrupting the grid
                Surface(
                    modifier = Modifier
                        .size(keySize)
                        .clip(CircleShape)
                        .align(Alignment.CenterEnd)
                        .offset(x = 12.dp) // Push slightly into the padding so it doesn't overlap the # key
                        .combinedClickable(
                            onClick = { if (dialNumber.isNotEmpty()) dialNumber = dialNumber.dropLast(1) },
                            onLongClick = { dialNumber = "" }
                        ),
                    shape = CircleShape,
                    color = if (dialNumber.isNotEmpty()) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                           else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Backspace,
                            contentDescription = "Backspace",
                            tint = if (dialNumber.isNotEmpty()) MaterialTheme.colorScheme.error
                                   else Color.Gray,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Call Actions Row (Audio & Video Buttons)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
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
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact", tint = TealPrimary, modifier = Modifier.size(22.dp))
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
                modifier = Modifier.size(callButtonSize),
                colors = ButtonDefaults.buttonColors(containerColor = GreenCall)
            ) {
                Icon(Icons.Default.Call, contentDescription = "Audio Call", tint = Color.White, modifier = Modifier.size(28.dp))
            }

            // Teal Video Call Button
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
                modifier = Modifier.size(callButtonSize),
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
            ) {
                Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun KeypadRow(
    d1: String, s1: String,
    d2: String, s2: String,
    d3: String, s3: String,
    keySize: androidx.compose.ui.unit.Dp,
    keyFontSize: androidx.compose.ui.unit.TextUnit,
    keySubFontSize: androidx.compose.ui.unit.TextUnit,
    rowPadding: androidx.compose.ui.unit.Dp,
    onDigitClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = rowPadding),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        KeypadButton(d1, s1, onClick = { onDigitClick(d1) }, keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize)
        KeypadButton(d2, s2, onClick = { onDigitClick(d2) }, onLongClick = if (d2 == "0") ({ onDigitClick("+") }) else null, keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize)
        KeypadButton(d3, s3, onClick = { onDigitClick(d3) }, keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadButton(
    digit: String,
    subText: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    keySize: androidx.compose.ui.unit.Dp = 64.dp,
    keyFontSize: androidx.compose.ui.unit.TextUnit = 24.sp,
    keySubFontSize: androidx.compose.ui.unit.TextUnit = 9.sp
) {
    Surface(
        modifier = Modifier
            .size(keySize)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
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
                fontSize = keyFontSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subText.isNotEmpty()) {
                Text(
                    text = subText,
                    fontSize = keySubFontSize,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
