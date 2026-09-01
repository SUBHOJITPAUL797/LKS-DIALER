package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalContact(
    val name: String,
    val phoneNumber: String,
    val normalizedNumber: String
)

object ContactsHelper {
    
    suspend fun getLocalContacts(context: Context): List<LocalContact> = withContext(Dispatchers.IO) {
        val contactsList = mutableListOf<LocalContact>()
        
        val contentResolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                val number = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                
                val normalizedNumber = normalizePhoneNumber(number)
                if (normalizedNumber.isNotBlank()) {
                    contactsList.add(LocalContact(name, number, normalizedNumber))
                }
            }
        }
        
        // Remove duplicates based on normalized number, preferring the first name found
        return@withContext contactsList.distinctBy { it.normalizedNumber }
    }

    /**
     * Normalizes a phone number to standard E.164-like format (digits and plus only).
     * This makes it easy to compare local contacts with Firebase registered phone numbers.
     */
    fun normalizePhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }

    /**
     * Checks if two phone numbers match, handling differing country code prefixes or formatting.
     */
    fun numbersMatch(num1: String, num2: String): Boolean {
        val clean1 = num1.replace(Regex("[^0-9]"), "")
        val clean2 = num2.replace(Regex("[^0-9]"), "")
        if (clean1.isBlank() || clean2.isBlank()) return false
        if (clean1 == clean2) return true
        if (clean1.length >= 10 && clean2.length >= 10) {
            return clean1.takeLast(10) == clean2.takeLast(10)
        }
        return false
    }
}
