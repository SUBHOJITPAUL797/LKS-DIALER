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
        if (savedPhone != null) {
            val savedProfilePic = prefs.getString("user_profile_pic", "") ?: ""
            val user = UserDto(
                phoneNumber = savedPhone,
                displayName = savedName ?: "",
                statusMessage = savedStatus ?: "Available on LKS DIALER",
                profilePictureUrl = savedProfilePic,
                registeredDeviceId = savedDeviceId ?: "",
                isOnline = true,
                lastSeen = System.currentTimeMillis()
            )
            _currentUser.value = user

            if (_isFirebaseConfigured.value) {
                // CRITICAL: Always attach listeners and write user to Firestore on startup.
                // This ensures the user document exists in the DB so others can call them.
                attachUserSpecificListeners(savedPhone)
                Log.d(TAG, "Syncing restored user $savedPhone to Firestore on startup")
                FirebaseFirestore.getInstance().collection("users")
                    .document(savedPhone)
                    .set(user)
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

    private fun showMissedCallNotification(missedCall: CallLogDto) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "missed_call_channel",
                "Missed Calls",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = android.content.Intent(context, com.example.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(context, "missed_call_channel")
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle("Missed Call")
            .setContentText("Missed call from ${missedCall.otherPartyName.ifBlank { missedCall.otherPartyNumber }}")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(missedCall.callId.hashCode(), notification)
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

        val user = UserDto(
            phoneNumber = phoneNumber,
            displayName = name.ifBlank { existing?.displayName ?: "User ${phoneNumber.takeLast(4)}" },
            statusMessage = finalStatus,
            profilePictureUrl = existing?.profilePictureUrl ?: "",
            registeredDeviceId = finalDeviceId,
            isOnline = true,
            lastSeen = System.currentTimeMillis(),
            createdAt = existing?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        _currentUser.value = user
        
        prefs.edit()
            .putString("user_phone", user.phoneNumber)
            .putString("user_name", user.displayName)
            .putString("user_status", user.statusMessage)
            .putString("user_profile_pic", user.profilePictureUrl)
            .putString("device_id", user.registeredDeviceId)
            .apply()

        // Attach listeners for this user
        if (_isFirebaseConfigured.value) {
            attachUserSpecificListeners(phoneNumber)
        }

        // Sync with Firestore immediately
        if (_isFirebaseConfigured.value) {
            try {
                FirebaseFirestore.getInstance().collection("users")
                    .document(phoneNumber)
                    .set(user)
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
                .set(updated)
                .addOnSuccessListener {
                    Log.d(TAG, "Profile successfully updated in Firestore.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update profile in Firestore: ${e.message}")
                }
        }
    }

    /**
     * Updates the user's online presence and lastSeen timestamp in Firestore.
     * Called on app foreground (isOnline = true) and background (isOnline = false).
     */
    fun updateUserPresence(isOnline: Boolean) {
        val user = _currentUser.value ?: return
        if (user.phoneNumber.isBlank()) return
        val now = System.currentTimeMillis()
        _currentUser.value = user.copy(isOnline = isOnline, lastSeen = now)
        if (_isFirebaseConfigured.value) {
            val updates = mapOf<String, Any>(
                "isOnline" to isOnline,
                "online" to isOnline,
                "lastSeen" to now
            )
            FirebaseFirestore.getInstance().collection("users").document(user.phoneNumber)
                .update(updates)
                .addOnSuccessListener {
                    Log.d(TAG, "Presence updated: isOnline=$isOnline for ${user.phoneNumber}")
                }
                .addOnFailureListener {
                    try {
                        FirebaseFirestore.getInstance().collection("users").document(user.phoneNumber)
                            .set(updates, com.google.firebase.firestore.SetOptions.merge())
                    } catch (_: Exception) {}
                }
        }
    }

    fun updateFcmToken(token: String) {
        val current = _currentUser.value ?: return
        if (current.fcmToken == token) return
        
        val updated = current.copy(fcmToken = token)
        _currentUser.value = updated

        if (_isFirebaseConfigured.value && updated.phoneNumber.isNotBlank()) {
            // Use set with merge=true so this works even if the document doesn't exist yet
            val db = FirebaseFirestore.getInstance()
            db.collection("users")
                .document(updated.phoneNumber)
                .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "FCM token updated in Firestore for ${updated.phoneNumber}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update FCM token in Firestore: ${e.message}")
                }

            val cleanDigits = updated.phoneNumber.replace(Regex("[^0-9]"), "")
            if (cleanDigits.isNotBlank() && cleanDigits != updated.phoneNumber) {
                db.collection("users")
                    .document(cleanDigits)
                    .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
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

        fun getInstance(context: Context): FirebaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
