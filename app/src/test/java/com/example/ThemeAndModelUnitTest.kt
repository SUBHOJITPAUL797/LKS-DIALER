package com.example

import com.example.ui.theme.AppThemeColor
import com.example.util.CountryCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAndModelUnitTest {

    @Test
    fun default_theme_is_purple() {
        val defaultTheme = AppThemeColor.PURPLE
        assertEquals("Royal Purple", defaultTheme.title)
    }

    @Test
    fun all_themes_have_unique_titles() {
        val themes = AppThemeColor.values()
        val titles = themes.map { it.title }.toSet()
        assertEquals(themes.size, titles.size)
        assertTrue(themes.size >= 7)
    }

    @Test
    fun country_code_default_is_valid() {
        val defaultCountry = CountryCodes.defaultCountry
        assertNotNull(defaultCountry)
        assertEquals("+91", defaultCountry.dialCode)
        assertEquals("IN", defaultCountry.isoCode)
    }

    @Test
    fun format_phone_number_formats_correctly() {
        val formatted = CountryCodes.formatPhoneNumber("+91", "9876543210")
        assertEquals("+919876543210", formatted)
        
        val alreadyFormatted = CountryCodes.formatPhoneNumber("+91", "+919876543210")
        assertEquals("+919876543210", alreadyFormatted)
    }
}
