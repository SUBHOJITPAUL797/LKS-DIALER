package com.example.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.ui.theme.GreenCall
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.LocalThemeColor

@Composable
fun ChatMediaKeyboardPicker(
    modifier: Modifier = Modifier,
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    onStickerSelected: (stickerCode: String, stickerName: String, stickerUrl: String) -> Unit,
    onGifSelected: (gifUrl: String, gifTitle: String) -> Unit,
    onClose: () -> Unit = {}
) {
    val themeColor = LocalThemeColor.current
    val isDark = LocalIsDarkTheme.current
    val haptic = LocalHapticFeedback.current

    var selectedTab by remember { mutableStateOf(MediaKeyboardTab.EMOJI) }
    var isExpanded by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }

    val imeBottomDp = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val isImeOpen = imeBottomDp > 90.dp

    val animatedHeight by animateDpAsState(
        targetValue = when {
            isImeOpen -> 236.dp
            isExpanded -> 478.dp
            else -> 372.dp
        },
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "mediaPickerHeight"
    )

    val containerBg = if (isDark) Color(0xFF111822) else Color(0xFFF7F9FC)
    val elevatedSurface = if (isDark) Color(0xFF1C2735) else Color(0xFFEDF1F7)
    val topGlowBorder = if (isDark) Color.White.copy(alpha = 0.11f) else themeColor.primary.copy(alpha = 0.16f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = containerBg,
        border = BorderStroke(0.8.dp, topGlowBorder),
        tonalElevation = 4.dp,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Gesture Handle Bar (Drag Up to Expand • Drag Down to Close) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(isExpanded) {
                        detectVerticalDragGestures(
                            onDragStart = { dragAccumulated = 0f },
                            onVerticalDrag = { _, dragAmount -> dragAccumulated += dragAmount },
                            onDragEnd = {
                                when {
                                    dragAccumulated < -60f && !isExpanded -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isExpanded = true
                                    }
                                    dragAccumulated > 70f && isExpanded -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isExpanded = false
                                    }
                                    dragAccumulated > 85f && !isExpanded -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onClose()
                                    }
                                }
                                dragAccumulated = 0f
                            }
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { isExpanded = !isExpanded }
                    .padding(top = 8.dp, bottom = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.24f)
                            else Color(0xFF94A3B8).copy(alpha = 0.42f)
                        )
                )
            }

            // ── Sleek Single-Row Header: Clean Tabs + Search / Actions ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = elevatedSurface,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .height(36.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        MediaTabPill(
                            title = "Emoji",
                            icon = Icons.Outlined.EmojiEmotions,
                            selected = selectedTab == MediaKeyboardTab.EMOJI,
                            accentColor = themeColor.primary,
                            accentDarkColor = themeColor.primaryDark,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = MediaKeyboardTab.EMOJI
                                searchQuery = ""
                            }
                        )
                        MediaTabPill(
                            title = "GIF",
                            icon = Icons.Outlined.GifBox,
                            selected = selectedTab == MediaKeyboardTab.GIF,
                            accentColor = themeColor.primary,
                            accentDarkColor = themeColor.primaryDark,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = MediaKeyboardTab.GIF
                                searchQuery = ""
                            }
                        )
                        MediaTabPill(
                            title = "Stickers",
                            icon = Icons.Outlined.Style,
                            selected = selectedTab == MediaKeyboardTab.STICKER,
                            accentColor = themeColor.primary,
                            accentDarkColor = themeColor.primaryDark,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = MediaKeyboardTab.STICKER
                                searchQuery = ""
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HeaderCircleAction(
                        icon = Icons.Default.Search,
                        contentDescription = if (isSearchVisible) "Hide search" else "Search",
                        elevatedSurface = if (isSearchVisible) themeColor.primary.copy(alpha = 0.16f) else elevatedSurface,
                        tint = if (isSearchVisible) themeColor.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isSearchVisible) {
                                isSearchVisible = false
                                searchQuery = ""
                            } else {
                                isSearchVisible = true
                            }
                        }
                    )

                    if (selectedTab == MediaKeyboardTab.EMOJI) {
                        HeaderCircleAction(
                            icon = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Backspace",
                            elevatedSurface = elevatedSurface,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBackspace()
                            }
                        )
                    }

                    HeaderCircleAction(
                        icon = if (isExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                        contentDescription = if (isExpanded) "Collapse picker" else "Expand picker",
                        elevatedSurface = elevatedSurface,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isExpanded = !isExpanded
                        }
                    )

                    HeaderCircleAction(
                        icon = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Close picker",
                        elevatedSurface = elevatedSurface,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconSize = 19,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClose()
                        }
                    )
                }
            }

            // ── Collapsible Animated Search Input (keeps picker airy by default) ──
            AnimatedVisibility(
                visible = isSearchVisible,
                enter = expandVertically(animationSpec = tween(160)) + fadeIn(tween(150)),
                exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(tween(100))
            ) {
                val searchHint = when (selectedTab) {
                    MediaKeyboardTab.EMOJI -> "Search emojis (love, fire, bro, party…)"
                    MediaKeyboardTab.GIF -> "Search animated GIFs…"
                    MediaKeyboardTab.STICKER -> "Search 3D & Clay stickers (bro, love, desi…)"
                }
                PickerSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = searchHint,
                    elevatedSurface = elevatedSurface,
                    accentColor = themeColor.primary
                )
            }

            // ── Smooth Animated Tab Content ──
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(160)) + scaleIn(
                        initialScale = 0.985f,
                        animationSpec = tween(160)
                    )) togetherWith fadeOut(animationSpec = tween(90))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "mediaTabTransition"
            ) { tab ->
                when (tab) {
                    MediaKeyboardTab.EMOJI -> {
                        EmojiPickerContent(
                            searchQuery = searchQuery,
                            elevatedSurface = elevatedSurface,
                            accentColor = themeColor.primary,
                            onEmojiSelected = onEmojiSelected
                        )
                    }
                    MediaKeyboardTab.GIF -> {
                        GifPickerContent(
                            searchQuery = searchQuery,
                            elevatedSurface = elevatedSurface,
                            accentColor = themeColor.primary,
                            onGifSelected = onGifSelected
                        )
                    }
                    MediaKeyboardTab.STICKER -> {
                        StickerPickerContent(
                            searchQuery = searchQuery,
                            elevatedSurface = elevatedSurface,
                            accentColor = themeColor.primary,
                            onStickerSelected = onStickerSelected
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCircleAction(
    icon: ImageVector,
    contentDescription: String,
    elevatedSurface: Color,
    tint: Color,
    iconSize: Int = 15,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = elevatedSurface,
        modifier = Modifier.size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize.dp)
            )
        }
    }
}

