package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * UniversalOemAutostartHelper
 * Comprehensive background and autostart manager supporting all major Android OEMs:
 * - Xiaomi, Redmi, POCO (MIUI / HyperOS)
 * - Samsung (One UI / Smart Manager)
 * - OnePlus (OxygenOS)
 * - Oppo, Realme (ColorOS / Realme UI)
 * - Vivo, iQOO (FuntouchOS / OriginOS)
 * - Huawei, Honor (EMUI / MagicOS)
 * - Asus (ZenUI / ROG UI)
 * - Transsion (Tecno, Infinix, itel)
 */
object UniversalOemAutostartHelper {

    private const val TAG = "UniversalOemHelper"
    private const val PREFS_NAME = "oem_autostart_prefs"
    private const val KEY_PROMPT_SHOWN = "oem_prompt_shown_v1"

    enum class OemBrand(
        val displayName: String,
        val autostartStepTitle: String,
        val batteryStepTitle: String,
        val ramLockTip: String
    ) {
        XIAOMI(
            displayName = "Xiaomi / Redmi / POCO",
            autostartStepTitle = "Enable MIUI Autostart",
            batteryStepTitle = "Set Battery Saver to 'No Restrictions'",
            ramLockTip = "In Recent Apps, long-press LKS Dialer and tap the Lock (🔒) icon."
        ),
        SAMSUNG(
            displayName = "Samsung (One UI)",
            autostartStepTitle = "Add to 'Never Sleeping Apps'",
            batteryStepTitle = "Set Battery to 'Unrestricted'",
            ramLockTip = "In Recent Apps, tap the LKS Dialer app icon above its card and select 'Keep open' (🔒)."
        ),
        ONEPLUS(
            displayName = "OnePlus (OxygenOS)",
            autostartStepTitle = "Enable Auto-Launch & Background Run",
            batteryStepTitle = "Disable Battery Optimization",
            ramLockTip = "In Recent Apps, tap the 3 dots on LKS Dialer and tap 'Lock' (🔒)."
        ),
        OPPO_REALME(
            displayName = "Oppo / Realme (ColorOS)",
            autostartStepTitle = "Enable Auto-Startup",
            batteryStepTitle = "Allow Background Activity",
            ramLockTip = "In Recent Apps, tap the 2/3 dots on LKS Dialer and select 'Lock' (🔒)."
        ),
        VIVO_IQOO(
            displayName = "Vivo / iQOO (Funtouch OS)",
            autostartStepTitle = "Enable Autostart",
            batteryStepTitle = "Set 'High Background Power Consumption'",
            ramLockTip = "In Recent Apps, drag down on the LKS Dialer card and tap the Lock (🔒) icon."
        ),
        HUAWEI_HONOR(
            displayName = "Huawei / Honor (EMUI)",
            autostartStepTitle = "Set Launch to 'Manage Manually' (Enable Auto-launch)",
            batteryStepTitle = "Disable Power Saving Restrictions",
            ramLockTip = "In Recent Apps, swipe down on the LKS Dialer card to lock (🔒) it."
        ),
        ASUS(
            displayName = "Asus (ZenUI / ROG)",
            autostartStepTitle = "Enable Auto-Start in Mobile Manager",
            batteryStepTitle = "Set PowerMaster to Allow Background Run",
            ramLockTip = "In Recent Apps, tap the Pin or Lock (🔒) icon."
        ),
        TRANSSION(
            displayName = "Tecno / Infinix / itel",
            autostartStepTitle = "Enable Auto-start in Phone Master",
            batteryStepTitle = "Allow Background Power",
            ramLockTip = "In Recent Apps, tap the Lock (🔒) icon."
        ),
        GENERIC(
            displayName = "Android Device",
            autostartStepTitle = "Allow Background Activity",
            batteryStepTitle = "Disable Battery Optimization",
            ramLockTip = "In Recent Apps, lock LKS Dialer so RAM Clean never closes it (🔒)."
        )
    }

    /**
     * Detects the device manufacturer / brand.
     */
    fun getDeviceBrand(): OemBrand {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()

        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                    brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> OemBrand.XIAOMI

            manufacturer.contains("samsung") || brand.contains("samsung") -> OemBrand.SAMSUNG

            manufacturer.contains("oneplus") || brand.contains("oneplus") -> OemBrand.ONEPLUS

            manufacturer.contains("oppo") || brand.contains("oppo") ||
                    manufacturer.contains("realme") || brand.contains("realme") -> OemBrand.OPPO_REALME

            manufacturer.contains("vivo") || brand.contains("vivo") ||
                    manufacturer.contains("iqoo") || brand.contains("iqoo") -> OemBrand.VIVO_IQOO

            manufacturer.contains("huawei") || brand.contains("huawei") ||
                    manufacturer.contains("honor") || brand.contains("honor") -> OemBrand.HUAWEI_HONOR

            manufacturer.contains("asus") || brand.contains("asus") -> OemBrand.ASUS

            manufacturer.contains("transsion") || manufacturer.contains("tecno") ||
                    manufacturer.contains("infinix") || manufacturer.contains("itel") -> OemBrand.TRANSSION

            else -> OemBrand.GENERIC
        }
    }

    fun isAggressiveOem(): Boolean {
        return getDeviceBrand() != OemBrand.GENERIC
    }

    fun isPromptNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(KEY_PROMPT_SHOWN, false)
    }

    fun markPromptDismissed(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()
    }

    /**
     * Opens the brand-specific Autostart or Auto-launch settings screen.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val intents = mutableListOf<Intent>()

        when (getDeviceBrand()) {
            OemBrand.XIAOMI -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                })
                intents.add(Intent("miui.intent.action.OP_AUTO_START").apply { addCategory(Intent.CATEGORY_DEFAULT) })
                intents.add(Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                })
            }

            OemBrand.SAMSUNG -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.cstyle.BaseAppListActivity")
                })
                intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }

            OemBrand.ONEPLUS -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.oplus.battery", "com.oplus.battery.view.BatteryUsageActivity")
                })
            }

            OemBrand.OPPO_REALME -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
                })
            }

            OemBrand.VIVO_IQOO -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                })
            }

            OemBrand.HUAWEI_HONOR -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
                })
            }

            OemBrand.ASUS -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
                })
            }

            OemBrand.TRANSSION -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.autostart.AutoStartActivity")
                })
            }

            OemBrand.GENERIC -> {
                intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // Standard fallbacks for all devices
        intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:")
        })

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Successfully launched intent: ")
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Opens the brand-specific battery optimization settings to allow unrestricted background execution.
     */
    fun openBatterySettings(context: Context): Boolean {
        val intents = mutableListOf<Intent>()

        when (getDeviceBrand()) {
            OemBrand.XIAOMI -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    putExtra("package_name", context.packageName)
                    putExtra("package_label", "LKS Dialer")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    putExtra("package_name", context.packageName)
                })
            }

            OemBrand.SAMSUNG -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
                })
            }

            OemBrand.OPPO_REALME, OemBrand.ONEPLUS -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.oplus.battery", "com.oplus.battery.view.BatteryUsageActivity")
                })
            }

            OemBrand.VIVO_IQOO -> {
                intents.add(Intent().apply {
                    component = ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                })
            }

            else -> {}
        }

        // Android standard battery optimization exemption request
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intents.add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:")
            })
            intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:")
        })

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Successfully launched battery intent: ")
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Opens secondary permissions (e.g. Xiaomi 'Show on Lock screen' and background popups).
     */
    fun openSpecialPermissionsSettings(context: Context): Boolean {
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
                data = Uri.parse("package:")
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }
}
