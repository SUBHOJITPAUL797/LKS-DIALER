package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.UUID

object DeviceUtils {

    private const val PREFS_NAME = "device_security_prefs"
    private const val KEY_DEVICE_ID = "hardware_device_id"

    /**
     * Retrieves or generates a unique, persistent Hardware Device ID bound to this physical installation.
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)

        if (deviceId.isNull_or_blank()) {
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }

            val generatedId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                "DEV-AND-$androidId"
            } else {
                "DEV-UUID-" + UUID.randomUUID().toString().take(12)
            }

            deviceId = generatedId
            prefs.edit().putString(KEY_DEVICE_ID, generatedId).apply()
        }

        return deviceId ?: "DEV-DEFAULT-ID"
    }

    /**
     * Attempts to auto-detect SIM card phone number from hardware Telephony / Subscription services.
     */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun detectSimPhoneNumber(context: Context): String? {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val number = telephonyManager?.line1Number

            if (!number.isNullOrBlank()) {
                number.trim()
            } else {
                // Try SubscriptionManager on API 22+
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val activeList = subscriptionManager?.activeSubscriptionInfoList
                val subNumber = activeList?.firstOrNull()?.number
                if (!subNumber.isNullOrBlank()) subNumber.trim() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun formatDeviceIdForDisplay(deviceId: String): String {
        return if (deviceId.length > 14) {
            "${deviceId.take(8)}...${deviceId.takeLast(4)}"
        } else deviceId
    }
}

private fun String?.isNull_or_blank(): Boolean = this == null || this.isBlank()
