package com.example.ui.screens.call

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VideoFilterConfig
import com.example.data.model.VideoFilterPreset
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class VideoFilterManager private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("video_filter_prefs", Context.MODE_PRIVATE)

    private val _filterConfig = MutableStateFlow(loadSavedConfig())
    val filterConfig: StateFlow<VideoFilterConfig> = _filterConfig.asStateFlow()

    private fun loadSavedConfig(): VideoFilterConfig {
        val raw = prefs.getString("saved_filter_config", null)
        return VideoFilterConfig.fromCompactString(raw)
    }

    fun updateConfig(newConfig: VideoFilterConfig) {
        _filterConfig.value = newConfig
        prefs.edit().putString("saved_filter_config", newConfig.toCompactString()).apply()
    }

    fun selectPreset(preset: VideoFilterPreset) {
        val current = _filterConfig.value
        val updated = when (preset) {
            VideoFilterPreset.NONE -> current.copy(
                preset = preset,
                brightness = 0f,
                contrast = 1f,
                warmth = 0f,
                vignette = 0.12f
            )
            VideoFilterPreset.BEAUTY_GLOW -> current.copy(
                preset = preset,
                brightness = 0.10f,
                contrast = 0.94f,
                warmth = 0.14f,
                vignette = 0.22f
            )
            VideoFilterPreset.WARM_CINEMA -> current.copy(
                preset = preset,
                brightness = 0.04f,
                contrast = 1.14f,
                warmth = 0.28f,
                vignette = 0.38f
            )
            VideoFilterPreset.CYBER_NEON -> current.copy(
                preset = preset,
                brightness = 0.06f,
                contrast = 1.22f,
                warmth = -0.18f,
                vignette = 0.34f
            )
            VideoFilterPreset.MONOCHROME -> current.copy(
                preset = preset,
                brightness = 0.02f,
                contrast = 1.28f,
                warmth = 0f,
                vignette = 0.42f
            )
            VideoFilterPreset.COOL_ARCTIC -> current.copy(
                preset = preset,
                brightness = 0.08f,
                contrast = 1.08f,
                warmth = -0.26f,
                vignette = 0.20f
            )
            VideoFilterPreset.PORTRAIT_FOCUS -> current.copy(
                preset = preset,
                brightness = 0.05f,
                contrast = 1.06f,
                warmth = 0.06f,
                vignette = 0.62f
            )
        }
        updateConfig(updated)
    }

    fun resetToDefault() {
        updateConfig(VideoFilterConfig())
    }

    companion object {
        @Volatile
        private var INSTANCE: VideoFilterManager? = null

        fun getInstance(context: Context): VideoFilterManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VideoFilterManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

/**
 * Wraps a WebRTC video surface with real-time optical filter grading, adjustable
 * brightness/contrast/warmth/vignette, Studio Rim Lighting, and Glass HUD overlays.
 */
@Composable
fun FilteredVideoViewport(
    videoTrack: org.webrtc.VideoTrack,
    eglBaseContext: org.webrtc.EglBase.Context,
    filterConfig: VideoFilterConfig,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    isOverlay: Boolean = false,
    cornerRadius: Dp = 0.dp,
    participantLabel: String? = null,
    onFirstFrameRendered: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "studio_rim_pulse")
    val rimPulse by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rim_pulse"
    )

    Box(
        modifier = if (cornerRadius > 0.dp) {
            modifier.clip(RoundedCornerShape(cornerRadius))
        } else {
            modifier
        }
    ) {
        // 1. Hardware-accelerated WebRTC surface
        WebRtcVideoRenderer(
            videoTrack = videoTrack,
            eglBaseContext = eglBaseContext,
            modifier = Modifier.fillMaxSize(),
            mirror = mirror,
            isOverlay = isOverlay,
            onFirstFrameRendered = onFirstFrameRendered
        )

        // 2. Optical Color Grading & Custom Filter Layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val maxDim = maxOf(w, h).coerceAtLeast(1f)

            // Preset-specific optical tone wash
            when (filterConfig.preset) {
                VideoFilterPreset.NONE -> Unit
                VideoFilterPreset.BEAUTY_GLOW -> {
                    // Soft rose-gold skin diffusion + luminous center bloom
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFFF1F2).copy(alpha = 0.16f),
                                Color(0xFFFDA4AF).copy(alpha = 0.10f),
                                Color(0xFFF43F5E).copy(alpha = 0.05f)
                            ),
                            center = Offset(w * 0.5f, h * 0.42f),
                            radius = maxDim * 0.65f
                        ),
                        blendMode = BlendMode.Screen
                    )
                }
                VideoFilterPreset.WARM_CINEMA -> {
                    // Golden hour amber grade + subtle teal shadow contrast
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF59E0B).copy(alpha = 0.18f),
                                Color(0xFFD97706).copy(alpha = 0.10f),
                                Color(0xFF0F172A).copy(alpha = 0.22f)
                            )
                        )
                    )
                }
                VideoFilterPreset.CYBER_NEON -> {
                    // Dual-tone cyan & magenta diagonal neon refraction
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF00F5D4).copy(alpha = 0.20f),
                                Color.Transparent,
                                Color(0xFFD946EF).copy(alpha = 0.22f)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(w, h)
                        )
                    )
                }
                VideoFilterPreset.MONOCHROME -> {
                    // Silver-halide studio noir desaturation + charcoal contrast veil
                    drawRect(
                        color = Color(0xFF0F172A).copy(alpha = 0.36f),
                        blendMode = BlendMode.Saturation
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFE2E8F0).copy(alpha = 0.08f),
                                Color(0xFF020617).copy(alpha = 0.34f)
                            ),
                            center = Offset(w * 0.5f, h * 0.5f),
                            radius = maxDim * 0.68f
                        )
                    )
                }
                VideoFilterPreset.COOL_ARCTIC -> {
                    // Crisp sapphire daylight tone
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF38BDF8).copy(alpha = 0.16f),
                                Color(0xFF60A5FA).copy(alpha = 0.08f),
                                Color(0xFF1E3A8A).copy(alpha = 0.16f)
                            )
                        )
                    )
                }
                VideoFilterPreset.PORTRAIT_FOCUS -> {
                    // Deep radial edge focus vignette simulating f/1.4 portrait lens
                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.48f to Color.Transparent,
                                0.78f to Color(0xFF090D16).copy(alpha = 0.42f),
                                1.0f to Color(0xFF020617).copy(alpha = 0.72f)
                            ),
                            center = Offset(w * 0.5f, h * 0.45f),
                            radius = maxDim * 0.62f
                        )
                    )
                }
            }

            // Live Warmth Slider Adjustment (-0.40 .. +0.40)
            if (abs(filterConfig.warmth) > 0.02f) {
                val warmthAlpha = (abs(filterConfig.warmth) * 0.42f).coerceIn(0f, 0.25f)
                val warmthColor = if (filterConfig.warmth > 0f) {
                    Color(0xFFF59E0B).copy(alpha = warmthAlpha)
                } else {
                    Color(0xFF38BDF8).copy(alpha = warmthAlpha)
                }
                drawRect(color = warmthColor)
            }

            // Live Brightness Slider Adjustment (-0.35 .. +0.35)
            if (abs(filterConfig.brightness) > 0.02f) {
                val brightAlpha = (abs(filterConfig.brightness) * 0.65f).coerceIn(0f, 0.32f)
                if (filterConfig.brightness > 0f) {
                    drawRect(
                        color = Color.White.copy(alpha = brightAlpha),
                        blendMode = BlendMode.Screen
                    )
                } else {
                    drawRect(
                        color = Color.Black.copy(alpha = brightAlpha)
                    )
                }
            }

            // Live Contrast Slider Adjustment (0.70 .. 1.45)
            val contrastDelta = filterConfig.contrast - 1f
            if (abs(contrastDelta) > 0.03f) {
                if (contrastDelta > 0f) {
                    val cAlpha = (contrastDelta * 0.38f).coerceIn(0f, 0.22f)
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = cAlpha * 0.4f),
                                Color(0xFF020617).copy(alpha = cAlpha)
                            ),
                            center = Offset(w * 0.5f, h * 0.5f),
                            radius = maxDim * 0.65f
                        )
                    )
                } else {
                    val softAlpha = (abs(contrastDelta) * 0.40f).coerceIn(0f, 0.18f)
                    drawRect(color = Color(0xFFCBD5E1).copy(alpha = softAlpha))
                }
            }

            // Live Vignette Slider Adjustment (0.00 .. 0.85)
            if (filterConfig.vignette > 0.05f) {
                val vigAlpha = (filterConfig.vignette * 0.68f).coerceIn(0f, 0.65f)
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1.0f to Color(0xFF020617).copy(alpha = vigAlpha)
                        ),
                        center = Offset(w * 0.5f, h * 0.5f),
                        radius = maxDim * 0.66f
                    )
                )
            }

            // 3. Studio Rim Light Effect (Illumination Ring for Low-Light & Studio Glow)
            if (filterConfig.studioRimLight) {
                val rimStroke = 10.dp.toPx()
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.78f * rimPulse),
                            Color(0xFF7DD3FC).copy(alpha = 0.65f * rimPulse),
                            Color(0xFFFDE68A).copy(alpha = 0.72f * rimPulse),
                            Color.White.copy(alpha = 0.78f * rimPulse)
                        )
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = rimStroke)
                )
            }

            // 4. Glass HUD Optical Reticle Corners
            if (filterConfig.hudOverlayEnabled) {
                val bracketLen = 22.dp.toPx()
                val inset = 12.dp.toPx()
                val strokeW = 2.dp.toPx()
                val hudColor = CyberMint.copy(alpha = 0.78f)

                // Top-Left
                drawLine(hudColor, Offset(inset, inset), Offset(inset + bracketLen, inset), strokeW, StrokeCap.Round)
                drawLine(hudColor, Offset(inset, inset), Offset(inset, inset + bracketLen), strokeW, StrokeCap.Round)
                // Top-Right
                drawLine(hudColor, Offset(w - inset, inset), Offset(w - inset - bracketLen, inset), strokeW, StrokeCap.Round)
                drawLine(hudColor, Offset(w - inset, inset), Offset(w - inset, inset + bracketLen), strokeW, StrokeCap.Round)
                // Bottom-Left
                drawLine(hudColor, Offset(inset, h - inset), Offset(inset + bracketLen, h - inset), strokeW, StrokeCap.Round)
                drawLine(hudColor, Offset(inset, h - inset), Offset(inset, h - inset - bracketLen), strokeW, StrokeCap.Round)
                // Bottom-Right
                drawLine(hudColor, Offset(w - inset, h - inset), Offset(w - inset - bracketLen, h - inset), strokeW, StrokeCap.Round)
                drawLine(hudColor, Offset(w - inset, h - inset), Offset(w - inset, h - inset - bracketLen), strokeW, StrokeCap.Round)
            }
        }

        // Optional HUD Filter Badge at Top-Left when HUD Overlay or non-default filter is active
        if (filterConfig.hudOverlayEnabled || filterConfig.preset != VideoFilterPreset.NONE) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.42f),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.30f)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(filterConfig.preset.accentHex),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (!participantLabel.isNullOrBlank()) {
                            "$participantLabel • ${filterConfig.preset.title}"
                        } else {
                            filterConfig.preset.title
                        },
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Interactive iPhone Liquid Glass Video Filter & Studio Lighting Bottom Drawer
 * usable in both 1-to-1 Video Calls and Multi-Party Group Video Calls.
 */
