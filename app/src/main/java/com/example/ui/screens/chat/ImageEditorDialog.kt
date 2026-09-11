package com.example.ui.screens.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import java.io.File
import java.io.FileOutputStream

data class DrawStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorDialog(
    imageFile: File,
    onDismiss: () -> Unit,
    onSendImage: (File, String) -> Unit
) {
    val context = LocalContext.current

    // Rotation state (0, 90, 180, 270)
    var rotationAngle by remember { mutableStateOf(0f) }

    // Drawing state
    var isDrawMode by remember { mutableStateOf(false) }
    val colors = listOf(
        Color.White,
        Color(0xFFFFEB3B), // Yellow
        Color(0xFF4CAF50), // Green
        TealPrimary,       // Teal
        Color(0xFF2196F3), // Blue
        Color(0xFFF44336)  // Red
    )
    var selectedColor by remember { mutableStateOf(colors[5]) }
    var strokes by remember { mutableStateOf(listOf<DrawStroke>()) }
    var currentStrokePoints by remember { mutableStateOf(listOf<Offset>()) }

    var captionText by remember { mutableStateOf("") }
    var displayWidthPx by remember { mutableStateOf(1f) }
    var displayHeightPx by remember { mutableStateOf(1f) }

    // Load original bitmap
    val originalBitmap = remember(imageFile) {
        try {
            BitmapFactory.decodeFile(imageFile.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    // Rotated preview bitmap
    val previewBitmap = remember(originalBitmap, rotationAngle) {
        if (originalBitmap == null) null
        else if (rotationAngle == 0f) originalBitmap
        else {
            val matrix = Matrix().apply { postRotate(rotationAngle) }
            Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (isDrawMode) "Draw on Photo" else "Edit Photo",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                        }
                    },
                    actions = {
                        // Undo button (only if there are strokes)
                        if (strokes.isNotEmpty()) {
                            IconButton(onClick = { strokes = strokes.dropLast(1) }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = Color.White)
                            }
                        }

                        // Pen / Draw mode toggle
                        IconButton(onClick = { isDrawMode = !isDrawMode }) {
                            Icon(
                                Icons.Default.Brush,
                                contentDescription = "Draw",
                                tint = if (isDrawMode) selectedColor else Color.White
                            )
                        }

                        // Rotate 90 degrees button
                        IconButton(onClick = {
                            rotationAngle = (rotationAngle + 90f) % 360f
                            strokes = emptyList() // clear display strokes when rotation changes
                        }) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.85f))
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    // Color palette row when in draw mode
                    if (isDrawMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            colors.forEach { color ->
                                val isSelected = (color == selectedColor)
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 36.dp else 28.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .then(
                                            if (isSelected) Modifier.border(2.5.dp, Color.White, CircleShape)
                                            else Modifier
                                        )
                                        .clickable { selectedColor = color }
                                )
                            }
                        }
                    }

                    // Caption + Send row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = captionText,
                            onValueChange = { captionText = it },
                            placeholder = { Text("Add a caption…", color = Color.Gray) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF262D31),
                                unfocusedContainerColor = Color(0xFF1F2428),
                                focusedBorderColor = TealPrimary,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )

                        // Send button
                        IconButton(
                            onClick = {
                                if (previewBitmap != null) {
                                    try {
                                        // Bake drawings onto final bitmap
                                        val mutableBitmap = previewBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                        val canvas = android.graphics.Canvas(mutableBitmap)
                                        val paint = android.graphics.Paint().apply {
                                            isAntiAlias = true
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeCap = android.graphics.Paint.Cap.ROUND
                                            strokeJoin = android.graphics.Paint.Join.ROUND
                                        }

                                        val scaleX = mutableBitmap.width.toFloat() / displayWidthPx.coerceAtLeast(1f)
                                        val scaleY = mutableBitmap.height.toFloat() / displayHeightPx.coerceAtLeast(1f)

                                        strokes.forEach { stroke ->
                                            paint.color = stroke.color.toArgb()
                                            paint.strokeWidth = stroke.strokeWidth * ((scaleX + scaleY) / 2f)
                                            val path = android.graphics.Path()
                                            stroke.points.forEachIndexed { i, pt ->
                                                val bx = pt.x * scaleX
                                                val by = pt.y * scaleY
                                                if (i == 0) path.moveTo(bx, by) else path.lineTo(bx, by)
                                            }
                                            canvas.drawPath(path, paint)
                                        }

                                        val outFile = File(context.cacheDir, "edited_${System.currentTimeMillis()}.jpg")
                                        FileOutputStream(outFile).use { out ->
                                            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                        }
                                        onSendImage(outFile, captionText.trim())
                                    } catch (e: Exception) {
                                        onSendImage(imageFile, captionText.trim())
                                    }
                                } else {
                                    onSendImage(imageFile, captionText.trim())
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(GreenCall)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (previewBitmap != null) {
                    val imgBitmap = remember(previewBitmap) { previewBitmap.asImageBitmap() }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                displayWidthPx = coordinates.size.width.toFloat()
                                displayHeightPx = coordinates.size.height.toFloat()
                            }
                    ) {
                        Image(
                            bitmap = imgBitmap,
                            contentDescription = "Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Drawing overlay Canvas
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isDrawMode) {
                                        Modifier.pointerInput(selectedColor) {
                                            detectDragGestures(
                                                onDragStart = { offset ->
                                                    currentStrokePoints = listOf(offset)
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    currentStrokePoints = currentStrokePoints + change.position
                                                },
                                                onDragEnd = {
                                                    if (currentStrokePoints.isNotEmpty()) {
                                                        strokes = strokes + DrawStroke(
                                                            points = currentStrokePoints,
                                                            color = selectedColor,
                                                            strokeWidth = 6f
                                                        )
                                                        currentStrokePoints = emptyList()
                                                    }
                                                },
                                                onDragCancel = {
                                                    currentStrokePoints = emptyList()
                                                }
                                            )
                                        }
                                    } else Modifier
                                )
                        ) {
                            // Draw all committed strokes
                            strokes.forEach { stroke ->
                                if (stroke.points.size > 1) {
                                    val path = androidx.compose.ui.graphics.Path()
                                    path.moveTo(stroke.points[0].x, stroke.points[0].y)
                                    for (i in 1 until stroke.points.size) {
                                        path.lineTo(stroke.points[i].x, stroke.points[i].y)
                                    }
                                    drawPath(
                                        path = path,
                                        color = stroke.color,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = stroke.strokeWidth,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                                        )
                                    )
                                }
                            }

                            // Draw current stroke in progress
                            if (currentStrokePoints.size > 1) {
                                val path = androidx.compose.ui.graphics.Path()
                                path.moveTo(currentStrokePoints[0].x, currentStrokePoints[0].y)
                                for (i in 1 until currentStrokePoints.size) {
                                    path.lineTo(currentStrokePoints[i].x, currentStrokePoints[i].y)
                                }
                                drawPath(
                                    path = path,
                                    color = selectedColor,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 6f,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }
                } else {
                    CircularProgressIndicator(color = TealPrimary)
                }
            }
        }
    }
}
