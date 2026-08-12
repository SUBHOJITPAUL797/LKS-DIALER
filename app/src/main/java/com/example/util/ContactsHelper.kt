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
    
    @SuppressLint("Range")
    suspend fun getLocalContacts(context: Context): List<LocalContact> = withContext(Dispatchers.IO) {
        val contactsList = mutableListOf<LocalContact>()
        
        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""
                val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                
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
}
