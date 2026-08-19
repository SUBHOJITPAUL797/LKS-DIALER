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
import androidx.compose.material.icons.automirrored.filled.Backspace
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
import com.example.ui.theme.LocalThemeColor
import com.example.util.CountryCodes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    firebaseManager: FirebaseManager,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val themeColor = LocalThemeColor.current
    var dialNumber by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf(CountryCodes.defaultCountry) }
    var showCountryPicker by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp

    // Adaptive sizing for all Android device screen sizes
    val keySize = when {
        screenHeight < 620 -> 50.dp
        screenHeight < 700 -> 56.dp
        screenHeight < 800 -> 60.dp
        else -> 64.dp
    }
    val keyFontSize = if (screenHeight < 700) 22.sp else 25.sp
    val keySubFontSize = if (screenHeight < 700) 8.sp else 9.sp
    val keyRowPadding = if (screenHeight < 700) 1.dp else 3.dp
    val callButtonSize = if (screenHeight < 700) 54.dp else 62.dp

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
        // WhatsApp-Style Top App Bar
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LKS DIALER",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        ),
                        color = themeColor.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Surface(
                        color = themeColor.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(GreenCall)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "VoIP Ready",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = themeColor.primary
                            )
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = {
                    val clipStr = clipboardManager.getText()?.text?.toString()
                    if (!clipStr.isNullOrBlank()) {
                        dialNumber = clipStr.filter { it.isDigit() || it == '+' }
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste number", tint = themeColor.primary)
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = themeColor.primary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // Center Column: Sits keypad & input centered with max-width for tablets & foldables
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .widthIn(max = 420.dp)
                .align(Alignment.CenterHorizontally),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Country Code selector chip
            Surface(
                onClick = { showCountryPicker = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = selectedCountry.flagEmoji, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = selectedCountry.dialCode,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Number Input Display Row with Dedicated Inline Backspace on the right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Left spacer to keep the digits perfectly centered
                Spacer(modifier = Modifier.size(44.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (dialNumber.isEmpty()) "Enter number..." else dialNumber,
                        fontSize = when {
                            dialNumber.length > 13 -> 22.sp
                            dialNumber.length > 10 -> 26.sp
                            else -> 32.sp
                        },
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (dialNumber.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Dedicated Backspace button on the right
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (dialNumber.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { if (dialNumber.isNotEmpty()) dialNumber = dialNumber.dropLast(1) },
                                    onLongClick = { dialNumber = "" }
                                ),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Backspace",
                                    tint = themeColor.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Matched User Inline Badge
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
                                color = themeColor.primary
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

            Spacer(modifier = Modifier.height(10.dp))

            // Clean 3-Column Keypad Grid (No overlaps)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                KeypadRow("1", "", "2", "ABC", "3", "DEF", keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize, rowPadding = keyRowPadding) { dialNumber += it }
                KeypadRow("4", "GHI", "5", "JKL", "6", "MNO", keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize, rowPadding = keyRowPadding) { dialNumber += it }
                KeypadRow("7", "PQRS", "8", "TUV", "9", "WXYZ", keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize, rowPadding = keyRowPadding) { dialNumber += it }
                KeypadRow("*", "★", "0", "+", "#", "♯", keySize = keySize, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize, rowPadding = keyRowPadding) { digit ->
                    if (digit == "+") dialNumber += "+" else dialNumber += digit
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Call Actions Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp),
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
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact", tint = themeColor.primary, modifier = Modifier.size(22.dp))
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

                // Theme Accent Video Call Button
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
                    colors = ButtonDefaults.buttonColors(containerColor = themeColor.primary)
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.White, modifier = Modifier.size(28.dp))
                }
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
