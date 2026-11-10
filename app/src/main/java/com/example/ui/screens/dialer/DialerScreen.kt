package com.example.ui.screens.dialer

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallType
import com.example.data.model.UserDto
import com.example.data.repository.FirebaseManager
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.CountryCodes
import com.example.util.DeviceUtils
import com.example.util.SimManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    firebaseManager: FirebaseManager,
    onStartCall: (number: String, name: String, callType: CallType) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLinkedDevices: () -> Unit = {},
    onNavigateToChats: () -> Unit = {},
    onNavigateToContacts: () -> Unit = {},
    onOpenDirectChat: ((number: String, name: String) -> Unit)? = null
) {
    val themeColor = LocalThemeColor.current
    val context = LocalContext.current
    var dialNumber by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf(CountryCodes.defaultCountry) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var showSimPicker by remember { mutableStateOf(false) }

    // Progressive Disclosure States (OFF by default so optional features never clutter core work)
    var showFieldHudTools by remember { mutableStateOf(false) }
    var showQuickGuideOverride by remember { mutableStateOf(false) }
    var isFirstTimeGuideDismissed by remember { mutableStateOf(false) }

    val activeSims = remember { SimManager.getActiveSimCards(context) }
    val clipboardManager = LocalClipboardManager.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp

    val fullDialNumber = remember(selectedCountry, dialNumber) {
        if (dialNumber.isNotBlank()) CountryCodes.formatPhoneNumber(selectedCountry.dialCode, dialNumber) else ""
    }

    var lastCallClickTime by remember { mutableStateOf(0L) }
    val safeCall = { num: String, name: String, type: CallType ->
        val now = System.currentTimeMillis()
        if (now - lastCallClickTime > 1200L) {
            lastCallClickTime = now
            onStartCall(num, name, type)
        }
    }

    val surfaceStyle = LocalSurfaceStyle.current
    // Adaptive sizing for compact 320dp phones, standard phones, foldables, and tablets
    val keyHeight = when {
        screenHeight < 640 -> 46.dp
        screenHeight < 740 -> 50.dp
        else -> 54.dp
    }
    val keyFontSize = if (screenHeight < 700) 20.sp else 22.sp
    val keySubFontSize = if (screenHeight < 700) 8.sp else 9.sp

    val isFirebaseConfigured by firebaseManager.isFirebaseConfigured.collectAsState()
    val isDndEnabled by firebaseManager.isDndEnabled.collectAsState()
    val currentUser by firebaseManager.currentUser.collectAsState()
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

    val matchedUser: UserDto? by remember(dialNumber, registeredUsers, selectedCountry) {
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

    var simPickerTargetName by remember { mutableStateOf<String?>(null) }

    if (showSimPicker && dialNumber.isNotBlank()) {
        val calleeName = simPickerTargetName
            ?: matchedUser?.displayName
            ?: nonLksContacts.firstOrNull { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, fullDialNumber) }?.name
            ?: syncedContacts.firstOrNull { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, fullDialNumber) }?.name
            ?: fullDialNumber
        SimCallPickerModal(
            phoneNumber = fullDialNumber,
            displayName = calleeName,
            isRegisteredOnLks = matchedUser != null,
            activeSims = activeSims,
            onDismissRequest = {
                showSimPicker = false
                simPickerTargetName = null
            },
            onStartVoipCall = { type ->
                safeCall(fullDialNumber, calleeName, type)
            },
            onStartCellularCall = { sim ->
                SimManager.placeCellularCall(context, fullDialNumber, sim)
                val simLabel = sim?.displayName ?: "SIM"
                firebaseManager.recordCellularCall(fullDialNumber, calleeName, simLabel)
                Toast.makeText(context, "Calling via $simLabel...", Toast.LENGTH_SHORT).show()
                simPickerTargetName = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackgroundPrimary)
            .statusBarsPadding()
    ) {
        // 1. TOP HEADER: Compact Status Bar showing App Name and Live Status Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "RB DIALER",
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    letterSpacing = 0.6.sp,
                    color = HudTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                val statusLabel = when {
                    isDndEnabled -> "DND SHIELD"
                    isFirebaseConfigured -> "READY"
                    else -> "LOCAL"
                }
                val statusColor = when {
                    isDndEnabled -> SignalAmber
                    isFirebaseConfigured -> CyberMint
                    else -> TelemetryBlue
                }
                TacticalStatusPill(
                    text = statusLabel,
                    dotColor = statusColor
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { showFieldHudTools = !showFieldHudTools },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("toggle_field_hud")
                ) {
                    Icon(
                        Icons.Default.Radar,
                        contentDescription = "Connection Status",
                        tint = if (showFieldHudTools) SignalAmber else HudTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = {
                        showQuickGuideOverride = !showQuickGuideOverride
                        if (showQuickGuideOverride) isFirstTimeGuideDismissed = false
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("toggle_quick_guide")
                ) {
                    Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = "Quick Guide",
                        tint = if (showQuickGuideOverride) CyberMint else HudTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val clipStr = clipboardManager.getText()?.text?.toString()
                        if (!clipStr.isNullOrBlank()) {
                            dialNumber = clipStr.filter { it.isDigit() || it == '+' }
                        } else {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("paste_number_button")
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = "Paste Number",
                        tint = HudTextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("settings_button")
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = SignalAmber,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Responsive Balanced Body: Weighted Top Contextual Zone + Fixed Bottom-Anchored Dialer & Keypad
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .widthIn(max = 520.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP FLEXIBLE ZONE (Modifier.weight(1f)): Absorbs dynamic T9 matches & Verified Peer cards
            // so the Keypad and Bottom Call/Video Call buttons NEVER shift downwards!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // When no number is being typed (or no T9 matches yet), show the 2x2 Quick Action Grid
                AnimatedVisibility(
                    visible = dialNumber.isEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TacticalActionCard(
                                title = "Audio Call",
                                subtitle = if (dialNumber.isNotBlank()) "DIAL NOW" else "VOICE VOIP",
                                icon = Icons.Default.Call,
                                accentColor = CyberMint,
                                isActive = dialNumber.isNotBlank(),
                                testTag = "action_card_audio_call",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (dialNumber.isNotBlank()) {
                                        val calleeName = matchedUser?.displayName ?: fullDialNumber
                                        if (matchedUser != null) {
                                            safeCall(fullDialNumber, calleeName, CallType.AUDIO)
                                        } else {
                                            showSimPicker = true
                                        }
                                    } else {
                                        val lastCalled = callLogs.firstOrNull()?.otherPartyNumber
                                        if (!lastCalled.isNullOrBlank()) {
                                            dialNumber = lastCalled.filter { it.isDigit() || it == '+' }
                                        } else {
                                            onNavigateToContacts()
                                        }
                                    }
                                }
                            )
                            TacticalActionCard(
                                title = "Video Call",
                                subtitle = if (dialNumber.isNotBlank()) "HD STREAM" else "SRTP VIDEO",
                                icon = Icons.Default.Videocam,
                                accentColor = SignalAmber,
                                isActive = dialNumber.isNotBlank(),
                                testTag = "action_card_video_call",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (dialNumber.isNotBlank()) {
                                        val calleeName = matchedUser?.displayName ?: fullDialNumber
                                        safeCall(fullDialNumber, calleeName, CallType.VIDEO)
                                    } else {
                                        onNavigateToContacts()
                                    }
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TacticalActionCard(
                                title = "Secure Chat",
                                subtitle = "E2EE P2P",
                                icon = Icons.Default.ChatBubbleOutline,
                                accentColor = TelemetryBlue,
                                isActive = false,
                                testTag = "action_card_secure_chat",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (dialNumber.isNotBlank() && onOpenDirectChat != null) {
                                        val targetName = matchedUser?.displayName ?: fullDialNumber
                                        onOpenDirectChat(fullDialNumber, targetName)
                                    } else {
                                        onNavigateToChats()
                                    }
                                }
                            )
                            TacticalActionCard(
                                title = "Link Device",
                                subtitle = "WEB QR",
                                icon = Icons.Default.QrCodeScanner,
                                accentColor = SignalAmber,
                                isActive = false,
                                testTag = "action_card_link_device",
                                modifier = Modifier.weight(1f),
                                onClick = onNavigateToLinkedDevices
                            )
                        }
                    }
                }

                // When typing a number, show Matched RB Peer Card & Smart T9 Suggestions inside the Top Zone
                AnimatedVisibility(
                    visible = matchedUser != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    matchedUser?.let { user ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = CyberMint.copy(alpha = 0.12f),
                            border = BorderStroke(1.2.dp, CyberMint.copy(alpha = 0.65f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = CyberMint.copy(alpha = 0.22f),
                                        border = BorderStroke(1.dp, CyberMint),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = user.displayName.take(1).uppercase(),
                                                fontFamily = SpaceGroteskFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = CyberMint
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = user.displayName,
                                                fontFamily = SpaceGroteskFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = HudTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.Verified,
                                                contentDescription = "Verified RB Peer",
                                                tint = CyberMint,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        Text(
                                            text = "AVAILABLE ON RB DIALER • HD VOIP & VIDEO",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            color = CyberMint,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (onOpenDirectChat != null) {
                                    Surface(
                                        onClick = { onOpenDirectChat(fullDialNumber, user.displayName) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = TelemetryBlue.copy(alpha = 0.2f),
                                        border = BorderStroke(1.dp, TelemetryBlue.copy(alpha = 0.6f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.ChatBubbleOutline,
                                                contentDescription = "Chat",
                                                tint = TelemetryBlue,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "CHAT",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp,
                                                color = TelemetryBlue
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Smart T9 Suggestions Row inside Top Flexible Zone (never pushes keypad down!)
                AnimatedVisibility(
                    visible = t9Matches.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
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
                                    if (match.isLksUser) {
                                        safeCall(match.phoneNumber, match.name, CallType.AUDIO)
                                    } else {
                                        val clean = match.phoneNumber.filter { it.isDigit() || it == '+' }
                                        dialNumber = clean
                                        simPickerTargetName = match.name
                                        showSimPicker = true
                                    }
                                }
                            )
                        }
                    }
                }

                // Optional Progressive Disclosure Panel: Security Status & DND Shield (OFF by default)
                AnimatedVisibility(
                    visible = showFieldHudTools,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = HudGlassOverlay,
                        border = BorderStroke(1.dp, HudBorderSubtle)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SECURITY & ENCRYPTION",
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = HudTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "E2EE VERIFIED • OPUS 48kHz",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = CyberMint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TacticalToggleChip(
                                    label = if (isDndEnabled) "DND: ON" else "DND: OFF",
                                    icon = Icons.Default.DoNotDisturbOn,
                                    selected = isDndEnabled,
                                    activeColor = CrimsonAlert,
                                    testTag = "toggle_dnd_chip",
                                    modifier = Modifier.widthIn(min = 110.dp),
                                    onClick = {
                                        firebaseManager.setDndEnabled(!isDndEnabled)
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SIM TELEMETRY: ${if (activeSims.isEmpty()) "VOIP ONLY" else "${activeSims.size} ACTIVE SIM(S)"}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = CyberMint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "DTLS-SRTP",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = SignalAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 4. OPTIONAL QUICK START GUIDE (Only shown when explicitly toggled by user)
                val shouldShowQuickGuide = showQuickGuideOverride
                AnimatedVisibility(
                    visible = shouldShowQuickGuide,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Box(modifier = Modifier.padding(top = 4.dp)) {
                        QuickStartGuideCard(
                            headerTitle = "3-STEP QUICK START GUIDE",
                            headerSubtitle = "Place encrypted VoIP or cellular calls in 5s",
                            steps = listOf(
                                QuickStartStep(
                                    number = "01",
                                    title = "Enter Number or Pick Contact",
                                    description = "Type digits on the tactical keypad below or select a peer from Contacts.",
                                    accentColor = SignalAmber
                                ),
                                QuickStartStep(
                                    number = "02",
                                    title = "Select Audio, Video, or SIM",
                                    description = "Use Cyber Mint for VoIP Audio, Signal Amber for HD Video, or SIM for cellular.",
                                    accentColor = CyberMint
                                ),
                                QuickStartStep(
                                    number = "03",
                                    title = "Instant E2EE Chat & Link",
                                    description = "Tap Secure Chat for zero-retention messaging or Link Device for Web access.",
                                    accentColor = TelemetryBlue
                                )
                            ),
                            actionButtonText = if (syncedContacts.isEmpty() && nonLksContacts.isEmpty()) "Sync Contacts Now" else "Got It • Ready to Dial",
                            actionButtonIcon = if (syncedContacts.isEmpty() && nonLksContacts.isEmpty()) Icons.Default.Sync else Icons.Default.Check,
                            onActionClick = {
                                if (syncedContacts.isEmpty() && nonLksContacts.isEmpty()) {
                                    firebaseManager.syncNativeContacts()
                                }
                                isFirstTimeGuideDismissed = true
                                showQuickGuideOverride = false
                            }
                        )
                    }
                }
            }

            // BOTTOM-ANCHORED DIALER & KEYPAD ZONE (Constant height — Call & Video buttons NEVER shift down!)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 5. LIVE NUMBER READOUT & COUNTRY SELECTOR CARD (Constant single-row height)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .surfaceFinishOverlay(surfaceStyle, 16.dp, if (matchedUser != null) CyberMint else SignalAmber),
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceStyle.cardColor,
                    border = BorderStroke(
                        width = 1.2.dp,
                        color = when {
                            matchedUser != null -> CyberMint.copy(alpha = 0.75f)
                            dialNumber.isNotBlank() -> SignalAmber.copy(alpha = 0.65f)
                            else -> surfaceStyle.borderColor.copy(alpha = 0.65f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Country Code Selector Chip (>= 48dp touch target)
                        Surface(
                            onClick = { showCountryPicker = true },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("country_code_chip"),
                            shape = RoundedCornerShape(12.dp),
                            color = HudSurfaceContainer,
                            border = BorderStroke(1.dp, HudBorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = selectedCountry.flagEmoji, fontSize = 15.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = selectedCountry.dialCode,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = HudTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Country Code",
                                    tint = SignalAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Monospace Bold Live Number Display + Fixed Subtitle Slot
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (dialNumber.isEmpty()) "ENTER NUMBER" else dialNumber,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = when {
                                    dialNumber.isEmpty() -> 16.sp
                                    dialNumber.length > 13 -> 18.sp
                                    dialNumber.length > 10 -> 21.sp
                                    else -> 24.sp
                                },
                                color = if (dialNumber.isEmpty()) HudTextSecondary.copy(alpha = 0.55f) else HudTextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    matchedUser != null -> "✓ ${matchedUser!!.displayName} • RB VERIFIED"
                                    dialNumber.isNotEmpty() -> "READY TO CALL • VOIP OR SIM"
                                    else -> "HD VOIP • 4K VIDEO • CELLULAR"
                                },
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = when {
                                    matchedUser != null -> CyberMint
                                    dialNumber.isNotEmpty() -> SignalAmber
                                    else -> HudTextSecondary.copy(alpha = 0.65f)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Backspace Button (>= 48dp touch target)
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (dialNumber.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .combinedClickable(
                                            onClick = { if (dialNumber.isNotEmpty()) dialNumber = dialNumber.dropLast(1) },
                                            onLongClick = { dialNumber = "" }
                                        )
                                        .testTag("backspace_button"),
                                    shape = RoundedCornerShape(12.dp),
                                    color = HudSurfaceContainer,
                                    border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.45f))
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                                            contentDescription = "Backspace",
                                            tint = CrimsonAlert,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        val lastCalled = callLogs.firstOrNull()?.otherPartyNumber
                                        if (!lastCalled.isNullOrBlank()) {
                                            dialNumber = lastCalled.filter { it.isDigit() || it == '+' }
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = "Redial Last Number",
                                        tint = HudTextSecondary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. TACTICAL 3-COLUMN KEYPAD GRID (Anti-Scramble, Weighted Columns, Monospace Bold Digits)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeypadRow("1", "VOIP", "2", "ABC", "3", "DEF", keyHeight = keyHeight, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize) { dialNumber += it }
                    KeypadRow("4", "GHI", "5", "JKL", "6", "MNO", keyHeight = keyHeight, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize) { dialNumber += it }
                    KeypadRow("7", "PQRS", "8", "TUV", "9", "WXYZ", keyHeight = keyHeight, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize) { dialNumber += it }
                    KeypadRow("*", "SYM", "0", "+", "#", "TAG", keyHeight = keyHeight, keyFontSize = keyFontSize, keySubFontSize = keySubFontSize) { digit ->
                        if (digit == "+") dialNumber += "+" else dialNumber += digit
                    }
                }

                // 7. DOCKED BOTTOM CALL CONTROLS CARD (Arrangement.spacedBy(8.dp) & weighted widths Modifier.weight(1f))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .surfaceFinishOverlay(surfaceStyle, 16.dp, CyberMint),
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceStyle.cardColor,
                    border = BorderStroke(1.dp, surfaceStyle.borderColor.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    // 1. SIM / Cellular Button
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = {
                                    if (dialNumber.isNotBlank()) {
                                        if (activeSims.size > 1) {
                                            simPickerTargetName = null
                                            showSimPicker = true
                                        } else {
                                            val calleeName = matchedUser?.displayName
                                                ?: nonLksContacts.firstOrNull { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, fullDialNumber) }?.name
                                                ?: syncedContacts.firstOrNull { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, fullDialNumber) }?.name
                                                ?: fullDialNumber
                                            val targetSim = activeSims.firstOrNull()
                                            SimManager.placeCellularCall(context, fullDialNumber, targetSim)
                                            val simLabel = targetSim?.displayName ?: "SIM"
                                            firebaseManager.recordCellularCall(fullDialNumber, calleeName, simLabel)
                                            Toast.makeText(context, "Calling via $simLabel...", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Enter a number on the keypad first", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onLongClick = {
                                    if (dialNumber.isNotBlank()) {
                                        showSimPicker = true
                                    }
                                }
                            )
                            .testTag("sim_call_button"),
                        shape = RoundedCornerShape(12.dp),
                        color = TelemetryBlue.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, TelemetryBlue.copy(alpha = 0.55f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.SimCard,
                                contentDescription = "Cellular SIM Call",
                                tint = TelemetryBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (activeSims.size > 1) "SIM 1/2" else (activeSims.firstOrNull()?.displayName?.take(6)?.uppercase() ?: "SIM"),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = HudTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 2. Cyber Mint Audio Call Button
                    Surface(
                        modifier = Modifier
                            .weight(1.25f)
                            .defaultMinSize(minHeight = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = {
                                    if (dialNumber.isNotBlank()) {
                                        val calleeName = matchedUser?.displayName ?: fullDialNumber
                                        if (matchedUser != null) {
                                            safeCall(fullDialNumber, calleeName, CallType.AUDIO)
                                        } else {
                                            showSimPicker = true
                                        }
                                    } else {
                                        val lastCalled = callLogs.firstOrNull()?.otherPartyNumber
                                        if (!lastCalled.isNullOrBlank()) {
                                            dialNumber = lastCalled.filter { it.isDigit() || it == '+' }
                                        } else {
                                            Toast.makeText(context, "Enter a phone number to call", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (dialNumber.isNotBlank()) {
                                        showSimPicker = true
                                    }
                                }
                            )
                            .testTag("audio_call_button"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (dialNumber.isNotBlank()) CyberMint else CyberMint.copy(alpha = 0.22f),
                        border = BorderStroke(1.5.dp, CyberMint)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = "Audio Call",
                                tint = if (dialNumber.isNotBlank()) Color.White else CyberMint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AUDIO",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (dialNumber.isNotBlank()) Color.White else HudTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 3. Signal Amber Video Call Button
                    Surface(
                        onClick = {
                            if (dialNumber.isNotBlank()) {
                                val calleeName = matchedUser?.displayName ?: fullDialNumber
                                safeCall(fullDialNumber, calleeName, CallType.VIDEO)
                            } else {
                                Toast.makeText(context, "Enter a phone number for Video Call", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 52.dp)
                            .testTag("video_call_button"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (dialNumber.isNotBlank()) SignalAmber else SignalAmber.copy(alpha = 0.2f),
                        border = BorderStroke(1.5.dp, SignalAmber)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = "Video Call",
                                tint = if (dialNumber.isNotBlank()) Color.White else SignalAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "VIDEO",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (dialNumber.isNotBlank()) Color.White else HudTextPrimary,
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

@Composable
private fun KeypadRow(
    d1: String, s1: String,
    d2: String, s2: String,
    d3: String, s3: String,
    keyHeight: androidx.compose.ui.unit.Dp,
    keyFontSize: androidx.compose.ui.unit.TextUnit,
    keySubFontSize: androidx.compose.ui.unit.TextUnit,
    onDigitClick: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KeypadButton(
            digit = d1,
            subText = s1,
            onClick = { onDigitClick(d1) },
            keyHeight = keyHeight,
            keyFontSize = keyFontSize,
            keySubFontSize = keySubFontSize,
            modifier = Modifier.weight(1f)
        )
        KeypadButton(
            digit = d2,
            subText = s2,
            onClick = { onDigitClick(d2) },
            onLongClick = if (d2 == "0") ({ onDigitClick("+") }) else null,
            keyHeight = keyHeight,
            keyFontSize = keyFontSize,
            keySubFontSize = keySubFontSize,
            modifier = Modifier.weight(1f)
        )
        KeypadButton(
            digit = d3,
            subText = s3,
            onClick = { onDigitClick(d3) },
            keyHeight = keyHeight,
            keyFontSize = keyFontSize,
            keySubFontSize = keySubFontSize,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadButton(
    digit: String,
    subText: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    keyHeight: androidx.compose.ui.unit.Dp = 54.dp,
    keyFontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    keySubFontSize: androidx.compose.ui.unit.TextUnit = 9.sp,
    modifier: Modifier = Modifier
) {
    val surfaceStyle = LocalSurfaceStyle.current
    Surface(
        modifier = modifier
            .height(keyHeight)
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .surfaceFinishOverlay(surfaceStyle, 14.dp, SignalAmber)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("keypad_button_$digit"),
        shape = RoundedCornerShape(14.dp),
        color = surfaceStyle.cardColor,
        border = BorderStroke(1.dp, surfaceStyle.borderColor.copy(alpha = 0.65f))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                text = digit,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = keyFontSize,
                fontWeight = FontWeight.Bold,
                color = HudTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subText.isNotEmpty()) {
                Text(
                    text = subText,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = keySubFontSize,
                    color = HudTextSecondary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(14.dp),
        color = HudSurfaceContainer,
        border = BorderStroke(1.dp, if (match.isLksUser) CyberMint.copy(alpha = 0.5f) else HudBorderSubtle),
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (match.isLksUser) CyberMint.copy(alpha = 0.2f) else SignalAmber.copy(alpha = 0.18f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = match.name.take(1).uppercase(),
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (match.isLksUser) CyberMint else SignalAmber
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.widthIn(max = 150.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = match.name,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = HudTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (match.isLksUser) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            color = CyberMint.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "RB",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberMint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = match.phoneNumber,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = HudTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onCall,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(CyberMint)
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Call ${match.name}",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
