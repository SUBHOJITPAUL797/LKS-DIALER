package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Filter1
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary

/**
 * High-performance, butter-smooth zoomable image viewer dialog.
 * Features:
 *  - 2-finger pinch-to-zoom (scale 1.0f .. 5.0f)
 *  - Pan & drag when zoomed in with edge bounding clamping
 *  - Double-tap to smoothly zoom in (2.5x) or reset to 1x
 *  - Single-tap to toggle top controls for true immersive viewing
 *  - View Once badge & callback support
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

    // Scale and Pan offsets
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Toggle controls overlay visibility
    var showOverlay by remember { mutableStateOf(true) }

    // Smooth reset or double-tap animation
    var targetScale by remember { mutableFloatStateOf(1f) }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 260),
        label = "ZoomAnimation"
    )

    // Sync targetScale with pinch scale
    LaunchedEffect(animatedScale) {
        if (targetScale == 1f && animatedScale == 1f) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        } else if (targetScale > 1f) {
            scale = animatedScale
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        BackHandler {
            if (scale > 1.1f) {
                // First back press resets zoom
                targetScale = 1f
                scale = 1f
                offsetX = 0f
                offsetY = 0f
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
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (scale > 1.2f) {
                                targetScale = 1f
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                targetScale = 2.5f
                                scale = 2.5f
                                // Center zoom on tap position
                                val centerX = containerSize.width / 2f
                                val centerY = containerSize.height / 2f
                                offsetX = (centerX - tapOffset.x) * 1.5f
                                offsetY = (centerY - tapOffset.y) * 1.5f
                            }
                        },
                        onTap = {
                            showOverlay = !showOverlay
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1.0f, 5.0f)
                        scale = newScale
                        targetScale = newScale

                        if (scale > 1.0f) {
                            // Calculate pan bounds based on image container dimensions
                            val maxPanX = (containerSize.width * (scale - 1f)) / 2f
                            val maxPanY = (containerSize.height * (scale - 1f)) / 2f

                            val newOffsetX = offsetX + pan.x
                            val newOffsetY = offsetY + pan.y

                            offsetX = newOffsetX.coerceIn(-maxPanX, maxPanX)
                            offsetY = newOffsetY.coerceIn(-maxPanY, maxPanY)
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Main Zoomable Image
            AsyncImage(
                model = imageModel,
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
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

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

                        // Reset Zoom button (visible when zoomed in)
                        if (scale > 1.05f) {
                            IconButton(onClick = {
                                targetScale = 1f
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }) {
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
