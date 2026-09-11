package com.example.ui.screens.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GreenCall
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

private const val TAG = "WhatsAppPhotoCropper"

enum class AspectRatioPreset(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1.0f),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f)
}

private enum class DragHandle {
    NONE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_EDGE,
    BOTTOM_EDGE,
    LEFT_EDGE,
    RIGHT_EDGE,
    CENTER_PAN
}

/**
 * Full-screen WhatsApp-style interactive Photo Crop and Rotate tool.
 * Provides draggable corner/edge brackets, rule-of-thirds grid lines,
 * aspect ratio locks, 90-degree rotation, and high-performance bitmap output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppPhotoCropper(
    imageFile: File,
    initialRotation: Float = 0f,
    onCropDone: (File) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Load initial bitmap from file
    val rawBitmap = remember(imageFile) {
        try {
            BitmapFactory.decodeFile(imageFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode bitmap: ${e.message}")
            null
        }
    }

    if (rawBitmap == null) {
        LaunchedEffect(Unit) { onCancel() }
        return
    }

    var rotationDegrees by remember { mutableFloatStateOf(initialRotation) }

    // Rotated bitmap based on rotationDegrees
    val activeBitmap = remember(rawBitmap, rotationDegrees) {
        if (rotationDegrees % 360f == 0f) rawBitmap
        else {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        }
    }

    var selectedAspect by remember { mutableStateOf(AspectRatioPreset.FREE) }

    // Normalized crop rectangle: [0.0f .. 1.0f] relative to displayed image bounds
    var normLeft by remember { mutableFloatStateOf(0f) }
    var normTop by remember { mutableFloatStateOf(0f) }
    var normRight by remember { mutableFloatStateOf(1f) }
    var normBottom by remember { mutableFloatStateOf(1f) }

    fun resetCrop() {
        normLeft = 0f
        normTop = 0f
        normRight = 1f
        normBottom = 1f
        selectedAspect = AspectRatioPreset.FREE
    }

    // When aspect ratio changes, adjust normalized crop rect to match target ratio
    fun applyAspectRatio(preset: AspectRatioPreset, displayedW: Float, displayedH: Float) {
        selectedAspect = preset
        val targetRatio = preset.ratio ?: return

        val currentW = (normRight - normLeft) * displayedW
        val currentH = (normBottom - normTop) * displayedH
        val centerX = (normLeft + normRight) / 2f
        val centerY = (normTop + normBottom) / 2f

        var newW = currentW
        var newH = currentW / targetRatio

        if (newH > displayedH) {
            newH = displayedH
            newW = displayedH * targetRatio
        }
        if (newW > displayedW) {
            newW = displayedW
            newH = displayedW / targetRatio
        }

        val normNewW = (newW / displayedW).coerceIn(0.1f, 1f)
        val normNewH = (newH / displayedH).coerceIn(0.1f, 1f)

        normLeft = (centerX - normNewW / 2f).coerceIn(0f, 1f - normNewW)
        normRight = normLeft + normNewW
        normTop = (centerY - normNewH / 2f).coerceIn(0f, 1f - normNewH)
        normBottom = normTop + normNewH
    }

    BackHandler { onCancel() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Crop & Rotate",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                },
                actions = {
                    // Reset Button
                    IconButton(onClick = { resetCrop() }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Reset Crop", tint = Color.White)
                    }
                    // Apply / Done Check Button
                    IconButton(
                        onClick = {
                            try {
                                val bWidth = activeBitmap.width
                                val bHeight = activeBitmap.height

                                val pxX = (normLeft * bWidth).toInt().coerceIn(0, bWidth - 1)
                                val pxY = (normTop * bHeight).toInt().coerceIn(0, bHeight - 1)
                                val pxW = ((normRight - normLeft) * bWidth).toInt().coerceIn(1, bWidth - pxX)
                                val pxH = ((normBottom - normTop) * bHeight).toInt().coerceIn(1, bHeight - pxY)

                                val cropped = Bitmap.createBitmap(activeBitmap, pxX, pxY, pxW, pxH)
                                val outFile = File(context.cacheDir, "crop_${System.currentTimeMillis()}.jpg")
                                FileOutputStream(outFile).use { out ->
                                    cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                }
                                onCropDone(outFile)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error cropping bitmap: ${e.message}", e)
                                onCancel()
                            }
                        }
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = GreenCall,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, contentDescription = "Apply", tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                // Aspect Ratio Selector Chips + Rotate Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Aspect ratio chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AspectRatioPreset.values().forEach { preset ->
                            val isSelected = (selectedAspect == preset)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) GreenCall else Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.clickable {
                                    selectedAspect = preset
                                }
                            ) {
                                Text(
                                    text = preset.label,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Rotate 90 degrees button
                    IconButton(
                        onClick = {
                            rotationDegrees = (rotationDegrees + 90f) % 360f
                            resetCrop()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = "Rotate 90 degrees",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()

            // Calculate fit bounds of activeBitmap inside container
            val bmpW = activeBitmap.width.toFloat()
            val bmpH = activeBitmap.height.toFloat()
            val imgAspect = bmpW / bmpH
            val screenAspect = containerWidth / containerHeight

            val displayedW = if (imgAspect > screenAspect) containerWidth else containerHeight * imgAspect
            val displayedH = if (imgAspect > screenAspect) containerWidth / imgAspect else containerHeight

            val imgLeft = (containerWidth - displayedW) / 2f
            val imgTop = (containerHeight - displayedH) / 2f

            // Recalculate aspect ratio if locked and bitmap rotated
            LaunchedEffect(selectedAspect, activeBitmap) {
                if (selectedAspect.ratio != null) {
                    applyAspectRatio(selectedAspect, displayedW, displayedH)
                }
            }

            val handleRadiusPx = with(density) { 36.dp.toPx() }
            val minSizeNorm = 0.08f

            var activeHandle by remember { mutableStateOf(DragHandle.NONE) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(displayedW, displayedH, selectedAspect) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val curCropLeft = imgLeft + normLeft * displayedW
                                val curCropTop = imgTop + normTop * displayedH
                                val curCropRight = imgLeft + normRight * displayedW
                                val curCropBottom = imgTop + normBottom * displayedH

                                fun isNear(x: Float, y: Float) =
                                    (startOffset.x - x) * (startOffset.x - x) + (startOffset.y - y) * (startOffset.y - y) <= handleRadiusPx * handleRadiusPx

                                activeHandle = when {
                                    isNear(curCropLeft, curCropTop) -> DragHandle.TOP_LEFT
                                    isNear(curCropRight, curCropTop) -> DragHandle.TOP_RIGHT
                                    isNear(curCropLeft, curCropBottom) -> DragHandle.BOTTOM_LEFT
                                    isNear(curCropRight, curCropBottom) -> DragHandle.BOTTOM_RIGHT
                                    kotlin.math.abs(startOffset.y - curCropTop) <= handleRadiusPx && startOffset.x in curCropLeft..curCropRight -> DragHandle.TOP_EDGE
                                    kotlin.math.abs(startOffset.y - curCropBottom) <= handleRadiusPx && startOffset.x in curCropLeft..curCropRight -> DragHandle.BOTTOM_EDGE
                                    kotlin.math.abs(startOffset.x - curCropLeft) <= handleRadiusPx && startOffset.y in curCropTop..curCropBottom -> DragHandle.LEFT_EDGE
                                    kotlin.math.abs(startOffset.x - curCropRight) <= handleRadiusPx && startOffset.y in curCropTop..curCropBottom -> DragHandle.RIGHT_EDGE
                                    startOffset.x in curCropLeft..curCropRight && startOffset.y in curCropTop..curCropBottom -> DragHandle.CENTER_PAN
                                    else -> DragHandle.NONE
                                }
                            },
                            onDragEnd = { activeHandle = DragHandle.NONE },
                            onDragCancel = { activeHandle = DragHandle.NONE },
                            onDrag = { _, dragAmount ->
                                if (activeHandle == DragHandle.NONE) return@detectDragGestures
                                val dNormX = dragAmount.x / displayedW
                                val dNormY = dragAmount.y / displayedH

                                when (activeHandle) {
                                    DragHandle.CENTER_PAN -> {
                                        val w = normRight - normLeft
                                        val h = normBottom - normTop
                                        val newL = (normLeft + dNormX).coerceIn(0f, 1f - w)
                                        val newT = (normTop + dNormY).coerceIn(0f, 1f - h)
                                        normLeft = newL
                                        normRight = newL + w
                                        normTop = newT
                                        normBottom = newT + h
                                    }
                                    DragHandle.TOP_LEFT -> {
                                        normLeft = (normLeft + dNormX).coerceIn(0f, normRight - minSizeNorm)
                                        normTop = (normTop + dNormY).coerceIn(0f, normBottom - minSizeNorm)
                                        if (selectedAspect.ratio != null) {
                                            val w = (normRight - normLeft) * displayedW
                                            val targetH = w / selectedAspect.ratio!!
                                            val normH = targetH / displayedH
                                            normTop = (normBottom - normH).coerceIn(0f, normBottom - minSizeNorm)
                                        }
                                    }
                                    DragHandle.TOP_RIGHT -> {
                                        normRight = (normRight + dNormX).coerceIn(normLeft + minSizeNorm, 1f)
                                        normTop = (normTop + dNormY).coerceIn(0f, normBottom - minSizeNorm)
                                        if (selectedAspect.ratio != null) {
                                            val w = (normRight - normLeft) * displayedW
                                            val targetH = w / selectedAspect.ratio!!
                                            val normH = targetH / displayedH
                                            normTop = (normBottom - normH).coerceIn(0f, normBottom - minSizeNorm)
                                        }
                                    }
                                    DragHandle.BOTTOM_LEFT -> {
                                        normLeft = (normLeft + dNormX).coerceIn(0f, normRight - minSizeNorm)
                                        normBottom = (normBottom + dNormY).coerceIn(normTop + minSizeNorm, 1f)
                                        if (selectedAspect.ratio != null) {
                                            val w = (normRight - normLeft) * displayedW
                                            val targetH = w / selectedAspect.ratio!!
                                            val normH = targetH / displayedH
                                            normBottom = (normTop + normH).coerceIn(normTop + minSizeNorm, 1f)
                                        }
                                    }
                                    DragHandle.BOTTOM_RIGHT -> {
                                        normRight = (normRight + dNormX).coerceIn(normLeft + minSizeNorm, 1f)
                                        normBottom = (normBottom + dNormY).coerceIn(normTop + minSizeNorm, 1f)
                                        if (selectedAspect.ratio != null) {
                                            val w = (normRight - normLeft) * displayedW
                                            val targetH = w / selectedAspect.ratio!!
                                            val normH = targetH / displayedH
                                            normBottom = (normTop + normH).coerceIn(normTop + minSizeNorm, 1f)
                                        }
                                    }
                                    DragHandle.TOP_EDGE -> {
                                        normTop = (normTop + dNormY).coerceIn(0f, normBottom - minSizeNorm)
                                        if (selectedAspect.ratio != null) {
                                            val h = (normBottom - normTop) * displayedH
                                            val targetW = h * selectedAspect.ratio!!
                                            val normW = (targetW / displayedW).coerceIn(minSizeNorm, 1f)
                                            val midX = (normLeft + normRight) / 2f
                                            normLeft = (midX - normW / 2f).coerceIn(0f, 1f - normW)
                                            normRight = normLeft + normW
                                        }
                                    }
                                    DragHandle.BOTTOM_EDGE -> {
                                        normBottom = (normBottom + dNormY).coerceIn(normTop + minSizeNorm, 1f)
                                        if (selectedAspect.ratio != null) {
                                            val h = (normBottom - normTop) * displayedH
                                            val targetW = h * selectedAspect.ratio!!
                                            val normW = (targetW / displayedW).coerceIn(minSizeNorm, 1f)
                                            val midX = (normLeft + normRight) / 2f
                                            normLeft = (midX - normW / 2f).coerceIn(0f, 1f - normW)
                                            normRight = normLeft + normW
                                        }
                                    }
                                    DragHandle.LEFT_EDGE -> {
                                        normLeft = (normLeft + dNormX).coerceIn(0f, normRight - minSizeNorm)
                                        if (selectedAspect.ratio != null) {
                                            val w = (normRight - normLeft) * displayedW
                                            val targetH = w / selectedAspect.ratio!!
                                            val normH = (targetH / displayedH).coerceIn(minSizeNorm, 1f)
                                            val midY = (normTop + normBottom) / 2f
                                            normTop = (midY - normH / 2f).coerceIn(0f, 1f - normH)
                                            normBottom = normTop + normH
                                        }
                                    }
                                    DragHandle.RIGHT_EDGE -> {
                                        normRight = (normRight + dNormX).coerceIn(normLeft + minSizeNorm, 1f)
                                        if (selectedAspect.ratio != null) {
                                            val w = (normRight - normLeft) * displayedW
                                            val targetH = w / selectedAspect.ratio!!
                                            val normH = (targetH / displayedH).coerceIn(minSizeNorm, 1f)
                                            val midY = (normTop + normBottom) / 2f
                                            normTop = (midY - normH / 2f).coerceIn(0f, 1f - normH)
                                            normBottom = normTop + normH
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        )
                    }
            ) {
                // Main Canvas: Draws the fitted photo and the interactive crop overlay
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 1. Draw source bitmap fitted to bounds
                    drawImage(
                        image = activeBitmap.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(imgLeft.toInt(), imgTop.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(displayedW.toInt(), displayedH.toInt())
                    )

                    val cropLeft = imgLeft + normLeft * displayedW
                    val cropTop = imgTop + normTop * displayedH
                    val cropRight = imgLeft + normRight * displayedW
                    val cropBottom = imgTop + normBottom * displayedH
                    val cropWidth = cropRight - cropLeft
                    val cropHeight = cropBottom - cropTop

                    val scrimColor = Color.Black.copy(alpha = 0.65f)

                    // 2. Draw 4 dark scrim rects around the crop area
                    // Top scrim
                    drawRect(scrimColor, topLeft = Offset(0f, 0f), size = Size(size.width, cropTop))
                    // Bottom scrim
                    drawRect(scrimColor, topLeft = Offset(0f, cropBottom), size = Size(size.width, size.height - cropBottom))
                    // Left scrim
                    drawRect(scrimColor, topLeft = Offset(0f, cropTop), size = Size(cropLeft, cropHeight))
                    // Right scrim
                    drawRect(scrimColor, topLeft = Offset(cropRight, cropTop), size = Size(size.width - cropRight, cropHeight))

                    // 3. Draw crop border
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(cropLeft, cropTop),
                        size = Size(cropWidth, cropHeight),
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // 4. Rule-of-thirds grid lines (2 horizontal, 2 vertical)
                    val gridColor = Color.White.copy(alpha = 0.35f)
                    val strokeGrid = Stroke(width = 1.dp.toPx())

                    // Vertical grid lines
                    drawLine(gridColor, Offset(cropLeft + cropWidth / 3f, cropTop), Offset(cropLeft + cropWidth / 3f, cropBottom), strokeGrid.width)
                    drawLine(gridColor, Offset(cropLeft + 2f * cropWidth / 3f, cropTop), Offset(cropLeft + 2f * cropWidth / 3f, cropBottom), strokeGrid.width)
                    // Horizontal grid lines
                    drawLine(gridColor, Offset(cropLeft, cropTop + cropHeight / 3f), Offset(cropRight, cropTop + cropHeight / 3f), strokeGrid.width)
                    drawLine(gridColor, Offset(cropLeft, cropTop + 2f * cropHeight / 3f), Offset(cropRight, cropTop + 2f * cropHeight / 3f), strokeGrid.width)

                    // 5. Four bold corner brackets (WhatsApp style L-shapes)
                    val cornerLen = 22.dp.toPx()
                    val cornerThickness = 3.5.dp.toPx()
                    val cornerColor = Color.White

                    // Top-Left corner
                    drawLine(cornerColor, Offset(cropLeft - 1f, cropTop), Offset(cropLeft + cornerLen, cropTop), cornerThickness)
                    drawLine(cornerColor, Offset(cropLeft, cropTop - 1f), Offset(cropLeft, cropTop + cornerLen), cornerThickness)

                    // Top-Right corner
                    drawLine(cornerColor, Offset(cropRight + 1f, cropTop), Offset(cropRight - cornerLen, cropTop), cornerThickness)
                    drawLine(cornerColor, Offset(cropRight, cropTop - 1f), Offset(cropRight, cropTop + cornerLen), cornerThickness)

                    // Bottom-Left corner
                    drawLine(cornerColor, Offset(cropLeft - 1f, cropBottom), Offset(cropLeft + cornerLen, cropBottom), cornerThickness)
                    drawLine(cornerColor, Offset(cropLeft, cropBottom + 1f), Offset(cropLeft, cropBottom - cornerLen), cornerThickness)

                    // Bottom-Right corner
                    drawLine(cornerColor, Offset(cropRight + 1f, cropBottom), Offset(cropRight - cornerLen, cropBottom), cornerThickness)
                    drawLine(cornerColor, Offset(cropRight, cropBottom + 1f), Offset(cropRight, cropBottom - cornerLen), cornerThickness)
                }
            }
        }
    }
}
