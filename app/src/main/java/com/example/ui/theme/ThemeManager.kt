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
 * - LIQUID_GLASS: iPhone iOS Liquid Transparent Glass with specular refraction & prism rim
 * - FROSTED_GLASS: Translucent Frosted Fog Glass with satin acrylic diffusion
 * - CLAY_3D: Tactile 3D Soft Claymorphism with warm sculpted volume & soft inner rim
 * - TACTICAL_HUD: Clean Utility surface with crisp 1dp border
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
    LIQUID_GLASS(
        title = "iPhone Liquid Glass",
        subtitle = "Optical refractive transparent glass with specular prism rim",
        badge = "LIQUID GLASS",
        cardColor = Color(0xEBFFFFFF),
        elevatedColor = Color(0xF5FFFFFF),
        borderColor = Color(0x8038BDF8),
        highlightColor = Color(0xFF0EA5E9),
        cornerRadiusDp = 24
    ),
    FROSTED_GLASS(
        title = "Frosted Fog Glass",
        subtitle = "Diffused satin fog glass with soft luminescence",
        badge = "FOG GLASS",
        cardColor = Color(0xF2F8FAFC),
        elevatedColor = Color(0xFAF8FAFC),
        borderColor = Color(0x7094A3B8),
        highlightColor = Color(0xFF0284C7),
        cornerRadiusDp = 22
    ),
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
    TACTICAL_HUD(
        title = "Clean Utility",
        subtitle = "Clean precision field utility with crisp borders",
        badge = "UTILITY",
        cardColor = HudSurfaceContainer,
        elevatedColor = HudElevatedCard,
        borderColor = HudBorderSubtle,
        highlightColor = SignalAmber,
        cornerRadiusDp = 16
    );

    fun resolvedCardColor(isDark: Boolean): Color = if (!isDark) cardColor else when (this) {
        LIQUID_GLASS -> Color(0xBF131F2C)
        FROSTED_GLASS -> Color(0xD9182432)
        CLAY_3D -> Color(0xFF1A2530)
        TACTICAL_HUD -> Color(0xFF162029)
    }

    fun resolvedElevatedColor(isDark: Boolean): Color = if (!isDark) elevatedColor else when (this) {
        LIQUID_GLASS -> Color(0xD91B2B3C)
        FROSTED_GLASS -> Color(0xE6202F40)
        CLAY_3D -> Color(0xFF22303F)
        TACTICAL_HUD -> Color(0xFF1E2B37)
    }

    fun resolvedBorderColor(isDark: Boolean): Color = if (!isDark) borderColor else when (this) {
        LIQUID_GLASS -> Color(0x887DD3FC)
        FROSTED_GLASS -> Color(0x6694A3B8)
        CLAY_3D -> Color(0xFF2E3F50)
        TACTICAL_HUD -> Color(0xFF2B3B4B)
    }
}

/**
 * Incoming Call Screen Visual Style:
 * - IPHONE_LIQUID_GLASS: iPhone iOS Liquid Glass full-screen translucent call UI
 * - SAMSUNG_ONE_UI: Samsung One UI call screen with concentric aura & dual expanding orb rings
 */
enum class IncomingCallUiStyle(
    val title: String,
    val subtitle: String,
    val badge: String
) {
    IPHONE_LIQUID_GLASS(
        title = "iPhone Liquid Glass",
        subtitle = "Refractive liquid glass capsule, specular sheen & iOS controls",
        badge = "iOS LIQUID"
    ),
    SAMSUNG_ONE_UI(
        title = "Samsung One UI",
        subtitle = "Samsung Galaxy expanding answer/decline rings & smooth wave aura",
        badge = "ONE UI"
    )
}

/**
 * Incoming Call Pickup Swipe Direction Mode:
 * - ANY_DIRECTION: Swipe Up, Left, or Right to answer smoothly
 * - IPHONE_SLIDE_RIGHT: Classic iPhone horizontal Liquid Glass slide-right to answer
 * - SWIPE_UP: Vertical swipe-up to answer, swipe-down to decline
 * - SAMSUNG_EXPAND_RING: Pull green answer orb in any direction to answer, red orb to decline
 */
