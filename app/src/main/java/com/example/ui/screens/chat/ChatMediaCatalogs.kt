package com.example.ui.screens.chat

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class MediaKeyboardTab {
    EMOJI,
    GIF,
    STICKER
}

enum class StickerVisualStyle {
    FLUENT_3D,
    ANIMATED_3D,
    CLAY_POP,
    CLAY_DESI,
    CLAY_LOVE,
    CLAY_PET
}

data class StickerItem(
    val id: String,
    val name: String,
    val packName: String,
    val imageUrl: String,
    val previewEmoji: String,
    val style: StickerVisualStyle = StickerVisualStyle.FLUENT_3D,
    val clayTopColorHex: Long = 0xFFFF9A8B,
    val clayBottomColorHex: Long = 0xFFFF6A88,
    val clayBadgeText: String = ""
)

data class GifItem(
    val id: String,
    val title: String,
    val category: String,
    val url: String,
    val previewUrl: String
)

data class EmojiEntry(
    val emoji: String,
    val keywords: String
)

data class EmojiCategory(
    val id: String,
    val title: String,
    val icon: String,
    val items: List<EmojiEntry>
)

data class StickerPack(
    val id: String,
    val packName: String,
    val packIcon: String,
    val badge: String = "",
    val stickers: List<StickerItem>
)

// ──────────────────────────────────────────────────────────────────────────────
// Persistent Recent Emojis & Recent Stickers Preferences
// ──────────────────────────────────────────────────────────────────────────────
object MediaPickerPreferences {
    private const val PREFS_NAME = "dialer_prefs"
    private const val KEY_RECENT_EMOJIS = "chat_recent_emojis_v2"
    private const val KEY_RECENT_STICKERS = "chat_recent_sticker_ids_v2"
    private const val MAX_RECENT_EMOJIS = 32
    private const val MAX_RECENT_STICKERS = 18

    private val defaultRecentEmojis = listOf(
        "😂", "❤️", "🤣", "👍", "😭", "🙏", "😘", "🥰",
        "😍", "😊", "🔥", "🎉", "🫶", "🥹", "😎", "💯",
        "✨", "🥳", "🤗", "😅", "👀", "🤝", "🤙", "💕"
    )

    fun getRecentEmojis(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_EMOJIS, null)
        if (raw.isNullOrBlank()) return defaultRecentEmojis
        val parsed = raw.split("|").filter { it.isNotBlank() }
        return if (parsed.isEmpty()) defaultRecentEmojis else parsed
    }

    fun recordRecentEmoji(context: Context, emoji: String): List<String> {
        val current = getRecentEmojis(context).toMutableList()
        current.remove(emoji)
        current.add(0, emoji)
        val trimmed = current.take(MAX_RECENT_EMOJIS)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_EMOJIS, trimmed.joinToString("|"))
            .apply()
        return trimmed
    }

    fun getRecentStickerIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_STICKERS, null) ?: return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    fun recordRecentSticker(context: Context, stickerId: String): List<String> {
        if (stickerId.isBlank()) return getRecentStickerIds(context)
        val current = getRecentStickerIds(context).toMutableList()
        current.remove(stickerId)
        current.add(0, stickerId)
        val trimmed = current.take(MAX_RECENT_STICKERS)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_STICKERS, trimmed.joinToString("|"))
            .apply()
        return trimmed
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Native 3D Claymorphic Sticker Renderer (Instant, Soft-Lit, Glossy & Offline-Ready)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun Clay3DStickerVisual(
    sticker: StickerItem,
    modifier: Modifier = Modifier,
    isBubbleSize: Boolean = false,
    isLoading: Boolean = false
) {
    val topColor = Color(sticker.clayTopColorHex)
    val bottomColor = Color(sticker.clayBottomColorHex)
    val cornerRadius = if (isBubbleSize) 34.dp else 24.dp
    val shadowElev = if (isBubbleSize) 14.dp else 7.dp
    val emojiSize = if (isBubbleSize) {
        if (sticker.clayBadgeText.isNotBlank()) 56.sp else 72.sp
    } else {
        if (sticker.clayBadgeText.isNotBlank()) 36.sp else 46.sp
    }

    Surface(
        modifier = modifier.shadow(
            elevation = shadowElev,
            shape = RoundedCornerShape(cornerRadius),
            ambientColor = bottomColor.copy(alpha = 0.45f),
            spotColor = bottomColor.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isBubbleSize) 1.5.dp else 1.1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.58f),
                    Color.White.copy(alpha = 0.08f),
                    Color.Black.copy(alpha = 0.14f)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(topColor, bottomColor),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // 3D Clay specular rim lighting & soft volumetric glow canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Soft warm core ambient light
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.48f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.25f, size.height * 0.2f),
                        radius = size.minDimension * 0.62f
                    ),
                    center = Offset(size.width * 0.25f, size.height * 0.2f),
                    radius = size.minDimension * 0.62f
                )

                // Glossy top-left clay reflection pill
                drawOval(
                    color = Color.White.copy(alpha = 0.32f),
                    topLeft = Offset(size.width * 0.14f, size.height * 0.08f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.42f, size.height * 0.14f)
                )

                // Bottom-right soft clay occlusion bounce
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.82f, size.height * 0.88f),
                        radius = size.minDimension * 0.55f
                    ),
                    center = Offset(size.width * 0.82f, size.height * 0.88f),
                    radius = size.minDimension * 0.55f
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isBubbleSize) 10.dp else 6.dp, vertical = if (isBubbleSize) 10.dp else 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = sticker.previewEmoji,
                    fontSize = emojiSize,
                    textAlign = TextAlign.Center
                )

                if (sticker.clayBadgeText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(if (isBubbleSize) 6.dp else 4.dp))
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.94f),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = sticker.clayBadgeText,
                            color = Color(0xFF0F172A),
                            fontSize = if (isBubbleSize) 13.sp else 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.4.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                horizontal = if (isBubbleSize) 12.dp else 8.dp,
                                vertical = if (isBubbleSize) 3.dp else 2.dp
                            )
                        )
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .size(12.dp),
                    strokeWidth = 1.6.dp,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Searchable Emoji Catalog