@Composable
fun VideoFilterStudioSheet(
    filterConfig: VideoFilterConfig,
    onSelectPreset: (VideoFilterPreset) -> Unit,
    onUpdateConfig: (VideoFilterConfig) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = Color(0xFF0B1528).copy(alpha = 0.94f),
        border = BorderStroke(
            1.4.dp,
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.65f),
                    Color(0xFF38BDF8).copy(alpha = 0.45f),
                    CyberMint.copy(alpha = 0.40f)
                )
            )
        ),
        shadowElevation = 24.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Grab Handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.35f))
            )

            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = CyberMint.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.5f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = CyberMint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Video Filters & Studio Lighting",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Real-time optical grading synced live in 1-to-1 & group calls",
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onReset) {
                        Text(
                            text = "Reset",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = SignalAmber
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close Filters",
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // 1. Preset Filter Carousel
            Text(
                text = "OPTICAL COLOR PRESETS",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = CyberMint
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VideoFilterPreset.entries.forEach { preset ->
                    val isSelected = filterConfig.preset == preset
                    val accent = Color(preset.accentHex)
                    val borderColor by animateColorAsState(
                        if (isSelected) accent else Color.White.copy(alpha = 0.20f),
                        label = "preset_border"
                    )

                    Surface(
                        onClick = { onSelectPreset(preset) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
                        modifier = Modifier.width(118.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    accent,
                                                    Color.White.copy(alpha = 0.8f)
                                                )
                                            )
                                        )
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Active",
                                        tint = accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = preset.title,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = preset.subtitle,
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 2. Fine-Tuning Sliders (Brightness, Contrast, Warmth, Vignette)
            Text(
                text = "LIVE STUDIO ADJUSTMENTS",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = CyberMint
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.07f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterSliderRow(
                        label = "Brightness",
                        valueText = "${(filterConfig.brightness * 100).toInt()}%",
                        value = filterConfig.brightness,
                        valueRange = -0.35f..0.35f,
                        onValueChange = { onUpdateConfig(filterConfig.copy(brightness = it)) }
                    )
                    FilterSliderRow(
                        label = "Contrast",
                        valueText = "${(filterConfig.contrast * 100).toInt()}%",
                        value = filterConfig.contrast,
                        valueRange = 0.70f..1.45f,
                        onValueChange = { onUpdateConfig(filterConfig.copy(contrast = it)) }
                    )
                    FilterSliderRow(
                        label = "Warmth",
                        valueText = "${(filterConfig.warmth * 100).toInt()}",
                        value = filterConfig.warmth,
                        valueRange = -0.40f..0.40f,
                        onValueChange = { onUpdateConfig(filterConfig.copy(warmth = it)) }
                    )
                    FilterSliderRow(
                        label = "Portrait Vignette",
                        valueText = "${(filterConfig.vignette * 100).toInt()}%",
                        value = filterConfig.vignette,
                        valueRange = 0.0f..0.85f,
                        onValueChange = { onUpdateConfig(filterConfig.copy(vignette = it)) }
                    )
                }
            }

            // 3. Studio Rim Light & Glass HUD Switches
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = {
                        onUpdateConfig(filterConfig.copy(studioRimLight = !filterConfig.studioRimLight))
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = if (filterConfig.studioRimLight) SignalAmber.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(
                        1.dp,
                        if (filterConfig.studioRimLight) SignalAmber else Color.White.copy(alpha = 0.20f)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LightMode,
                            contentDescription = null,
                            tint = if (filterConfig.studioRimLight) SignalAmber else Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Studio Rim Light",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (filterConfig.studioRimLight) "Active Glow" else "Off",
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Surface(
                    onClick = {
                        onUpdateConfig(filterConfig.copy(hudOverlayEnabled = !filterConfig.hudOverlayEnabled))
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = if (filterConfig.hudOverlayEnabled) CyberMint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(
                        1.dp,
                        if (filterConfig.hudOverlayEnabled) CyberMint else Color.White.copy(alpha = 0.20f)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CenterFocusStrong,
                            contentDescription = null,
                            tint = if (filterConfig.hudOverlayEnabled) CyberMint else Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Glass HUD Frame",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (filterConfig.hudOverlayEnabled) "Reticle On" else "Off",
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.90f)
            )
            Text(
                text = valueText,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = CyberMint
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = CyberMint,
                activeTrackColor = CyberMint,
                inactiveTrackColor = Color.White.copy(alpha = 0.20f)
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}
