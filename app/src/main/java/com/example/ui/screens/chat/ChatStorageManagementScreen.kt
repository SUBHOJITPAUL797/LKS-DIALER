package com.example.ui.screens.chat

import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.ConversationStorageItem
import com.example.data.local.StorageUsageSummary
import com.example.data.repository.ChatRepository
import com.example.data.repository.FirebaseManager
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatStorageManagementScreen(
    chatRepository: ChatRepository,
    firebaseManager: FirebaseManager,
    onBackClick: () -> Unit,
    onOpenChat: (phoneNumber: String, displayName: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var summary by remember { mutableStateOf<StorageUsageSummary?>(null) }
    var rankedChats by remember { mutableStateOf<List<ConversationStorageItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedChatForClean by remember { mutableStateOf<ConversationStorageItem?>(null) }
    var showMasterMediaConfirm by remember { mutableStateOf(false) }
    var showMasterAllChatsConfirm by remember { mutableStateOf(false) }

    fun refreshStorageData() {
        coroutineScope.launch {
            isLoading = true
            try {
                summary = chatRepository.getStorageUsageSummary()
                rankedChats = chatRepository.getRankedChatStorageList()
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageData()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.1f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Manage Storage",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshStorageData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        if (isLoading && summary == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TealPrimary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    // ── Storage Overview Card ────────────────────────────────
                    summary?.let { s ->
                        StorageOverviewCard(summary = s, formatBytes = ::formatBytes)
                    }
                }

                item {
                    // ── Master Clean Actions ─────────────────────────────────
                    MasterCleanActionsCard(
                        summary = summary,
                        formatBytes = ::formatBytes,
                        onClearAllMediaClick = { showMasterMediaConfirm = true },
                        onClearAllChatsClick = { showMasterAllChatsConfirm = true }
                    )
                }

                item {
                    // ── Section Header: Ranked Chats ──────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Chats Ranked by Storage",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sorted from largest space user to smallest",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = TealPrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "${rankedChats.size} chats",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = TealPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (rankedChats.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = GreenCall,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No Chat Storage Used",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "All chat media and message caches are completely clean!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(rankedChats, key = { it.phoneNumber }) { chatItem ->
                        RankedChatItemCard(
                            item = chatItem,
                            formatBytes = ::formatBytes,
                            onCleanClick = { selectedChatForClean = chatItem },
                            onItemClick = { onOpenChat(chatItem.phoneNumber, chatItem.displayName) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // ── Selective Clean Dialog for a Specific Chat ───────────────────────────
    selectedChatForClean?.let { chatItem ->
        SelectiveChatCleanModal(
            item = chatItem,
            formatBytes = ::formatBytes,
            onDismiss = { selectedChatForClean = null },
            onConfirmClean = { v, p, a, d, t ->
                coroutineScope.launch {
                    val bytesFreed = chatRepository.clearConversationSpecificMedia(
                        phoneNumber = chatItem.phoneNumber,
                        deleteVideos = v,
                        deletePhotos = p,
                        deleteAudio = a,
                        deleteDocs = d,
                        deleteText = t
                    )
                    Toast.makeText(context, "Freed ${formatBytes(bytesFreed)} of storage!", Toast.LENGTH_SHORT).show()
                    selectedChatForClean = null
                    refreshStorageData()
                }
            }
        )
    }

    // ── Master Media Confirm Dialog ──────────────────────────────────────────
    if (showMasterMediaConfirm) {
        AlertDialog(
            onDismissRequest = { showMasterMediaConfirm = false },
            icon = { Icon(Icons.Default.CleaningServices, contentDescription = null, tint = TealPrimary) },
            title = { Text("Delete All Media Across All Chats?") },
            text = {
                Text("This will delete all downloaded videos, photos, audio notes, and documents from your phone to free up storage space. Your chat text conversations will remain intact.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMasterMediaConfirm = false
                        coroutineScope.launch {
                            val freed = chatRepository.clearAllChatMedia()
                            Toast.makeText(context, "Cleared all media! Freed ${formatBytes(freed)}", Toast.LENGTH_LONG).show()
                            refreshStorageData()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                ) {
                    Text("Delete All Media")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMasterMediaConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Master All Chats Confirm Dialog ──────────────────────────────────────
    if (showMasterAllChatsConfirm) {
        AlertDialog(
            onDismissRequest = { showMasterAllChatsConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear All Chats & Media?") },
            text = {
                Text("Are you sure you want to permanently delete ALL chats, messages, and all media files from this device? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMasterAllChatsConfirm = false
                        coroutineScope.launch {
                            chatRepository.clearAllChats()
                            Toast.makeText(context, "All chats and media cleared", Toast.LENGTH_LONG).show()
                            refreshStorageData()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Everything", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMasterAllChatsConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StorageOverviewCard(
    summary: StorageUsageSummary,
    formatBytes: (Long) -> String
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LKS Chat Media",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatBytes(summary.totalChatBytes),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = TealPrimary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GreenCall.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${formatBytes(summary.freeDeviceBytes)} free on phone",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = GreenCall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Storage Progress Bar
            val total = summary.totalDeviceBytes.coerceAtLeast(1L).toFloat()
            val chatFrac = (summary.totalChatBytes / total).coerceIn(0f, 1f)
            val otherFrac = ((summary.usedDeviceBytes - summary.totalChatBytes).coerceAtLeast(0L) / total).coerceIn(0f, 1f)
            val freeFrac = (summary.freeDeviceBytes / total).coerceIn(0f, 1f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Gray.copy(alpha = 0.2f))
            ) {
                if (chatFrac > 0.001f) {
                    Box(
                        modifier = Modifier
                            .weight(chatFrac.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .background(TealPrimary)
                    )
                }
                if (otherFrac > 0.001f) {
                    Box(
                        modifier = Modifier
                            .weight(otherFrac.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .background(Color(0xFF5C6B73))
                    )
                }
                if (freeFrac > 0.001f) {
                    Box(
                        modifier = Modifier
                            .weight(freeFrac.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .background(Color(0xFFE0E0E0))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LegendIndicator(color = TealPrimary, label = "LKS Chat")
                LegendIndicator(color = Color(0xFF5C6B73), label = "Apps & System")
                LegendIndicator(color = Color(0xFFBDBDBD), label = "Free")
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(12.dp))

            // Media Breakdown Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MediaChip(label = "Videos", icon = Icons.Default.Videocam, size = formatBytes(summary.totalChatVideoBytes), color = Color(0xFFE53935))
                MediaChip(label = "Photos", icon = Icons.Default.Image, size = formatBytes(summary.totalChatPhotoBytes), color = Color(0xFF1E88E5))
                MediaChip(label = "Audio", icon = Icons.Default.Mic, size = formatBytes(summary.totalChatAudioBytes), color = Color(0xFF43A047))
                MediaChip(label = "Docs", icon = Icons.Default.InsertDriveFile, size = formatBytes(summary.totalChatDocumentBytes), color = Color(0xFFFB8C00))
            }
        }
    }
}

@Composable
private fun LegendIndicator(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MediaChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, size: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = size, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MasterCleanActionsCard(
    summary: StorageUsageSummary?,
    formatBytes: (Long) -> String,
    onClearAllMediaClick: () -> Unit,
    onClearAllChatsClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Quick Clean Storage",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Action 1: Delete all media across all chats
            Button(
                onClick = onClearAllMediaClick,
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Delete All Media Across All Chats (${formatBytes(summary?.totalChatBytes ?: 0L)})",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action 2: Clear all chats
            OutlinedButton(
                onClick = onClearAllChatsClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Clear All Chats & History",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@Composable
private fun RankedChatItemCard(
    item: ConversationStorageItem,
    formatBytes: (Long) -> String,
    onCleanClick: () -> Unit,
    onItemClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onItemClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatAvatar(
                name = item.displayName,
                profilePic = item.profilePicUrl,
                size = 46.dp,
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (item.totalBytes > 20 * 1024 * 1024L) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                               else TealPrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = formatBytes(item.totalBytes),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (item.totalBytes > 20 * 1024 * 1024L) MaterialTheme.colorScheme.error else TealPrimary
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Breakdown string: e.g. "🎬 2 (45 MB) • 📷 4 (12 MB)"
                val parts = mutableListOf<String>()
                if (item.videoCount > 0) parts.add("🎬 ${item.videoCount} vid (${formatBytes(item.videoBytes)})")
                if (item.photoCount > 0) parts.add("📷 ${item.photoCount} img (${formatBytes(item.photoBytes)})")
                if (item.audioCount > 0) parts.add("🎵 ${item.audioCount} aud (${formatBytes(item.audioBytes)})")
                if (item.documentCount > 0) parts.add("📄 ${item.documentCount} doc (${formatBytes(item.documentBytes)})")
                if (parts.isEmpty()) parts.add("${item.messageCount} text messages")

                Text(
                    text = parts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onCleanClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    Icons.Default.CleaningServices,
                    contentDescription = "Clean this chat",
                    tint = TealPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectiveChatCleanModal(
    item: ConversationStorageItem,
    formatBytes: (Long) -> String,
    onDismiss: () -> Unit,
    onConfirmClean: (videos: Boolean, photos: Boolean, audio: Boolean, docs: Boolean, text: Boolean) -> Unit
) {
    var delVideos by remember { mutableStateOf(item.videoCount > 0) }
    var delPhotos by remember { mutableStateOf(item.photoCount > 0) }
    var delAudio by remember { mutableStateOf(item.audioCount > 0) }
    var delDocs by remember { mutableStateOf(item.documentCount > 0) }
    var delText by remember { mutableStateOf(false) }

    val calculatedReclaimBytes = (if (delVideos) item.videoBytes else 0L) +
            (if (delPhotos) item.photoBytes else 0L) +
            (if (delAudio) item.audioBytes else 0L) +
            (if (delDocs) item.documentBytes else 0L)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(0.95f)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    text = "Clean Chat Storage",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TealPrimary.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Total chat size: ${formatBytes(item.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = TealPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (item.videoCount > 0) {
                    CleanCheckboxRow(
                        title = "Videos (${item.videoCount})",
                        size = formatBytes(item.videoBytes),
                        checked = delVideos,
                        onCheckedChange = { delVideos = it }
                    )
                }

                if (item.photoCount > 0) {
                    CleanCheckboxRow(
                        title = "Photos (${item.photoCount})",
                        size = formatBytes(item.photoBytes),
                        checked = delPhotos,
                        onCheckedChange = { delPhotos = it }
                    )
                }

                if (item.audioCount > 0) {
                    CleanCheckboxRow(
                        title = "Audio / Voice Notes (${item.audioCount})",
                        size = formatBytes(item.audioBytes),
                        checked = delAudio,
                        onCheckedChange = { delAudio = it }
                    )
                }

                if (item.documentCount > 0) {
                    CleanCheckboxRow(
                        title = "Documents (${item.documentCount})",
                        size = formatBytes(item.documentBytes),
                        checked = delDocs,
                        onCheckedChange = { delDocs = it }
                    )
                }

                CleanCheckboxRow(
                    title = "Text Messages (${item.messageCount})",
                    size = "History",
                    checked = delText,
                    onCheckedChange = { delText = it }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirmClean(delVideos, delPhotos, delAudio, delDocs, delText)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (delText && calculatedReclaimBytes == item.totalBytes) MaterialTheme.colorScheme.error else TealPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (calculatedReclaimBytes > 0) "Free ${formatBytes(calculatedReclaimBytes)}" else "Clean Selected",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanCheckboxRow(
    title: String,
    size: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(checkedColor = TealPrimary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
        }
        Text(
            text = size,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
