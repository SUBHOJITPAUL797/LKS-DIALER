package com.example.ui.screens.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "WhatsAppMediaPreview"

data class DrawStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float
)

data class EditablePhotoItem(
    val id: String = UUID.randomUUID().toString(),
    val originalFile: File,
    var caption: String = "",
    var rotationAngle: Float = 0f,
    var strokes: List<DrawStroke> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppMediaPreviewScreen(
    initialPhotos: List<File>,
    onSendPhotos: (List<Pair<File, String>>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    // List of photos currently being reviewed/edited
    val photoItems = remember {
        mutableStateListOf<EditablePhotoItem>().apply {
            addAll(initialPhotos.map { EditablePhotoItem(originalFile = it) })
        }
    }

    var activeIndex by remember { mutableIntStateOf(0) }
    val safeActiveIndex = activeIndex.coerceIn(0, (photoItems.size - 1).coerceAtLeast(0))

    if (photoItems.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val currentItem = photoItems[safeActiveIndex]

    // Active item editing states
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
    var currentStrokePoints by remember { mutableStateOf(listOf<Offset>()) }

    var displayWidthPx by remember { mutableFloatStateOf(1f) }
    var displayHeightPx by remember { mutableFloatStateOf(1f) }

    // Load original bitmap for current active photo
    val originalBitmap = remember(currentItem.originalFile, currentItem.id) {
        try {
            BitmapFactory.decodeFile(currentItem.originalFile.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    // Rotated preview bitmap for current active photo
    val previewBitmap = remember(originalBitmap, currentItem.rotationAngle) {
        if (originalBitmap == null) null
        else if (currentItem.rotationAngle == 0f) originalBitmap
        else {
            val matrix = Matrix().apply { postRotate(currentItem.rotationAngle) }
            Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
        }
    }

    // Gallery Picker launcher to add more photos to the carousel
    val addPhotosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    val tempFile = File(context.cacheDir, "preview_add_${System.currentTimeMillis()}_${photoItems.size}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        photoItems.add(EditablePhotoItem(originalFile = tempFile))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed adding extra image: ${e.message}")
                }
            }
        }
    }

    BackHandler { onClose() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (photoItems.size > 1) "${safeActiveIndex + 1} of ${photoItems.size}" else "Edit Photo",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                },
                actions = {
                    // Undo button (only if current photo has strokes)
                    if (currentItem.strokes.isNotEmpty()) {
                        IconButton(onClick = {
                            val updated = currentItem.copy(strokes = currentItem.strokes.dropLast(1))
                            photoItems[safeActiveIndex] = updated
                        }) {
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
                        val newAngle = (currentItem.rotationAngle + 90f) % 360f
                        val updated = currentItem.copy(rotationAngle = newAngle, strokes = emptyList())
                        photoItems[safeActiveIndex] = updated
                    }) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate", tint = Color.White)
                    }

                    // Delete current photo button (if more than 1 photo in list)
                    if (photoItems.size > 1) {
                        IconButton(onClick = {
                            photoItems.removeAt(safeActiveIndex)
                            if (activeIndex >= photoItems.size) {
                                activeIndex = photoItems.size - 1
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove photo", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.85f))
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .padding(top = 6.dp, bottom = 8.dp)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Color palette row when in draw mode
                if (isDrawMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
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

                // WhatsApp Bottom Carousel Strip (Thumbnails + Add button)
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(photoItems) { index, item ->
                        val isSelected = (index == safeActiveIndex)
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) GreenCall else Color.White.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    activeIndex = index
                                    isDrawMode = false
                                }
                        ) {
                            AsyncImage(
                                model = item.originalFile,
                                contentDescription = "Photo ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            if (photoItems.size > 1) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .clickable {
                                            photoItems.removeAt(index)
                                            if (activeIndex >= photoItems.size) {
                                                activeIndex = (photoItems.size - 1).coerceAtLeast(0)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // "+ Add" More Photos Button in Carousel
                    item {
                        Surface(
                            onClick = { addPhotosLauncher.launch("image/*") },
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add more photos",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text("Add", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // Caption Input + Green Send Button Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = currentItem.caption,
                        onValueChange = { newCaption ->
                            val updated = currentItem.copy(caption = newCaption)
                            photoItems[safeActiveIndex] = updated
                        },
                        placeholder = { Text("Add a caption…", color = Color.LightGray.copy(alpha = 0.8f)) },
                        leadingIcon = {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(20.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        shape = RoundedCornerShape(26.dp),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            unfocusedContainerColor = Color.White.copy(alpha = 0.15f),
                            focusedContainerColor = Color.White.copy(alpha = 0.22f),
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = GreenCall
                        )
                    )

                    // WhatsApp-style round green send button
                    FloatingActionButton(
                        onClick = {
                            // Bake edits & rotations for all photos before sending
                            val results = mutableListOf<Pair<File, String>>()
                            photoItems.forEach { item ->
                                val bakedFile = bakeImageEdits(context, item)
                                results.add(Pair(bakedFile, item.caption.trim()))
                            }
                            onSendPhotos(results)
                        },
                        containerColor = GreenCall,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Photos",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // Main Image Viewport with Drawing Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                val imgW = previewBitmap.width.toFloat()
                val imgH = previewBitmap.height.toFloat()
                val fitScale = minOf(displayWidthPx / imgW, displayHeightPx / imgH)
                val renderW = (imgW * fitScale).coerceAtLeast(1f)
                val renderH = (imgH * fitScale).coerceAtLeast(1f)
                val offsetX = (displayWidthPx - renderW) / 2f
                val offsetY = (displayHeightPx - renderH) / 2f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            displayWidthPx = coordinates.size.width.toFloat().coerceAtLeast(1f)
                            displayHeightPx = coordinates.size.height.toFloat().coerceAtLeast(1f)
                        }
                        .pointerInput(isDrawMode, selectedColor, offsetX, offsetY, renderW, renderH) {
                            if (!isDrawMode) return@pointerInput
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val nx = ((offset.x - offsetX) / renderW).coerceIn(0f, 1f)
                                    val ny = ((offset.y - offsetY) / renderH).coerceIn(0f, 1f)
                                    currentStrokePoints = listOf(Offset(nx, ny))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val nx = ((change.position.x - offsetX) / renderW).coerceIn(0f, 1f)
                                    val ny = ((change.position.y - offsetY) / renderH).coerceIn(0f, 1f)
                                    currentStrokePoints = currentStrokePoints + Offset(nx, ny)
                                },
                                onDragEnd = {
                                    if (currentStrokePoints.isNotEmpty()) {
                                        val newStroke = DrawStroke(
                                            points = currentStrokePoints,
                                            color = selectedColor,
                                            strokeWidth = 10f
                                        )
                                        val updated = currentItem.copy(strokes = currentItem.strokes + newStroke)
                                        photoItems[safeActiveIndex] = updated
                                        currentStrokePoints = emptyList()
                                    }
                                },
                                onDragCancel = { currentStrokePoints = emptyList() }
                            )
                        }
                ) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Editing photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    // Overlay Canvas rendering drawing strokes
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (stroke in currentItem.strokes) {
                            val pts = stroke.points
                            for (i in 0 until pts.size - 1) {
                                val p1 = pts[i]
                                val p2 = pts[i + 1]
                                drawLine(
                                    color = stroke.color,
                                    start = Offset(offsetX + p1.x * renderW, offsetY + p1.y * renderH),
                                    end = Offset(offsetX + p2.x * renderW, offsetY + p2.y * renderH),
                                    strokeWidth = stroke.strokeWidth,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            }
                        }

                        // Current active stroke while dragging
                        if (currentStrokePoints.size > 1) {
                            for (i in 0 until currentStrokePoints.size - 1) {
                                val p1 = currentStrokePoints[i]
                                val p2 = currentStrokePoints[i + 1]
                                drawLine(
                                    color = selectedColor,
                                    start = Offset(offsetX + p1.x * renderW, offsetY + p1.y * renderH),
                                    end = Offset(offsetX + p2.x * renderW, offsetY + p2.y * renderH),
                                    strokeWidth = 10f,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            }
                        }
                    }
                }
            } else {
                CircularProgressIndicator(color = TealPrimary)
            }
        }
    }
}

/**
 * Bakes rotation and drawing strokes into a new output file on disk.
 */
private fun bakeImageEdits(
    context: android.content.Context,
    item: EditablePhotoItem
): File {
    try {
        val original = BitmapFactory.decodeFile(item.originalFile.absolutePath) ?: return item.originalFile

        // 1. Apply rotation
        val rotated = if (item.rotationAngle != 0f) {
            val matrix = Matrix().apply { postRotate(item.rotationAngle) }
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        } else original

        // 2. If no strokes, return rotated bitmap file directly
        if (item.strokes.isEmpty()) {
            if (item.rotationAngle == 0f) return item.originalFile
            val outputFile = File(context.cacheDir, "edited_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
            FileOutputStream(outputFile).use { fos ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            return outputFile
        }

        // 3. Bake strokes onto the bitmap using normalized coordinates
        val mutableBitmap = rotated.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = AndroidCanvas(mutableBitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        val scaleRatio = (mutableBitmap.width.toFloat() / 1080f).coerceAtLeast(1f)

        for (stroke in item.strokes) {
            paint.color = stroke.color.toArgb()
            paint.strokeWidth = stroke.strokeWidth * scaleRatio

            val pts = stroke.points
            for (i in 0 until pts.size - 1) {
                val p1 = pts[i]
                val p2 = pts[i + 1]
                canvas.drawLine(
                    p1.x * mutableBitmap.width,
                    p1.y * mutableBitmap.height,
                    p2.x * mutableBitmap.width,
                    p2.y * mutableBitmap.height,
                    paint
                )
            }
        }

        val outputFile = File(context.cacheDir, "edited_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        FileOutputStream(outputFile).use { fos ->
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        return outputFile
    } catch (e: Exception) {
        Log.e(TAG, "Error baking image edits: ${e.message}", e)
        return item.originalFile
    }
}