enum class IncomingSwipeDirection(
    val title: String,
    val subtitle: String
) {
    ANY_DIRECTION(
        title = "Omni-Swipe (Up, Left, or Right)",
        subtitle = "Swipe the Liquid Glass orb Up, Left, or Right to pick up"
    ),
    IPHONE_SLIDE_RIGHT(
        title = "iPhone Slide Right / Left",
        subtitle = "Slide right on the Liquid Glass track to answer, left to decline"
    ),
    SWIPE_UP(
        title = "Vertical Swipe Up / Down",
        subtitle = "Swipe up to answer the call, swipe down to decline"
    ),
    SAMSUNG_EXPAND_RING(
        title = "Samsung Dual Swipe Rings",
        subtitle = "Drag green orb in any direction to answer or red orb to decline"
    )
}

class ThemeManager private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)
    
    private val _currentTheme = MutableStateFlow(loadSavedTheme())
    val currentTheme: StateFlow<AppThemeColor> = _currentTheme.asStateFlow()

    private val _currentSurfaceStyle = MutableStateFlow(loadSavedSurfaceStyle())
    val currentSurfaceStyle: StateFlow<AppSurfaceStyle> = _currentSurfaceStyle.asStateFlow()

    // App-level Dark Mode preference — strictly FALSE (Light Theme) by default unless user explicitly enables it
    private val _isDarkMode = MutableStateFlow(prefs.getBoolean("app_dark_mode", false))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _incomingCallStyle = MutableStateFlow(loadSavedIncomingCallStyle())
    val incomingCallStyle: StateFlow<IncomingCallUiStyle> = _incomingCallStyle.asStateFlow()

    private val _incomingSwipeDirection = MutableStateFlow(loadSavedIncomingSwipeDirection())
    val incomingSwipeDirection: StateFlow<IncomingSwipeDirection> = _incomingSwipeDirection.asStateFlow()

    private fun loadSavedTheme(): AppThemeColor {
        val savedName = prefs.getString("selected_theme_color", AppThemeColor.SWIGGY.name)
        return try {
            AppThemeColor.valueOf(savedName ?: AppThemeColor.SWIGGY.name)
        } catch (_: Exception) {
            AppThemeColor.SWIGGY
        }
    }

    private fun loadSavedSurfaceStyle(): AppSurfaceStyle {
        val savedName = prefs.getString("selected_surface_style", AppSurfaceStyle.LIQUID_GLASS.name)
        return try {
            AppSurfaceStyle.valueOf(savedName ?: AppSurfaceStyle.LIQUID_GLASS.name)
        } catch (_: Exception) {
            AppSurfaceStyle.LIQUID_GLASS
        }
    }

    private fun loadSavedIncomingCallStyle(): IncomingCallUiStyle {
        val savedName = prefs.getString("incoming_call_ui_style", IncomingCallUiStyle.IPHONE_LIQUID_GLASS.name)
        return try {
            IncomingCallUiStyle.valueOf(savedName ?: IncomingCallUiStyle.IPHONE_LIQUID_GLASS.name)
        } catch (_: Exception) {
            IncomingCallUiStyle.IPHONE_LIQUID_GLASS
        }
    }

    private fun loadSavedIncomingSwipeDirection(): IncomingSwipeDirection {
        val savedName = prefs.getString("incoming_swipe_direction", IncomingSwipeDirection.ANY_DIRECTION.name)
        return try {
            IncomingSwipeDirection.valueOf(savedName ?: IncomingSwipeDirection.ANY_DIRECTION.name)
        } catch (_: Exception) {
            IncomingSwipeDirection.ANY_DIRECTION
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

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("app_dark_mode", enabled).apply()
        _isDarkMode.value = enabled
    }

    fun setIncomingCallStyle(style: IncomingCallUiStyle) {
        prefs.edit().putString("incoming_call_ui_style", style.name).apply()
        _incomingCallStyle.value = style
    }

    fun setIncomingSwipeDirection(direction: IncomingSwipeDirection) {
        prefs.edit().putString("incoming_swipe_direction", direction.name).apply()
        _incomingSwipeDirection.value = direction
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
val LocalSurfaceStyle = compositionLocalOf { AppSurfaceStyle.LIQUID_GLASS }
val LocalIsDarkTheme = compositionLocalOf { false }

