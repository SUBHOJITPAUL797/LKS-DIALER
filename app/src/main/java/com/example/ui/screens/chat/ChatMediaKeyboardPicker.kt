package com.example.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.ui.theme.GreenCall
import com.example.ui.theme.TealPrimary

enum class MediaKeyboardTab {
    EMOJI,
    GIF,
    STICKER
}

data class StickerItem(
    val id: String,
    val name: String,
    val packName: String,
    val imageUrl: String,
    val previewEmoji: String
)

data class GifItem(
    val id: String,
    val title: String,
    val category: String,
    val url: String,
    val previewUrl: String
)

@Composable
fun ChatMediaKeyboardPicker(
    modifier: Modifier = Modifier,
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    onStickerSelected: (stickerCode: String, stickerName: String, stickerUrl: String) -> Unit,
    onGifSelected: (gifUrl: String, gifTitle: String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(MediaKeyboardTab.EMOJI) }
    var isExpanded by remember { mutableStateOf(false) }

    val animatedHeight by animateDpAsState(
        targetValue = if (isExpanded) 460.dp else 320.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "mediaPickerHeight"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Drag / resize handle pill at top (clickable to toggle expanded view)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(top = 6.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .width(38.dp)
                        .height(4.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
                ) {}
            }

            // ── Top Media Tabs Header (WhatsApp style pill switcher) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TabPillButton(
                            title = "Emoji",
                            icon = "😊",
                            selected = selectedTab == MediaKeyboardTab.EMOJI,
                            onClick = { selectedTab = MediaKeyboardTab.EMOJI }
                        )
                        TabPillButton(
                            title = "GIF",
                            icon = "🎬",
                            selected = selectedTab == MediaKeyboardTab.GIF,
                            onClick = { selectedTab = MediaKeyboardTab.GIF }
                        )
                        TabPillButton(
                            title = "Stickers",
                            icon = "🦄",
                            selected = selectedTab == MediaKeyboardTab.STICKER,
                            onClick = { selectedTab = MediaKeyboardTab.STICKER }
                        )
                    }
                }

                // Right controls: Expand/Collapse toggle button + Backspace
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = if (isExpanded) "Collapse picker" else "Expand picker",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (selectedTab == MediaKeyboardTab.EMOJI) {
                        IconButton(
                            onClick = onBackspace,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Backspace",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.6.dp
            )

            // ── Tab Content with ultra-fast Crossfade (no heavy layout jump) ──
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(durationMillis = 90),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "tabCrossfade"
            ) { tab ->
                when (tab) {
                    MediaKeyboardTab.EMOJI -> {
                        EmojiPickerContent(
                            onEmojiSelected = onEmojiSelected
                        )
                    }
                    MediaKeyboardTab.GIF -> {
                        GifPickerContent(
                            onGifSelected = onGifSelected
                        )
                    }
                    MediaKeyboardTab.STICKER -> {
                        StickerPickerContent(
                            onStickerSelected = onStickerSelected
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPillButton(
    title: String,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) TealPrimary else Color.Transparent,
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = icon, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 1. High-Performance 120fps Emoji Picker (Fixed 8-column grid + Recent/Popular)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun EmojiPickerContent(
    onEmojiSelected: (String) -> Unit
) {
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    val categories = remember { EmojiCatalog.categories }
    val currentCategory = remember(selectedCategoryIndex) { categories[selectedCategoryIndex] }
    val currentEmojis = currentCategory.emojis
    val gridState = rememberLazyGridState()

    LaunchedEffect(selectedCategoryIndex) {
        gridState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Category Icons Horizontal Row (WhatsApp top category strip)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEachIndexed { index, cat ->
                val isSelected = index == selectedCategoryIndex
                Surface(
                    onClick = { selectedCategoryIndex = index },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) TealPrimary.copy(alpha = 0.16f) else Color.Transparent
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = cat.icon,
                            fontSize = 17.sp
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(2.dp)
                                    .clip(CircleShape)
                                    .background(TealPrimary)
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
            thickness = 0.5.dp
        )

        // Category title header
        Text(
            text = currentCategory.title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )

        // Fixed 8-column grid avoids adaptive measurement overhead for 120fps scrolling
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            state = gridState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(
                items = currentEmojis,
                key = { index, emoji -> "${selectedCategoryIndex}_${index}_$emoji" },
                contentType = { _, _ -> "emoji_cell" }
            ) { _, emoji ->
                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onEmojiSelected(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emoji,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 2. High-Performance GIF Picker (Memory/Disk Cached + Search + Categories)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun GifPickerContent(
    onGifSelected: (gifUrl: String, gifTitle: String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
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
        // WhatsApp-style compact GIF Search Bar + Category Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search GIFs",
                        tint = TealPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search Tenor / Giphy GIFs…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        // Categories row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val gifCategories = remember {
                listOf("Trending", "Laugh", "Love", "Dance", "Shock", "Thumbs Up", "Party", "Cry", "Popcorn")
            }
            gifCategories.forEach { cat ->
                val isSelected = selectedCategory == cat
                Surface(
                    onClick = { selectedCategory = cat },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) TealPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.height(26.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 11.dp)
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

        // GIFs 2-column Grid with pre-built cached ImageRequests
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
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            .crossfade(100)
                            .build()
                    }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(108.dp)
                            .clickable { onGifSelected(gif.url, gif.title) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = gif.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // GIF badge + title overlay
                            Surface(
                                color = Color.Black.copy(alpha = 0.52f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
// 3. Sticker Picker
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun StickerPickerContent(
    onStickerSelected: (stickerCode: String, stickerName: String, stickerUrl: String) -> Unit
) {
    val packs = remember { StickerCatalog.packs }
    var selectedPackIndex by remember { mutableIntStateOf(0) }
    val currentPack = remember(selectedPackIndex) { packs[selectedPackIndex] }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pack selector tab row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            packs.forEachIndexed { index, pack ->
                val isSelected = index == selectedPackIndex
                Surface(
                    onClick = { selectedPackIndex = index },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) TealPrimary.copy(alpha = 0.15f) else Color.Transparent,
                    border = if (isSelected) BorderStroke(1.2.dp, TealPrimary) else null,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = pack.packIcon, fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = pack.packName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) TealPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            thickness = 0.5.dp
        )

        // Stickers Grid (4 columns)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = currentPack.stickers,
                key = { it.id },
                contentType = { "sticker_cell" }
            ) { sticker ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val code = sticker.previewEmoji.ifBlank { sticker.name }
                            onStickerSelected(code, sticker.name, sticker.imageUrl)
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (sticker.imageUrl.startsWith("http")) {
                        AsyncImage(
                            model = sticker.imageUrl,
                            contentDescription = sticker.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = sticker.previewEmoji,
                            fontSize = 42.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Catalog Data: Emojis, Stickers, GIFs
// ──────────────────────────────────────────────────────────────────────────────
data class EmojiCategory(
    val title: String,
    val icon: String,
    val emojis: List<String>
)

data class StickerPack(
    val packName: String,
    val packIcon: String,
    val stickers: List<StickerItem>
)

object EmojiCatalog {
    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            title = "Frequently Used",
            icon = "🕒",
            emojis = listOf(
                "😂", "❤️", "🤣", "👍", "😭", "🙏", "😘", "🥰", "😍", "😊", "🎉", "🔥", "😁", "💕", "🥺", "😅",
                "☺️", "🤦", "♥️", "🤷", "🙄", "😆", "🤗", "😉", "🎂", "🤔", "👏", "🙂", "😳", "🥳", "😎", "👌",
                "💜", "😔", "💪", "✨", "💖", "👀", "😋", "😏", "😢", "👉", "💗", "😩", "💯", "🌹", "💞", "🎈"
            )
        ),
        EmojiCategory(
            title = "Smileys & Emotion",
            icon = "😀",
            emojis = listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
                "😘", "😗", "😚", "😙", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨",
                "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕",
                "🤢", "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "🥸", "😎", "🤓", "🧐", "😕", "😟",
                "🙁", "☹️", "😮", "😯", "😲", "😳", "🥺", "😦", "😧", "😨", "😰", "😥", "😢", "😭", "😱", "😖",
                "😣", "😞", "😓", "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀", "☠️", "💩", "🤡",
                "👹", "👺", "👻", "👽", "👾", "🤖"
            )
        ),
        EmojiCategory(
            title = "Gestures & People",
            icon = "👋",
            emojis = listOf(
                "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆",
                "🖕", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✍️",
                "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀",
                "👁️", "👅", "👄"
            )
        ),
        EmojiCategory(
            title = "Hearts & Party",
            icon = "❤️",
            emojis = listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "❣️", "💕", "💞", "💓",
                "💗", "💖", "💘", "💝", "💟", "🎉", "🎊", "🎈", "🎁", "🎂", "🥂", "🍻", "🔥", "✨", "🌟", "💫",
                "💯", "⚡", "💥", "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️"
            )
        ),
        EmojiCategory(
            title = "Animals & Nature",
            icon = "🐶",
            emojis = listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐻‍❄️", "🐨", "🐯", "🦁", "🐮", "🐷", "🐽", "🐸",
                "🐵", "🙈", "🙉", "🙊", "🐒", "🐔", "🐧", "🐦", "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺",
                "🐗", "🐴", "🦄", "🐝", "🪱", "🐛", "🦋", "🐌", "🐞", "🐜", "🪰", "🪲", "🪳", "🦟", "🦗", "🕷️",
                "🕸️", "🦂", "🐢", "🐍", "🦎", "🦖", "🦕", "🐙", "🦑", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬",
                "🐳", "🐋", "🦈", "🦭", "🐊", "🐅", "🐆", "🦓", "🦍", "🦧", "🦣", "🐘", "🦛", "🦏", "🐪", "🐫"
            )
        ),
        EmojiCategory(
            title = "Food & Drink",
            icon = "🍔",
            emojis = listOf(
                "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥",
                "🥝", "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️", "🫑", "🌽", "🥕", "🫒", "🧄", "🧅", "🥔", "🍠",
                "🥐", "🥯", "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓", "🥩", "🍗", "🍖", "🦴",
                "🌭", "🍔", "🍟", "🍕", "🫓", "🥪", "🥙", "🧆", "🌮", "🌯", "🫔", "🥗", "🥘", "🫕", "🍲",
                "🍜", "🍝", "🍢", "🍣", "🍤", "🍥", "🥮", "🍡", "🥟", "🥠", "🥡", "🍦", "🍧", "🍨", "🍩",
                "🍪", "🎂", "🍰", "🧁", "🥧", "🍫", "🍬", "🍭", "🍮", "🍯", "🍼", "🥛", "☕", "🫖", "🍵", "🧃",
                "🥤", "🧋", "🍺", "🍻", "🥂", "🍷", "🥃", "🍸", "🍹", "🍾"
            )
        ),
        EmojiCategory(
            title = "Activities & Travel",
            icon = "✈️",
            emojis = listOf(
                "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🥍",
                "🏏", "🪃", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🥌",
                "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤼", "🤸", "🤺", "⛹️", "🤾", "🧗", "🚗", "🚕", "🚙", "🚌", "🚎",
                "🏎️", "🚓", "🚑", "🚒", "🚐", "🛻", "🚚", "🚛", "🚜", "🛵", "🏍️", "🛺", "🚲", "🛴", "✈️", "🚀"
            )
        )
    ).map { cat -> cat.copy(emojis = cat.emojis.distinct()) }
}

object StickerCatalog {
    val packs: List<StickerPack> = listOf(
        StickerPack(
            packName = "3D Expressive",
            packIcon = "🔥",
            stickers = listOf(
                StickerItem("s_fire", "Fire 100", "3D Expressive", "", "🔥"),
                StickerItem("s_love", "Heart Eyes", "3D Expressive", "", "😍"),
                StickerItem("s_laugh", "LOL Tears", "3D Expressive", "", "🤣"),
                StickerItem("s_party", "Party Popper", "3D Expressive", "", "🎉"),
                StickerItem("s_cool", "Cool Shades", "3D Expressive", "", "😎"),
                StickerItem("s_mindblown", "Mind Blown", "3D Expressive", "", "🤯"),
                StickerItem("s_thumbsup", "Thumbs Up", "3D Expressive", "", "👍"),
                StickerItem("s_100", "Top Score 100", "3D Expressive", "", "💯"),
                StickerItem("s_rocket", "To The Moon", "3D Expressive", "", "🚀"),
                StickerItem("s_clap", "Applause", "3D Expressive", "", "👏"),
                StickerItem("s_pray", "Respect / Thanks", "3D Expressive", "", "🙏"),
                StickerItem("s_sparkles", "Magic Vibe", "3D Expressive", "", "✨")
            )
        ),
        StickerPack(
            packName = "Cute Animals",
            packIcon = "🐱",
            stickers = listOf(
                StickerItem("s_cat_peek", "Peeking Cat", "Cute Animals", "", "🐱"),
                StickerItem("s_dog_wag", "Happy Doge", "Cute Animals", "", "🐶"),
                StickerItem("s_panda_hug", "Panda Hug", "Cute Animals", "", "🐼"),
                StickerItem("s_fox_smirk", "Sly Fox", "Cute Animals", "", "🦊"),
                StickerItem("s_bunny_love", "Sweet Bunny", "Cute Animals", "", "🐰"),
                StickerItem("s_penguin_dance", "Happy Penguin", "Cute Animals", "", "🐧"),
                StickerItem("s_hamster_eat", "Munch Hamster", "Cute Animals", "", "🐹"),
                StickerItem("s_koala_chill", "Chill Koala", "Cute Animals", "", "🐨"),
                StickerItem("s_lion_king", "Brave Lion", "Cute Animals", "", "🦁"),
                StickerItem("s_frog_vibes", "Frog Vibe", "Cute Animals", "", "🐸"),
                StickerItem("s_unicorn_magic", "Sparkle Unicorn", "Cute Animals", "", "🦄"),
                StickerItem("s_sloth_slow", "Sleepy Sloth", "Cute Animals", "", "🦥")
            )
        ),
        StickerPack(
            packName = "Chat Vibes",
            packIcon = "🤙",
            stickers = listOf(
                StickerItem("s_call_me", "Call Me Now", "Chat Vibes", "", "🤙"),
                StickerItem("s_coffee_first", "Coffee First", "Chat Vibes", "", "☕"),
                StickerItem("s_coding_grind", "Hustle Mode", "Chat Vibes", "", "💻"),
                StickerItem("s_shipped", "Shipped It!", "Chat Vibes", "", "📦"),
                StickerItem("s_nailed_it", "Nailed It", "Chat Vibes", "", "🎯"),
                StickerItem("s_popcorn", "Popcorn Time", "Chat Vibes", "", "🍿"),
                StickerItem("s_peace_out", "Peace Out", "Chat Vibes", "", "✌️"),
                StickerItem("s_facepalm", "Facepalm", "Chat Vibes", "", "🤦"),
                StickerItem("s_big_brain", "Galaxy Brain", "Chat Vibes", "", "🧠"),
                StickerItem("s_gg_bro", "GG Bro", "Chat Vibes", "", "🤝"),
                StickerItem("s_loading", "Brain Loading", "Chat Vibes", "", "⏳"),
                StickerItem("s_cheers", "Cheers Drink", "Chat Vibes", "", "🍻")
            )
        )
    )

    fun resolveEmoji(codeOrName: String): String {
        if (codeOrName.isBlank()) return "✨"
        if (codeOrName.length <= 4 && !codeOrName.contains(" ")) return codeOrName
        for (pack in packs) {
            for (item in pack.stickers) {
                if (item.name.equals(codeOrName, ignoreCase = true) ||
                    item.id.equals(codeOrName, ignoreCase = true) ||
                    item.previewEmoji.equals(codeOrName, ignoreCase = true)) {
                    return item.previewEmoji
                }
            }
        }
        return codeOrName
    }
}

object GifCatalog {
    // Curated high quality GIF library with fast CDN links
    val gifs: List<GifItem> = listOf(
        GifItem(
            id = "g_laugh_cat",
            title = "Laughing Tears",
            category = "Laugh",
            url = "https://media.giphy.com/media/ICOgUNjpvO0PC/giphy.gif",
            previewUrl = "https://media.giphy.com/media/ICOgUNjpvO0PC/200w.gif"
        ),
        GifItem(
            id = "g_laugh_minion",
            title = "Minion LOL",
            category = "Laugh",
            url = "https://media.giphy.com/media/10JhviFuU2gWD6/giphy.gif",
            previewUrl = "https://media.giphy.com/media/10JhviFuU2gWD6/200w.gif"
        ),
        GifItem(
            id = "g_thumbs_up",
            title = "Thumbs Up Cool",
            category = "Thumbs Up",
            url = "https://media.giphy.com/media/111ebonMs90YLu/giphy.gif",
            previewUrl = "https://media.giphy.com/media/111ebonMs90YLu/200w.gif"
        ),
        GifItem(
            id = "g_love_cat",
            title = "Heart Love",
            category = "Love",
            url = "https://media.giphy.com/media/MDJ9IbxxvDUQM/giphy.gif",
            previewUrl = "https://media.giphy.com/media/MDJ9IbxxvDUQM/200w.gif"
        ),
        GifItem(
            id = "g_love_hug",
            title = "Warm Hug",
            category = "Love",
            url = "https://media.giphy.com/media/3oEdv4hwWTzBhWvaU0/giphy.gif",
            previewUrl = "https://media.giphy.com/media/3oEdv4hwWTzBhWvaU0/200w.gif"
        ),
        GifItem(
            id = "g_shock_cat",
            title = "OMG Shock",
            category = "Shock",
            url = "https://media.giphy.com/media/3o72F8t9TDi2xVnxOE/giphy.gif",
            previewUrl = "https://media.giphy.com/media/3o72F8t9TDi2xVnxOE/200w.gif"
        ),
        GifItem(
            id = "g_shock_doge",
            title = "Mind Blown",
            category = "Shock",
            url = "https://media.giphy.com/media/26ufdipQqU2lhNA4g/giphy.gif",
            previewUrl = "https://media.giphy.com/media/26ufdipQqU2lhNA4g/200w.gif"
        ),
        GifItem(
            id = "g_party_confetti",
            title = "Celebration Party",
            category = "Party",
            url = "https://media.giphy.com/media/artj92V8o75VPL7AeQ/giphy.gif",
            previewUrl = "https://media.giphy.com/media/artj92V8o75VPL7AeQ/200w.gif"
        ),
        GifItem(
            id = "g_dance_happy",
            title = "Happy Vibe",
            category = "Dance",
            url = "https://media.giphy.com/media/blSTtZehjAZ8I/giphy.gif",
            previewUrl = "https://media.giphy.com/media/blSTtZehjAZ8I/200w.gif"
        ),
        GifItem(
            id = "g_dance_snoopy",
            title = "Snoopy Vibe",
            category = "Dance",
            url = "https://media.giphy.com/media/o75ajIFH0QnQC3nCeD/giphy.gif",
            previewUrl = "https://media.giphy.com/media/o75ajIFH0QnQC3nCeD/200w.gif"
        ),
        GifItem(
            id = "g_popcorn",
            title = "Eating Popcorn",
            category = "Popcorn",
            url = "https://media.giphy.com/media/gl0mkIZOW6Nwc/giphy.gif",
            previewUrl = "https://media.giphy.com/media/gl0mkIZOW6Nwc/200w.gif"
        ),
        GifItem(
            id = "g_cry_cat",
            title = "Sad Tears",
            category = "Cry",
            url = "https://media.giphy.com/media/OPU6wzx8JrHna/giphy.gif",
            previewUrl = "https://media.giphy.com/media/OPU6wzx8JrHna/200w.gif"
        )
    )
}
