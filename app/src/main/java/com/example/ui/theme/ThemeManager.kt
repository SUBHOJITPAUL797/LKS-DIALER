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
    PURPLE(
        title = "Royal Purple",
        primary = Color(0xFF8B5CF6),
        primaryDark = Color(0xFF7C3AED),
        accent = Color(0xFFA78BFA),
        previewColor = Color(0xFF8B5CF6)
    ),
    EMERALD(
        title = "Emerald Green",
        primary = Color(0xFF00A884),
        primaryDark = Color(0xFF008069),
        accent = Color(0xFF25D366),
        previewColor = Color(0xFF00A884)
    ),
    BLUE(
        title = "Ocean Blue",
        primary = Color(0xFF3B82F6),
        primaryDark = Color(0xFF2563EB),
        accent = Color(0xFF60A5FA),
        previewColor = Color(0xFF3B82F6)
    ),
    CRIMSON(
        title = "Ruby Crimson",
        primary = Color(0xFFE11D48),
        primaryDark = Color(0xFFBE123C),
        accent = Color(0xFFF43F5E),
        previewColor = Color(0xFFE11D48)
    ),
    AMBER(
        title = "Cyber Amber",
        primary = Color(0xFFF59E0B),
        primaryDark = Color(0xFFD97706),
        accent = Color(0xFFFBBF24),
        previewColor = Color(0xFFF59E0B)
    ),
    PINK(
        title = "Neon Pink",
        primary = Color(0xFFEC4899),
        primaryDark = Color(0xFFDB2777),
        accent = Color(0xFFF472B6),
        previewColor = Color(0xFFEC4899)
    ),
    CYAN(
        title = "Midnight Cyan",
        primary = Color(0xFF06B6D4),
        primaryDark = Color(0xFF0891B2),
        accent = Color(0xFF22D3EE),
        previewColor = Color(0xFF06B6D4)
    )
}

class ThemeManager private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)
    
    private val _currentTheme = MutableStateFlow(loadSavedTheme())
    val currentTheme: StateFlow<AppThemeColor> = _currentTheme.asStateFlow()

    private fun loadSavedTheme(): AppThemeColor {
        val savedName = prefs.getString("selected_theme_color", AppThemeColor.PURPLE.name)
        return try {
            AppThemeColor.valueOf(savedName ?: AppThemeColor.PURPLE.name)
        } catch (_: Exception) {
            AppThemeColor.PURPLE
        }
    }

    fun setTheme(theme: AppThemeColor) {
        prefs.edit().putString("selected_theme_color", theme.name).apply()
        _currentTheme.value = theme
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

val LocalThemeColor = compositionLocalOf { AppThemeColor.PURPLE }
