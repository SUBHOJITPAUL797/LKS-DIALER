package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * XiaomiAutostartHelper
 * Handles MIUI and HyperOS-specific background restrictions for Xiaomi, Redmi, and POCO devices.
 * Provides 1-tap intents to open Autostart and Battery Saver management activities.
 */
object XiaomiAutostartHelper {

    private const val TAG = "XiaomiAutostart"

    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        return manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    }

    /**
     * Attempts to open the MIUI / HyperOS Autostart management screen.
     * Falls back gracefully to application details if OEM activity is missing.
     */
    fun openAutostartSettings(context: Context) {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            Intent().apply {
                action = "miui.intent.action.OP_AUTO_START"
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched Autostart intent: ${intent.component}")
                return
            } catch (_: Exception) {}
        }

        // Fallback: Open standard application details
        try {
            val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appDetailsIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings fallback: ${e.message}")
        }
    }

    /**
     * Attempts to open the MIUI Battery Saver screen to configure "No restrictions".
     */
    fun openBatterySaverSettings(context: Context) {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", "LKS Dialer")
            },
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched Battery Saver intent: ${intent.component}")
                return
            } catch (_: Exception) {}
        }

        // Fallback: Open standard application details
        try {
            val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appDetailsIntent)
        } catch (_: Exception) {}
    }

    /**
     * Attempts to open the MIUI "Other permissions" screen where "Show on Lock screen"
     * and "Display pop-up windows while running in the background" are configured.
     */
    fun openOtherPermissionsSettings(context: Context) {
        val intents = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched Other Permissions intent: ${intent.component ?: intent.action}")
                return
            } catch (_: Exception) {}
        }
    }
}
