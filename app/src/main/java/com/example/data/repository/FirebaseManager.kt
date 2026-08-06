package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

sealed class RegisterResult {
    data class Success(val user: UserDto) : RegisterResult()
    data class NewUser(val phoneNumber: String) : RegisterResult()
    data class DeviceBlocked(val phoneNumber: String, val lockedToDeviceId: String) : RegisterResult()
}

class FirebaseManager private constructor(private val context: Context) {

    private val _isFirebaseConfigured = MutableStateFlow<Boolean>(false)
    val isFirebaseConfigured: StateFlow<Boolean> = _isFirebaseConfigured.asStateFlow()

    private val _currentUser = MutableStateFlow<UserDto?>(null)
    val currentUser: StateFlow<UserDto?> = _currentUser.asStateFlow()

    private val _callLogs = MutableStateFlow<List<CallLogDto>>(emptyList())
    val callLogs: StateFlow<List<CallLogDto>> = _callLogs.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactDto>>(emptyList())
    val contacts: StateFlow<List<ContactDto>> = _contacts.asStateFlow()

    private val _activeCall = MutableStateFlow<CallDto?>(null)
    val activeCall: StateFlow<CallDto?> = _activeCall.asStateFlow()

    private val _registeredUsers = MutableStateFlow<Map<String, UserDto>>(emptyMap())
    private val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)

    init {
        checkFirebaseAvailability()
        // mock data disabled
        
        // Restore user if exists
        val savedPhone = prefs.getString("user_phone", null)
        val savedName = prefs.getString("user_name", "")
        val savedDeviceId = prefs.getString("device_id", "")
        if (savedPhone != null) {
            _currentUser.value = UserDto(
                phoneNumber = savedPhone,
                displayName = savedName ?: "",
                registeredDeviceId = savedDeviceId ?: ""
            )
        }
    }

    private fun checkFirebaseAvailability() {
        try {
            // Check if Firebase app is initialized (google-services.json present)
            val firestore = FirebaseFirestore.getInstance()
            _isFirebaseConfigured.value = firestore.app != null
            Log.d("FirebaseManager", "Firebase configuration check: ${_isFirebaseConfigured.value}")

            if (_isFirebaseConfigured.value) {
                listenToFirestoreUsers()
            }
        } catch (e: Exception) {
            Log.w("FirebaseManager", "Firebase not initialized yet or google-services.json missing: ${e.message}")
            _isFirebaseConfigured.value = false
        }
    }

    private fun listenToFirestoreUsers() {
        FirebaseFirestore.getInstance().collection("users")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val users = snapshot.toObjects(UserDto::class.java)
                val newMap = _registeredUsers.value.toMutableMap()
                for (user in users) {
                    newMap[user.phoneNumber] = user
                }
                _registeredUsers.value = newMap
                
                // Update current user if it was updated remotely
                _currentUser.value?.let { current ->
                    newMap[current.phoneNumber]?.let { updated ->
                        _currentUser.value = updated
                    }
                }
            }
    }

    private fun seedMockData() {
        // Mock data removed per user request
    }

    fun lookupUserByNumber(phoneNumber: String): UserDto? {
        val cleanNumber = phoneNumber.replace(" ", "").trim()
        return _registeredUsers.value[cleanNumber] ?: _registeredUsers.value.values.find {
            it.phoneNumber.endsWith(cleanNumber.takeLast(10))
        }
    }

    /**
     * Verifies if the phone number is registered to this device or another device.
     * Prevents Person B from using Person A's registered number on another hardware device.
     */
    fun verifyAndRegisterNumber(phoneNumber: String, currentDeviceId: String): RegisterResult {
        val existingUser = lookupUserByNumber(phoneNumber)

        return if (existingUser != null) {
            val registeredDev = existingUser.registeredDeviceId
            if (registeredDev.isBlank() || registeredDev == currentDeviceId) {
                // Number belongs to this device (or unassigned mock) - Allow login!
                val updatedUser = existingUser.copy(registeredDeviceId = currentDeviceId)
                _currentUser.value = updatedUser
                val map = _registeredUsers.value.toMutableMap()
                map[phoneNumber] = updatedUser
                _registeredUsers.value = map
                RegisterResult.Success(updatedUser)
            } else {
                // Number is registered to ANOTHER physical device! BLOCK ACCESS!
                RegisterResult.DeviceBlocked(phoneNumber, registeredDev)
            }
        } else {
            // New user number -> Allow registration and bind to current device
            RegisterResult.NewUser(phoneNumber)
        }
    }

    fun loginWithPhone(phoneNumber: String, name: String, deviceId: String = "") {
        val existing = lookupUserByNumber(phoneNumber)
        val finalDeviceId = deviceId.ifBlank { existing?.registeredDeviceId ?: "DEV-${UUID.randomUUID().toString().take(8)}" }

        var user = UserDto(
            phoneNumber = phoneNumber,
            displayName = name.ifBlank { existing?.displayName ?: "User ${phoneNumber.takeLast(4)}" },
            statusMessage = existing?.statusMessage ?: "Available on LKS DIALER",
            registeredDeviceId = finalDeviceId
        )
        _currentUser.value = user
        
        prefs.edit()
            .putString("user_phone", user.phoneNumber)
            .putString("user_name", user.displayName)
            .putString("device_id", user.registeredDeviceId)
            .apply()
            
        // Try to get FCM Token to save with user profile
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                user = user.copy(fcmToken = token)
                _currentUser.value = user
                
                if (_isFirebaseConfigured.value) {
                    FirebaseFirestore.getInstance().collection("users")
                        .document(phoneNumber)
                        .set(user)
                }
            }
        } catch (e: Exception) {
            Log.e("FirebaseManager", "Failed to get FCM token: ${e.message}")
        }

        
        // Add to registered users
        val currentMap = _registeredUsers.value.toMutableMap()
        currentMap[phoneNumber] = user
        _registeredUsers.value = currentMap

        // Sync with Firestore if configured
        if (_isFirebaseConfigured.value) {
            try {
                FirebaseFirestore.getInstance().collection("users")
                    .document(phoneNumber)
                    .set(user)
                    .addOnSuccessListener {
                        Log.d("FirebaseManager", "User successfully saved to Firestore.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirebaseManager", "Error saving user to Firestore: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e("FirebaseManager", "Exception saving user to Firestore: ${e.message}")
            }
        } else {
             Log.w("FirebaseManager", "Firebase not configured, cannot save user to Firestore.")
        }
    }

    fun addContact(name: String, number: String) {
        val registered = lookupUserByNumber(number) != null
        val newContact = ContactDto(
            id = UUID.randomUUID().toString(),
            name = name,
            phoneNumber = number,
            isVoiceLinkUser = registered,
            statusMessage = if (registered) "Available" else "Not on LKS DIALER yet"
        )
        _contacts.value = _contacts.value + newContact
    }

    fun logCall(
        direction: CallDirection,
        otherPartyNumber: String,
        otherPartyName: String,
        callType: CallType,
        status: CallStatus,
        durationSeconds: Int
    ) {
        val newLog = CallLogDto(
            id = UUID.randomUUID().toString(),
            callId = UUID.randomUUID().toString(),
            direction = direction,
            otherPartyNumber = otherPartyNumber,
            otherPartyName = otherPartyName,
            callType = callType,
            status = status,
            startedAt = System.currentTimeMillis(),
            durationSeconds = durationSeconds
        )
        _callLogs.value = listOf(newLog) + _callLogs.value
    }

    fun clearCallLogs() {
        _callLogs.value = emptyList()
    }

    companion object {
        private const val TAG = "FirebaseManager"
        @Volatile
        private var INSTANCE: FirebaseManager? = null

        fun getInstance(context: Context): FirebaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
