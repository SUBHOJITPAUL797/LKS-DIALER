package com.example.util

import android.content.Context

/**
 * XiaomiAutostartHelper
 * Retained for backwards compatibility. Delegates to UniversalOemAutostartHelper.
 */
object XiaomiAutostartHelper {

    fun isXiaomiDevice(): Boolean {
        return UniversalOemAutostartHelper.getDeviceBrand() == UniversalOemAutostartHelper.OemBrand.XIAOMI
    }

    fun openAutostartSettings(context: Context) {
        UniversalOemAutostartHelper.openAutostartSettings(context)
    }

    fun openBatterySaverSettings(context: Context) {
        UniversalOemAutostartHelper.openBatterySettings(context)
    }

    fun openOtherPermissionsSettings(context: Context) {
        UniversalOemAutostartHelper.openSpecialPermissionsSettings(context)
    }
}
