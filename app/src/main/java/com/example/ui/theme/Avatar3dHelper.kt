package com.example.ui.theme

import androidx.annotation.DrawableRes
import com.example.R
import kotlin.math.abs

object Avatar3dHelper {
    /**
     * Deterministically returns a vibrant 3D character avatar drawable
     * based on the person's name and/or phone number.
     */
    @DrawableRes
    fun getAvatarResId(name: String, phoneNumber: String = ""): Int {
        val key = (name.trim().lowercase() + phoneNumber.filter { it.isDigit() }).ifBlank { "user" }
        val hash = abs(key.hashCode())
        return if (hash % 2 == 0) {
            R.drawable.character_3d_boy
        } else {
            R.drawable.character_3d_girl
        }
    }
}