@Composable
private fun MediaTabPill(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    accentColor: Color,
    accentDarkColor: Color,
    onClick: () -> Unit
) {
    val bgModifier = if (selected) {
        Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.horizontalGradient(listOf(accentColor, accentDarkColor)))
    } else {
        Modifier.clip(RoundedCornerShape(17.dp))
    }

    Box(
        modifier = Modifier
            .height(30.dp)
            .then(bgModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun PickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    elevatedSurface: Color,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = elevatedSurface,
        border = BorderStroke(
            0.7.dp,
            if (query.isNotEmpty()) accentColor.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(34.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = if (query.isNotEmpty()) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    cursorBrush = SolidColor(accentColor),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 1. Airy Searchable Emoji Picker with Persisted Recents & Spring Pop
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun EmojiPickerContent(
    searchQuery: String,
    elevatedSurface: Color,
    accentColor: Color,
    onEmojiSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var recentEmojis by remember { mutableStateOf(MediaPickerPreferences.getRecentEmojis(context)) }
    val gridState = rememberLazyGridState()

    val categories = remember { EmojiCatalog.categories }
    val totalCategoryTabs = categories.size + 1

    val activeTitle = remember(selectedCategoryIndex, searchQuery) {
        when {
            searchQuery.isNotBlank() -> "SEARCH RESULTS"
            selectedCategoryIndex == 0 -> "RECENTLY USED"
            else -> categories[selectedCategoryIndex - 1].title.uppercase()
        }
    }

    val displayedEmojis = remember(selectedCategoryIndex, searchQuery, recentEmojis) {
        if (searchQuery.isNotBlank()) {
            EmojiCatalog.search(searchQuery)
        } else if (selectedCategoryIndex == 0) {
            recentEmojis
        } else {
            categories[selectedCategoryIndex - 1].items.map { it.emoji }
        }
    }

    LaunchedEffect(selectedCategoryIndex, searchQuery) {
        gridState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (searchQuery.isBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (index in 0 until totalCategoryTabs) {
                    val isSelected = index == selectedCategoryIndex
                    val icon = if (index == 0) "🕒" else categories[index - 1].icon
                    val label = if (index == 0) "Recent" else categories[index - 1].title.substringBefore(" ")

                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedCategoryIndex = index
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) accentColor.copy(alpha = 0.14f) else elevatedSurface.copy(alpha = 0.7f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(text = icon, fontSize = 15.sp)
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = activeTitle,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
        )

        if (displayedEmojis.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No emojis found for \"$searchQuery\"",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                itemsIndexed(
                    items = displayedEmojis,
                    key = { index, emoji -> "${selectedCategoryIndex}_${index}_$emoji" },
                    contentType = { _, _ -> "emoji_cell" }
                ) { _, emoji ->
                    EmojiBounceCell(
                        emoji = emoji,
                        highlightColor = accentColor,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            recentEmojis = MediaPickerPreferences.recordRecentEmoji(context, emoji)
                            onEmojiSelected(emoji)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiBounceCell(
    emoji: String,
    highlightColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.3f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "emojiScale"
    )

    Box(
        modifier = Modifier
            .height(44.dp)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isPressed) highlightColor.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = highlightColor),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 25.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 2. GIF Picker (Preserved Fast Cache + Matching Theme Colors)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun GifPickerContent(
    searchQuery: String,
    elevatedSurface: Color,
    accentColor: Color,
    onGifSelected: (gifUrl: String, gifTitle: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedCategory by remember { mutableStateOf("Trending") }
    val allGifs = remember { GifCatalog.gifs }

    val filteredGifs = remember(searchQuery, selectedCategory) {
        allGifs.filter { gif ->
            val matchCategory = selectedCategory == "Trending" || gif.category.equals(selectedCategory, ignoreCase = true)
            val matchSearch = searchQuery.isBlank() ||
                gif.title.contains(searchQuery, ignoreCase = true) ||
                gif.category.contains(searchQuery, ignoreCase = true)
            matchCategory && matchSearch
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val gifCategories = remember {
                listOf("Trending", "Laugh", "Love", "Dance", "Shock", "Thumbs Up", "Party", "Cry", "Popcorn")
            }
            gifCategories.forEach { cat ->
                val isSelected = selectedCategory == cat
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedCategory = cat
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) accentColor else elevatedSurface,
                    modifier = Modifier.height(28.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = cat,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (filteredGifs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No GIFs found for \"$searchQuery\"",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredGifs,
                    key = { it.id },
                    contentType = { "gif_card" }
                ) { gif ->
                    val imageRequest = remember(gif.id, gif.previewUrl) {
                        ImageRequest.Builder(context)
                            .data(gif.previewUrl)
                            .memoryCacheKey(gif.id)
                            .diskCacheKey(gif.id)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .crossfade(120)
                            .build()
                    }
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(112.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onGifSelected(gif.url, gif.title)
                            },
                        colors = CardDefaults.cardColors(containerColor = elevatedSurface)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = gif.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.56f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = gif.title,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "GIF",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = GreenCall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 3. Clean Flagship 3D & Clay Sticker Studio (No Boxy Borders, No Cluttered Captions)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun StickerPickerContent(
    searchQuery: String,
    elevatedSurface: Color,
    accentColor: Color,
    onStickerSelected: (stickerCode: String, stickerName: String, stickerUrl: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var recentIds by remember { mutableStateOf(MediaPickerPreferences.getRecentStickerIds(context)) }

    val allPacks = remember(recentIds) {
        val recentItems = recentIds.mapNotNull { id ->
            StickerCatalog.allStickers.firstOrNull { it.id == id }
        }
        if (recentItems.isNotEmpty()) {
            listOf(
                StickerPack(
                    id = "recent_stickers",
                    packName = "Recent",
                    packIcon = "🕒",
                    badge = "",
                    stickers = recentItems
                )
            ) + StickerCatalog.packs
        } else {
            StickerCatalog.packs
        }
    }

    var selectedPackIndex by remember { mutableIntStateOf(if (recentIds.isNotEmpty()) 1 else 0) }
    val safePackIndex = selectedPackIndex.coerceIn(0, (allPacks.size - 1).coerceAtLeast(0))
    val currentPack = allPacks[safePackIndex]

    val displayedStickers = remember(searchQuery, currentPack) {
        if (searchQuery.isNotBlank()) StickerCatalog.searchStickers(searchQuery)
        else currentPack.stickers
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(safePackIndex, searchQuery) {
        gridState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Clean Pack Strip (No redundant micro-badge boxes)
        if (searchQuery.isBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                allPacks.forEachIndexed { index, pack ->
                    val isSelected = index == safePackIndex
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedPackIndex = index
                        },
                        shape = RoundedCornerShape(15.dp),
                        color = if (isSelected) accentColor.copy(alpha = 0.14f) else elevatedSurface.copy(alpha = 0.72f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = pack.packIcon, fontSize = 15.sp)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = pack.packName,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (displayedStickers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No stickers matching \"$searchQuery\"",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = displayedStickers,
                    key = { "${safePackIndex}_${it.id}" },
                    contentType = { "sticker_card" }
                ) { sticker ->
                    StickerStudioTile(
                        sticker = sticker,
                        accentColor = accentColor,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            recentIds = MediaPickerPreferences.recordRecentSticker(context, sticker.id)
                            val code = if (sticker.imageUrl.isBlank()) sticker.id else sticker.previewEmoji
                            onStickerSelected(code, sticker.name, sticker.imageUrl)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StickerStudioTile(
    sticker: StickerItem,
    accentColor: Color,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "stickerScale"
    )

    // Soft ambient tint matching each sticker's own clay palette instead of cold bordered boxes
    val softPodBg = if (isDark) {
        Color(sticker.clayTopColorHex).copy(alpha = 0.13f)
    } else {
        Color(sticker.clayTopColorHex).copy(alpha = 0.22f)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .then(
                if (sticker.imageUrl.startsWith("http")) {
                    Modifier.background(softPodBg)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = accentColor),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (sticker.imageUrl.startsWith("http")) {
            val req = remember(sticker.id, sticker.imageUrl) {
                ImageRequest.Builder(context)
                    .data(sticker.imageUrl)
                    .memoryCacheKey("stk_${sticker.id}")
                    .diskCacheKey("stk_${sticker.id}")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(120)
                    .build()
            }
            SubcomposeAsyncImage(
                model = req,
                contentDescription = sticker.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                contentScale = ContentScale.Fit,
                loading = {
                    Clay3DStickerVisual(
                        sticker = sticker,
                        modifier = Modifier.fillMaxSize(),
                        isLoading = true
                    )
                },
                error = {
                    Clay3DStickerVisual(
                        sticker = sticker,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        } else {
            Clay3DStickerVisual(
                sticker = sticker,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
