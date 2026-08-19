package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun LksDialerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: AppThemeColor = AppThemeColor.PURPLE,
    content: @Composable () -> Unit
) {
    val darkColorScheme = darkColorScheme(
        primary = themeColor.primary,
        onPrimary = Color.White,
        primaryContainer = themeColor.primaryDark,
        onPrimaryContainer = Color.White,
        secondary = themeColor.accent,
        onSecondary = Color.Black,
        background = BackgroundDark,
        surface = SurfaceDark,
        surfaceVariant = SurfaceCardDark,
        onBackground = OnSurfaceDark,
        onSurface = OnSurfaceDark,
        onSurfaceVariant = OnSurfaceSubtleDark,
        error = RedEndCall
    )

    val lightColorScheme = lightColorScheme(
        primary = themeColor.primaryDark,
        onPrimary = Color.White,
        primaryContainer = themeColor.primary,
        onPrimaryContainer = Color.White,
        secondary = themeColor.accent,
        onSecondary = Color.White,
        background = BackgroundLight,
        surface = SurfaceLight,
        surfaceVariant = SurfaceCardLight,
        onBackground = OnSurfaceLight,
        onSurface = OnSurfaceLight,
        onSurfaceVariant = OnSurfaceSubtleLight,
        error = RedEndCall
    )

    val colorScheme = if (darkTheme) darkColorScheme else lightColorScheme

    CompositionLocalProvider(LocalThemeColor provides themeColor) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    LksDialerTheme(darkTheme = darkTheme, content = content)
}
