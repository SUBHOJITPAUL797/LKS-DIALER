package com.example.ui.screens.chat

import android.content.pm.PackageManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private const val TAG = "WhatsAppCameraScreen"

@Composable
fun WhatsAppCameraScreen(
    onPhotoCaptured: (File) -> Unit,
    onPhotosSelectedFromGallery: (List<File>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.1f),
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Camera Access Required",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Allow LKS Dialer to access your camera to take and send photos directly in chat.",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { permissionLauncher.launch(android.Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenCall),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Grant Permission", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onClose) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        }
        return
    }

    // Camera Configuration State
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) } // OFF, ON, AUTO
    var isCapturing by remember { mutableStateOf(false) }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var showZoomBadge by remember { mutableStateOf(false) }

    // Tap to focus state
    var focusRingOffset by remember { mutableStateOf<Offset?>(null) }
    var showFocusRing by remember { mutableStateOf(false) }

    // Lens flip animation
    var flipRotationTarget by remember { mutableFloatStateOf(0f) }
    val flipRotation by animateFloatAsState(
        targetValue = flipRotationTarget,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "flipRotation"
    )

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()
    }

    // Gallery Picker launcher for direct gallery access from camera
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val files = mutableListOf<File>()
            uris.forEach { uri ->
                try {
                    val tempFile = File(context.cacheDir, "gallery_${System.currentTimeMillis()}_${files.size}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        files.add(tempFile)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading image from URI: ${e.message}")
                }
            }
            if (files.isNotEmpty()) {
                onPhotosSelectedFromGallery(files)
            }
        }
    }

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // CameraX PreviewView with Tap-to-Focus & Pinch-to-Zoom
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewViewRef = previewView

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                        cameraControl = camera.cameraControl
                        cameraInfo = camera.cameraInfo
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera binding failed: ${e.message}", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            update = { previewView ->
                previewViewRef = previewView
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    imageCapture.flashMode = flashMode

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                        cameraControl = camera.cameraControl
                        cameraInfo = camera.cameraInfo
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera update failed: ${e.message}", e)
                    }
                }, ContextCompat.getMainExecutor(context))
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val currentZoom = zoomRatio
                        val newZoom = (currentZoom * zoom).coerceIn(1f, 8f)
                        zoomRatio = newZoom
                        cameraControl?.setZoomRatio(newZoom)
                        showZoomBadge = true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val pv = previewViewRef ?: return@detectTapGestures
                        val factory = pv.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point).build()
                        cameraControl?.startFocusAndMetering(action)

                        focusRingOffset = offset
                        showFocusRing = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
        )

        // Hide zoom badge after inactivity
        LaunchedEffect(zoomRatio) {
            if (showZoomBadge) {
                kotlinx.coroutines.delay(1800)
                showZoomBadge = false
            }
        }

        // Hide focus ring after 1.2 seconds
        LaunchedEffect(focusRingOffset) {
            if (showFocusRing) {
                kotlinx.coroutines.delay(1200)
                showFocusRing = false
            }
        }

        // Tap to Focus Ring Animation
        if (showFocusRing && focusRingOffset != null) {
            val ringScale by animateFloatAsState(
                targetValue = if (showFocusRing) 1f else 1.3f,
                animationSpec = tween(250),
                label = "focusScale"
            )
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (focusRingOffset!!.x - 36.dp.toPx()).toDp() },
                        y = with(density) { (focusRingOffset!!.y - 36.dp.toPx()).toDp() }
                    )
                    .size(72.dp)
                    .scale(ringScale)
                    .border(2.dp, Color(0xFFFFEB3B), RoundedCornerShape(8.dp))
            )
        }

        // Top Controls Glassmorphism Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close Button
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            // Flash Toggle Pill (Auto / On / Off)
            Surface(
                onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                },
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        else -> Icons.Default.FlashOff
                    }
                    val tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) Color(0xFFFFD54F) else Color.White
                    val label = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> "On"
                        ImageCapture.FLASH_MODE_AUTO -> "Auto"
                        else -> "Off"
                    }
                    Icon(icon, contentDescription = "Flash", tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Center Floating Zoom Badge (Appears when pinching)
        AnimatedVisibility(
            visible = showZoomBadge,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(bottom = 120.dp)
            ) {
                Text(
                    text = String.format("%.1fx", zoomRatio),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        // Bottom Controls Bar (Gallery, Shutter Button, Switch Camera)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery Shortcut Button (Bottom Left)
            IconButton(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = "Gallery",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            // WhatsApp Shutter Button (Center)
            val scaleAnimation by animateFloatAsState(if (isCapturing) 0.88f else 1f, label = "shutterScale")
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .scale(scaleAnimation)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(enabled = !isCapturing) {
                        isCapturing = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                        val photoFile = File(
                            context.cacheDir,
                            "captured_${System.currentTimeMillis()}.jpg"
                        )
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                        val cameraExecutor = Executors.newSingleThreadExecutor()

                        imageCapture.takePicture(
                            outputOptions,
                            cameraExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    isCapturing = false
                                    ContextCompat.getMainExecutor(context).execute {
                                        onPhotoCaptured(photoFile)
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    isCapturing = false
                                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                                    ContextCompat.getMainExecutor(context).execute {
                                        Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = GreenCall,
                        strokeWidth = 3.dp
                    )
                }
            }

            // Flip Camera Button with 180° rotation animation (Bottom Right)
            IconButton(
                onClick = {
                    flipRotationTarget += 180f
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    Icons.Default.FlipCameraAndroid,
                    contentDescription = "Switch Camera",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(flipRotation)
                )
            }
        }
    }
}
