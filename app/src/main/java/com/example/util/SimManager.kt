package com.example.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Representation of an active SIM card subscription on the device.
 */
data class SimInfo(
    val slotIndex: Int,              // 0 for SIM 1, 1 for SIM 2
    val subscriptionId: Int,         // Unique subscription ID
    val displayName: String,         // e.g. "Jio 4G", "Airtel"
    val carrierName: String,         // e.g. "Jio", "Vodafone"
    val phoneNumber: String? = null, // Phone number on SIM (if provisioned)
    val countryIso: String = "",     // e.g. "IN", "US"
    val iconTint: Int = 0,           // User-assigned or carrier color
    val phoneAccountHandle: PhoneAccountHandle? = null
) {
    val displaySlotLabel: String get() = "SIM ${slotIndex + 1}"
}

/**
 * Utility to detect active dual/single SIM cards and dispatch cellular calls.
 */
object SimManager {

    private const val TAG = "SimManager"

    /**
     * Retrieves all active SIM cards on the device.
     * Returns empty list if device has no SIM or lacks permissions.
     */
    fun getActiveSimCards(context: Context): List<SimInfo> {
        val simList = mutableListOf<SimInfo>()

        // Check READ_PHONE_STATE permission
        val hasPhoneStatePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && hasPhoneStatePermission) {
            try {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

                // Call-capable phone accounts from TelecomManager on API 23+
                val callAccountHandles: List<PhoneAccountHandle> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        telecomManager?.callCapablePhoneAccounts ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else emptyList()

                val activeSubscriptions: List<SubscriptionInfo>? = subscriptionManager?.activeSubscriptionInfoList

                if (!activeSubscriptions.isNullOrEmpty()) {
                    for (sub in activeSubscriptions) {
                        val matchingHandle = callAccountHandles.firstOrNull { handle ->
                            handle.id == sub.subscriptionId.toString() ||
                                (sub.iccId != null && handle.id.contains(sub.iccId))
                        }

                        val resolvedDisplayName = sub.displayName?.toString()?.takeIf { it.isNotBlank() }
                            ?: "SIM ${sub.simSlotIndex + 1}"
                        val resolvedCarrier = sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
                            ?: resolvedDisplayName

                        simList.add(
                            SimInfo(
                                slotIndex = sub.simSlotIndex,
                                subscriptionId = sub.subscriptionId,
                                displayName = resolvedDisplayName,
                                carrierName = resolvedCarrier,
                                phoneNumber = sub.number?.takeIf { it.isNotBlank() },
                                countryIso = sub.countryIso?.uppercase() ?: "",
                                iconTint = sub.iconTint,
                                phoneAccountHandle = matchingHandle
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query SubscriptionManager: ${e.message}")
            }
        }

        // Fallback: Check TelephonyManager if no subscriptions detected
        if (simList.isEmpty()) {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                if (tm != null && (tm.simState == TelephonyManager.SIM_STATE_READY || tm.phoneType != TelephonyManager.PHONE_TYPE_NONE)) {
                    val opName = tm.networkOperatorName.takeIf { !it.isNullOrBlank() }
                        ?: tm.simOperatorName.takeIf { !it.isNullOrBlank() }
                        ?: "Cellular SIM"

                    simList.add(
                        SimInfo(
                            slotIndex = 0,
                            subscriptionId = 0,
                            displayName = opName,
                            carrierName = opName,
                            phoneNumber = null,
                            countryIso = tm.networkCountryIso.uppercase(),
                            iconTint = 0,
                            phoneAccountHandle = null
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fallback to TelephonyManager: ${e.message}")
            }
        }

        return simList.sortedBy { it.slotIndex }
    }

    /**
     * Dials a regular cellular phone call using either a specific SIM card (if provided)
     * or the device default SIM slot.
     */
    fun placeCellularCall(
        context: Context,
        phoneNumber: String,
        simInfo: SimInfo? = null
    ) {
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (cleanNumber.isBlank()) {
            Log.w(TAG, "Cannot place cellular call: phone number is blank")
            return
        }

        val uri = Uri.fromParts("tel", cleanNumber, null)

        val hasCallPhonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val extras = Bundle()
        if (simInfo != null) {
            // Android OEM standard SIM routing flags
            extras.putInt("com.android.phone.force.slot", simInfo.slotIndex)
            extras.putInt("Cdma_Supp", simInfo.subscriptionId)
            extras.putInt("simSlot", simInfo.slotIndex)
            extras.putInt("slot", simInfo.slotIndex)
            extras.putInt("subscription", simInfo.subscriptionId)
            extras.putInt("phone_subscription", simInfo.subscriptionId)
            extras.putLong("sub_id", simInfo.subscriptionId.toLong())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && simInfo.phoneAccountHandle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simInfo.phoneAccountHandle)
            }
        }

        if (hasCallPhonePermission) {
            // 1. Try TelecomManager directly if PhoneAccountHandle is resolved
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && simInfo?.phoneAccountHandle != null) {
                try {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    if (telecomManager != null) {
                        telecomManager.placeCall(uri, extras)
                        Log.i(TAG, "Placed cellular call via TelecomManager using ${simInfo.displayName}")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TelecomManager.placeCall failed, falling back to ACTION_CALL: ${e.message}")
                }
            }

            // 2. Direct ACTION_CALL with SIM slot extras
            try {
                val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtras(extras)
                }
                context.startActivity(callIntent)
                Log.i(TAG, "Placed cellular call via Intent.ACTION_CALL to $cleanNumber")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Intent.ACTION_CALL failed, falling back to ACTION_DIAL: ${e.message}")
            }
        }

        // 3. Fallback to ACTION_DIAL (pre-fills system dialer without crashing if permission denied)
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            Log.i(TAG, "Opened system dialer via ACTION_DIAL for $cleanNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ACTION_DIAL: ${e.message}")
        }
    }
}
