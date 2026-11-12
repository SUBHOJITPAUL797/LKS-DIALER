package com.example.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun LksDialerTheme(
    darkTheme: Boolean = false,
    themeColor: AppThemeColor = AppThemeColor.SWIGGY,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val surfaceStyle by themeManager.currentSurfaceStyle.collectAsState()
    val savedDarkMode by themeManager.isDarkMode.collectAsState()
    val effectiveDark = darkTheme || savedDarkMode

    // Keep global snapshot state in sync before composition reads color tokens
    ThemeStateHolder.isDarkMode = effectiveDark
    ThemeStateHolder.surfaceStyle = surfaceStyle
    ThemeStateHolder.themeColor = themeColor
    SideEffect {
        ThemeStateHolder.isDarkMode = effectiveDark
        ThemeStateHolder.surfaceStyle = surfaceStyle
        ThemeStateHolder.themeColor = themeColor
    }

    val hudColorScheme = if (effectiveDark) {
        darkColorScheme(
            primary = themeColor.primary,
            onPrimary = Color.White,
            primaryContainer = themeColor.primaryDark,
            onPrimaryContainer = Color.White,
            secondary = CyberMint,
            onSecondary = Color.White,
            tertiary = TelemetryBlue,
            onTertiary = Color.White,
            background = HudBackgroundPrimary,
            surface = surfaceStyle.resolvedCardColor(true),
            surfaceVariant = surfaceStyle.resolvedElevatedColor(true),
            onBackground = Color(0xFFF8FAFC),
            onSurface = Color(0xFFF8FAFC),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = surfaceStyle.resolvedBorderColor(true),
            outlineVariant = surfaceStyle.resolvedBorderColor(true),
            error = CrimsonAlert,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = themeColor.primary,
            onPrimary = Color.White,
            primaryContainer = themeColor.primaryDark,
            onPrimaryContainer = Color.White,
            secondary = CyberMint,
            onSecondary = Color.White,
            tertiary = TelemetryBlue,
            onTertiary = Color.White,
            background = HudBackgroundPrimary,
            surface = surfaceStyle.resolvedCardColor(false),
            surfaceVariant = surfaceStyle.resolvedElevatedColor(false),
            onBackground = Color(0xFF0F172A),
            onSurface = Color(0xFF0F172A),
            onSurfaceVariant = Color(0xFF64748B),
            outline = surfaceStyle.resolvedBorderColor(false),
            outlineVariant = surfaceStyle.resolvedBorderColor(false),
            error = CrimsonAlert,
            onError = Color.White
        )
    }

    CompositionLocalProvider(
        LocalThemeColor provides themeColor,
        LocalSurfaceStyle provides surfaceStyle,
        LocalIsDarkTheme provides effectiveDark
    ) {
        MaterialTheme(
            colorScheme = hudColorScheme,
            typography = Typography
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        // Subtle refractive ambient light caustics across the screen when Liquid Glass or Fog Glass is selected
                        if (surfaceStyle == AppSurfaceStyle.LIQUID_GLASS || surfaceStyle == AppSurfaceStyle.FROSTED_GLASS) {
                            val topGlowAlpha = if (effectiveDark) 0.10f else 0.14f
                            val bottomGlowAlpha = if (effectiveDark) 0.08f else 0.10f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF38BDF8).copy(alpha = topGlowAlpha),
                                        themeColor.primary.copy(alpha = topGlowAlpha * 0.45f),
                                        Color.Transparent
                                    ),
                                    center = Offset(size.width * 0.15f, size.height * 0.08f),
                                    radius = size.width * 0.75f
                                ),
                                center = Offset(size.width * 0.15f, size.height * 0.08f),
                                radius = size.width * 0.75f
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        CyberMint.copy(alpha = bottomGlowAlpha),
                                        Color(0xFFA855F7).copy(alpha = bottomGlowAlpha * 0.5f),
                                        Color.Transparent
                                    ),
                                    center = Offset(size.width * 0.88f, size.height * 0.85f),
                                    radius = size.width * 0.70f
                                ),
                                center = Offset(size.width * 0.88f, size.height * 0.85f),
                                radius = size.width * 0.70f
                            )
                        }
                    }
            ) {
                content()
            }
        }
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    LksDialerTheme(darkTheme = darkTheme, content = content)
}
