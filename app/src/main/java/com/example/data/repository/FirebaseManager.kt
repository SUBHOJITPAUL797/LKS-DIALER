package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.*
import com.example.util.ContactsHelper
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private val _registeredUsers = MutableStateFlow<List<UserDto>>(emptyList())
    val registeredUsers: StateFlow<List<UserDto>> = _registeredUsers.asStateFlow()

    private val _syncedContacts = MutableStateFlow<List<ContactDto>>(emptyList())
    val syncedContacts: StateFlow<List<ContactDto>> = _syncedContacts.asStateFlow()

    private val _nonLksContacts = MutableStateFlow<List<com.example.util.LocalContact>>(emptyList())
    val nonLksContacts: StateFlow<List<com.example.util.LocalContact>> = _nonLksContacts.asStateFlow()

    private val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)

    private val _isDndEnabled = MutableStateFlow<Boolean>(prefs.getBoolean("dnd_enabled", false))
    val isDndEnabled: StateFlow<Boolean> = _isDndEnabled.asStateFlow()

    private val _blockedNumbers = MutableStateFlow<List<String>>(
        (prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()).toList()
    )
    val blockedNumbers: StateFlow<List<String>> = _blockedNumbers.asStateFlow()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var syncJob: kotlinx.coroutines.Job? = null

    private var contactsListener: ListenerRegistration? = null
    private var callLogsListener: ListenerRegistration? = null
    // BUG-14 FIX: Store reference so it can be removed if needed
    private var usersListener: ListenerRegistration? = null
    private val activeCallLogIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        // Step 1: Check if Firebase is properly configured first
        checkFirebaseAvailability()

        // Step 2: Restore the logged-in user from local storage
        val savedPhone = prefs.getString("user_phone", null)
        val savedName = prefs.getString("user_name", "")
        val savedDeviceId = prefs.getString("device_id", "")
        val savedStatus = prefs.getString("user_status", "Available on LKS DIALER")
        val savedFcmToken = prefs.getString("fcm_token", "") ?: ""
        if (savedPhone != null) {
            val savedProfilePic = prefs.getString("user_profile_pic", "") ?: ""
            val myPublicKey = com.example.data.crypto.ChatCryptoManager.getInstance(context).getMyPublicKeyBase64()
            val user = UserDto(
                phoneNumber = savedPhone,
                displayName = savedName ?: "",
                statusMessage = savedStatus ?: "Available on LKS DIALER",
                profilePictureUrl = savedProfilePic,
                registeredDeviceId = savedDeviceId ?: "",
                fcmToken = savedFcmToken,
                isOnline = true,
                lastSeen = System.currentTimeMillis(),
                blockedNumbers = _blockedNumbers.value,
                isDndEnabled = _isDndEnabled.value,
                publicKey = myPublicKey
            )
            _currentUser.value = user

            if (_isFirebaseConfigured.value) {
                // CRITICAL: Always attach listeners and write user to Firestore on startup.
                // This ensures the user document exists in the DB so others can call them.
                attachUserSpecificListeners(savedPhone)
                Log.d(TAG, "Syncing restored user $savedPhone to Firestore on startup")
                FirebaseFirestore.getInstance().collection("users")
                    .document(savedPhone)
                    .set(user, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "User $savedPhone synced to Firestore successfully on startup")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to sync restored user to Firestore (check Firestore rules!): ${e.message}")
                    }
            }
            // Refresh FCM token so push notifications work
            fetchAndUpdateFcmToken()
        }
    }

    private val pendingLookups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun checkFirebaseAvailability() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            try {
                val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        com.google.firebase.firestore.PersistentCacheSettings.newBuilder()
                            .setSizeBytes(100 * 1024 * 1024L) // 100MB persistent disk cache
                            .build()
                    )
                    .build()
                firestore.firestoreSettings = settings
            } catch (_: Exception) {}
            _isFirebaseConfigured.value = firestore.app != null
            Log.d(TAG, "Firebase configuration check: ${_isFirebaseConfigured.value}")

            if (_isFirebaseConfigured.value) {
                // Fetch recent registered users up to 300 to keep DB reads strictly bounded
                listenToFirestoreUsers()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase not initialized yet or google-services.json missing: ${e.message}")
            _isFirebaseConfigured.value = false
        }
    }

    private fun listenToFirestoreUsers() {
        if (!_isFirebaseConfigured.value) return
        usersListener?.remove()
        // Bound query to 300 to prevent runaway reads at high scale
        usersListener = FirebaseFirestore.getInstance().collection("users")
            .limit(300)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) {
                    Log.e(TAG, "Listen to users failed", e)
                    return@addSnapshotListener
                }
                val users = snapshot.toObjects(UserDto::class.java).filter { it.phoneNumber.isNotBlank() }
                _registeredUsers.value = users
                
                // Update current user if it was updated remotely
                _currentUser.value?.let { current ->
                    users.find { it.phoneNumber == current.phoneNumber }?.let { updated ->
                        _currentUser.value = updated
                    }
                }
                
                // Sync with native device contacts
                syncDeviceContactsWithUsers(users)
            }
    }

    fun syncNativeContacts() {
        syncDeviceContactsWithUsers(_registeredUsers.value)
    }
    
    private fun syncDeviceContactsWithUsers(firebaseUsers: List<UserDto>) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            syncJob?.cancel()
            syncJob = scope.launch {
                try {
                    val localContacts = com.example.util.ContactsHelper.getLocalContacts(context)
                    val currentPhone = _currentUser.value?.phoneNumber ?: ""
                    
                    val syncedList = mutableListOf<ContactDto>()
                    val nonLksList = mutableListOf<com.example.util.LocalContact>()
                    
                    for (local in localContacts) {
                        if (currentPhone.isNotBlank() && com.example.util.ContactsHelper.numbersMatch(local.phoneNumber, currentPhone)) {
                            continue // Skip self
                        }
                        
                        val matchingUser = firebaseUsers.find { user ->
                            user.phoneNumber.isNotBlank() && 
                            user.phoneNumber != currentPhone &&
                            com.example.util.ContactsHelper.numbersMatch(local.phoneNumber, user.phoneNumber)
                        }
                        
                        if (matchingUser != null) {
                            syncedList.add(
                                ContactDto(
                                    id = matchingUser.phoneNumber,
                                    name = local.name.ifBlank { matchingUser.displayName },
                                    phoneNumber = matchingUser.phoneNumber,
                                    profilePictureUrl = matchingUser.profilePictureUrl,
                                    statusMessage = matchingUser.statusMessage
                                )
                            )
                        } else {
                            nonLksList.add(local)
                        }
                    }
                    
                    // Also include any registered LKS users that might not be saved in local phonebook (except self)
                    for (user in firebaseUsers) {
                        if (user.phoneNumber.isBlank() || user.phoneNumber == currentPhone) continue
                        val alreadyInList = syncedList.any { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, user.phoneNumber) }
                        if (!alreadyInList) {
                            syncedList.add(
                                ContactDto(
                                    id = user.phoneNumber,
                                    name = user.displayName.ifBlank { user.phoneNumber },
                                    phoneNumber = user.phoneNumber,
                                    profilePictureUrl = user.profilePictureUrl,
                                    statusMessage = user.statusMessage
                                )
                            )
                        }
                    }
                    
                    _syncedContacts.value = syncedList.distinctBy { it.phoneNumber }.sortedBy { it.name.lowercase() }
                    _nonLksContacts.value = nonLksList.distinctBy { it.normalizedNumber }.sortedBy { it.name.lowercase() }
                    Log.d(TAG, "Synced contacts: ${syncedList.size} on LKS, ${nonLksList.size} to invite")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync device contacts", e)
                }
            }
        } else {
            Log.w(TAG, "READ_CONTACTS permission not granted, cannot sync contacts.")
        }
    }

    private fun attachUserSpecificListeners(phoneNumber: String) {
        if (!_isFirebaseConfigured.value || phoneNumber.isBlank()) return
        
        // Attach ephemeral E2EE chat listeners
        try {
            ChatRepository.getInstance(context).attachChatListeners(phoneNumber)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach chat listeners: ${e.message}")
        }

        val db = FirebaseFirestore.getInstance()

        // 1. Sync Contacts for user
        contactsListener?.remove()
        contactsListener = db.collection("users").document(phoneNumber)
            .collection("contacts")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val fetchedContacts = snapshot.toObjects(ContactDto::class.java)
                _contacts.value = fetchedContacts
            }

        // 2. Sync Call Logs — scoped to this user's own log collection
        callLogsListener?.remove()
        callLogsListener = db.collection("users").document(phoneNumber)
            .collection("callLogs")
            .orderBy("startedAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val logs = snapshot.toObjects(CallLogDto::class.java)
                _callLogs.value = logs

                val prefs = context.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
                val lastSeenMissedCallAt = prefs.getLong("lastSeenMissedCallAt", 0L)
                if (lastSeenMissedCallAt == 0L) {
                    // On first run / fresh install, initialize to now so we don't spam old historical missed calls
                    val maxTimestamp = logs.filter { it.direction == CallDirection.MISSED }
                        .maxOfOrNull { it.startedAt } ?: System.currentTimeMillis()
                    prefs.edit().putLong("lastSeenMissedCallAt", maxTimestamp).apply()
                    return@addSnapshotListener
                }
                var maxMissedCallAt = lastSeenMissedCallAt
                
                logs.filter { it.direction == CallDirection.MISSED && it.startedAt > lastSeenMissedCallAt }
                    .forEach { missedCall ->
                        if (missedCall.startedAt > maxMissedCallAt) {
                            maxMissedCallAt = missedCall.startedAt
                        }
                        showMissedCallNotification(missedCall)
                    }
                
                if (maxMissedCallAt > lastSeenMissedCallAt) {
                    prefs.edit().putLong("lastSeenMissedCallAt", maxMissedCallAt).apply()
                }
            }
    }

    fun showMissedCallNotification(
        callerNumber: String,
        callerName: String,
        callType: CallType = CallType.AUDIO,
        callId: String = ""
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "missed_call_channel",
                "Missed Calls",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for missed VoIP calls"
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val resolvedName = if (callerName.isNotBlank() && callerName != "Unknown Caller") {
            callerName
        } else {
            val contact = _syncedContacts.value.find { ContactsHelper.numbersMatch(it.phoneNumber, callerNumber) }
            val reg = _registeredUsers.value.find { ContactsHelper.numbersMatch(it.phoneNumber, callerNumber) }
            reg?.displayName?.ifBlank { null } ?: contact?.name?.ifBlank { null } ?: callerNumber
        }

        val notifId = if (callId.isNotBlank()) callId.hashCode() else (callerNumber + System.currentTimeMillis()).hashCode()

        // Tap notification opens Recents tab in MainActivity
        val openIntent = android.content.Intent(context, com.example.MainActivity::class.java).apply {
            action = "com.example.ACTION_OPEN_RECENTS_$notifId"
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "RECENTS")
            putExtra("notification_id", notifId)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Call Back Action: directly triggers outgoing call back to the person
        val callBackIntent = android.content.Intent(context, com.example.MainActivity::class.java).apply {
            action = "com.example.ACTION_CALL_BACK_$notifId"
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("call_back_number", callerNumber)
            putExtra("call_back_name", resolvedName)
            putExtra("call_back_type", callType.name)
            putExtra("notification_id", notifId)
        }
        val callBackPendingIntent = android.app.PendingIntent.getActivity(
            context,
            notifId + 1,
            callBackIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val callTypeLabel = if (callType == CallType.VIDEO) "Video" else "Audio"
        val notification = androidx.core.app.NotificationCompat.Builder(context, "missed_call_channel")
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed $callTypeLabel Call")
            .setContentText("Missed call from $resolvedName${if (callerNumber.isNotBlank() && resolvedName != callerNumber) " • $callerNumber" else ""}")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.sym_action_call,
                "Call Back",
                callBackPendingIntent
            )
            .build()

        notificationManager.notify(notifId, notification)
    }

    fun showMissedCallNotification(missedCall: CallLogDto) {
        showMissedCallNotification(
            callerNumber = missedCall.otherPartyNumber,
            callerName = missedCall.otherPartyName,
            callType = missedCall.callType,
            callId = missedCall.callId
        )
    }

    fun lookupUserByNumber(phoneNumber: String): UserDto? {
        val cleanNumber = ContactsHelper.normalizePhoneNumber(phoneNumber)
        val found = _registeredUsers.value.find { it.phoneNumber == cleanNumber } 
            ?: _registeredUsers.value.find { ContactsHelper.numbersMatch(it.phoneNumber, phoneNumber) }
        if (found != null) return found

        // Asynchronously query Firestore for this specific number if not in memory cache
        if (_isFirebaseConfigured.value && cleanNumber.length >= 7 && pendingLookups.add(cleanNumber)) {
            scope.launch {
                try {
                    val db = FirebaseFirestore.getInstance()
                    db.collection("users").document(cleanNumber).get().addOnSuccessListener { doc ->
                        pendingLookups.remove(cleanNumber)
                        val user = doc.toObject(UserDto::class.java)
                        if (user != null && user.phoneNumber.isNotBlank()) {
                            val list = _registeredUsers.value.toMutableList()
                            if (list.none { it.phoneNumber == user.phoneNumber }) {
                                list.add(user)
                                _registeredUsers.value = list
                            }
                        }
                    }.addOnFailureListener {
                        pendingLookups.remove(cleanNumber)
                    }
                } catch (_: Exception) {
                    pendingLookups.remove(cleanNumber)
                }
            }
        }
        return null
    }

    fun verifyAndRegisterNumber(phoneNumber: String, currentDeviceId: String): RegisterResult {
        // Check local in-memory map first (fast path)
        val existingUser = lookupUserByNumber(phoneNumber)

        return if (existingUser != null) {
            val registeredDev = existingUser.registeredDeviceId
            if (registeredDev.isBlank() || registeredDev == currentDeviceId) {
                val updatedUser = existingUser.copy(
                    registeredDeviceId = currentDeviceId,
                    isOnline = true,
                    lastSeen = System.currentTimeMillis()
                )
                _currentUser.value = updatedUser
                val list = _registeredUsers.value.toMutableList()
                val index = list.indexOfFirst { it.phoneNumber == phoneNumber }
                if (index != -1) {
                    list[index] = updatedUser
                } else {
                    list.add(updatedUser)
                }
                _registeredUsers.value = list
                // Also write update back to Firestore
                if (_isFirebaseConfigured.value) {
                    FirebaseFirestore.getInstance().collection("users")
                        .document(phoneNumber)
                        .set(updatedUser)
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to update existing user on login: ${e.message}")
                        }
                }
                RegisterResult.Success(updatedUser)
            } else {
                RegisterResult.DeviceBlocked(phoneNumber, registeredDev)
            }
        } else {
            // User not found in local map (Firestore listener may not have populated yet).
            // Treat as new user so they can register — loginWithPhone will write to Firestore.
            Log.d(TAG, "User $phoneNumber not in local map — treating as NewUser for registration")
            RegisterResult.NewUser(phoneNumber)
        }
    }

    fun loginWithPhone(phoneNumber: String, name: String, deviceId: String = "", status: String = "") {
        val existing = lookupUserByNumber(phoneNumber)
        val finalDeviceId = deviceId.ifBlank { existing?.registeredDeviceId ?: "DEV-${UUID.randomUUID().toString().take(8)}" }
        // BUG-24 FIX: Use the provided status, fall back to existing/default
        val finalStatus = status.ifBlank { existing?.statusMessage ?: "Available on LKS DIALER" }

        val savedFcmToken = prefs.getString("fcm_token", "") ?: ""
        val finalFcmToken = existing?.fcmToken?.ifBlank { savedFcmToken } ?: savedFcmToken

        val myPublicKey = com.example.data.crypto.ChatCryptoManager.getInstance(context).getMyPublicKeyBase64()
        val user = UserDto(
            phoneNumber = phoneNumber,
            displayName = name.ifBlank { existing?.displayName ?: "User ${phoneNumber.takeLast(4)}" },
            statusMessage = finalStatus,
            profilePictureUrl = existing?.profilePictureUrl ?: "",
            registeredDeviceId = finalDeviceId,
            fcmToken = finalFcmToken,
            isOnline = true,
            lastSeen = System.currentTimeMillis(),
            createdAt = existing?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            blockedNumbers = existing?.blockedNumbers?.ifEmpty { _blockedNumbers.value } ?: _blockedNumbers.value,
            isDndEnabled = existing?.isDndEnabled ?: _isDndEnabled.value,
            publicKey = myPublicKey
        )
        _currentUser.value = user
        
        prefs.edit()
            .putString("user_phone", user.phoneNumber)
            .putString("user_name", user.displayName)
            .putString("user_status", user.statusMessage)
            .putString("user_profile_pic", user.profilePictureUrl)
            .putString("device_id", user.registeredDeviceId)
            .putString("fcm_token", user.fcmToken)
            .putBoolean("dnd_enabled", user.isDndEnabled)
            .putStringSet("blocked_numbers", user.blockedNumbers.toSet())
            .apply()
        _isDndEnabled.value = user.isDndEnabled
        _blockedNumbers.value = user.blockedNumbers

        // Attach listeners for this user
        if (_isFirebaseConfigured.value) {
            attachUserSpecificListeners(phoneNumber)
        }

        // Sync with Firestore immediately
        if (_isFirebaseConfigured.value) {
            try {
                FirebaseFirestore.getInstance().collection("users")
                    .document(phoneNumber)
                    .set(user, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "User $phoneNumber saved to Firestore successfully.")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error saving user to Firestore: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Exception saving user to Firestore: ${e.message}")
            }
        }

        // Add to local registered users list
        val currentList = _registeredUsers.value.toMutableList()
        val index = currentList.indexOfFirst { it.phoneNumber == phoneNumber }
        if (index != -1) {
            currentList[index] = user
        } else {
            currentList.add(user)
        }
        _registeredUsers.value = currentList

        // Fetch & update FCM token asynchronously
        fetchAndUpdateFcmToken()
    }

    fun updateProfile(displayName: String, statusMessage: String, profilePicUrl: String = "") {
        val current = _currentUser.value ?: return
        val updated = current.copy(
            displayName = displayName.ifBlank { current.displayName },
            statusMessage = statusMessage.ifBlank { current.statusMessage },
            profilePictureUrl = profilePicUrl.ifBlank { current.profilePictureUrl }
        )
        _currentUser.value = updated

        prefs.edit()
            .putString("user_name", updated.displayName)
            .putString("user_status", updated.statusMessage)
            .putString("user_profile_pic", updated.profilePictureUrl)
            .apply()

        if (_isFirebaseConfigured.value && updated.phoneNumber.isNotBlank()) {
            FirebaseFirestore.getInstance().collection("users")
                .document(updated.phoneNumber)
                .set(updated, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Profile successfully updated in Firestore.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update profile in Firestore: ${e.message}")
                }
        }
    }

    private var presenceHeartbeatJob: kotlinx.coroutines.Job? = null

    /**
     * Starts the periodic presence heartbeat while app is in foreground.
     * Refreshes presence every 25 seconds so peers know this user is actively online.
     */
    fun startPresenceHeartbeat() {
        presenceHeartbeatJob?.cancel()
        updateUserPresence(true)
        presenceHeartbeatJob = scope.launch {
            while (true) {
                delay(25_000L)
                updateUserPresence(true)
            }
        }
    }

    /**
     * Stops presence heartbeat and immediately marks user as offline in Firestore.
     * Called when app is paused, backgrounded, or quit.
     */
    fun stopPresenceHeartbeat() {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
        updateUserPresence(false)
    }

    /**
     * Updates the user's online presence and lastSeen timestamp in Firestore.
     * Called on app foreground (isOnline = true) and background (isOnline = false).
     */
    fun updateUserPresence(isOnline: Boolean) {
        val phone = _currentUser.value?.phoneNumber?.ifBlank { null }
            ?: prefs.getString("user_phone", "")?.ifBlank { null }
            ?: return
        val now = System.currentTimeMillis()
        _currentUser.value?.let { user ->
            _currentUser.value = user.copy(isOnline = isOnline, lastSeen = now)
        }
        if (_isFirebaseConfigured.value) {
            val updates = mapOf<String, Any>(
                "isOnline" to isOnline,
                "online" to isOnline,
                "lastSeen" to now
            )
            FirebaseFirestore.getInstance().collection("users").document(phone)
                .update(updates)
                .addOnSuccessListener {
                    Log.d(TAG, "Presence updated: isOnline=$isOnline for $phone")
                }
                .addOnFailureListener {
                    try {
                        FirebaseFirestore.getInstance().collection("users").document(phone)
                            .set(updates, com.google.firebase.firestore.SetOptions.merge())
                    } catch (_: Exception) {}
                }
        }
    }

    /**
     * Toggles Do Not Disturb (DND) mode.
     * When DND is active, incoming calls are automatically declined without ringing.
     */
    fun setDndEnabled(enabled: Boolean) {
        _isDndEnabled.value = enabled
        prefs.edit().putBoolean("dnd_enabled", enabled).apply()
        val user = _currentUser.value ?: return
        val updated = user.copy(isDndEnabled = enabled)
        _currentUser.value = updated
        if (_isFirebaseConfigured.value && updated.phoneNumber.isNotBlank()) {
            FirebaseFirestore.getInstance().collection("users").document(updated.phoneNumber)
                .update("isDndEnabled", enabled)
                .addOnFailureListener {
                    try {
                        FirebaseFirestore.getInstance().collection("users").document(updated.phoneNumber)
                            .set(mapOf("isDndEnabled" to enabled), com.google.firebase.firestore.SetOptions.merge())
                    } catch (_: Exception) {}
                }
        }
    }

    fun isDndEnabled(): Boolean = _isDndEnabled.value

    /**
     * Adds a phone number to the user's blocked numbers list.
     */
    fun blockNumber(phoneNumber: String) {
        val clean = ContactsHelper.normalizePhoneNumber(phoneNumber)
        if (clean.isBlank()) return
        val currentSet = prefs.getStringSet("blocked_numbers", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (currentSet.add(clean)) {
            prefs.edit().putStringSet("blocked_numbers", currentSet).apply()
            val newList = currentSet.toList()
            _blockedNumbers.value = newList
            val user = _currentUser.value ?: return
            val updated = user.copy(blockedNumbers = newList)
            _currentUser.value = updated
            if (_isFirebaseConfigured.value && updated.phoneNumber.isNotBlank()) {
                FirebaseFirestore.getInstance().collection("users").document(updated.phoneNumber)
                    .update("blockedNumbers", newList)
                    .addOnFailureListener {
                        try {
                            FirebaseFirestore.getInstance().collection("users").document(updated.phoneNumber)
                                .set(mapOf("blockedNumbers" to newList), com.google.firebase.firestore.SetOptions.merge())
                        } catch (_: Exception) {}
                    }
            }
        }
    }

    /**
     * Removes a phone number from the user's blocked numbers list.
     */
    fun unblockNumber(phoneNumber: String) {
        val clean = ContactsHelper.normalizePhoneNumber(phoneNumber)
        val currentSet = prefs.getStringSet("blocked_numbers", emptySet())?.toMutableSet() ?: mutableSetOf()
        val toRemove = currentSet.filter { it == clean || ContactsHelper.numbersMatch(it, phoneNumber) }
        if (toRemove.isNotEmpty()) {
            currentSet.removeAll(toRemove.toSet())
            prefs.edit().putStringSet("blocked_numbers", currentSet).apply()
            val newList = currentSet.toList()
            _blockedNumbers.value = newList
            val user = _currentUser.value ?: return
            val updated = user.copy(blockedNumbers = newList)
            _currentUser.value = updated
            if (_isFirebaseConfigured.value && updated.phoneNumber.isNotBlank()) {
                FirebaseFirestore.getInstance().collection("users").document(updated.phoneNumber)
                    .update("blockedNumbers", newList)
                    .addOnFailureListener {
                        try {
                            FirebaseFirestore.getInstance().collection("users").document(updated.phoneNumber)
                                .set(mapOf("blockedNumbers" to newList), com.google.firebase.firestore.SetOptions.merge())
                        } catch (_: Exception) {}
                    }
            }
        }
    }

    /**
     * Checks if a phone number is blocked by the user.
     */
    fun isNumberBlocked(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val clean = ContactsHelper.normalizePhoneNumber(phoneNumber)
        return _blockedNumbers.value.any { it == clean || ContactsHelper.numbersMatch(it, phoneNumber) }
    }

    /**
     * Resolves rich details for a blocked number (display name, avatar, LKS status).
     */
    fun resolveBlockedContactInfo(phoneNumber: String): com.example.data.model.BlockedContactInfo {
        val lksUser = _registeredUsers.value.find { ContactsHelper.numbersMatch(it.phoneNumber, phoneNumber) }
        if (lksUser != null) {
            return com.example.data.model.BlockedContactInfo(
                phoneNumber = phoneNumber,
                displayName = lksUser.displayName.ifBlank { phoneNumber },
                profilePictureUrl = lksUser.profilePictureUrl,
                isLksUser = true,
                statusMessage = lksUser.statusMessage
            )
        }
        val nativeContact = _syncedContacts.value.find { ContactsHelper.numbersMatch(it.phoneNumber, phoneNumber) }
        if (nativeContact != null) {
            return com.example.data.model.BlockedContactInfo(
                phoneNumber = phoneNumber,
                displayName = nativeContact.name.ifBlank { phoneNumber },
                profilePictureUrl = nativeContact.profilePictureUrl,
                isLksUser = nativeContact.isVoiceLinkUser,
                statusMessage = nativeContact.statusMessage
            )
        }
        return com.example.data.model.BlockedContactInfo(
            phoneNumber = phoneNumber,
            displayName = phoneNumber,
            profilePictureUrl = "",
            isLksUser = false,
            statusMessage = ""
        )
    }

    fun updateFcmToken(token: String) {
        if (token.isBlank()) return

        // Always persist to SharedPreferences so token survives app restarts and reboots
        prefs.edit().putString("fcm_token", token).apply()

        val current = _currentUser.value
        if (current != null) {
            val updated = current.copy(fcmToken = token)
            _currentUser.value = updated
        }

        val phone = current?.phoneNumber ?: prefs.getString("user_phone", null) ?: return
        if (_isFirebaseConfigured.value && phone.isNotBlank()) {
            val db = FirebaseFirestore.getInstance()
            val tokenData = mapOf("fcmToken" to token, "lastSeen" to System.currentTimeMillis())
            db.collection("users")
                .document(phone)
                .set(tokenData, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "FCM token updated in Firestore for $phone")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update FCM token in Firestore: ${e.message}")
                }

            val cleanDigits = phone.replace(Regex("[^0-9]"), "")
            if (cleanDigits.isNotBlank() && cleanDigits != phone) {
                db.collection("users")
                    .document(cleanDigits)
                    .set(tokenData, com.google.firebase.firestore.SetOptions.merge())
            }
        }
    }

    fun fetchAndUpdateFcmToken() {
        if (_currentUser.value == null) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                updateFcmToken(token)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch FCM token: ${e.message}")
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

        val myNum = _currentUser.value?.phoneNumber
        if (_isFirebaseConfigured.value && !myNum.isNullOrBlank()) {
            FirebaseFirestore.getInstance().collection("users")
                .document(myNum)
                .collection("contacts")
                .document(newContact.id)
                .set(newContact)
                .addOnSuccessListener {
                    Log.d(TAG, "Contact added to Firestore.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to save contact to Firestore: ${e.message}")
                }
        }
    }

    /**
     * Records an active call start in local state and Firestore as soon as it's answered.
     * Guarantees that even if the app crashes, is force-killed, or loses power mid-call,
     * the call record exists with startedAt.
     */
    fun recordCallStarted(
        callId: String,
        direction: CallDirection,
        otherPartyNumber: String,
        otherPartyName: String,
        callType: CallType
    ) {
        val userPhone = _currentUser.value?.phoneNumber ?: return
        val logId = UUID.randomUUID().toString()
        activeCallLogIds[callId] = logId

        val initialLog = CallLogDto(
            id = logId,
            callId = callId,
            direction = direction,
            otherPartyNumber = otherPartyNumber,
            otherPartyName = otherPartyName,
            callType = callType,
            status = CallStatus.ANSWERED,
            startedAt = System.currentTimeMillis(),
            durationSeconds = 0
        )
        if (_callLogs.value.none { it.callId == callId }) {
            _callLogs.value = listOf(initialLog) + _callLogs.value
        }

        if (_isFirebaseConfigured.value) {
            FirebaseFirestore.getInstance()
                .collection("users").document(userPhone)
                .collection("callLogs").document(logId)
                .set(initialLog)
                .addOnSuccessListener {
                    Log.d(TAG, "Call started log written to Firestore: callId=$callId logId=$logId")
                }
        }
    }

    /**
     * Updates an active call log with final duration and status when the call ends,
     * or writes a new completed entry if it was ended before an active log was created.
     */
    fun recordCallEnded(
        callId: String,
        status: CallStatus,
        durationSeconds: Int,
        fallbackDirection: CallDirection? = null,
        fallbackOtherNumber: String? = null,
        fallbackOtherName: String? = null,
        fallbackCallType: CallType = CallType.AUDIO
    ) {
        val userPhone = _currentUser.value?.phoneNumber ?: return
        val logId = activeCallLogIds.remove(callId)

        if (logId != null) {
            _callLogs.value = _callLogs.value.map { log ->
                if (log.id == logId || log.callId == callId) {
                    log.copy(status = status, durationSeconds = durationSeconds)
                } else log
            }

            if (_isFirebaseConfigured.value) {
                FirebaseFirestore.getInstance()
                    .collection("users").document(userPhone)
                    .collection("callLogs").document(logId)
                    .update(
                        "status", status.name,
                        "durationSeconds", durationSeconds
                    )
                    .addOnSuccessListener {
                        Log.d(TAG, "Call ended log updated in Firestore: callId=$callId")
                    }
            }
        } else {
            // No prior started log exists (e.g. rejected, missed, cancelled before answer)
            if (fallbackDirection != null && fallbackOtherNumber != null) {
                logCall(
                    direction = fallbackDirection,
                    otherPartyNumber = fallbackOtherNumber,
                    otherPartyName = fallbackOtherName ?: fallbackOtherNumber,
                    callType = fallbackCallType,
                    status = status,
                    durationSeconds = durationSeconds,
                    callId = callId
                )
            }
        }
    }

    fun logCall(
        direction: CallDirection,
        otherPartyNumber: String,
        otherPartyName: String,
        callType: CallType,
        status: CallStatus,
        durationSeconds: Int,
        callId: String? = null
    ) {
        val userPhone = _currentUser.value?.phoneNumber ?: return
        val resolvedCallId = callId ?: "${userPhone}_${System.currentTimeMillis()}"

        // Deduplication: if already tracked in activeCallLogIds, update instead
        if (activeCallLogIds.containsKey(resolvedCallId)) {
            recordCallEnded(resolvedCallId, status, durationSeconds)
            return
        }

        // Deduplication: check if already in local list with same callId
        val existingIndex = _callLogs.value.indexOfFirst { it.callId == resolvedCallId }
        if (existingIndex >= 0) {
            val existingLog = _callLogs.value[existingIndex]
            val updated = existingLog.copy(status = status, durationSeconds = durationSeconds)
            val mutable = _callLogs.value.toMutableList()
            mutable[existingIndex] = updated
            _callLogs.value = mutable
            if (_isFirebaseConfigured.value) {
                FirebaseFirestore.getInstance()
                    .collection("users").document(userPhone)
                    .collection("callLogs").document(existingLog.id)
                    .update("status", status.name, "durationSeconds", durationSeconds)
            }
            return
        }

        val logId = UUID.randomUUID().toString()
        val newLog = CallLogDto(
            id = logId,
            callId = resolvedCallId,
            direction = direction,
            otherPartyNumber = otherPartyNumber,
            otherPartyName = otherPartyName,
            callType = callType,
            status = status,
            startedAt = System.currentTimeMillis(),
            durationSeconds = durationSeconds
        )
        _callLogs.value = listOf(newLog) + _callLogs.value

        if (_isFirebaseConfigured.value) {
            FirebaseFirestore.getInstance()
                .collection("users").document(userPhone)
                .collection("callLogs").document(logId)
                .set(newLog)
                .addOnSuccessListener {
                    Log.d(TAG, "Call log saved to Firestore for user $userPhone.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error saving call log to Firestore: ${e.message}")
                }
        }
    }


    fun logMissedCallForOfflineUser(
        calleeNumber: String,
        callerNumber: String,
        callerName: String,
        callType: CallType
    ) {
        val logId = UUID.randomUUID().toString()
        val newLog = CallLogDto(
            id = logId,
            callId = "${calleeNumber}_${System.currentTimeMillis()}",
            direction = CallDirection.MISSED,
            otherPartyNumber = callerNumber,
            otherPartyName = callerName,
            callType = callType,
            status = CallStatus.MISSED,
            startedAt = System.currentTimeMillis(),
            durationSeconds = 0
        )

        if (_isFirebaseConfigured.value) {
            FirebaseFirestore.getInstance()
                .collection("users").document(calleeNumber)
                .collection("callLogs").document(logId)
                .set(newLog)
                .addOnSuccessListener {
                    Log.d(TAG, "Offline missed call log saved for callee $calleeNumber.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error saving offline missed call to Firestore: ${e.message}")
                }
        }
    }

    fun clearCallLogs() {
        _callLogs.value = emptyList()

        val userPhone = _currentUser.value?.phoneNumber
        if (_isFirebaseConfigured.value && !userPhone.isNullOrBlank()) {
            val db = FirebaseFirestore.getInstance()
            db.collection("users").document(userPhone).collection("callLogs").get().addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    // Firestore batches have a 500-op limit: chunk into 450 per batch
                    snapshot.documents.chunked(450).forEach { chunk ->
                        val batch = db.batch()
                        for (doc in chunk) {
                            batch.delete(doc.reference)
                        }
                        batch.commit()
                    }
                }
            }
        }
    }

    fun logout() {
        usersListener?.remove()
        usersListener = null
        contactsListener?.remove()
        contactsListener = null
        callLogsListener?.remove()
        callLogsListener = null
        syncJob?.cancel()
        _currentUser.value = null
        _registeredUsers.value = emptyList()
        _syncedContacts.value = emptyList()
        _callLogs.value = emptyList()
        _contacts.value = emptyList()
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "FirebaseManager"
        @Volatile
        private var INSTANCE: FirebaseManager? = null

        const val PRESENCE_TIMEOUT_MS = 60_000L // 60s timeout for presence staleness

        /**
         * Checks if a user is truly online:
         * Must have isOnline == true AND lastSeen within the last 60 seconds.
         */
        fun isUserOnline(user: UserDto?): Boolean {
            if (user == null) return false
            if (!user.isOnline) return false
            if (user.lastSeen <= 0L) return false
            return (System.currentTimeMillis() - user.lastSeen) < PRESENCE_TIMEOUT_MS
        }

        /**
         * Formats lastSeen timestamp into human-readable WhatsApp-style label:
         * e.g. "online", "last seen today at 11:42 AM", "last seen yesterday at 9:15 PM"
         */
        fun formatLastSeen(lastSeenMs: Long): String {
            if (lastSeenMs <= 0L) return ""
            val diff = System.currentTimeMillis() - lastSeenMs
            if (diff < 60_000L) return "online"
            if (diff < 120_000L) return "last seen 1m ago"
            if (diff < 3600_000L) return "last seen ${diff / 60_000L}m ago"

            val calNow = java.util.Calendar.getInstance()
            val calSeen = java.util.Calendar.getInstance().apply { timeInMillis = lastSeenMs }
            val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(lastSeenMs))

            return if (calNow.get(java.util.Calendar.YEAR) == calSeen.get(java.util.Calendar.YEAR) &&
                calNow.get(java.util.Calendar.DAY_OF_YEAR) == calSeen.get(java.util.Calendar.DAY_OF_YEAR)) {
                "last seen today at $timeFormat"
            } else if (calNow.get(java.util.Calendar.YEAR) == calSeen.get(java.util.Calendar.YEAR) &&
                calNow.get(java.util.Calendar.DAY_OF_YEAR) - calSeen.get(java.util.Calendar.DAY_OF_YEAR) == 1) {
                "last seen yesterday at $timeFormat"
            } else {
                val dateFormat = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(lastSeenMs))
                "last seen $dateFormat at $timeFormat"
            }
        }

        fun getInstance(context: Context): FirebaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
