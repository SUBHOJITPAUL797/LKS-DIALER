package com.example.ui.screens.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import com.example.data.repository.FirebaseManager
import com.example.ui.components.FirebaseSetupBanner
import com.example.ui.theme.TealPrimary
import com.example.util.ImageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    firebaseManager: FirebaseManager
) {
    val currentUser by firebaseManager.currentUser.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(currentUser?.displayName ?: "User") }
    var statusMsg by remember { mutableStateOf(currentUser?.statusMessage ?: "Available on LKS DIALER") }

    LaunchedEffect(currentUser) {
        currentUser?.let {
            editedName = it.displayName
            statusMsg = it.statusMessage
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
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "My Account",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Profile picture avatar
            Surface(
                modifier = Modifier
                    .size(100.dp)
                    .clickable { imagePickerLauncher.launch("image/*") },
                shape = CircleShape,
                color = TealPrimary
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
                            text = (currentUser?.displayName ?: "U").take(1).uppercase(),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Display Name Row
            if (isEditingName) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        if (editedName.isNotBlank()) {
                            firebaseManager.updateProfile(editedName, statusMsg)
                            isEditingName = false
                            Toast.makeText(context, "Profile name updated!", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Save", tint = TealPrimary)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentUser?.displayName ?: "User",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = { isEditingName = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Name", tint = Color.Gray)
                    }
                }
            }

            // Fixed Phone Number Card with Hardware Locking Info
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "LKS DIALER Number",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.Gray
                            )
                            Text(
                                text = currentUser?.phoneNumber ?: "+91 98765 43210",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(currentUser?.phoneNumber ?: ""))
                                Toast.makeText(context, "Number copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Hardware Bound: ${currentUser?.registeredDeviceId?.ifBlank { "DEV-HARDWARE-LOCKED" } ?: "DEV-HARDWARE-LOCKED"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TealPrimary
                        )
                    }
                }
            }

            // Status message
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = statusMsg,
                    onValueChange = { statusMsg = it },
                    label = { Text("Status Message") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    firebaseManager.updateProfile(editedName, statusMsg)
                    Toast.makeText(context, "Status updated!", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Save, contentDescription = "Save Status", tint = TealPrimary)
                }
            }
        }
    }
}