// ──────────────────────────────────────────────────────────────────────────────
object EmojiCatalog {
    private fun e(glyph: String, kw: String) = EmojiEntry(glyph, kw)

    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            id = "smileys",
            title = "Smileys & Emotion",
            icon = "😀",
            items = listOf(
                e("😀", "grin happy smile"), e("😃", "happy smile joy"), e("😄", "laugh smile happy"),
                e("😁", "grin teeth happy"), e("😆", "laugh haha xd"), e("😅", "sweat laugh nervous"),
                e("🤣", "rofl lol lmao laugh"), e("😂", "joy laugh tears lol"), e("🥹", "holding back tears cute emotional"),
                e("🙂", "slight smile ok"), e("🙃", "upside down silly"), e("😉", "wink flirt"),
                e("😊", "blush happy shy"), e("😇", "angel halo innocent"), e("🥰", "love hearts adore"),
                e("😍", "heart eyes love crush"), e("🤩", "star struck wow excited"), e("😘", "kiss blow heart"),
                e("😗", "kiss"), e("😚", "kiss blush"), e("😋", "yummy delicious tongue"),
                e("😛", "tongue silly"), e("😜", "wink tongue crazy"), e("🤪", "zany crazy silly goofy"),
                e("😝", "tongue closed eyes"), e("🤑", "money rich dollar"), e("🤗", "hug warm"),
                e("🤭", "giggle oops hand over mouth"), e("🫢", "gasp shock"), e("🫣", "peeking shy"),
                e("🤫", "shh secret quiet"), e("🤔", "think hmm wonder"), e("🫡", "salute yes sir bro"),
                e("🤐", "zipper mouth secret"), e("🤨", "skeptic brow sus"), e("😐", "neutral meh poker"),
                e("😑", "expressionless done"), e("😶", "speechless silent"), e("🫥", "dotted disappear"),
                e("😏", "smirk sly"), e("😒", "unamused annoyed"), e("🙄", "eyeroll whatever"),
                e("😬", "grimace awkward cringe"), e("😮‍💨", "exhale sigh relief"), e("🤥", "liar pinocchio"),
                e("😌", "relieved calm peace"), e("😔", "pensive sad down"), e("😪", "sleepy"),
                e("🤤", "drool hungry"), e("😴", "sleep zzz tired"), e("😷", "mask sick"),
                e("🤒", "fever sick"), e("🤕", "hurt bandage"), e("🤢", "nausea sick"),
                e("🤮", "vomit gross"), e("🤧", "sneeze"), e("🥵", "hot heat spicy"),
                e("🥶", "cold freeze chill"), e("🥴", "woozy dizzy"), e("😵", "dizzy dead"),
                e("🤯", "mind blown boom shock"), e("🤠", "cowboy yeehaw"), e("🥳", "party birthday celebrate"),
                e("🥸", "disguise glasses"), e("😎", "cool sunglasses swag bro"), e("🤓", "nerd geek smart"),
                e("🧐", "monocle inspect"), e("😕", "confused"), e("🫤", "diagonal meh"),
                e("😟", "worried"), e("🙁", "frown sad"), e("☹️", "sad"),
                e("😮", "wow surprised"), e("😯", "hushed wow"), e("😲", "astonished omg"),
                e("😳", "flushed shocked embarrassed"), e("🥺", "pleading puppy eyes cute"), e("😦", "frown open"),
                e("😨", "fear scared"), e("😰", "anxious sweat"), e("😥", "sad relief"),
                e("😢", "cry sad tear"), e("😭", "sob cry loud"), e("😱", "scream omg horror"),
                e("😖", "confounded"), e("😣", "persevere"), e("😞", "disappointed sad"),
                e("😓", "sweat"), e("😩", "weary tired"), e("😫", "tired"),
                e("🥱", "yawn bored sleep"), e("😤", "triumph mad huff"), e("😡", "angry rage mad"),
                e("😠", "angry mad"), e("🤬", "swear curse angry"), e("😈", "devil mischievous"),
                e("👿", "angry devil"), e("💀", "skull dead lmao"), e("☠️", "skull danger"),
                e("💩", "poop funny"), e("🤡", "clown joke"), e("👻", "ghost boo halloween"),
                e("👽", "alien ufo"), e("👾", "game alien pixel"), e("🤖", "robot bot ai"),
                e("🫠", "melting hot embarrassed")
            )
        ),
        EmojiCategory(
            id = "gestures",
            title = "Gestures & People",
            icon = "👋",
            items = listOf(
                e("👋", "wave hi hello bye"), e("🤚", "raised back hand"), e("🖐️", "hand five"),
                e("✋", "stop high five"), e("🖖", "vulcan spock"), e("🫱", "rightwards hand"),
                e("🫲", "leftwards hand"), e("👌", "ok okay nice perfect"), e("🤌", "pinched italian chef"),
                e("🤏", "pinch tiny little"), e("✌️", "peace victory two"), e("🤞", "fingers crossed luck"),
                e("🫰", "finger heart snap love money"), e("🤟", "love you rock"), e("🤘", "rock metal"),
                e("🤙", "call me shaka bro chill"), e("👈", "left point"), e("👉", "right point"),
                e("👆", "up point"), e("👇", "down point"), e("☝️", "one point"),
                e("🫵", "point you"), e("👍", "thumbs up yes good like bro"), e("👎", "thumbs down no dislike"),
                e("✊", "fist power"), e("👊", "punch fist bump bro"), e("🤛", "left fist bump"),
                e("🤜", "right fist bump"), e("👏", "clap applause bravo"), e("🙌", "hooray praise hands up"),
                e("🫶", "heart hands love"), e("👐", "open hands"), e("🤲", "palms prayer"),
                e("🤝", "handshake deal gg bro"), e("🙏", "pray please thanks namaste"), e("✍️", "write note"),
                e("💅", "nails slay"), e("🤳", "selfie phone"), e("💪", "muscle strong flex gym"),
                e("🫂", "hug embrace comfort"), e("🤦", "facepalm doh"), e("🤷", "shrug idk"),
                e("💃", "dance girl party"), e("🕺", "dance boy disco"), e("👀", "eyes look watch")
            )
        ),
        EmojiCategory(
            id = "hearts",
            title = "Love & Hearts",
            icon = "❤️",
            items = listOf(
                e("❤️", "red heart love"), e("🩷", "pink heart love"), e("🧡", "orange heart"),
                e("💛", "yellow heart"), e("💚", "green heart"), e("🩵", "light blue heart"),
                e("💙", "blue heart"), e("💜", "purple heart"), e("🤎", "brown heart"),
                e("🖤", "black heart"), e("🤍", "white heart"), e("💔", "broken heart sad"),
                e("❤️‍🔥", "heart on fire passion"), e("❤️‍🩹", "healing heart"), e("❣️", "heart exclamation"),
                e("💕", "two hearts love"), e("💞", "revolving hearts"), e("💓", "beating heart"),
                e("💗", "growing heart"), e("💖", "sparkling heart"), e("💘", "cupid arrow love"),
                e("💝", "gift heart ribbon"), e("💟", "heart decoration"), e("💌", "love letter message"),
                e("💋", "kiss lips"), e("💍", "ring wedding love"), e("💎", "diamond gem luxury"),
                e("🌹", "rose flower love"), e("💐", "bouquet flowers"), e("🌺", "hibiscus flower"),
                e("🌸", "cherry blossom"), e("🦋", "butterfly pretty"), e("🧸", "teddy bear cute")
            )
        ),
        EmojiCategory(
            id = "party",
            title = "Party, Cool & Vibes",
            icon = "🎉",
            items = listOf(
                e("🔥", "fire lit hot flame"), e("✨", "sparkles magic shine"), e("💯", "100 percent real"),
                e("🎉", "party confetti tada celebrate"), e("🎊", "confetti celebration"), e("🎈", "balloon birthday"),
                e("🎁", "gift present surprise"), e("🎂", "cake birthday"), e("🪩", "disco ball party"),
                e("🥳", "party hat celebrate"), e("🥂", "cheers champagne toast"), e("🍻", "beer cheers"),
                e("🏆", "trophy winner champion"), e("🥇", "gold medal first"), e("👑", "crown king queen royal"),
                e("⚡", "lightning zap fast power"), e("💥", "boom explosion"), e("🌟", "glowing star"),
                e("💫", "dizzy star"), e("🌈", "rainbow happy"), e("🚀", "rocket launch moon fast"),
                e("🎯", "bullseye target nailed it"), e("🎮", "gaming controller play"), e("🎧", "music headphones"),
                e("🎵", "music note song"), e("🎶", "music notes"), e("🎤", "mic karaoke voice")
            )
        ),
        EmojiCategory(
            id = "animals",
            title = "Animals & Nature",
            icon = "🐶",
            items = listOf(
                e("🐶", "dog puppy pet"), e("🐱", "cat kitty pet"), e("🐭", "mouse"),
                e("🐹", "hamster cute"), e("🐰", "rabbit bunny"), e("🦊", "fox clever"),
                e("🐻", "bear teddy"), e("🐼", "panda cute"), e("🐻‍❄️", "polar bear"),
                e("🐨", "koala chill"), e("🐯", "tiger"), e("🦁", "lion king"),
                e("🐮", "cow"), e("🐷", "pig"), e("🐸", "frog meme"),
                e("🐵", "monkey cheeky"), e("🙈", "see no evil monkey"), e("🙉", "hear no evil"),
                e("🙊", "speak no evil"), e("🐔", "chicken"), e("🐧", "penguin"),
                e("🐦", "bird"), e("🐣", "chick cute"), e("🦆", "duck"),
                e("🦅", "eagle"), e("🦉", "owl wise"), e("🐺", "wolf"),
                e("🦄", "unicorn magic"), e("🐝", "bee honey"), e("🦋", "butterfly"),
                e("🐢", "turtle slow chill"), e("🐍", "snake"), e("🦖", "dino"),
                e("🐙", "octopus"), e("🐬", "dolphin"), e("🐳", "whale"),
                e("🦈", "shark"), e("🐘", "elephant"), e("🦥", "sloth lazy")
            )
        ),
        EmojiCategory(
            id = "food",
            title = "Food & Drinks",
            icon = "🍔",
            items = listOf(
                e("☕", "coffee chai tea morning"), e("🫖", "tea pot chai"), e("🍵", "green tea"),
                e("🧋", "boba bubble tea"), e("🥤", "soda drink"), e("🧃", "juice box"),
                e("🍺", "beer"), e("🍻", "cheers"), e("🥂", "champagne"),
                e("🍕", "pizza party food"), e("🍔", "burger"), e("🍟", "fries"),
                e("🌭", "hotdog"), e("🌮", "taco"), e("🌯", "burrito wrap"),
                e("🍜", "ramen noodles maggi"), e("🍝", "pasta"), e("🍛", "curry rice"),
                e("🍱", "bento lunch"), e("🍣", "sushi"), e("🥟", "dumpling momo"),
                e("🍿", "popcorn movie"), e("🍩", "donut"), e("🍪", "cookie"),
                e("🎂", "birthday cake"), e("🧁", "cupcake"), e("🍰", "cake slice"),
                e("🍫", "chocolate sweet"), e("🍬", "candy"), e("🍦", "ice cream"),
                e("🥭", "mango fruit"), e("🍓", "strawberry"), e("🍉", "watermelon"),
                e("🍎", "apple"), e("🍌", "banana"), e("🥑", "avocado")
            )
        ),
        EmojiCategory(
            id = "travel",
            title = "Travel & Tech",
            icon = "🚀",
            items = listOf(
                e("🚗", "car drive omw"), e("🚕", "taxi cab"), e("🚌", "bus"),
                e("🏎️", "race car fast"), e("🏍️", "bike motorcycle"), e("🛵", "scooter"),
                e("🚲", "bicycle"), e("✈️", "airplane flight travel"), e("🚀", "rocket space"),
                e("🚆", "train"), e("📍", "location pin"), e("🏠", "home house"),
                e("🏢", "office work"), e("📱", "phone mobile call"), e("💻", "laptop work coding"),
                e("⌚", "watch time"), e("📷", "camera photo"), e("🔋", "battery full"),
                e("💡", "idea bulb bright"), e("🔒", "lock secure privacy"), e("🔑", "key"),
                e("📦", "box package delivered"), e("⏳", "hourglass wait loading"), e("⏰", "alarm clock")
            )
        )
    )

    val allEntries: List<EmojiEntry> by lazy {
        categories.flatMap { it.items }.distinctBy { it.emoji }
    }

    fun search(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return allEntries.filter { entry ->
            entry.keywords.contains(q) || entry.emoji == q
        }.map { it.emoji }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Rich Multi-Pack 3D & Clay Sticker Catalog (Animated 3D, Fluent 3D & Native Clay Studio)
// ──────────────────────────────────────────────────────────────────────────────
object StickerCatalog {
    private const val FLUENT_CDN = "https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@latest/assets"
    private const val NOTO_ANIM_CDN = "https://fonts.gstatic.com/s/e/notoemoji/latest"

    val packs: List<StickerPack> = listOf(
        // Pack 1: Offline-ready, sculpted 3D Claymorphism Studio Pack
        StickerPack(
            id = "clay_studio",
            packName = "Clay 3D",
            packIcon = "🎨",
            badge = "CLAY",
            stickers = listOf(
                StickerItem("clay_bro", "Bro Mode", "Clay 3D", "", "😎", StickerVisualStyle.CLAY_POP, 0xFF60A5FA, 0xFF2563EB, "BRO!"),
                StickerItem("clay_fire", "100% Fire", "Clay 3D", "", "🔥", StickerVisualStyle.CLAY_POP, 0xFFFF9A62, 0xFFFF4D00, "LIT 🔥"),
                StickerItem("clay_love", "Love You", "Clay 3D", "", "🥰", StickerVisualStyle.CLAY_POP, 0xFFFF85B3, 0xFFE11D48, "LOVE U"),
                StickerItem("clay_lmao", "LMAO Tears", "Clay 3D", "", "🤣", StickerVisualStyle.CLAY_POP, 0xFFFDE047, 0xFFF59E0B, "LMAO!"),
                StickerItem("clay_omw", "On My Way", "Clay 3D", "", "🚀", StickerVisualStyle.CLAY_POP, 0xFF34D399, 0xFF059669, "OMW!"),
                StickerItem("clay_callme", "Call Me", "Clay 3D", "", "🤙", StickerVisualStyle.CLAY_POP, 0xFFA78BFA, 0xFF6D28D9, "CALL ME"),
                StickerItem("clay_nocap", "No Cap 100", "Clay 3D", "", "💯", StickerVisualStyle.CLAY_POP, 0xFFFB7185, 0xFFBE123C, "NO CAP"),
                StickerItem("clay_gg", "GG Deal", "Clay 3D", "", "🤝", StickerVisualStyle.CLAY_POP, 0xFF38BDF8, 0xFF0284C7, "GG BRO"),
                StickerItem("clay_letsgo", "Let's Go!", "Clay 3D", "", "🥳", StickerVisualStyle.CLAY_POP, 0xFFC084FC, 0xFF7E22CE, "LET'S GO"),
                StickerItem("clay_thanks", "Thanks!", "Clay 3D", "", "🙏", StickerVisualStyle.CLAY_POP, 0xFFFBBF24, 0xFFD97706, "THANKS"),
                StickerItem("clay_sorry", "Sorry Pls", "Clay 3D", "", "🥺", StickerVisualStyle.CLAY_POP, 0xFFF472B6, 0xFFDB2777, "SORRY!"),
                StickerItem("clay_chill", "Chill Vibe", "Clay 3D", "", "🧊", StickerVisualStyle.CLAY_POP, 0xFF67E8F9, 0xFF0891B2, "CHILL"),
                StickerItem("clay_coffee", "Coffee Break", "Clay 3D", "", "☕", StickerVisualStyle.CLAY_POP, 0xFFD97706, 0xFF92400E, "COFFEE?"),
                StickerItem("clay_busy", "Busy Hustle", "Clay 3D", "", "💻", StickerVisualStyle.CLAY_POP, 0xFF818CF8, 0xFF4338CA, "BUSY RN"),
                StickerItem("clay_wait", "Wait One Sec", "Clay 3D", "", "⏳", StickerVisualStyle.CLAY_POP, 0xFFFB923C, 0xFFEA580C, "WAIT..."),
                StickerItem("clay_okay", "Okay Done", "Clay 3D", "", "👌", StickerVisualStyle.CLAY_POP, 0xFF4ADE80, 0xFF16A34A, "OKAY!"),
                StickerItem("clay_shock", "Mind Blown", "Clay 3D", "", "🤯", StickerVisualStyle.CLAY_POP, 0xFFF87171, 0xFFDC2626, "WHAT?!"),
                StickerItem("clay_bye", "See Ya Bye", "Clay 3D", "", "👋", StickerVisualStyle.CLAY_POP, 0xFF2DD4BF, 0xFF0D9488, "BYE!")
            )
        ),

        // Pack 2: Live 3D Animated Noto Stickers
        StickerPack(
            id = "anim_3d",
            packName = "Live 3D",
            packIcon = "✨",
            badge = "LIVE",
            stickers = listOf(
                StickerItem("a_joy", "Tears of Joy", "Live 3D", "$NOTO_ANIM_CDN/1f602/512.gif", "😂", StickerVisualStyle.ANIMATED_3D, 0xFFFEF08A, 0xFFEAB308),
                StickerItem("a_rofl", "ROFL Laugh", "Live 3D", "$NOTO_ANIM_CDN/1f923/512.gif", "🤣", StickerVisualStyle.ANIMATED_3D, 0xFFFDE68A, 0xFFD97706),
                StickerItem("a_hearteyes", "Heart Eyes", "Live 3D", "$NOTO_ANIM_CDN/1f60d/512.gif", "😍", StickerVisualStyle.ANIMATED_3D, 0xFFFECDD3, 0xFFE11D48),
                StickerItem("a_party", "Party Vibes", "Live 3D", "$NOTO_ANIM_CDN/1f973/512.gif", "🥳", StickerVisualStyle.ANIMATED_3D, 0xFFE9D5FF, 0xFF9333EA),
                StickerItem("a_fire", "On Fire", "Live 3D", "$NOTO_ANIM_CDN/1f525/512.gif", "🔥", StickerVisualStyle.ANIMATED_3D, 0xFFFED7AA, 0xFFEA580C),
                StickerItem("a_mindblown", "Mind Blown", "Live 3D", "$NOTO_ANIM_CDN/1f92f/512.gif", "🤯", StickerVisualStyle.ANIMATED_3D, 0xFFFECACA, 0xFFDC2626),
                StickerItem("a_cool", "Cool Swag", "Live 3D", "$NOTO_ANIM_CDN/1f60e/512.gif", "😎", StickerVisualStyle.ANIMATED_3D, 0xFFBFDBFE, 0xFF2563EB),
                StickerItem("a_pleading", "Please Cute", "Live 3D", "$NOTO_ANIM_CDN/1f97a/512.gif", "🥺", StickerVisualStyle.ANIMATED_3D, 0xFFFBCFE8, 0xFFDB2777),
                StickerItem("a_cry", "Crying Loud", "Live 3D", "$NOTO_ANIM_CDN/1f62d/512.gif", "😭", StickerVisualStyle.ANIMATED_3D, 0xFFBAE6FD, 0xFF0284C7),
                StickerItem("a_rocket", "Rocket Launch", "Live 3D", "$NOTO_ANIM_CDN/1f680/512.gif", "🚀", StickerVisualStyle.ANIMATED_3D, 0xFFA7F3D0, 0xFF059669),
                StickerItem("a_100", "100 Score", "Live 3D", "$NOTO_ANIM_CDN/1f4af/512.gif", "💯", StickerVisualStyle.ANIMATED_3D, 0xFFFECACA, 0xFFEF4444),
                StickerItem("a_confetti", "Celebration", "Live 3D", "$NOTO_ANIM_CDN/1f389/512.gif", "🎉", StickerVisualStyle.ANIMATED_3D, 0xFFDDD6FE, 0xFF7C3AED),
                StickerItem("a_sparkles", "Magic Sparkles", "Live 3D", "$NOTO_ANIM_CDN/2728/512.gif", "✨", StickerVisualStyle.ANIMATED_3D, 0xFFFEF3C7, 0xFFF59E0B),
                StickerItem("a_starstruck", "Star Struck", "Live 3D", "$NOTO_ANIM_CDN/1f929/512.gif", "🤩", StickerVisualStyle.ANIMATED_3D, 0xFFFDE68A, 0xFFF59E0B),
                StickerItem("a_hearthands", "Heart Hands", "Live 3D", "$NOTO_ANIM_CDN/1faf6/512.gif", "🫶", StickerVisualStyle.ANIMATED_3D, 0xFFFBCFE8, 0xFFEC4899),
                StickerItem("a_thumbsup", "Thumbs Up", "Live 3D", "$NOTO_ANIM_CDN/1f44d/512.gif", "👍", StickerVisualStyle.ANIMATED_3D, 0xFFBBF7D0, 0xFF16A34A)
            )
        ),

        // Pack 3: Glossy Sculpted Microsoft Fluent 3D Stickers
        StickerPack(
            id = "fluent_3d",
            packName = "3D Glossy",
            packIcon = "🔥",
            badge = "3D",
            stickers = listOf(
                StickerItem("f_fire", "Fire Flame", "3D Glossy", "$FLUENT_CDN/Fire/3D/fire_3d.png", "🔥", StickerVisualStyle.FLUENT_3D, 0xFFFFEDD5, 0xFFEA580C),
                StickerItem("f_rocket", "To The Moon", "3D Glossy", "$FLUENT_CDN/Rocket/3D/rocket_3d.png", "🚀", StickerVisualStyle.FLUENT_3D, 0xFFD1FAE5, 0xFF059669),
                StickerItem("f_party", "Party Popper", "3D Glossy", "$FLUENT_CDN/Party%20popper/3D/party_popper_3d.png", "🎉", StickerVisualStyle.FLUENT_3D, 0xFFF3E8FF, 0xFF9333EA),
                StickerItem("f_crown", "Royal Crown", "3D Glossy", "$FLUENT_CDN/Crown/3D/crown_3d.png", "👑", StickerVisualStyle.FLUENT_3D, 0xFFFEF3C7, 0xFFD97706),
                StickerItem("f_unicorn", "Magic Unicorn", "3D Glossy", "$FLUENT_CDN/Unicorn/3D/unicorn_3d.png", "🦄", StickerVisualStyle.FLUENT_3D, 0xFFFCE7F3, 0xFFDB2777),
                StickerItem("f_ghost", "Boo Ghost", "3D Glossy", "$FLUENT_CDN/Ghost/3D/ghost_3d.png", "👻", StickerVisualStyle.FLUENT_3D, 0xFFE2E8F0, 0xFF64748B),
                StickerItem("f_100", "100 Points", "3D Glossy", "$FLUENT_CDN/Hundred%20points/3D/hundred_points_3d.png", "💯", StickerVisualStyle.FLUENT_3D, 0xFFFEE2E2, 0xFFDC2626),
                StickerItem("f_star", "Glowing Star", "3D Glossy", "$FLUENT_CDN/Glowing%20star/3D/glowing_star_3d.png", "🌟", StickerVisualStyle.FLUENT_3D, 0xFFFEF9C3, 0xFFCA8A04),
                StickerItem("f_sparkles", "Sparkles", "3D Glossy", "$FLUENT_CDN/Sparkles/3D/sparkles_3d.png", "✨", StickerVisualStyle.FLUENT_3D, 0xFFFEF3C7, 0xFFF59E0B),
                StickerItem("f_zap", "Lightning Zap", "3D Glossy", "$FLUENT_CDN/High%20voltage/3D/high_voltage_3d.png", "⚡", StickerVisualStyle.FLUENT_3D, 0xFFE0F2FE, 0xFF0284C7),
                StickerItem("f_sunglasses", "Cool Shades", "3D Glossy", "$FLUENT_CDN/Smiling%20face%20with%20sunglasses/3D/smiling_face_with_sunglasses_3d.png", "😎", StickerVisualStyle.FLUENT_3D, 0xFFDBEAFE, 0xFF1D4ED8),
                StickerItem("f_hearteyes", "In Love", "3D Glossy", "$FLUENT_CDN/Smiling%20face%20with%20heart-eyes/3D/smiling_face_with_heart-eyes_3d.png", "😍", StickerVisualStyle.FLUENT_3D, 0xFFFCE7F3, 0xFFBE185D),
                StickerItem("f_partying", "Party Face", "3D Glossy", "$FLUENT_CDN/Partying%20face/3D/partying_face_3d.png", "🥳", StickerVisualStyle.FLUENT_3D, 0xFFEDE9FE, 0xFF6D28D9),
                StickerItem("f_exploding", "Mind Blown", "3D Glossy", "$FLUENT_CDN/Exploding%20head/3D/exploding_head_3d.png", "🤯", StickerVisualStyle.FLUENT_3D, 0xFFFEE2E2, 0xFFB91C1C),
                StickerItem("f_pleading", "Cute Puppy Eyes", "3D Glossy", "$FLUENT_CDN/Pleading%20face/3D/pleading_face_3d.png", "🥺", StickerVisualStyle.FLUENT_3D, 0xFFFDF2F8, 0xFFDB2777),
                StickerItem("f_smilinghearts", "Warm Adore", "3D Glossy", "$FLUENT_CDN/Smiling%20face%20with%20hearts/3D/smiling_face_with_hearts_3d.png", "🥰", StickerVisualStyle.FLUENT_3D, 0xFFFFE4E6, 0xFFE11D48)
            )
        ),

        // Pack 4: Desi & Hinglish Clay 3D Expressions
        StickerPack(
            id = "clay_desi",
            packName = "Desi Vibes",
            packIcon = "🤙",
            badge = "CLAY",
            stickers = listOf(
                StickerItem("d_bhaiop", "Bhai OP", "Desi Vibes", "", "🔥", StickerVisualStyle.CLAY_DESI, 0xFFFF9F43, 0xFFEE5253, "BHAI OP!"),
                StickerItem("d_masthai", "Mast Hai", "Desi Vibes", "", "😎", StickerVisualStyle.CLAY_DESI, 0xFF48DBFB, 0xFF0ABDE3, "MAST HAI"),
                StickerItem("d_aaraha", "Aa Raha Hu", "Desi Vibes", "", "🛵", StickerVisualStyle.CLAY_DESI, 0xFF1DD1A1, 0xFF10AC84, "AA RAHA!"),
                StickerItem("d_kyascene", "Kya Scene", "Desi Vibes", "", "👀", StickerVisualStyle.CLAY_DESI, 0xFFA29BFE, 0xFF6C5CE7, "KYA SCENE?"),
                StickerItem("d_callkar", "Call Karo", "Desi Vibes", "", "📞", StickerVisualStyle.CLAY_DESI, 0xFF55E6C1, 0xFF1B9CFC, "CALL KAR"),
                StickerItem("d_chaitime", "Chai Time", "Desi Vibes", "", "☕", StickerVisualStyle.CLAY_DESI, 0xFFF8B500, 0xFFE67E22, "CHAI TIME"),
                StickerItem("d_donehai", "Done Hai", "Desi Vibes", "", "🤝", StickerVisualStyle.CLAY_DESI, 0xFF00D2D3, 0xFF01A3A4, "DONE HAI"),
                StickerItem("d_wahbhai", "Wah Bhai", "Desi Vibes", "", "👏", StickerVisualStyle.CLAY_DESI, 0xFFFF9FF3, 0xFFF368E0, "WAH!"),
                StickerItem("d_partykab", "Party Kab?", "Desi Vibes", "", "🥳", StickerVisualStyle.CLAY_DESI, 0xFFFECA57, 0xFFFF6B6B, "PARTY KAB"),
                StickerItem("d_soja", "So Ja Bro", "Desi Vibes", "", "😴", StickerVisualStyle.CLAY_DESI, 0xFF8395A7, 0xFF222F3E, "SO JA"),
                StickerItem("d_thikhai", "Thik Hai", "Desi Vibes", "", "👍", StickerVisualStyle.CLAY_DESI, 0xFF7BED9F, 0xFF2ED573, "THIK HAI"),
                StickerItem("d_shukriya", "Shukriya", "Desi Vibes", "", "🙏", StickerVisualStyle.CLAY_DESI, 0xFFFAB1A0, 0xFFE17055, "SHUKRIYA")
            )
        ),

        // Pack 5: Cute 3D Clay Pets
        StickerPack(
            id = "clay_pets",
            packName = "3D Pets",
            packIcon = "🐼",
            badge = "3D",
            stickers = listOf(
                StickerItem("p_panda", "Chubby Panda", "3D Pets", "$FLUENT_CDN/Panda/3D/panda_3d.png", "🐼", StickerVisualStyle.CLAY_PET, 0xFFE2E8F0, 0xFF94A3B8),
                StickerItem("p_cat", "Happy Kitty", "3D Pets", "$FLUENT_CDN/Cat%20face/3D/cat_face_3d.png", "🐱", StickerVisualStyle.CLAY_PET, 0xFFFEF3C7, 0xFFF59E0B),
                StickerItem("p_dog", "Good Puppy", "3D Pets", "$FLUENT_CDN/Dog%20face/3D/dog_face_3d.png", "🐶", StickerVisualStyle.CLAY_PET, 0xFFFED7AA, 0xFFD97706),
                StickerItem("p_fox", "Clever Fox", "3D Pets", "$FLUENT_CDN/Fox/3D/fox_3d.png", "🦊", StickerVisualStyle.CLAY_PET, 0xFFFFEDD5, 0xFFEA580C),
                StickerItem("p_bunny", "Cute Bunny", "3D Pets", "$FLUENT_CDN/Rabbit%20face/3D/rabbit_face_3d.png", "🐰", StickerVisualStyle.CLAY_PET, 0xFFFCE7F3, 0xFFF472B6),
                StickerItem("p_teddy", "Teddy Hug", "3D Pets", "$FLUENT_CDN/Teddy%20bear/3D/teddy_bear_3d.png", "🧸", StickerVisualStyle.CLAY_PET, 0xFFFDE68A, 0xFFB45309),
                StickerItem("p_koala", "Chill Koala", "3D Pets", "$FLUENT_CDN/Koala/3D/koala_3d.png", "🐨", StickerVisualStyle.CLAY_PET, 0xFFE0E7FF, 0xFF6366F1),
                StickerItem("p_lion", "King Lion", "3D Pets", "$FLUENT_CDN/Lion/3D/lion_3d.png", "🦁", StickerVisualStyle.CLAY_PET, 0xFFFEF08A, 0xFFCA8A04),
                StickerItem("p_frog", "Vibe Frog", "3D Pets", "$FLUENT_CDN/Frog/3D/frog_3d.png", "🐸", StickerVisualStyle.CLAY_PET, 0xFFDCFCE7, 0xFF16A34A),
                StickerItem("p_penguin", "Waddle Penguin", "3D Pets", "$FLUENT_CDN/Penguin/3D/penguin_3d.png", "🐧", StickerVisualStyle.CLAY_PET, 0xFFDBEAFE, 0xFF0284C7),
                StickerItem("p_butterfly", "Butterfly", "3D Pets", "$FLUENT_CDN/Butterfly/3D/butterfly_3d.png", "🦋", StickerVisualStyle.CLAY_PET, 0xFFCCFBF1, 0xFF0D9488),
                StickerItem("p_octopus", "Octo Hugs", "3D Pets", "$FLUENT_CDN/Octopus/3D/octopus_3d.png", "🐙", StickerVisualStyle.CLAY_PET, 0xFFFFE4E6, 0xFFE11D48)
            )
        ),

        // Pack 6: 3D Love & Hugs
        StickerPack(
            id = "love_3d",
            packName = "Love 3D",
            packIcon = "💖",
            badge = "3D",
            stickers = listOf(
                StickerItem("l_redheart", "Pure Love", "Love 3D", "$FLUENT_CDN/Red%20heart/3D/red_heart_3d.png", "❤️", StickerVisualStyle.CLAY_LOVE, 0xFFFFE4E6, 0xFFE11D48),
                StickerItem("l_sparkleheart", "Sparkle Love", "Love 3D", "$FLUENT_CDN/Sparkling%20heart/3D/sparkling_heart_3d.png", "💖", StickerVisualStyle.CLAY_LOVE, 0xFFFCE7F3, 0xFFDB2777),
                StickerItem("l_cupid", "Cupid Heart", "Love 3D", "$FLUENT_CDN/Heart%20with%20arrow/3D/heart_with_arrow_3d.png", "💘", StickerVisualStyle.CLAY_LOVE, 0xFFFBCFE8, 0xFFEC4899),
                StickerItem("l_rose", "Red Rose", "Love 3D", "$FLUENT_CDN/Rose/3D/rose_3d.png", "🌹", StickerVisualStyle.CLAY_LOVE, 0xFFFECDD3, 0xFFBE123C),
                StickerItem("l_bouquet", "Flower Bouquet", "Love 3D", "$FLUENT_CDN/Bouquet/3D/bouquet_3d.png", "💐", StickerVisualStyle.CLAY_LOVE, 0xFFF3E8FF, 0xFFA855F7),
                StickerItem("l_kiss", "Kiss Blow", "Love 3D", "$FLUENT_CDN/Face%20blowing%20a%20kiss/3D/face_blowing_a_kiss_3d.png", "😘", StickerVisualStyle.CLAY_LOVE, 0xFFFEF3C7, 0xFFF43F5E),
                StickerItem("l_hugu", "Big Hug U", "Love 3D", "", "🫂", StickerVisualStyle.CLAY_LOVE, 0xFFFF8A80, 0xFFE91E63, "HUG U"),
                StickerItem("l_missu", "Miss You", "Love 3D", "", "🥺", StickerVisualStyle.CLAY_LOVE, 0xFFF48FB1, 0xFFC2185B, "MISS U"),
                StickerItem("l_muah", "Muah Kiss", "Love 3D", "", "💋", StickerVisualStyle.CLAY_LOVE, 0xFFFF80AB, 0xFFD81B60, "MUAH!"),
                StickerItem("l_forever", "Forever", "Love 3D", "", "💍", StickerVisualStyle.CLAY_LOVE, 0xFFEA80FC, 0xFF8E24AA, "FOREVER"),
                StickerItem("l_cutie", "So Cutie", "Love 3D", "", "🥹", StickerVisualStyle.CLAY_LOVE, 0xFFFFB74D, 0xFFF50057, "CUTIE"),
                StickerItem("l_mine", "All Mine", "Love 3D", "", "🫶", StickerVisualStyle.CLAY_LOVE, 0xFFFF6E7F, 0xFFBFE9FF, "MINE ❤️")
            )
        )
    )

    val allStickers: List<StickerItem> by lazy {
        packs.flatMap { it.stickers }
    }

    fun findSticker(codeOrId: String?, name: String?): StickerItem? {
        val c = codeOrId?.trim().orEmpty()
        val n = name?.trim().orEmpty()
        if (c.isNotBlank()) {
            allStickers.firstOrNull { it.id.equals(c, ignoreCase = true) }?.let { return it }
        }
        if (n.isNotBlank()) {
            allStickers.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it }
            allStickers.firstOrNull { it.clayBadgeText.equals(n, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    fun resolveEmoji(codeOrName: String): String {
        if (codeOrName.isBlank()) return "✨"
        findSticker(codeOrName, codeOrName)?.let { return it.previewEmoji }
        if (codeOrName.length <= 4 && !codeOrName.contains(" ")) return codeOrName
        return codeOrName
    }

    fun searchStickers(query: String): List<StickerItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return allStickers.filter { s ->
            s.name.lowercase().contains(q) ||
                s.packName.lowercase().contains(q) ||
                s.clayBadgeText.lowercase().contains(q) ||
                s.previewEmoji.contains(q)
        }
    }
}

object GifCatalog {
    val gifs: List<GifItem> = listOf(
        GifItem("g_laugh_cat", "Laughing Tears", "Laugh", "https://media.giphy.com/media/ICOgUNjpvO0PC/giphy.gif", "https://media.giphy.com/media/ICOgUNjpvO0PC/200w.gif"),
        GifItem("g_laugh_minion", "Minion LOL", "Laugh", "https://media.giphy.com/media/10JhviFuU2gWD6/giphy.gif", "https://media.giphy.com/media/10JhviFuU2gWD6/200w.gif"),
        GifItem("g_thumbs_up", "Thumbs Up Cool", "Thumbs Up", "https://media.giphy.com/media/111ebonMs90YLu/giphy.gif", "https://media.giphy.com/media/111ebonMs90YLu/200w.gif"),
        GifItem("g_love_cat", "Heart Love", "Love", "https://media.giphy.com/media/MDJ9IbxxvDUQM/giphy.gif", "https://media.giphy.com/media/MDJ9IbxxvDUQM/200w.gif"),
        GifItem("g_love_hug", "Warm Hug", "Love", "https://media.giphy.com/media/3oEdv4hwWTzBhWvaU0/giphy.gif", "https://media.giphy.com/media/3oEdv4hwWTzBhWvaU0/200w.gif"),
        GifItem("g_shock_cat", "OMG Shock", "Shock", "https://media.giphy.com/media/3o72F8t9TDi2xVnxOE/giphy.gif", "https://media.giphy.com/media/3o72F8t9TDi2xVnxOE/200w.gif"),
        GifItem("g_shock_doge", "Mind Blown", "Shock", "https://media.giphy.com/media/26ufdipQqU2lhNA4g/giphy.gif", "https://media.giphy.com/media/26ufdipQqU2lhNA4g/200w.gif"),
        GifItem("g_party_confetti", "Celebration Party", "Party", "https://media.giphy.com/media/artj92V8o75VPL7AeQ/giphy.gif", "https://media.giphy.com/media/artj92V8o75VPL7AeQ/200w.gif"),
        GifItem("g_dance_happy", "Happy Vibe", "Dance", "https://media.giphy.com/media/blSTtZehjAZ8I/giphy.gif", "https://media.giphy.com/media/blSTtZehjAZ8I/200w.gif"),
        GifItem("g_dance_snoopy", "Snoopy Vibe", "Dance", "https://media.giphy.com/media/o75ajIFH0QnQC3nCeD/giphy.gif", "https://media.giphy.com/media/o75ajIFH0QnQC3nCeD/200w.gif"),
        GifItem("g_popcorn", "Eating Popcorn", "Popcorn", "https://media.giphy.com/media/gl0mkIZOW6Nwc/giphy.gif", "https://media.giphy.com/media/gl0mkIZOW6Nwc/200w.gif"),
        GifItem("g_cry_cat", "Sad Tears", "Cry", "https://media.giphy.com/media/OPU6wzx8JrHna/giphy.gif", "https://media.giphy.com/media/OPU6wzx8JrHna/200w.gif")
    )
}
