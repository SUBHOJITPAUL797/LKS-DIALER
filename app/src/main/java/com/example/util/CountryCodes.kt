package com.example.util

import com.example.data.model.CountryCode

object CountryCodes {
    val defaultCountry = CountryCode("India", "🇮🇳", "+91", "IN")

    val allCountries = listOf(
        CountryCode("India", "🇮🇳", "+91", "IN"),
        CountryCode("Bangladesh", "🇧🇩", "+880", "BD"),
        CountryCode("United States", "🇺🇸", "+1", "US"),
        CountryCode("United Kingdom", "🇬🇧", "+44", "GB"),
        CountryCode("Pakistan", "🇵🇰", "+92", "PK"),
        CountryCode("United Arab Emirates", "🇦🇪", "+971", "AE"),
        CountryCode("Saudi Arabia", "🇸🇦", "+966", "SA"),
        CountryCode("Canada", "🇨🇦", "+1", "CA"),
        CountryCode("Malaysia", "🇲🇾", "+60", "MY"),
        CountryCode("Singapore", "🇸🇬", "+65", "SG"),
        CountryCode("Australia", "🇦🇺", "+61", "AU"),
        CountryCode("Germany", "🇩🇪", "+49", "DE"),
        CountryCode("France", "🇫🇷", "+33", "FR"),
        CountryCode("Italy", "🇮🇹", "+39", "IT"),
        CountryCode("Qatar", "🇶🇦", "+974", "QA"),
        CountryCode("Kuwait", "🇰🇼", "+965", "KW"),
        CountryCode("Oman", "🇴🇲", "+968", "OM"),
        CountryCode("Bahrain", "🇧🇭", "+973", "BH"),
        CountryCode("Nepal", "🇳🇵", "+977", "NP"),
        CountryCode("Sri Lanka", "🇱🇰", "+94", "LK")
    )

    fun formatPhoneNumber(dialCode: String, number: String): String {
        val trimmed = number.trim()
        val cleanDialCodeDigits = dialCode.replace(Regex("[^0-9]"), "")
        val rawDigits = trimmed.replace(Regex("[^0-9]"), "")
        if (rawDigits.isEmpty()) return ""
        if (trimmed.startsWith("+")) {
            return "+$rawDigits"
        }
        val cleanNumber = rawDigits.trimStart('0').ifEmpty { rawDigits }
        return if (
            cleanDialCodeDigits.isNotEmpty() &&
            cleanNumber.startsWith(cleanDialCodeDigits) &&
            cleanNumber.length >= 10 + cleanDialCodeDigits.length
        ) {
            "+$cleanNumber"
        } else {
            "$dialCode$cleanNumber"
        }
    }
}
