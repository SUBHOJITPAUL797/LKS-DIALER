package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// VIBRANT, POLISHED & COLORFUL MODERN PALETTE (LIGHT HUD & CLEAN UTILITY)
// ============================================================================

// 1. Core Backgrounds & Surfaces (Bright, Crisp, Colorful Light Canvas)
val HudBackgroundPrimary = Color(0xFFF4F6FB)     // Clean vibrant canvas with soft lavender-slate tint (#F4F6FB)
val HudSurfaceContainer = Color(0xFFFFFFFF)      // Pure White Card Container (#FFFFFF)
val HudElevatedCard = Color(0xFFFFFFFF)          // Elevated Pure White Card (#FFFFFF)
val HudGlassOverlay = Color(0xF2FFFFFF)          // Frosted Glass Pure White Overlay (95% opacity)
val HudBorderSubtle = Color(0xFFE2E8F0)          // Crisp 1dp Border (#E2E8F0)

// 2. Tactical & Modern Accents
val SignalAmber = Color(0xFFFF6D00)              // Vibrant Swiggy / Signal Amber (#FF6D00)
val SignalAmberDeep = Color(0xFFE65100)
val SignalAmberGlow = Color(0xFFFF9100)

val CyberMint = Color(0xFF00C48C)                // Verified / Success Mint (#00C48C)
val TelemetryBlue = Color(0xFF2563EB)            // Vibrant Telemetry Blue (#2563EB)
val CrimsonAlert = Color(0xFFEF4444)             // Vibrant Crimson Alert (#EF4444)

// 3. Typography Colors
val HudTextPrimary = Color(0xFF0F172A)           // Crisp Slate / Graphite (#0F172A)
val HudTextSecondary = Color(0xFF64748B)         // Warm Subtitle Slate (#64748B)

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

// Dark HUD Theme Tokens (Mapped to crisp high-contrast colorful tokens)
val BackgroundDark = HudBackgroundPrimary
val SurfaceDark = HudSurfaceContainer
val SurfaceCardDark = HudElevatedCard
val OnSurfaceDark = HudTextPrimary
val OnSurfaceSubtleDark = HudTextSecondary

// Light Mode Tokens
val BackgroundLight = HudBackgroundPrimary
val SurfaceLight = HudSurfaceContainer
val SurfaceCardLight = HudElevatedCard
val OnSurfaceLight = HudTextPrimary
val OnSurfaceSubtleLight = HudTextSecondary

val DividerColor = HudBorderSubtle
val MissedCallRed = CrimsonAlert
val IncomingCallGreen = CyberMint
val OutgoingCallBlue = TelemetryBlue
