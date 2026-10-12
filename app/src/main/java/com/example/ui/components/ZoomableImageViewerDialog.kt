package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import kotlinx.coroutines.launch

/**
 * High-performance, butter-smooth zoomable image viewer dialog.
 * Features:
 *  - 2-finger pinch-to-zoom (scale 1.0f .. 5.0f) with zero jitter / zero feedback loops
 *  - Pan & drag when zoomed in with edge bounding clamping
 *  - Double-tap to smoothly zoom in (2.5x) or reset to 1x via coroutine animation
 *  - Explicit Zoom In (+) and Zoom Out (–) buttons for precision control
 *  - Single-tap to toggle top controls for true immersive viewing
 *  - View Once badge & callback support with FLAG_SECURE and Coil cache bypass
 */
@Composable
fun ZoomableImageViewerDialog(
    imageModel: Any?,
    title: String = "Photo",
    subtitle: String? = null,
    isViewOnce: Boolean = false,
    onDismissRequest: () -> Unit
) {
    if (imageModel == null) return

    // Scale and Pan offsets — directly updated during gestures for 120fps fluid response
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Toggle controls overlay visibility
    var showOverlay by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Smooth programmatic animation helper (for double-tap, zoom buttons, reset, and back press)
    fun animateZoomTo(targetScale: Float, targetOffset: Offset = Offset.Zero) {
        scope.launch {
            val startScale = scale
            val startOffsetX = offsetX
            val startOffsetY = offsetY
            androidx.compose.animation.core.animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 220)
            ) { progress, _ ->
                scale = startScale + (targetScale - startScale) * progress
                offsetX = startOffsetX + (targetOffset.x - startOffsetX) * progress
                offsetY = startOffsetY + (targetOffset.y - startOffsetY) * progress
            }
        }
    }

    val context = LocalContext.current
    val finalImageModel = remember(imageModel, isViewOnce) {
        if (isViewOnce) {
            ImageRequest.Builder(context)
                .data(imageModel)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        } else {
            imageModel
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            securePolicy = if (isViewOnce) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit
        )
    ) {
        BackHandler {
            if (scale > 1.1f) {
                // First back press smoothly resets zoom to 1x
                animateZoomTo(1f, Offset.Zero)
            } else {
                onDismissRequest()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
                .pointerInput(Unit) {
                    // Two-finger pinch-to-zoom and pan gesture
                    detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1.0f, 5.0f)
                        scale = newScale

                        if (newScale > 1.0f && containerSize.width > 0 && containerSize.height > 0) {
                            val maxPanX = (containerSize.width * (newScale - 1f)) / 2f
                            val maxPanY = (containerSize.height * (newScale - 1f)) / 2f

                            val newOffsetX = offsetX + pan.x
                            val newOffsetY = offsetY + pan.y

                            offsetX = newOffsetX.coerceIn(-maxPanX, maxPanX)
                            offsetY = newOffsetY.coerceIn(-maxPanY, maxPanY)
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    // Tap gestures: Double-tap to zoom in/out, Single-tap to toggle top controls
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (scale > 1.2f) {
                                animateZoomTo(1f, Offset.Zero)
                            } else {
                                val targetScale = 2.5f
                                if (containerSize.width > 0 && containerSize.height > 0) {
                                    val maxPanX = (containerSize.width * (targetScale - 1f)) / 2f
                                    val maxPanY = (containerSize.height * (targetScale - 1f)) / 2f
                                    val centerX = containerSize.width / 2f
                                    val centerY = containerSize.height / 2f
                                    val targetOffsetX = ((centerX - tapOffset.x) * 1.5f).coerceIn(-maxPanX, maxPanX)
                                    val targetOffsetY = ((centerY - tapOffset.y) * 1.5f).coerceIn(-maxPanY, maxPanY)
                                    animateZoomTo(targetScale, Offset(targetOffsetX, targetOffsetY))
                                } else {
                                    animateZoomTo(targetScale, Offset.Zero)
                                }
                            }
                        },
                        onTap = {
                            showOverlay = !showOverlay
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Main Zoomable Image
            AsyncImage(
                model = finalImageModel,
                contentDescription = "Fullscreen Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )

            // Top Bar Overlay
            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = title,
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                if (isViewOnce) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = GreenCall.copy(alpha = 0.22f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, GreenCall)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "①",
                                                color = GreenCall,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "View Once",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }

                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Zoom percentage badge
                        Surface(
                            color = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "${(scale * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Zoom Out (–) Button
                        IconButton(
                            onClick = {
                                val nextScale = (scale - 0.5f).coerceAtLeast(1.0f)
                                if (nextScale <= 1.0f) {
                                    animateZoomTo(1f, Offset.Zero)
                                } else if (containerSize.width > 0 && containerSize.height > 0) {
                                    val maxPanX = (containerSize.width * (nextScale - 1f)) / 2f
                                    val maxPanY = (containerSize.height * (nextScale - 1f)) / 2f
                                    animateZoomTo(nextScale, Offset(offsetX.coerceIn(-maxPanX, maxPanX), offsetY.coerceIn(-maxPanY, maxPanY)))
                                }
                            },
                            enabled = scale > 1.05f
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "Zoom Out",
                                tint = if (scale > 1.05f) Color.White else Color.Gray.copy(alpha = 0.4f)
                            )
                        }

                        // Zoom In (+) Button
                        IconButton(
                            onClick = {
                                val nextScale = (scale + 0.5f).coerceAtMost(5.0f)
                                if (containerSize.width > 0 && containerSize.height > 0) {
                                    val maxPanX = (containerSize.width * (nextScale - 1f)) / 2f
                                    val maxPanY = (containerSize.height * (nextScale - 1f)) / 2f
                                    animateZoomTo(nextScale, Offset(offsetX.coerceIn(-maxPanX, maxPanX), offsetY.coerceIn(-maxPanY, maxPanY)))
                                } else {
                                    animateZoomTo(nextScale, Offset.Zero)
                                }
                            },
                            enabled = scale < 4.95f
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Zoom In",
                                tint = if (scale < 4.95f) Color.White else Color.Gray.copy(alpha = 0.4f)
                            )
                        }

                        // Reset Zoom button (visible when zoomed in)
                        if (scale > 1.05f) {
                            IconButton(onClick = { animateZoomTo(1f, Offset.Zero) }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Reset Zoom",
                                    tint = GreenCall
                                )
                            }
                        }

                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Bottom Notice for View Once
            if (isViewOnce) {
                AnimatedVisibility(
                    visible = showOverlay,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .padding(bottom = 32.dp)
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "①",
                                color = GreenCall,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "This photo will be destroyed once you exit",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
