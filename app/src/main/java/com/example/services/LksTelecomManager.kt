package com.example.services

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.example.data.model.CallType

/**
 * LksTelecomManager
 * Registers a Self-Managed PhoneAccount with Android TelecomManager.
 * This allows connected Bluetooth headsets, TWS earbuds, smartwatches, and car systems
 * to receive Bluetooth HFP call events and send ATA (Answer) / AT+CHUP (Hangup) commands.
 */
object LksTelecomManager {
    private const val TAG = "LksTelecomManager"
    private const val ACCOUNT_ID = "LksDialerVoipAccount"

    fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
        val componentName = ComponentName(context, LksConnectionService::class.java)
        return PhoneAccountHandle(componentName, ACCOUNT_ID)
    }

    fun registerPhoneAccount(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val handle = getPhoneAccountHandle(context)

            val phoneAccount = PhoneAccount.builder(handle, "LKS DIALER")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setIcon(android.graphics.drawable.Icon.createWithResource(context, com.example.R.mipmap.ic_launcher))
                .setHighlightColor(0xFF00ADB5.toInt())
                .setShortDescription("LKS VoIP Calls")
                .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()

            telecomManager.registerPhoneAccount(phoneAccount)
            Log.d(TAG, "Self-managed PhoneAccount registered with TelecomManager successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PhoneAccount", e)
        }
    }

    private val reportedIncomingCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Report incoming call to Android Telecom system.
     *
     * WHY: This is the same mechanism WhatsApp and Telegram use. When you call
     * TelecomManager.addNewIncomingCall(), Android treats your app with the same priority
     * as a real phone call — it bypasses Doze, battery saver, MIUI background restrictions,
     * Samsung sleeping apps, OxygenOS/ColorOS app kill, and ALL manufacturer restrictions.
     * The system then calls LksConnectionService.onCreateIncomingConnection(), which calls
     * onShowIncomingCallUi() — guaranteed to show the call UI even on the most aggressive OEMs.
     *
     * We use SELF_MANAGED PhoneAccount so it does NOT show the system's native phone dialer UI
     * (which would conflict with our custom UI). Instead it just gives our app system-call priority.
     */
    fun reportIncomingCall(
        context: Context,
        callId: String,
        callerName: String,
        callerNumber: String,
        callType: CallType
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!reportedIncomingCalls.add(callId)) {
            Log.d(TAG, "reportIncomingCall: $callId already reported, skipping duplicate")
            return
        }
        // Limit set size to avoid memory leaks across many calls
        if (reportedIncomingCalls.size > 50) reportedIncomingCalls.clear()

        try {
            registerPhoneAccount(context)
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val handle = getPhoneAccountHandle(context)

            val extras = Bundle().apply {
                putString("call_id", callId)
                putString("caller_name", callerName)
                putString("caller_number", callerNumber)
                putString("call_type", callType.name)
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.fromParts(PhoneAccount.SCHEME_SIP, callerNumber.ifBlank { "LKS" }, null))
                putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    if (callType == CallType.VIDEO) android.telecom.VideoProfile.STATE_BIDIRECTIONAL
                    else android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }

            telecomManager.addNewIncomingCall(handle, extras)
            Log.i(TAG, "✅ Reported incoming call to Android Telecom system: callId=$callId from $callerName ($callerNumber)")
        } catch (e: SecurityException) {
            Log.w(TAG, "reportIncomingCall: SecurityException (MANAGE_OWN_CALLS permission issue?): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "reportIncomingCall: Failed to add call to Telecom: ${e.message}")
        }
    }

    fun reportOutgoingCall(
        context: Context,
        callId: String,
        calleeName: String,
        calleeNumber: String,
        callType: CallType
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            registerPhoneAccount(context)
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val handle = getPhoneAccountHandle(context)

            val outgoingExtras = Bundle().apply {
                putString("call_id", callId)
                putString("callee_name", calleeName)
                putString("callee_number", calleeNumber)
                putString("call_type", callType.name)
            }
            val uri = Uri.fromParts(PhoneAccount.SCHEME_SIP, calleeNumber.ifBlank { "LKS" }, null)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtras)
                if (callType == CallType.VIDEO) {
                    putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, android.telecom.VideoProfile.STATE_BIDIRECTIONAL)
                }
            }

            telecomManager.placeCall(uri, extras)
            Log.i(TAG, "Reported outgoing call to TelecomManager via placeCall: callId=$callId to $calleeName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to placeCall via TelecomManager: ${e.message}")
        }
    }
}
