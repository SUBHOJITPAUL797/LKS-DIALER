package com.example.ui.screens.profile

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.FirebaseManager
import com.example.ui.components.TacticalActionCard
import com.example.ui.components.TacticalStatusPill
import com.example.ui.components.surfaceFinishOverlay
import com.example.ui.screens.settings.SettingsCategory
import com.example.ui.theme.*
import com.example.util.ImageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    firebaseManager: FirebaseManager,
    onOpenSettingsCategory: (SettingsCategory) -> Unit = {}
) {
    val currentUser by firebaseManager.currentUser.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val surfaceStyle = LocalSurfaceStyle.current

    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(currentUser?.displayName ?: "User") }
    var statusMsg by remember {
        val raw = currentUser?.statusMessage ?: "Available on RB DIALER"
        mutableStateOf(raw.replace("LKS DIALER", "RB DIALER"))
    }

    LaunchedEffect(currentUser) {
        currentUser?.let {
            editedName = it.displayName
            statusMsg = it.statusMessage.replace("LKS DIALER", "RB DIALER")
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val base64Str = ImageUtils.compressUriToBase64(context, uri)
            if (base64Str != null) {
                firebaseManager.updateProfile(editedName, statusMsg, base64Str)
                Toast.makeText(context, "Profile picture updated!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to compress image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackgroundPrimary)
    ) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ACCOUNT & SETTINGS",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = HudTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TacticalStatusPill(
                        text = surfaceStyle.badge,
                        dotColor = CyberMint
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = { onOpenSettingsCategory(SettingsCategory.ALL) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "All Settings",
                        tint = SignalAmber
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = HudBackgroundPrimary
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── User Identity Card ──────────────────────────────────────────
            Surface(
                color = surfaceStyle.cardColor,
                shape = RoundedCornerShape(surfaceStyle.cornerRadiusDp.dp),
                border = BorderStroke(1.dp, surfaceStyle.borderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .surfaceFinishOverlay(surfaceStyle, surfaceStyle.cornerRadiusDp.dp, SignalAmber)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Profile picture avatar
                        Surface(
                            modifier = Modifier
                                .size(76.dp)
                                .clickable { imagePickerLauncher.launch("image/*") },
                            shape = CircleShape,
                            color = surfaceStyle.elevatedColor,
                            border = BorderStroke(2.dp, SignalAmber)
                        ) {
                            val profilePicUrl = currentUser?.profilePictureUrl ?: ""
                            val decodedBitmap = remember(profilePicUrl) {
                                if (profilePicUrl.isNotBlank()) ImageUtils.decodeBase64ToImageBitmap(profilePicUrl) else null
                            }

                            if (decodedBitmap != null) {
                                Image(
                                    bitmap = decodedBitmap,
                                    contentDescription = "Profile Picture",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = (currentUser?.displayName ?: "R").take(1).uppercase(),
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontSize = 30.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SignalAmber
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            if (isEditingName) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = editedName,
                                        onValueChange = { editedName = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = SignalAmber,
                                            unfocusedBorderColor = HudBorderSubtle,
                                            focusedContainerColor = surfaceStyle.elevatedColor,
                                            unfocusedContainerColor = surfaceStyle.elevatedColor
                                        )
                                    )
                                    IconButton(
                                        onClick = {
                                            if (editedName.isNotBlank()) {
                                                firebaseManager.updateProfile(editedName, statusMsg)
                                                isEditingName = false
                                                Toast.makeText(context, "Profile name updated!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = "Save", tint = CyberMint)
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = currentUser?.displayName?.ifBlank { "RB User" } ?: "RB User",
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                        color = HudTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    IconButton(
                                        onClick = { isEditingName = true },
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Name", tint = SignalAmber, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            val registeredNumber = currentUser?.phoneNumber?.ifBlank { "UNREGISTERED" } ?: "UNREGISTERED"
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = registeredNumber,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = CyberMint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (registeredNumber != "UNREGISTERED") {
                                    Surface(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(registeredNumber))
                                            Toast.makeText(context, "Number copied!", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = SignalAmber.copy(alpha = 0.2f),
                                        border = BorderStroke(1.dp, SignalAmber)
                                    ) {
                                        Text(
                                            text = "COPY",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            color = SignalAmber,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                color = CyberMint.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.VerifiedUser,
                                        contentDescription = "End-to-End Encrypted Verified",
                                        tint = CyberMint,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "E2EE VERIFIED • RB DIALER",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = CyberMint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Status message row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = statusMsg,
                            onValueChange = { statusMsg = it },
                            label = { Text("Status Message") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SignalAmber,
                                unfocusedBorderColor = surfaceStyle.borderColor,
                                focusedContainerColor = surfaceStyle.elevatedColor,
                                unfocusedContainerColor = surfaceStyle.elevatedColor
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                firebaseManager.updateProfile(editedName, statusMsg)
                                Toast.makeText(context, "Status updated!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save Status", tint = SignalAmber)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── UI Surface Style Quick Selector (Clay 3D / Frosted Glass / Liquid Glass / HUD) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UI MATERIAL & GLASS STYLE",
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = SignalAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = surfaceStyle.title.uppercase(),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = CyberMint,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val styleChunks = remember { AppSurfaceStyle.entries.chunked(2) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                styleChunks.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { styleOption ->
                            val isSelected = surfaceStyle == styleOption
                            Surface(
                                onClick = { themeManager.setSurfaceStyle(styleOption) },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 56.dp)
                                    .surfaceFinishOverlay(styleOption, styleOption.cornerRadiusDp.dp, styleOption.highlightColor),
                                shape = RoundedCornerShape(styleOption.cornerRadiusDp.dp),
                                color = if (isSelected) styleOption.highlightColor.copy(alpha = 0.20f) else styleOption.cardColor,
                                border = BorderStroke(
                                    if (isSelected) 1.6.dp else 1.dp,
                                    if (isSelected) styleOption.highlightColor else styleOption.borderColor
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = styleOption.title,
                                            fontFamily = SpaceGroteskFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = HudTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = styleOption.badge,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            color = if (isSelected) styleOption.highlightColor else HudTextSecondary,
                                            maxLines = 1
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = styleOption.highlightColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Organized Settings Categories Grid ──────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SETTINGS BY CATEGORY",
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = SignalAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "TAP TO OPEN",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = HudTextSecondary,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val categoryRows = remember {
                SettingsCategory.entries.filter { it != SettingsCategory.ALL }.chunked(2)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categoryRows.forEach { rowCats ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowCats.forEach { cat ->
                            TacticalActionCard(
                                title = cat.title,
                                subtitle = cat.subtitle,
                                icon = cat.icon,
                                accentColor = cat.accent,
                                testTag = "profile_settings_cat_${cat.name.lowercase()}",
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenSettingsCategory(cat) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
