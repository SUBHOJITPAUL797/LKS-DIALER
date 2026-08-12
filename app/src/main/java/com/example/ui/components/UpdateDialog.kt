package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.DownloadState
import com.example.util.UpdateInfo
import com.example.ui.theme.TealPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    downloadState: DownloadState,
    onDownloadClick: () -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!updateInfo.isMandatory && downloadState !is DownloadState.Downloading) {
                onDismissRequest()
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = TealPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Update Available", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Version ${updateInfo.latestVersion}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                if (updateInfo.isMandatory) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Critical Update Required",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "What's new:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Release notes in a scrollable area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = updateInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                when (downloadState) {
                    is DownloadState.Downloading -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Downloading...",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { downloadState.progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = TealPrimary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(downloadState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                    is DownloadState.Downloaded -> {
                        Text(
                            text = "Download complete. Starting installation...",
                            color = TealPrimary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    is DownloadState.Error -> {
                        Text(
                            text = "Error: ${downloadState.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    is DownloadState.Idle -> {
                        // Show nothing
                    }
                }
            }
        },
        confirmButton = {
            if (downloadState is DownloadState.Idle || downloadState is DownloadState.Error) {
                Button(
                    onClick = onDownloadClick,
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                ) {
                    Text("Download & Install")
                }
            }
        },
        dismissButton = {
            if (!updateInfo.isMandatory && (downloadState is DownloadState.Idle || downloadState is DownloadState.Error)) {
                TextButton(onClick = onDismissRequest) {
                    Text("Later")
                }
            }
        },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = !updateInfo.isMandatory,
            dismissOnClickOutside = !updateInfo.isMandatory
        )
    )
}
