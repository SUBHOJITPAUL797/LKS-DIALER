package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

enum class WallpaperType {
    DEFAULT,
    PRESET,
    CUSTOM
}

data class WallpaperPreset(
    val id: String,
    val name: String,
    val previewColor: Color,
    val gradientColors: List<Color>,
    val isDoodle: Boolean = false
)

data class WallpaperConfig(
    val type: WallpaperType = WallpaperType.DEFAULT,
    val presetId: String = "",
    val customImagePath: String? = null,
    val dimAlpha: Float = 0.2f
)

class ChatWallpaperManager private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "dialer_prefs"
        private const val KEY_WALLPAPER_TYPE = "chat_wallpaper_type"
        private const val KEY_WALLPAPER_PRESET = "chat_wallpaper_preset"
        private const val KEY_WALLPAPER_DIM = "chat_wallpaper_dim"
        private const val CUSTOM_WALLPAPER_FILENAME = "chat_wallpaper_custom.jpg"

        val PRESETS = listOf(
            WallpaperPreset(
                id = "DOODLE",
                name = "Doodle Pattern",
                previewColor = Color(0xFF0F2B20),
                gradientColors = listOf(Color(0xFF0B141A), Color(0xFF11212B)),
                isDoodle = true
            ),
            WallpaperPreset(
                id = "EMERALD_NIGHT",
                name = "Emerald VoIP",
                previewColor = Color(0xFF0A2B1D),
                gradientColors = listOf(Color(0xFF04180F), Color(0xFF0D3323))
            ),
            WallpaperPreset(
                id = "MIDNIGHT_OLED",
                name = "Midnight OLED",
                previewColor = Color(0xFF000000),
                gradientColors = listOf(Color(0xFF000000), Color(0xFF0D0D0D))
            ),
            WallpaperPreset(
                id = "DEEP_OCEAN",
                name = "Deep Ocean",
                previewColor = Color(0xFF091E3A),
                gradientColors = listOf(Color(0xFF040F1D), Color(0xFF0D294D))
            ),
            WallpaperPreset(
                id = "SUNSET_CRIMSON",
                name = "Sunset Plum",
                previewColor = Color(0xFF2E1229),
                gradientColors = listOf(Color(0xFF170815), Color(0xFF381432))
            ),
            WallpaperPreset(
                id = "MINIMAL_SLATE",
                name = "Minimal Slate",
                previewColor = Color(0xFF1C232B),
                gradientColors = listOf(Color(0xFF101418), Color(0xFF222B35))
            ),
            WallpaperPreset(
                id = "LAVENDER_MIST",
                name = "Lavender Mist",
                previewColor = Color(0xFF261938),
                gradientColors = listOf(Color(0xFF140C20), Color(0xFF321F4B))
            )
        )

        @Volatile
        private var instance: ChatWallpaperManager? = null

        fun getInstance(context: Context): ChatWallpaperManager {
            return instance ?: synchronized(this) {
                instance ?: ChatWallpaperManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<WallpaperConfig> = _config.asStateFlow()

    private fun loadConfig(): WallpaperConfig {
        val typeStr = prefs.getString(KEY_WALLPAPER_TYPE, WallpaperType.DEFAULT.name) ?: WallpaperType.DEFAULT.name
        val type = try { WallpaperType.valueOf(typeStr) } catch (_: Exception) { WallpaperType.DEFAULT }
        val preset = prefs.getString(KEY_WALLPAPER_PRESET, "DOODLE") ?: "DOODLE"
        val dim = prefs.getFloat(KEY_WALLPAPER_DIM, 0.25f)
        val customFile = File(context.filesDir, CUSTOM_WALLPAPER_FILENAME)
        val customPath = if (customFile.exists() && customFile.length() > 0) customFile.absolutePath else null

        return WallpaperConfig(
            type = if (type == WallpaperType.CUSTOM && customPath == null) WallpaperType.DEFAULT else type,
            presetId = preset,
            customImagePath = customPath,
            dimAlpha = dim
        )
    }

    fun setPreset(presetId: String) {
        prefs.edit()
            .putString(KEY_WALLPAPER_TYPE, WallpaperType.PRESET.name)
            .putString(KEY_WALLPAPER_PRESET, presetId)
            .apply()
        _config.value = loadConfig()
    }

    fun setCustomPhoto(uri: Uri): Boolean {
        return try {
            val destFile = File(context.filesDir, CUSTOM_WALLPAPER_FILENAME)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.exists() && destFile.length() > 0) {
                prefs.edit()
                    .putString(KEY_WALLPAPER_TYPE, WallpaperType.CUSTOM.name)
                    .apply()
                _config.value = loadConfig()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun setDimAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 0.75f)
        prefs.edit()
            .putFloat(KEY_WALLPAPER_DIM, clamped)
            .apply()
        _config.value = _config.value.copy(dimAlpha = clamped)
    }

    fun resetToDefault() {
        prefs.edit()
            .putString(KEY_WALLPAPER_TYPE, WallpaperType.DEFAULT.name)
            .apply()
        _config.value = loadConfig()
    }

    fun getCustomWallpaperBitmap(): Bitmap? {
        val file = File(context.filesDir, CUSTOM_WALLPAPER_FILENAME)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }
}
