package com.example.ui.components

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import com.example.ui.screens.chat.ScheduledMessageManager
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import com.example.util.logging.LksLogUploader
import com.example.util.logging.LksLogger
import kotlinx.coroutines.launch

/**
 * ShakeReportDialog
 * Interactive modal rendered when user shakes the phone or triggers "Send Diagnostic Logs".
 * Provides seamless, 1-tap reporting of system logs, AI diagnostic summary, device state, and user notes to Firestore.
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
    var uploadedReportId by remember { mutableStateOf<String?>(null) }

    val recentLogs = remember { LksLogger.getRecentLogs(800) }
    val errorDigest = remember { LksLogger.getErrorDigest(60) }
    val scheduledPending = remember { ScheduledMessageManager.scheduledMessages.value.size }

    Dialog(
        onDismissRequest = { if (!isUploading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.93f)
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
                            imageVector = when {
                                uploadedReportId != null -> Icons.Default.CheckCircle
                                triggerType == "SHAKE_GESTURE" -> Icons.Default.Vibration
                                else -> Icons.Default.BugReport
                            },
                            contentDescription = null,
                            tint = if (uploadedReportId != null) GreenCall else TealPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = when {
                        uploadedReportId != null -> "Cloud Diagnostic Report Saved!"
                        triggerType == "SHAKE_GESTURE" -> "Shake Detected!"
                        else -> "AI Cloud Diagnostic Reporter"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Text(
                    text = if (uploadedReportId != null) {
                        "Your full call engine, TURN/ICE state, scheduler state, and log chunks are now in Firestore for AI inspection."
                    } else {
                        "Send structured AI diagnostics, call/ICE telemetry, and live logs to the cloud database for instant root-cause fixing."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Device & Subsystem Health Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E2D33),
                        modifier = Modifier.padding(horizontal = 3.dp)
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
                        modifier = Modifier.padding(horizontal = 3.dp)
                    ) {
                        Text(
                            text = "${recentLogs.size} logs",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (errorDigest.isNotEmpty()) Color(0xFF3A1D24) else Color(0xFF1E2D33),
                        modifier = Modifier.padding(horizontal = 3.dp)
                    ) {
                        Text(
                            text = "${errorDigest.size} Warn/Err",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (errorDigest.isNotEmpty()) Color(0xFFFF8585) else GreenCall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (scheduledPending > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E2D33),
                            modifier = Modifier.padding(horizontal = 3.dp)
                        ) {
                            Text(
                                text = "⏰ $scheduledPending Scheduled",
                                style = MaterialTheme.typography.labelSmall,
                                color = TealPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (uploadedReportId != null) {
                    val rId = uploadedReportId ?: ""
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0E181D),
                        border = BorderStroke(1.dp, GreenCall.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboardManager.setText(AnnotatedString(rId))
                                Toast.makeText(context, "Report ID copied: $rId", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "CLOUD REPORT ID (TAP TO COPY)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GreenCall
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = rId,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy Report ID",
                                tint = GreenCall,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GreenCall)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                } else {
                    // Optional Note Input
                    OutlinedTextField(
                        value = userNote,
                        onValueChange = { userNote = it },
                        placeholder = { Text("What happened? (e.g. cross-network call delay, audio route, scheduled message...)", fontSize = 13.sp) },
                        label = { Text("Issue Description for AI / Developer (Optional)", fontSize = 12.sp) },
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
                                        val text = recentLogs.takeLast(250).joinToString("\n")
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
                                        logLine.contains("EVENT/") || logLine.contains("BREADCRUMB/") -> Color(0xFF4CC9F0)
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
                                    val res = LksLogUploader.uploadDiagnosticReportWithId(
                                        context = context,
                                        triggerType = triggerType,
                                        userNote = userNote,
                                        logs = recentLogs
                                    )
                                    isUploading = false
                                    val reportId = res.getOrNull()
                                    if (reportId != null) {
                                        uploadedReportId = reportId
                                        clipboardManager.setText(AnnotatedString(reportId))
                                        Toast.makeText(context, "✅ Sent to Cloud DB! Report ID copied: $reportId", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Cloud upload failed. Logs saved locally on device.", Toast.LENGTH_SHORT).show()
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
}

/**
 * LiveLogViewerDialog
 * Full-screen modal to search, filter by subsystem, inspect, copy, or 1-tap upload live logs to Firestore.
 */
