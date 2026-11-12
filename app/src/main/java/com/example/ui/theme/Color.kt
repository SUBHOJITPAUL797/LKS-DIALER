package com.example.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Snapshot-reactive holder synced with [ThemeManager] so that all color tokens
 * (`HudBackgroundPrimary`, `HudTextPrimary`, `HudTextSecondary`, `AppSurfaceStyle.cardColor`, etc.)
 * automatically recompose the entire app UI when Dark Mode or Surface Style changes.
 */
object ThemeStateHolder {
    var isDarkMode by mutableStateOf(false)
    var surfaceStyle by mutableStateOf(AppSurfaceStyle.LIQUID_GLASS)
    var themeColor by mutableStateOf(AppThemeColor.SWIGGY)
}

// ============================================================================
// DYNAMIC LIGHT / DARK & LIQUID GLASS REACTIVE PALETTE
// ============================================================================

// 1. Core Backgrounds & Surfaces (Automatically adapts to Light/Dark & Liquid Glass)
val HudBackgroundPrimary: Color
    get() = if (ThemeStateHolder.isDarkMode) {
        when (ThemeStateHolder.surfaceStyle) {
            AppSurfaceStyle.LIQUID_GLASS -> Color(0xFF081120)
            AppSurfaceStyle.FROSTED_GLASS -> Color(0xFF0A1424)
            AppSurfaceStyle.CLAY_3D -> Color(0xFF0E1622)
            AppSurfaceStyle.TACTICAL_HUD -> Color(0xFF0B141A)
        }
    } else {
        when (ThemeStateHolder.surfaceStyle) {
            AppSurfaceStyle.LIQUID_GLASS -> Color(0xFFEAF2FF)
            AppSurfaceStyle.FROSTED_GLASS -> Color(0xFFEEF4FB)
            AppSurfaceStyle.CLAY_3D -> Color(0xFFF3F6FA)
            AppSurfaceStyle.TACTICAL_HUD -> Color(0xFFF4F6FB)
        }
    }

val HudSurfaceContainer: Color
    get() = if (ThemeStateHolder.isDarkMode) Color(0xFF131F2E) else Color(0xFFFFFFFF)

val HudElevatedCard: Color
    get() = if (ThemeStateHolder.isDarkMode) Color(0xFF1A293C) else Color(0xFFFFFFFF)

val HudGlassOverlay: Color
    get() = if (ThemeStateHolder.isDarkMode) Color(0xCC101C2C) else Color(0xD9FFFFFF)

val HudBorderSubtle: Color
    get() = if (ThemeStateHolder.isDarkMode) Color(0xFF293B50) else Color(0xFFE2E8F0)

// 2. Tactical & Modern Accents
val SignalAmber = Color(0xFFFF6D00)              // Vibrant Swiggy / Signal Amber (#FF6D00)
val SignalAmberDeep = Color(0xFFE65100)
val SignalAmberGlow = Color(0xFFFF9100)

val CyberMint = Color(0xFF00C48C)                // Verified / Success Mint (#00C48C)
val TelemetryBlue = Color(0xFF2563EB)            // Vibrant Telemetry Blue (#2563EB)
val CrimsonAlert = Color(0xFFEF4444)             // Vibrant Crimson Alert (#EF4444)

// 3. Typography Colors (Snapshot-reactive for 100% Dark/Light Mode coverage across all screens)
val HudTextPrimary: Color
    get() = if (ThemeStateHolder.isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)

val HudTextSecondary: Color
    get() = if (ThemeStateHolder.isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

// ============================================================================
// SEMANTIC ALIASES (Retained for full compatibility across modules)
// ============================================================================
val SwiggyOrange = SignalAmber
val SwiggyOrangeDeep = SignalAmberDeep
val SwiggyAmber = SignalAmberGlow

val PurplePrimary = Color(0xFF8B5CF6)
val PurpleDark = Color(0xFF7C3AED)
val PurpleAccent = Color(0xFFA78BFA)

val TealPrimary = SignalAmber
val TealLight = SignalAmberGlow

val GreenCall = CyberMint
val RedEndCall = CrimsonAlert

// Dedicated Deep Call & Dark Tokens (Never white!)
val BackgroundDark = Color(0xFF081120)
val SurfaceDark: Color
    get() = HudSurfaceContainer
val SurfaceCardDark: Color
    get() = HudElevatedCard
val OnSurfaceDark: Color
    get() = HudTextPrimary
val OnSurfaceSubtleDark: Color
    get() = HudTextSecondary

// Light Mode Tokens
val BackgroundLight: Color
    get() = HudBackgroundPrimary
val SurfaceLight: Color
    get() = HudSurfaceContainer
val SurfaceCardLight: Color
    get() = HudElevatedCard
val OnSurfaceLight: Color
    get() = HudTextPrimary
val OnSurfaceSubtleLight: Color
    get() = HudTextSecondary

val DividerColor: Color
    get() = HudBorderSubtle
val MissedCallRed = CrimsonAlert
val IncomingCallGreen = CyberMint
val OutgoingCallBlue = TelemetryBlue
