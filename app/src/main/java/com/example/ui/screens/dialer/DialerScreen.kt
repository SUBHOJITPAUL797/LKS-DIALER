package com.example.ui.screens.dialer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
    val syncedContacts by firebaseManager.syncedContacts.collectAsState()
    val nonLksContacts by firebaseManager.nonLksContacts.collectAsState()
    val callLogs by firebaseManager.callLogs.collectAsState()

    val t9Matches by remember(dialNumber, syncedContacts, nonLksContacts, callLogs, registeredUsers) {
        derivedStateOf {
            val queryDigits = dialNumber.filter { it.isDigit() }
            if (queryDigits.isEmpty()) {
                emptyList<T9MatchItem>()
            } else {
                val seenNumbers = mutableSetOf<String>()
                val result = mutableListOf<T9MatchItem>()

                for (contact in syncedContacts) {
                    val cleanPhone = contact.phoneNumber.filter { it.isDigit() }
                    val t9Name = nameToT9(contact.name)
                    if (cleanPhone.contains(queryDigits) || t9Name.contains(queryDigits)) {
                        val norm = com.example.util.ContactsHelper.normalizePhoneNumber(contact.phoneNumber)
                        if (seenNumbers.add(norm)) {
                            result.add(
                                T9MatchItem(
                                    name = contact.name,
                                    phoneNumber = contact.phoneNumber,
                                    isLksUser = true,
                                    profilePic = contact.profilePictureUrl
                                )
                            )
                        }
                    }
                }

                for (contact in nonLksContacts) {
                    val cleanPhone = contact.phoneNumber.filter { it.isDigit() }
                    val t9Name = nameToT9(contact.name)
                    if (cleanPhone.contains(queryDigits) || t9Name.contains(queryDigits)) {
                        val norm = com.example.util.ContactsHelper.normalizePhoneNumber(contact.phoneNumber)
                        if (seenNumbers.add(norm)) {
                            result.add(
                                T9MatchItem(
                                    name = contact.name,
                                    phoneNumber = contact.phoneNumber,
                                    isLksUser = false
                                )
                            )
                        }
                    }
                }

                for (log in callLogs) {
                    val cleanPhone = log.otherPartyNumber.filter { it.isDigit() }
                    val t9Name = nameToT9(log.otherPartyName)
                    if (cleanPhone.contains(queryDigits) || t9Name.contains(queryDigits)) {
                        val norm = com.example.util.ContactsHelper.normalizePhoneNumber(log.otherPartyNumber)
                        if (seenNumbers.add(norm)) {
                            result.add(
                                T9MatchItem(
                                    name = log.otherPartyName.ifBlank { log.otherPartyNumber },
                                    phoneNumber = log.otherPartyNumber,
                                    isLksUser = registeredUsers.any { it.phoneNumber == norm },
                                    profilePic = log.otherPartyProfilePic
                                )
                            )
                        }
                    }
                }

                result.take(12)
            }
        }
    }

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

            // Smart Dialer T9 Suggestions Row
            AnimatedVisibility(
                visible = t9Matches.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(t9Matches, key = { it.phoneNumber }) { match ->
                        T9SuggestionChip(
                            match = match,
                            onSelect = {
                                val clean = match.phoneNumber.filter { it.isDigit() || it == '+' }
                                dialNumber = clean
                            },
                            onCall = {
                                onStartCall(match.phoneNumber, match.name, CallType.AUDIO)
                            }
                        )
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

data class T9MatchItem(
    val name: String,
    val phoneNumber: String,
    val isLksUser: Boolean,
    val profilePic: String = ""
)

private fun charToT9(c: Char): Char = when (c.uppercaseChar()) {
    'A', 'B', 'C' -> '2'
    'D', 'E', 'F' -> '3'
    'G', 'H', 'I' -> '4'
    'J', 'K', 'L' -> '5'
    'M', 'N', 'O' -> '6'
    'P', 'Q', 'R', 'S' -> '7'
    'T', 'U', 'V' -> '8'
    'W', 'X', 'Y', 'Z' -> '9'
    else -> '0'
}

private fun nameToT9(name: String): String {
    return buildString(name.length) {
        for (ch in name) {
            if (ch.isLetter()) {
                append(charToT9(ch))
            } else if (ch.isDigit()) {
                append(ch)
            }
        }
    }
}

@Composable
private fun T9SuggestionChip(
    match: T9MatchItem,
    onSelect: () -> Unit,
    onCall: () -> Unit
) {
    val themeColor = LocalThemeColor.current
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Initial avatar circle
            Surface(
                shape = CircleShape,
                color = if (match.isLksUser) GreenCall.copy(alpha = 0.2f) else themeColor.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = match.name.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (match.isLksUser) GreenCall else themeColor.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = match.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (match.isLksUser) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            color = GreenCall.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "LKS",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                color = GreenCall,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = match.phoneNumber,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Quick audio call button right on the chip
            IconButton(
                onClick = onCall,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(GreenCall)
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Call ${match.name}",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