@Composable
fun LiveLogViewerDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var isUploadingCloud by remember { mutableStateOf(false) }
    var lastUploadedReportId by remember { mutableStateOf<String?>(null) }

    val allLogs = remember(refreshTick) { LksLogger.getRecentLogs(1500) }

    val filteredLogs = remember(allLogs, searchQuery, selectedFilter) {
        allLogs.filter { line ->
            val matchesFilter = when (selectedFilter) {
                "ERROR" -> line.contains(" E/") || line.contains("CRITICAL")
                "WARN" -> line.contains(" W/")
                "CALL/ICE" -> line.contains("WebRtcEngine", ignoreCase = true) ||
                    line.contains("CALL_ENGINE", ignoreCase = true) ||
                    line.contains("CallSignaling", ignoreCase = true) ||
                    line.contains("ICE", ignoreCase = true) ||
                    line.contains("TURN", ignoreCase = true)
                "SCHEDULE" -> line.contains("ScheduledMsg", ignoreCase = true) ||
                    line.contains("SCHEDULED_MSG", ignoreCase = true)
                "AUDIO" -> line.contains("AudioRoute", ignoreCase = true) ||
                    line.contains("AUDIO", ignoreCase = true) ||
                    line.contains("Ringtone", ignoreCase = true)
                "EVENTS" -> line.contains("EVENT/") || line.contains("BREADCRUMB/")
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
                .padding(14.dp)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Live Diagnostic & Telemetry Console",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "${filteredLogs.size} / ${allLogs.size} lines in buffer",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { refreshTick++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TealPrimary)
                        }
                        IconButton(onClick = {
                            val exported = filteredLogs.joinToString("\n")
                            clipboardManager.setText(AnnotatedString(exported))
                            Toast.makeText(context, "Filtered logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy All", tint = TealPrimary)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                        }
                    }
                }

                // Cloud Report ID confirmation bar if uploaded
                AnimatedVisibility(visible = lastUploadedReportId != null) {
                    val rId = lastUploadedReportId ?: ""
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = GreenCall.copy(alpha = 0.15f),
                        border = BorderStroke(0.8.dp, GreenCall.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable {
                                clipboardManager.setText(AnnotatedString(rId))
                                Toast.makeText(context, "Copied Report ID: $rId", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "✅ Uploaded to Firestore • ID: $rId",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = GreenCall
                            )
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy ID", tint = GreenCall, modifier = Modifier.size(15.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search logs (e.g. relay, WebRTC, Scheduled, Xiaomi, speaker...)", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subsystem & Level Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("ALL", "CALL/ICE", "SCHEDULE", "AUDIO", "EVENTS", "ERROR", "WARN").forEach { filter ->
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
                    LaunchedEffect(filteredLogs.size, selectedFilter) {
                        if (filteredLogs.isNotEmpty()) {
                            listState.scrollToItem(filteredLogs.size - 1)
                        }
                    }
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
                                line.contains("EVENT/") || line.contains("BREADCRUMB/") -> Color(0xFF4CC9F0)
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

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Action Bar: Upload to Cloud DB & Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            LksLogger.clear()
                            refreshTick++
                            Toast.makeText(context, "Log buffer cleared", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.35f)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.LightGray)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", fontSize = 12.sp, color = Color.LightGray)
                    }

                    Button(
                        onClick = {
                            if (!isUploadingCloud) {
                                isUploadingCloud = true
                                coroutineScope.launch {
                                    val res = LksLogUploader.uploadDiagnosticReportWithId(
                                        context = context,
                                        triggerType = "LIVE_LOG_CONSOLE",
                                        userNote = "Uploaded directly from Live Log Console (filter=$selectedFilter, query=$searchQuery)",
                                        logs = allLogs
                                    )
                                    isUploadingCloud = false
                                    val rId = res.getOrNull()
                                    if (rId != null) {
                                        lastUploadedReportId = rId
                                        clipboardManager.setText(AnnotatedString(rId))
                                        Toast.makeText(context, "✅ Uploaded to Cloud DB! ID copied: $rId", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Cloud upload failed. Saved to local storage.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = !isUploadingCloud,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        modifier = Modifier.weight(0.65f)
                    ) {
                        if (isUploadingCloud) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Uploading to Cloud...", fontSize = 12.sp, color = Color.White)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Upload to Cloud for AI Fix", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
