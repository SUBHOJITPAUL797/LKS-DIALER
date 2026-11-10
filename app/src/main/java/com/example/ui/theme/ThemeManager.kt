package com.example.ui.theme

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeColor(
    val title: String,
    val primary: Color,
    val primaryDark: Color,
    val accent: Color,
    val previewColor: Color
) {
    SWIGGY(
        title = "Signal Amber",
        primary = SignalAmber,
        primaryDark = SignalAmberDeep,
        accent = SignalAmberGlow,
        previewColor = SignalAmber
    ),
    EMERALD(
        title = "Cyber Mint",
        primary = CyberMint,
        primaryDark = Color(0xFF00966C),
        accent = Color(0xFF34D399),
        previewColor = CyberMint
    ),
    BLUE(
        title = "Telemetry Blue",
        primary = TelemetryBlue,
        primaryDark = Color(0xFF3B82F6),
        accent = Color(0xFF93C5FD),
        previewColor = TelemetryBlue
    ),
    CRIMSON(
        title = "Crimson Alert",
        primary = CrimsonAlert,
        primaryDark = Color(0xFFD32F2F),
        accent = Color(0xFFFF8A80),
        previewColor = CrimsonAlert
    ),
    AMBER(
        title = "Tactical Gold",
        primary = Color(0xFFF59E0B),
        primaryDark = Color(0xFFD97706),
        accent = Color(0xFFFBBF24),
        previewColor = Color(0xFFF59E0B)
    ),
    CYAN(
        title = "Radar Cyan",
        primary = Color(0xFF06B6D4),
        primaryDark = Color(0xFF0891B2),
        accent = Color(0xFF22D3EE),
        previewColor = Color(0xFF06B6D4)
    ),
    PURPLE(
        title = "Spectre Violet",
        primary = Color(0xFF8B5CF6),
        primaryDark = Color(0xFF7C3AED),
        accent = Color(0xFFA78BFA),
        previewColor = Color(0xFF8B5CF6)
    ),
    PINK(
        title = "Laser Magenta",
        primary = Color(0xFFEC4899),
        primaryDark = Color(0xFFDB2777),
        accent = Color(0xFFF472B6),
        previewColor = Color(0xFFEC4899)
    )
}

/**
 * Selectable UI Surface Material Styles requested by the user:
 * - CLAY_3D: Tactile 3D Soft Claymorphism with warm sculpted volume & soft inner rim
 * - FROSTED_GLASS: Translucent Frosted Acrylic Glass with icy diffused refraction
 * - LIQUID_GLASS: High-gloss Liquid Glass with iridescent cyan/amber specular sheen
 * - TACTICAL_HUD: Clean Tactical Gunmetal HUD with crisp 1dp border
 */
enum class AppSurfaceStyle(
    val title: String,
    val subtitle: String,
    val badge: String,
    val cardColor: Color,
    val elevatedColor: Color,
    val borderColor: Color,
    val highlightColor: Color,
    val cornerRadiusDp: Int
) {
    CLAY_3D(
        title = "3D Soft Clay",
        subtitle = "Sculpted matte clay cards with warm tactile depth",
        badge = "CLAY 3D",
        cardColor = Color(0xFFFFFFFF),
        elevatedColor = Color(0xFFF8FAFC),
        borderColor = Color(0xFFE2E8F0),
        highlightColor = Color(0xFFFF9800),
        cornerRadiusDp = 22
    ),
    FROSTED_GLASS(
        title = "Frosted Glass",
        subtitle = "Translucent icy acrylic glass with soft frost border",
        badge = "FROSTED",
        cardColor = Color(0xF5FFFFFF),
        elevatedColor = Color(0xFAFFFFFF),
        borderColor = Color(0x6638BDF8),
        highlightColor = Color(0xFF0284C7),
        cornerRadiusDp = 20
    ),
    LIQUID_GLASS(
        title = "Liquid Glass",
        subtitle = "High-gloss refractive glass with iridescent sheen",
        badge = "LIQUID",
        cardColor = Color(0xF5FFFFFF),
        elevatedColor = Color(0xFAFFFFFF),
        borderColor = Color(0x66A855F7),
        highlightColor = Color(0xFF8B5CF6),
        cornerRadiusDp = 24
    ),
    TACTICAL_HUD(
        title = "Clean Utility",
        subtitle = "Clean precision field utility with crisp borders",
        badge = "UTILITY",
        cardColor = HudSurfaceContainer,
        elevatedColor = HudElevatedCard,
        borderColor = HudBorderSubtle,
        highlightColor = SignalAmber,
        cornerRadiusDp = 16
    )
}

class ThemeManager private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)
    
    private val _currentTheme = MutableStateFlow(loadSavedTheme())
    val currentTheme: StateFlow<AppThemeColor> = _currentTheme.asStateFlow()

    private val _currentSurfaceStyle = MutableStateFlow(loadSavedSurfaceStyle())
    val currentSurfaceStyle: StateFlow<AppSurfaceStyle> = _currentSurfaceStyle.asStateFlow()

    private fun loadSavedTheme(): AppThemeColor {
        val savedName = prefs.getString("selected_theme_color", AppThemeColor.SWIGGY.name)
        return try {
            AppThemeColor.valueOf(savedName ?: AppThemeColor.SWIGGY.name)
        } catch (_: Exception) {
            AppThemeColor.SWIGGY
        }
    }

    private fun loadSavedSurfaceStyle(): AppSurfaceStyle {
        val savedName = prefs.getString("selected_surface_style", AppSurfaceStyle.CLAY_3D.name)
        return try {
            AppSurfaceStyle.valueOf(savedName ?: AppSurfaceStyle.CLAY_3D.name)
        } catch (_: Exception) {
            AppSurfaceStyle.CLAY_3D
        }
    }

    fun setTheme(theme: AppThemeColor) {
        prefs.edit().putString("selected_theme_color", theme.name).apply()
        _currentTheme.value = theme
    }

    fun setSurfaceStyle(style: AppSurfaceStyle) {
        prefs.edit().putString("selected_surface_style", style.name).apply()
        _currentSurfaceStyle.value = style
    }

    companion object {
        @Volatile
        private var instance: ThemeManager? = null

        fun getInstance(context: Context): ThemeManager {
            return instance ?: synchronized(this) {
                instance ?: ThemeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

val LocalThemeColor = compositionLocalOf { AppThemeColor.SWIGGY }
val LocalSurfaceStyle = compositionLocalOf { AppSurfaceStyle.CLAY_3D }
