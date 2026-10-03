package com.example.ui.components

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import com.example.util.logging.LksLogUploader
import com.example.util.logging.LksLogger
import kotlinx.coroutines.launch

/**
 * ShakeReportDialog
 * Interactive modal rendered when user shakes the phone or triggers "Send Diagnostic Logs".
 * Provides seamless, 1-tap reporting of system logs, device state, and user notes to Firestore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShakeReportDialog(
    triggerType: String = "SHAKE_GESTURE",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var userNote by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var showLogPreview by remember { mutableStateOf(false) }

    val recentLogs = remember { LksLogger.getRecentLogs(500) }

    Dialog(
        onDismissRequest = { if (!isUploading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp)),
            color = Color(0xFF131F24),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Surface(
                    shape = CircleShape,
                    color = TealPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (triggerType == "SHAKE_GESTURE") Icons.Default.Vibration else Icons.Default.BugReport,
                            contentDescription = null,
                            tint = TealPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (triggerType == "SHAKE_GESTURE") "Shake Detected!" else "Diagnostic Bug Reporter",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Text(
                    text = "Send diagnostic logs and device state directly to the developer database to inspect and fix issues.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Device Info Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E2D33),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "📱 ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TealPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E2D33),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "OS: ${Build.VERSION.RELEASE}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GreenCall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E2D33),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "${recentLogs.size} logs",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Optional Note Input
                OutlinedTextField(
                    value = userNote,
                    onValueChange = { userNote = it },
                    placeholder = { Text("What happened? (e.g. ringtone in earpiece, audio lag, call delay...)", fontSize = 13.sp) },
                    label = { Text("User Note (Optional)", fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealPrimary,
                        unfocusedBorderColor = Color(0xFF2C3E47),
                        focusedLabelColor = TealPrimary,
                        cursorColor = TealPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Toggle Log Preview Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showLogPreview = !showLogPreview }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (showLogPreview) "Hide Log Details" else "Preview Collected Logs (${recentLogs.size} lines)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TealPrimary
                    )
                    Icon(
                        imageVector = if (showLogPreview) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Expandable Log Viewer
                AnimatedVisibility(visible = showLogPreview) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0B1114))
                            .border(1.dp, Color(0xFF1E2D33), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Buffer Preview",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            IconButton(
                                onClick = {
                                    val text = recentLogs.takeLast(200).joinToString("\n")
                                    clipboardManager.setText(AnnotatedString(text))
                                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TealPrimary, modifier = Modifier.size(16.dp))
                            }
                        }

                        val listState = rememberLazyListState()
                        LaunchedEffect(recentLogs.size) {
                            if (recentLogs.isNotEmpty()) {
                                listState.scrollToItem(recentLogs.size - 1)
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(recentLogs) { logLine ->
                                val color = when {
                                    logLine.contains(" E/") || logLine.contains("CRITICAL") -> Color(0xFFFF6B6B)
                                    logLine.contains(" W/") -> Color(0xFFFFD166)
                                    logLine.contains(" I/") -> Color(0xFF06D6A0)
                                    else -> Color.LightGray
                                }
                                Text(
                                    text = logLine,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = color,
                                    lineHeight = 12.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isUploading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel", color = Color.LightGray)
                    }

                    Button(
                        onClick = {
                            isUploading = true
                            coroutineScope.launch {
                                val success = LksLogUploader.uploadUserShakeReport(
                                    context = context,
                                    triggerType = triggerType,
                                    userNote = userNote,
                                    logs = recentLogs
                                )
                                isUploading = false
                                if (success) {
                                    Toast.makeText(context, "✅ Diagnostic report sent to developer cloud!", Toast.LENGTH_LONG).show()
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "Upload failed. Logs saved locally.", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                            }
                        },
                        enabled = !isUploading,
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Uploading...", color = Color.White)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send Logs", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * LiveLogViewerDialog
 * Full-screen modal to search, inspect, and export all live logs collected since app start.
 */
@Composable
fun LiveLogViewerDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }

    val allLogs = remember { LksLogger.getRecentLogs(1000) }

    val filteredLogs = remember(allLogs, searchQuery, selectedFilter) {
        allLogs.filter { line ->
            val matchesFilter = when (selectedFilter) {
                "ERROR" -> line.contains(" E/") || line.contains("CRITICAL")
                "WARN" -> line.contains(" W/")
                "INFO" -> line.contains(" I/")
                else -> true
            }
            val matchesSearch = if (searchQuery.isBlank()) true else line.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = Color(0xFF0E161A),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Live Diagnostic Logs",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "${filteredLogs.size} / ${allLogs.size} lines in buffer",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }

                    Row {
                        IconButton(onClick = {
                            val exported = allLogs.joinToString("\n")
                            clipboardManager.setText(AnnotatedString(exported))
                            Toast.makeText(context, "All logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy All", tint = TealPrimary)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search logs (e.g. Xiaomi, speaker, WebRTC...)", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("ALL", "ERROR", "WARN", "INFO").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Log List
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    color = Color(0xFF060A0C)
                ) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        items(filteredLogs) { line ->
                            val color = when {
                                line.contains(" E/") || line.contains("CRITICAL") -> Color(0xFFFF6B6B)
                                line.contains(" W/") -> Color(0xFFFFD166)
                                line.contains(" I/") -> Color(0xFF06D6A0)
                                else -> Color.LightGray
                            }
                            Text(
                                text = line,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                lineHeight = 13.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
