package com.example.ui.screens.chat

import java.util.Locale
import android.content.pm.PackageManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.*
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

    BackHandler { onClose() }

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
                .background(HudBackgroundPrimary),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Surface(
                    color = HudSurfaceContainer,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, HudBorderSubtle),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = SignalAmber,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Camera Access Required",
                    fontFamily = SpaceGroteskFontFamily,
                    color = HudTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Allow LKS Dialer to access your camera to capture and send encrypted field photos.",
                    color = HudTextSecondary,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { permissionLauncher.launch(android.Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SignalAmber,
                        contentColor = Color(0xFF0B1118)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        "Grant Permission",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = HudTextSecondary)
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

    // RULE 1: 3D AR Character Studio State — Strictly OFF by default
    var arState by remember { mutableStateOf(ArCharacterState(enabled = false)) }

    // Real-time 60fps animation clock (only runs when 3D character is enabled)
    var animTimeSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(arState.enabled) {
        if (arState.enabled) {
            val startNanos = withFrameNanos { it }
            while (true) {
                withFrameNanos { frameNanos ->
                    animTimeSeconds = (frameNanos - startNanos) / 1_000_000_000f
                }
            }
        }
    }

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

    // Gallery Picker launcher for direct gallery access from camera (Photos & Videos)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val files = mutableListOf<File>()
            uris.forEach { uri ->
                try {
                    var displayName = "media_${System.currentTimeMillis()}"
                    var mimeType = context.contentResolver.getType(uri) ?: ""
                    try {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    val name = cursor.getString(nameIdx)
                                    if (!name.isNullOrBlank()) displayName = name
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    val extFromName = displayName.substringAfterLast('.', "")
                    val ext = if (extFromName.isNotBlank() && extFromName.length <= 5) {
                        extFromName.lowercase(Locale.getDefault())
                    } else {
                        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
                    }

                    val prefix = if (mimeType.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")) "video" else "gallery"
                    val tempFile = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}_${files.size}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        files.add(tempFile)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading media from URI: ${e.message}")
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
            .background(HudBackgroundPrimary)
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
                .pointerInput(arState.enabled) {
                    if (!arState.enabled) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val currentZoom = zoomRatio
                            val newZoom = (currentZoom * zoom).coerceIn(1f, 8f)
                            zoomRatio = newZoom
                            cameraControl?.setZoomRatio(newZoom)
                            showZoomBadge = true
                        }
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

        // RULE 2, 3, 4: Optional Interactive 3D AR Character Overlay Canvas
        if (arState.enabled) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("ar_character_canvas")
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            arState = arState.copy(
                                normalizedX = (arState.normalizedX + pan.x / w).coerceIn(0.12f, 0.88f),
                                normalizedY = (arState.normalizedY + pan.y / h).coerceIn(0.18f, 0.78f),
                                scale = (arState.scale * zoom).coerceIn(0.5f, 2.2f),
                                rotationYDeg = (arState.rotationYDeg + rotation * 1.4f) % 360f
                            )
                        }
                    }
            ) {
                with(ArCharacter3dRenderer) {
                    drawCharacterOnComposeCanvas(
                        state = arState,
                        timeSec = animTimeSeconds
                    )
                }
            }
        }

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
        val ringOffset = focusRingOffset
        if (showFocusRing && ringOffset != null) {
            val ringScale by animateFloatAsState(
                targetValue = if (showFocusRing) 1f else 1.3f,
                animationSpec = tween(250),
                label = "focusScale"
            )
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (ringOffset.x - 36.dp.toPx()).toDp() },
                        y = with(density) { (ringOffset.y - 36.dp.toPx()).toDp() }
                    )
                    .size(72.dp)
                    .scale(ringScale)
                    .border(2.dp, SignalAmber, RoundedCornerShape(8.dp))
            )
        }

        // RULE 1 & RULE 5: Top Controls in a Single Compact Row (with "+ 3D Character" / "3D ON" Toggle Chip)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close Button (>= 48dp touch target)
            Surface(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("camera_close_button"),
                shape = CircleShape,
                color = HudGlassOverlay,
                border = BorderStroke(1.dp, HudBorderSubtle)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = HudTextPrimary)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // RULE 1: Optional 3D Character Toggle Chip ("+ 3D Character" / "3D ON") — OFF by default
            Surface(
                onClick = {
                    arState = arState.copy(enabled = !arState.enabled)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("camera_3d_character_toggle"),
                shape = RoundedCornerShape(24.dp),
                color = if (arState.enabled) CyberMint.copy(alpha = 0.22f) else HudGlassOverlay,
                border = BorderStroke(
                    width = if (arState.enabled) 1.5.dp else 1.dp,
                    color = if (arState.enabled) CyberMint else HudBorderSubtle
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (arState.enabled) Icons.Default.ViewInAr else Icons.Default.AutoAwesome,
                        contentDescription = "Toggle 3D Character",
                        tint = if (arState.enabled) CyberMint else SignalAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (arState.enabled) "3D ON" else "+ 3D Character",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (arState.enabled) CyberMint else HudTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Flash Toggle Pill (Auto / On / Off) (>= 48dp touch target)
            val isFlashActive = flashMode != ImageCapture.FLASH_MODE_OFF
            Surface(
                onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("camera_flash_toggle"),
                shape = RoundedCornerShape(24.dp),
                color = if (isFlashActive) SignalAmber.copy(alpha = 0.2f) else HudGlassOverlay,
                border = BorderStroke(1.dp, if (isFlashActive) SignalAmber else HudBorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        else -> Icons.Default.FlashOff
                    }
                    val tint = if (isFlashActive) SignalAmber else HudTextPrimary
                    val label = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> "ON"
                        ImageCapture.FLASH_MODE_AUTO -> "AUTO"
                        else -> "OFF"
                    }
                    Icon(icon, contentDescription = "Flash", tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = tint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Center Floating Zoom Badge (Appears when pinching camera zoom)
        AnimatedVisibility(
            visible = showZoomBadge && !arState.enabled,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = HudGlassOverlay,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, SignalAmber),
                modifier = Modifier.padding(bottom = 120.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fX", zoomRatio),
                    fontFamily = JetBrainsMonoFontFamily,
                    color = SignalAmber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        // Bottom Area: Optional 3D Studio Control Panel + Docked Camera Action Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Progressive Disclosure: 3D AR Character Studio Controls ONLY visible when explicitly turned ON
            AnimatedVisibility(
                visible = arState.enabled,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                ArCharacterControlPanel(
                    state = arState,
                    onStateChange = { arState = it },
                    onHide3D = { arState = arState.copy(enabled = false) }
                )
            }

            // RULE 5: Bottom Controls in a Clean Docked Card with Arrangement.spacedBy(8.dp) and weighted widths (Modifier.weight(1f))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = HudGlassOverlay,
                border = BorderStroke(1.dp, HudBorderSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Gallery Shortcut Button
                    Surface(
                        onClick = {
                            try {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "PickMultipleVisualMedia failed: ${e.message}")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("camera_gallery_button"),
                        shape = RoundedCornerShape(14.dp),
                        color = HudSurfaceContainer,
                        border = BorderStroke(1.dp, HudBorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = "Gallery",
                                tint = HudTextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Gallery",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = HudTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 2. Capture Shutter Button (Primary Action Highlight)
                    val scaleAnimation by animateFloatAsState(if (isCapturing) 0.92f else 1f, label = "shutterScale")
                    Surface(
                        onClick = {
                            if (!isCapturing) {
                                isCapturing = true
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                                val capturedArState = arState
                                val capturedAnimTime = animTimeSeconds
                                val isFrontCam = lensFacing == CameraSelector.LENS_FACING_FRONT

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
                                            // RULE 5: If 3D Character toggle is ON, composite 3D character onto saved photo Bitmap
                                            if (capturedArState.enabled) {
                                                try {
                                                    ArCharacter3dRenderer.compositeCharacterOntoPhotoFile(
                                                        photoFile = photoFile,
                                                        state = capturedArState,
                                                        timeSec = capturedAnimTime,
                                                        isFrontCamera = isFrontCam
                                                    )
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "3D Character photo composite error: ${e.message}", e)
                                                }
                                            }
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
                            }
                        },
                        modifier = Modifier
                            .weight(1.2f)
                            .height(54.dp)
                            .scale(scaleAnimation)
                            .testTag("camera_shutter_button"),
                        shape = RoundedCornerShape(14.dp),
                        color = SignalAmber,
                        border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCapturing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color(0xFF0B1118),
                                    strokeWidth = 2.5.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "Capture",
                                    tint = Color(0xFF0B1118),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (arState.enabled) "SNAP 3D" else "CAPTURE",
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF0B1118),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // 3. Flip Camera Button
                    val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
                    Surface(
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
                            .weight(1f)
                            .height(54.dp)
                            .testTag("camera_flip_button"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isFront) CyberMint.copy(alpha = 0.16f) else HudSurfaceContainer,
                        border = BorderStroke(1.dp, if (isFront) CyberMint else HudBorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.FlipCameraAndroid,
                                contentDescription = "Switch Camera",
                                tint = if (isFront) CyberMint else HudTextPrimary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(flipRotation)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isFront) "Front" else "Flip",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (isFront) CyberMint else HudTextPrimary,
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
