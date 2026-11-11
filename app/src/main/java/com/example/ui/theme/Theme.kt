package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
            background = Color(0xFF0B141A),
            surface = Color(0xFF182229),
            surfaceVariant = Color(0xFF1F2C34),
            onBackground = Color(0xFFF1F5F9),
            onSurface = Color(0xFFF1F5F9),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = Color(0xFF2A3942),
            outlineVariant = Color(0xFF2A3942),
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
            surface = surfaceStyle.cardColor,
            surfaceVariant = surfaceStyle.elevatedColor,
            onBackground = HudTextPrimary,
            onSurface = HudTextPrimary,
            onSurfaceVariant = HudTextSecondary,
            outline = surfaceStyle.borderColor,
            outlineVariant = surfaceStyle.borderColor,
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
            typography = Typography,
            content = content
        )
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
