package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.LinkedDeviceDto
import com.example.data.model.QrSessionStatus
import com.example.data.model.UserDto
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
import kotlinx.coroutines.tasks.await
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

    private val _spamNumbers = MutableStateFlow<List<String>>(
        (prefs.getStringSet("spam_numbers", emptySet()) ?: emptySet()).toList()
    )
    val spamNumbers: StateFlow<List<String>> = _spamNumbers.asStateFlow()

    private val _linkedDevices = MutableStateFlow<List<LinkedDeviceDto>>(emptyList())
    val linkedDevices: StateFlow<List<LinkedDeviceDto>> = _linkedDevices.asStateFlow()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var syncJob: kotlinx.coroutines.Job? = null

    private var contactsListener: ListenerRegistration? = null
    private var callLogsListener: ListenerRegistration? = null
    private var linkedDevicesListener: ListenerRegistration? = null
    // BUG-14 FIX: Store reference so it can be removed if needed
    private var usersListener: ListenerRegistration? = null
    private val activeCallLogIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile
    private var lastSyncedCallerTuneSignature: String = ""

    init {
        // Step 1: Check if Firebase is properly configured first
        checkFirebaseAvailability()

        // Step 2: Restore the logged-in user from local storage
        val savedPhone = prefs.getString("user_phone", null)
        val savedName = prefs.getString("user_name", "")
        val savedDeviceId = prefs.getString("device_id", "")
        val savedStatus = prefs.getString("user_status", "Available on RB DIALER")?.replace("LKS DIALER", "RB DIALER")
        val savedFcmToken = prefs.getString("fcm_token", "") ?: ""
        if (savedPhone != null) {
            val savedProfilePic = prefs.getString("user_profile_pic", "") ?: ""
            val myPublicKey = com.example.data.crypto.ChatCryptoManager.getInstance(context).getMyPublicKeyBase64()
            val savedTune = com.example.util.LksCallerTuneManager.getMyCallerTune(context)
            val user = UserDto(
                phoneNumber = savedPhone,
                displayName = savedName ?: "",
                statusMessage = savedStatus ?: "Available on RB DIALER",
                profilePictureUrl = savedProfilePic,
                registeredDeviceId = savedDeviceId ?: "",
                fcmToken = savedFcmToken,
                isOnline = true,
                lastSeen = System.currentTimeMillis(),
                blockedNumbers = _blockedNumbers.value,
                isDndEnabled = _isDndEnabled.value,
                publicKey = myPublicKey,
                callerTuneTitle = savedTune.title,
                callerTuneArtist = savedTune.artist,
                callerTuneUrl = savedTune.audioUrl,
                callerTuneAudioBase64 = savedTune.audioBase64,
                callerTuneSource = savedTune.source,
                callerTuneStartMs = savedTune.startMs,
                callerTuneEndMs = savedTune.endMs
            )
            _currentUser.value = user
        syncCallerTuneToLocal(user)

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
                        syncCallerTuneToLocal(updated)
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
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        syncJob?.cancel()
        syncJob = scope.launch {
            try {
                // Debounce rapid snapshot emissions so only the latest contact sync runs
                delay(100L)
                val currentPhone = _currentUser.value?.phoneNumber ?: ""
                val localContacts = if (hasPermission) {
                    com.example.util.ContactsHelper.getLocalContacts(context).toMutableList()
                } else {
                    mutableListOf()
                }

                // Also incorporate any contacts the user explicitly created/saved inside LKS Dialer (_contacts)
                for (saved in _contacts.value) {
                    if (saved.phoneNumber.isNotBlank() && !localContacts.any { com.example.util.ContactsHelper.numbersMatch(it.phoneNumber, saved.phoneNumber) }) {
                        localContacts.add(
                            com.example.util.LocalContact(
                                name = saved.name.ifBlank { saved.phoneNumber },
                                phoneNumber = saved.phoneNumber,
                                normalizedNumber = com.example.util.ContactsHelper.normalizePhoneNumber(saved.phoneNumber)
                            )
                        )
                    }
                }

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

                // WhatsApp Privacy Model: ONLY show contacts saved in the user's phonebook/contacts.
                // NEVER dump random strangers from the registeredUsers database into the user's contact list!

                _syncedContacts.value = syncedList.distinctBy { it.phoneNumber }.sortedBy { it.name.lowercase() }
                _nonLksContacts.value = nonLksList.distinctBy { it.normalizedNumber }.sortedBy { it.name.lowercase() }
                Log.d(TAG, "Synced contacts: ${syncedList.size} on LKS, ${nonLksList.size} to invite (device contacts: ${localContacts.size})")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync device contacts", e)
            }
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
                val rawLogs = snapshot.toObjects(CallLogDto::class.java)
                val logs = rawLogs.map { log ->
                    val resolvedNum = log.resolveOtherPartyNumber()
                    val cleanCurrentNum = CallIdentitySanitizer.cleanNumber(log.otherPartyNumber)
                    val cleanCurrentName = CallIdentitySanitizer.cleanName(log.otherPartyName)
                    var effectiveNum = cleanCurrentNum.ifBlank { resolvedNum }
                    if (effectiveNum.isBlank() && log.startedAt > 0L) {
                        val adjacent = rawLogs.firstOrNull { other ->
                            other.id != log.id &&
                                other.resolveOtherPartyNumber().isNotBlank() &&
                                (
                                    kotlin.math.abs(other.startedAt - log.startedAt) <= 180_000L ||
                                        kotlin.math.abs((other.startedAt + other.durationSeconds * 1000L) - log.startedAt) <= 180_000L ||
                                        kotlin.math.abs((log.startedAt + log.durationSeconds * 1000L) - other.startedAt) <= 180_000L
                                    )
                        }
                        if (adjacent != null) {
                            effectiveNum = adjacent.resolveOtherPartyNumber()
                        }
                    }
                    val effectiveName = cleanCurrentName.ifBlank {
                        if (effectiveNum.isNotBlank()) resolveDisplayNameForNumber(effectiveNum) ?: effectiveNum else ""
                    }
                    if (effectiveNum.isBlank() && log.id.isNotBlank() && backfilledHistoricalLogIds.add(log.id)) {
                        scheduleCallLogIdentityBackfill(log.callId, log.id, phoneNumber, log.direction)
                    } else if (effectiveNum.isNotBlank() && CallIdentitySanitizer.isPlaceholderNumber(log.otherPartyNumber) && log.id.isNotBlank() && backfilledHistoricalLogIds.add(log.id)) {
                        db.collection("users").document(phoneNumber)
                            .collection("callLogs").document(log.id)
                            .update(
                                mapOf(
                                    "otherPartyNumber" to effectiveNum,
                                    "otherPartyName" to effectiveName.ifBlank { effectiveNum },
                                    "callerNumber" to (if (log.direction == CallDirection.INCOMING) effectiveNum else phoneNumber),
                                    "calleeNumber" to (if (log.direction == CallDirection.OUTGOING) effectiveNum else phoneNumber)
                                )
                            )
                    }
                    if (effectiveNum != log.otherPartyNumber || (effectiveName.isNotBlank() && effectiveName != log.otherPartyName)) {
                        log.copy(
                            otherPartyNumber = effectiveNum.ifBlank { log.otherPartyNumber },
                            otherPartyName = effectiveName.ifBlank { log.otherPartyName },
                            callerNumber = if (log.direction == CallDirection.INCOMING && effectiveNum.isNotBlank()) effectiveNum else log.callerNumber,
                            calleeNumber = if (log.direction == CallDirection.OUTGOING && effectiveNum.isNotBlank()) effectiveNum else log.calleeNumber
                        )
                    } else {
                        log
                    }
                }
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

        // 3. Sync Linked Devices for Web login
        linkedDevicesListener?.remove()
        linkedDevicesListener = db.collection("users").document(phoneNumber)
            .collection("linked_devices")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Linked devices snapshot error: ${e.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val devices = snapshot.documents.mapNotNull { doc ->
                    try {
                        val sessionId = doc.getString("sessionId") ?: doc.id
                        val deviceName = doc.getString("deviceName") ?: "Web Browser"
                        val userAgent = doc.getString("userAgent") ?: ""
                        val linkedAt = doc.getLong("linkedAt") ?: (doc.get("linkedAt") as? Number)?.toLong() ?: 0L
                        val lastActive = doc.getLong("lastActive") ?: (doc.get("lastActive") as? Number)?.toLong() ?: 0L
                        val revoked = doc.getBoolean("revoked") ?: false
                        if (!revoked && sessionId.isNotBlank()) {
                            LinkedDeviceDto(sessionId, deviceName, userAgent, linkedAt, lastActive, revoked)
                        } else null
                    } catch (ex: Exception) {
                        null
                    }
                }
                _linkedDevices.value = devices.sortedByDescending { it.linkedAt }
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

        // Message Action: directly opens chat with "Sorry, I missed your call"
        val messageIntent = android.content.Intent(context, com.example.MainActivity::class.java).apply {
            action = "com.example.ACTION_MESSAGE_MISSED_CALL_$notifId"
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("chat_peer_number", callerNumber)
            putExtra("chat_peer_name", resolvedName)
            putExtra("prefill_text", "Sorry, I missed your call")
            putExtra("notification_id", notifId)
        }
        val messagePendingIntent = android.app.PendingIntent.getActivity(
            context,
            notifId + 2,
            messageIntent,
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
            .addAction(
                android.R.drawable.sym_action_chat,
                "Message",
                messagePendingIntent
            )
            .build()

        notificationManager.notify(notifId, notification)
    }

    fun showMissedCallNotification(missedCall: CallLogDto) {
        showMissedCallNotification(
            callerNumber = missedCall.resolveOtherPartyNumber(),
            callerName = missedCall.resolveOtherPartyName(),
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
                    val variations = ContactsHelper.generateNumberVariations(cleanNumber)
                    for (variant in variations) {
                        try {
                            val doc = db.collection("users").document(variant).get().await()
                            val user = doc.toObject(UserDto::class.java)
                            if (user != null && user.phoneNumber.isNotBlank()) {
                                val list = _registeredUsers.value.toMutableList()
                                if (list.none { it.phoneNumber == user.phoneNumber }) {
                                    list.add(user)
                                    _registeredUsers.value = list
                                }
                                break
                            }
                        } catch (_: Exception) {}
                    }
                    pendingLookups.remove(cleanNumber)
                } catch (_: Exception) {
                    pendingLookups.remove(cleanNumber)
                }
            }
        }
        return null
    }

    fun verifyAndRegisterNumber(phoneNumber: String, currentDeviceId: String): RegisterResult {
        val existingUser = lookupUserByNumber(phoneNumber)
        return resolveRegistrationForUser(existingUser, phoneNumber, currentDeviceId)
    }

    suspend fun verifyAndRegisterNumberAsync(phoneNumber: String, currentDeviceId: String): RegisterResult {
        var existingUser = lookupUserByNumber(phoneNumber)
        if (existingUser == null && _isFirebaseConfigured.value) {
            val cleanNumber = ContactsHelper.normalizePhoneNumber(phoneNumber)
            val variations = ContactsHelper.generateNumberVariations(cleanNumber)
            val db = FirebaseFirestore.getInstance()
            existingUser = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                var fetched: UserDto? = null
                for (variant in variations) {
                    try {
                        val doc = db.collection("users").document(variant).get().await()
                        val user = doc.toObject(UserDto::class.java)
                        if (user != null && user.phoneNumber.isNotBlank()) {
                            fetched = user
                            val list = _registeredUsers.value.toMutableList()
                            if (list.none { it.phoneNumber == user.phoneNumber }) {
                                list.add(user)
                                _registeredUsers.value = list
                            }
                            break
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Exception) {}
                }
                fetched
            }
        }
        return resolveRegistrationForUser(existingUser, phoneNumber, currentDeviceId)
    }

    private fun resolveRegistrationForUser(
        existingUser: UserDto?,
        phoneNumber: String,
        currentDeviceId: String
    ): RegisterResult {
        return if (existingUser != null) {
            val registeredDev = existingUser.registeredDeviceId
            if (registeredDev.isBlank() || registeredDev == currentDeviceId) {
                val targetPhone = existingUser.phoneNumber.ifBlank { phoneNumber }
                loginWithPhone(
                    phoneNumber = targetPhone,
                    name = existingUser.displayName,
                    deviceId = currentDeviceId,
                    status = existingUser.statusMessage
                )
                val loggedInUser = _currentUser.value ?: existingUser.copy(
                    registeredDeviceId = currentDeviceId,
                    isOnline = true,
                    lastSeen = System.currentTimeMillis()
                )
                RegisterResult.Success(loggedInUser)
            } else {
                RegisterResult.DeviceBlocked(phoneNumber, registeredDev)
            }
        } else {
            Log.d(TAG, "User $phoneNumber not found — treating as NewUser for registration")
            RegisterResult.NewUser(phoneNumber)
        }
    }

    fun loginWithPhone(phoneNumber: String, name: String, deviceId: String = "", status: String = "") {
        val existing = lookupUserByNumber(phoneNumber)
        val finalDeviceId = deviceId.ifBlank { existing?.registeredDeviceId ?: "DEV-${UUID.randomUUID().toString().take(8)}" }
        // BUG-24 FIX: Use the provided status, fall back to existing/default
        val finalStatus = status.ifBlank { existing?.statusMessage ?: "Available on RB DIALER" }.replace("LKS DIALER", "RB DIALER")

        val savedFcmToken = prefs.getString("fcm_token", "") ?: ""
        val finalFcmToken = existing?.fcmToken?.ifBlank { savedFcmToken } ?: savedFcmToken

        val myPublicKey = com.example.data.crypto.ChatCryptoManager.getInstance(context).getMyPublicKeyBase64()
        val localTune = com.example.util.LksCallerTuneManager.getMyCallerTune(context)
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
            publicKey = myPublicKey,
            callerTuneTitle = existing?.callerTuneTitle?.ifBlank { localTune.title } ?: localTune.title,
            callerTuneArtist = existing?.callerTuneArtist?.ifBlank { localTune.artist } ?: localTune.artist,
            callerTuneUrl = existing?.callerTuneUrl?.ifBlank { localTune.audioUrl } ?: localTune.audioUrl,
            callerTuneAudioBase64 = existing?.callerTuneAudioBase64?.ifBlank { localTune.audioBase64 } ?: localTune.audioBase64,
            callerTuneSource = existing?.callerTuneSource ?: localTune.source,
            callerTuneStartMs = existing?.callerTuneStartMs?.takeIf { it > 0 } ?: localTune.startMs,
            callerTuneEndMs = existing?.callerTuneEndMs?.takeIf { it > 0 } ?: localTune.endMs
        )
        _currentUser.value = user
        syncCallerTuneToLocal(user)
        
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

        // Attach listeners for this user (and re-attach global users listener if logged out previously)
        if (_isFirebaseConfigured.value) {
            if (usersListener == null) {
                listenToFirestoreUsers()
            }
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

    private fun syncCallerTuneToLocal(user: UserDto) {
        val signature = "${user.phoneNumber}|${user.callerTuneTitle}|${user.callerTuneUrl}|${user.callerTuneStartMs}|${user.callerTuneEndMs}|${user.callerTuneAudioBase64.length}|${user.callerTuneAudioBase64.hashCode()}"
        if (signature == lastSyncedCallerTuneSignature) return
        lastSyncedCallerTuneSignature = signature

        scope.launch {
            try {
                val local = com.example.util.LksCallerTuneManager.getMyCallerTune(context)
                if (user.callerTuneTitle.isNotBlank() && (user.callerTuneAudioBase64.isNotBlank() || user.callerTuneUrl.isNotBlank())) {
                    val targetFile = java.io.File(context.filesDir, "my_caller_tune.mp3")
                    var targetValid = targetFile.exists() && com.example.util.LksCallerTuneManager.isValidAudioFile(targetFile)
                    if (targetFile.exists() && !targetValid) {
                        // Delete broken cached file once so future checks don't keep invoking NuMediaExtractor
                        try { targetFile.delete() } catch (_: Exception) {}
                    }

                    if (user.callerTuneAudioBase64.isNotBlank() && !com.example.util.LksCallerTuneManager.isBase64KnownInvalid(context, user.callerTuneAudioBase64)) {
                        val needFileWrite = !targetValid ||
                                local.title != user.callerTuneTitle ||
                                local.audioBase64 != user.callerTuneAudioBase64

                        if (needFileWrite) {
                            try {
                                val cleanBase64 = if (user.callerTuneAudioBase64.contains(",")) {
                                    user.callerTuneAudioBase64.substringAfter(",")
                                } else {
                                    user.callerTuneAudioBase64
                                }
                                val decoded = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                                if (decoded.size > 2048) {
                                    val verifyFile = java.io.File(context.cacheDir, "sync_b64_tune_${System.currentTimeMillis()}.mp3")
                                    java.io.FileOutputStream(verifyFile).use { it.write(decoded) }
                                    if (com.example.util.LksCallerTuneManager.isValidAudioFile(verifyFile)) {
                                        verifyFile.copyTo(targetFile, overwrite = true)
                                        targetValid = true
                                        Log.i(TAG, "Decoded and saved caller tune to my_caller_tune.mp3 (${decoded.size} bytes)")
                                    } else {
                                        com.example.util.LksCallerTuneManager.markBase64Invalid(context, user.callerTuneAudioBase64)
                                        Log.w(TAG, "Decoded caller tune base64 failed validation")
                                    }
                                    try { verifyFile.delete() } catch (_: Exception) {}
                                } else {
                                    com.example.util.LksCallerTuneManager.markBase64Invalid(context, user.callerTuneAudioBase64)
                                }
                            } catch (be: Exception) {
                                Log.w(TAG, "Failed decoding caller tune from Base64: ${be.message}")
                            }
                        }
                    }

                    val resolvedLocalUri = if (targetValid) {
                        android.net.Uri.fromFile(targetFile).toString()
                    } else {
                        local.localUri
                    }

                    val needsSync = local.title != user.callerTuneTitle ||
                        local.audioUrl != user.callerTuneUrl ||
                        (local.audioBase64.isBlank() && user.callerTuneAudioBase64.isNotBlank()) ||
                        local.startMs != user.callerTuneStartMs ||
                        local.localUri != resolvedLocalUri

                    if (needsSync) {
                        com.example.util.LksCallerTuneManager.saveMyCallerTune(
                            context,
                            com.example.util.CallerTuneInfo(
                                title = user.callerTuneTitle,
                                artist = user.callerTuneArtist,
                                audioUrl = user.callerTuneUrl,
                                audioBase64 = user.callerTuneAudioBase64,
                                source = user.callerTuneSource,
                                startMs = user.callerTuneStartMs,
                                endMs = user.callerTuneEndMs,
                                localUri = resolvedLocalUri,
                                thumbnailUrl = local.thumbnailUrl
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed syncing caller tune from Firestore to local prefs: ${e.message}")
            }
        }
    }

    fun updateCallerTune(
        title: String,
        artist: String,
        audioUrl: String,
        audioBase64: String,
        source: String,
        startMs: Long = 0L,
        endMs: Long = 30000L
    ) {
        val current = _currentUser.value ?: return
        val updated = current.copy(
            callerTuneTitle = title,
            callerTuneArtist = artist,
            callerTuneUrl = audioUrl,
            callerTuneAudioBase64 = audioBase64,
            callerTuneSource = source,
            callerTuneStartMs = startMs,
            callerTuneEndMs = endMs
        )
        _currentUser.value = updated

        // CRITICAL: Always persist to SharedPreferences so FCM handler can read it
        // when the phone is in background/killed. FCM cannot access in-memory state.
        val targetFile = java.io.File(context.filesDir, "my_caller_tune.mp3")
        val localUri = if (targetFile.exists() && targetFile.length() > 0) android.net.Uri.fromFile(targetFile).toString() else null

        com.example.util.LksCallerTuneManager.saveMyCallerTune(
            context,
            com.example.util.CallerTuneInfo(
                title = title,
                artist = artist,
                audioUrl = audioUrl,
                audioBase64 = audioBase64,
                source = source,
                startMs = startMs,
                endMs = endMs,
                localUri = localUri
            )
        )
        Log.i(TAG, "Caller Tune saved to SharedPreferences: $title")

        if (_isFirebaseConfigured.value && updated.phoneNumber.isNotBlank()) {
            val updates = mapOf(
                "callerTuneTitle" to title,
                "callerTuneArtist" to artist,
                "callerTuneUrl" to audioUrl,
                "callerTuneAudioBase64" to audioBase64,
                "callerTuneSource" to source,
                "callerTuneStartMs" to startMs,
                "callerTuneEndMs" to endMs
            )
            FirebaseFirestore.getInstance().collection("users")
                .document(updated.phoneNumber)
                .set(updates, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "Caller Tune synced to Firestore: $title")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to sync Caller Tune to Firestore: ${e.message}")
                }
        }
    }

    fun removeCallerTune() {
        val current = _currentUser.value ?: return
        val updated = current.copy(
            callerTuneTitle = "",
            callerTuneArtist = "",
            callerTuneUrl = "",
            callerTuneAudioBase64 = "",
            callerTuneSource = "DEFAULT",
            callerTuneStartMs = 0L,
            callerTuneEndMs = 0L
        )
        _currentUser.value = updated

        // CRITICAL: Clear SharedPreferences so FCM handler won't attach a stale tune
        com.example.util.LksCallerTuneManager.removeMyCallerTune(context)
        Log.i(TAG, "Caller Tune removed from SharedPreferences")

        if (_isFirebaseConfigured.value && updated.phoneNumber.isNotBlank()) {
            val updates = mapOf(
                "callerTuneTitle" to "",
                "callerTuneArtist" to "",
                "callerTuneUrl" to "",
                "callerTuneAudioBase64" to "",
                "callerTuneSource" to "DEFAULT",
                "callerTuneStartMs" to 0L,
                "callerTuneEndMs" to 0L
            )
            FirebaseFirestore.getInstance().collection("users")
                .document(updated.phoneNumber)
                .set(updates, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "Caller Tune removed from Firestore")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to remove Caller Tune from Firestore: ${e.message}")
                }
        }
    }

    enum class PresenceState {
        OFFLINE,
        ONLINE_HEARTBEAT,
        ONLINE_TRANSFER_HOLD
    }

    private val _presenceState = MutableStateFlow(PresenceState.OFFLINE)
    val presenceState: StateFlow<PresenceState> = _presenceState.asStateFlow()

    private var presenceHeartbeatJob: kotlinx.coroutines.Job? = null

    /**
     * Starts the periodic presence heartbeat while app is in foreground.
     * Refreshes presence every 25 seconds so peers know this user is actively online.
     */
    fun startPresenceHeartbeat() {
        presenceHeartbeatJob?.cancel()
        _presenceState.value = PresenceState.ONLINE_HEARTBEAT
        updateUserPresence(true)
        presenceHeartbeatJob = scope.launch {
            while (true) {
                delay(25_000L)
                updateUserPresence(true)
            }
        }
    }

    /**
     * Stops presence heartbeat and immediately marks user as offline in Firestore,
     * unless an active file transfer requires preserving ONLINE_TRANSFER_HOLD.
     */
    fun stopPresenceHeartbeat() {
        // If there is an active file transfer running in background, DO NOT mark user offline!
        try {
            if (com.example.data.repository.ChatRepository.getInstance(context).hasActiveTransfers()) {
                _presenceState.value = PresenceState.ONLINE_TRANSFER_HOLD
                Log.d(TAG, "Transfer active in background — transitioning to ONLINE_TRANSFER_HOLD")
                return
            }
        } catch (_: Exception) {}
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
        _presenceState.value = PresenceState.OFFLINE
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
     * Checks if a phone number is blocked or flagged as spam by the user.
     */
    fun isNumberBlocked(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val clean = ContactsHelper.normalizePhoneNumber(phoneNumber)
        return _blockedNumbers.value.any { it == clean || ContactsHelper.numbersMatch(it, phoneNumber) } ||
               _spamNumbers.value.any { it == clean || ContactsHelper.numbersMatch(it, phoneNumber) }
    }

    /**
     * Checks if a phone number is specifically flagged as spam.
     */
    fun isNumberSpam(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val clean = ContactsHelper.normalizePhoneNumber(phoneNumber)
        return _spamNumbers.value.any { it == clean || ContactsHelper.numbersMatch(it, phoneNumber) }
    }

    /**
     * Marks a number as spam, blocks it from future calls/messages, and reports to community database.
     */
    fun reportSpam(phoneNumber: String, reason: String = "Spam / Telemarketer") {
        if (phoneNumber.isBlank()) return
        val clean = ContactsHelper.normalizePhoneNumber(phoneNumber)
        val currentSet = prefs.getStringSet("spam_numbers", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (currentSet.add(clean)) {
            prefs.edit().putStringSet("spam_numbers", currentSet).apply()
            val newList = currentSet.toList()
            _spamNumbers.value = newList
            blockNumber(phoneNumber)
            if (_isFirebaseConfigured.value) {
                try {
                    val reportData = mapOf(
                        "reportedNumber" to clean,
                        "reporterPhone" to (_currentUser.value?.phoneNumber ?: ""),
                        "reason" to reason,
                        "timestamp" to System.currentTimeMillis()
                    )
                    FirebaseFirestore.getInstance().collection("spam_reports").add(reportData)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Removes a phone number from the spam list.
     */
    fun unmarkSpam(phoneNumber: String) {
        val clean = ContactsHelper.normalizePhoneNumber(phoneNumber)
        val currentSet = prefs.getStringSet("spam_numbers", emptySet())?.toMutableSet() ?: mutableSetOf()
        val toRemove = currentSet.filter { it == clean || ContactsHelper.numbersMatch(it, phoneNumber) }
        if (toRemove.isNotEmpty()) {
            currentSet.removeAll(toRemove.toSet())
            prefs.edit().putStringSet("spam_numbers", currentSet).apply()
            _spamNumbers.value = currentSet.toList()
        }
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
            statusMessage = if (registered) "Available" else "Not on RB DIALER yet"
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

    fun getEffectiveUserPhone(): String? {
        val phone = _currentUser.value?.phoneNumber?.ifBlank { null }
        if (phone != null) return phone
        val dialerPhone = prefs.getString("user_phone", null)?.ifBlank { null }
        if (dialerPhone != null) return dialerPhone
        val sp = context.getSharedPreferences("lks_prefs", Context.MODE_PRIVATE)
        return sp.getString("user_phone", null)?.ifBlank { null }
    }

    private fun resolveDisplayNameForNumber(number: String): String? {
        val clean = CallIdentitySanitizer.cleanNumber(number)
        if (clean.isBlank()) return null
        val contactName = _syncedContacts.value
            .find { ContactsHelper.numbersMatch(it.phoneNumber, clean) }
            ?.name
            ?.let { CallIdentitySanitizer.cleanName(it) }
            ?.ifBlank { null }
        if (contactName != null) return contactName
        return lookupUserByNumber(clean)
            ?.displayName
            ?.let { CallIdentitySanitizer.cleanName(it) }
            ?.ifBlank { null }
    }

    private fun extractPhoneFromCallId(callId: String): String {
        val candidate = callId.substringBefore('_').trim()
        return if (candidate.matches(Regex("^[+]?[0-9]{7,15}$"))) candidate else ""
    }

    private val backfilledHistoricalLogIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun resolveIdentityFromBubble(callId: String): Pair<String, String> {
        val bubble = com.example.services.FloatingCallBubbleService.bubbleState.value
        val bubbleNum = if (bubble.activeCallId == callId) CallIdentitySanitizer.cleanNumber(bubble.activeCallerNumber) else ""
        val bubbleName = if (bubble.activeCallId == callId) CallIdentitySanitizer.cleanName(bubble.activeCallerName) else ""
        val engine = com.example.webrtc.WebRtcEngine.getInstanceIfCreated()
        val cachedNum = bubbleNum.ifBlank {
            if (callId.isNotBlank()) engine?.resolveCallerNumberForCall(callId).orEmpty() else ""
        }
        val cachedName = bubbleName.ifBlank {
            if (callId.isNotBlank()) engine?.resolveCallerNameForCall(callId, null, cachedNum).orEmpty() else ""
        }
        return cachedNum to cachedName
    }

    private fun scheduleCallLogIdentityBackfill(
        callId: String,
        logId: String,
        userPhone: String,
        direction: CallDirection
    ) {
        if (!_isFirebaseConfigured.value || callId.isBlank() || callId.startsWith("sim_")) return
        scope.launch {
            for (waitMs in longArrayOf(0L, 1200L, 2500L)) {
                if (waitMs > 0L) delay(waitMs)
                val currentLog = _callLogs.value.find { it.id == logId || it.callId == callId }
                if (currentLog != null &&
                    !CallIdentitySanitizer.isPlaceholderNumber(currentLog.otherPartyNumber) &&
                    !CallIdentitySanitizer.isPlaceholderName(currentLog.otherPartyName)
                ) return@launch

                try {
                    val doc = FirebaseFirestore.getInstance().collection("calls").document(callId).get().await()
                    if (!doc.exists()) continue
                    val cCaller = CallIdentitySanitizer.cleanNumber(doc.getString("callerNumber"))
                    val cCallee = CallIdentitySanitizer.cleanNumber(doc.getString("calleeNumber"))
                    val cCallerName = CallIdentitySanitizer.cleanName(doc.getString("callerName"))
                    val cCalleeName = CallIdentitySanitizer.cleanName(doc.getString("calleeName"))
                    val isOutgoing = direction == CallDirection.OUTGOING
                    val resolvedNumber = if (isOutgoing) cCallee.ifBlank { cCaller } else cCaller.ifBlank { cCallee }
                    if (resolvedNumber.isBlank()) continue
                    val resolvedName = (if (isOutgoing) cCalleeName.ifBlank { cCallerName } else cCallerName.ifBlank { cCalleeName })
                        .ifBlank { resolveDisplayNameForNumber(resolvedNumber) ?: resolvedNumber }

                    var patchedLogId = logId
                    _callLogs.value = _callLogs.value.map { entry ->
                        if (entry.id == logId || entry.callId == callId) {
                            patchedLogId = entry.id
                            val bestNum = CallIdentitySanitizer.cleanNumber(entry.otherPartyNumber).ifBlank { resolvedNumber }
                            val bestName = CallIdentitySanitizer.cleanName(entry.otherPartyName).ifBlank { resolvedName }
                            entry.copy(
                                otherPartyNumber = bestNum,
                                otherPartyName = bestName,
                                callerNumber = if (direction == CallDirection.INCOMING) bestNum else userPhone,
                                calleeNumber = if (direction == CallDirection.OUTGOING) bestNum else userPhone
                            )
                        } else entry
                    }
                    FirebaseFirestore.getInstance()
                        .collection("users").document(userPhone)
                        .collection("callLogs").document(patchedLogId)
                        .update(
                            mapOf(
                                "otherPartyNumber" to resolvedNumber,
                                "otherPartyName" to resolvedName,
                                "callerNumber" to (if (direction == CallDirection.INCOMING) resolvedNumber else userPhone),
                                "calleeNumber" to (if (direction == CallDirection.OUTGOING) resolvedNumber else userPhone)
                            )
                        )
                    return@launch
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {}
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
        val userPhone = getEffectiveUserPhone() ?: return

        val (bubbleNum, bubbleName) = resolveIdentityFromBubble(callId)
        val finalNumber = CallIdentitySanitizer.cleanNumber(otherPartyNumber)
            .ifBlank { bubbleNum }
            .ifBlank { extractPhoneFromCallId(callId) }

        var finalName = CallIdentitySanitizer.cleanName(otherPartyName)
            .ifBlank { bubbleName }

        if (finalNumber.isNotBlank() && (finalName.isBlank() || finalName == finalNumber)) {
            finalName = resolveDisplayNameForNumber(finalNumber) ?: finalNumber
        }

        val resolvedNumberOrPlaceholder = finalNumber.ifBlank { "Unknown" }
        val resolvedNameOrPlaceholder = finalName.ifBlank {
            if (resolvedNumberOrPlaceholder != "Unknown") resolvedNumberOrPlaceholder else "Unknown Caller"
        }

        val existingLog = _callLogs.value.find { it.callId == callId }
        val existingLogId = activeCallLogIds[callId] ?: existingLog?.id

        if (existingLogId != null) {
            activeCallLogIds[callId] = existingLogId
            if (existingLog != null) {
                val curNum = CallIdentitySanitizer.cleanNumber(existingLog.otherPartyNumber)
                val curName = CallIdentitySanitizer.cleanName(existingLog.otherPartyName)
                val upgradedNum = curNum.ifBlank { finalNumber }
                val upgradedName = curName.ifBlank { finalName }
                if ((upgradedNum.isNotBlank() && upgradedNum != curNum) || (upgradedName.isNotBlank() && upgradedName != curName)) {
                    val outNum = upgradedNum.ifBlank { existingLog.otherPartyNumber.ifBlank { "Unknown" } }
                    val outName = upgradedName.ifBlank { existingLog.otherPartyName.ifBlank { "Unknown Caller" } }
                    _callLogs.value = _callLogs.value.map {
                        if (it.id == existingLogId || it.callId == callId) {
                            it.copy(
                                otherPartyNumber = outNum,
                                otherPartyName = outName,
                                callerNumber = if (direction == CallDirection.INCOMING && outNum != "Unknown") outNum else it.callerNumber,
                                calleeNumber = if (direction == CallDirection.OUTGOING && outNum != "Unknown") outNum else it.calleeNumber
                            )
                        } else it
                    }
                    if (_isFirebaseConfigured.value) {
                        val updates = mutableMapOf<String, Any>()
                        if (outNum != "Unknown") {
                            updates["otherPartyNumber"] = outNum
                            updates[if (direction == CallDirection.INCOMING) "callerNumber" else "calleeNumber"] = outNum
                        }
                        if (outName != "Unknown Caller") {
                            updates["otherPartyName"] = outName
                        }
                        if (updates.isNotEmpty()) {
                            FirebaseFirestore.getInstance()
                                .collection("users").document(userPhone)
                                .collection("callLogs").document(existingLogId)
                                .update(updates)
                        }
                    }
                }
            }
            if (finalNumber.isBlank() || finalName.isBlank()) {
                scheduleCallLogIdentityBackfill(callId, existingLogId, userPhone, direction)
            }
            return
        }

        val logId = UUID.randomUUID().toString()
        activeCallLogIds[callId] = logId

        val initialLog = CallLogDto(
            id = logId,
            callId = callId,
            direction = direction,
            otherPartyNumber = resolvedNumberOrPlaceholder,
            otherPartyName = resolvedNameOrPlaceholder,
            callType = callType,
            status = CallStatus.ANSWERED,
            startedAt = System.currentTimeMillis(),
            durationSeconds = 0,
            callerNumber = if (direction == CallDirection.INCOMING) resolvedNumberOrPlaceholder else userPhone,
            calleeNumber = if (direction == CallDirection.OUTGOING) resolvedNumberOrPlaceholder else userPhone
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

        if (resolvedNumberOrPlaceholder == "Unknown" || resolvedNameOrPlaceholder == "Unknown Caller") {
            scheduleCallLogIdentityBackfill(callId, logId, userPhone, direction)
        }
    }

    /**
     * Records a cellular call placed via SIM card so it appears in recent call logs.
     */
    fun recordCellularCall(otherPartyNumber: String, otherPartyName: String, simDisplayName: String) {
        val userPhone = getEffectiveUserPhone() ?: return
        val logId = UUID.randomUUID().toString()
        val cellularCallId = "sim_${UUID.randomUUID().toString().take(8)}"

        val resolvedName = if (otherPartyName.isNotBlank() && otherPartyName != otherPartyNumber) {
            otherPartyName
        } else {
            lookupUserByNumber(otherPartyNumber)?.displayName ?: otherPartyNumber
        }

        val log = CallLogDto(
            id = logId,
            callId = cellularCallId,
            direction = CallDirection.OUTGOING,
            otherPartyNumber = otherPartyNumber.ifBlank { "Unknown" },
            otherPartyName = "$resolvedName ($simDisplayName)",
            callType = CallType.AUDIO,
            status = CallStatus.ANSWERED,
            startedAt = System.currentTimeMillis(),
            durationSeconds = 0,
            callerNumber = userPhone,
            calleeNumber = otherPartyNumber
        )
        _callLogs.value = listOf(log) + _callLogs.value.filter { it.id != logId }

        if (_isFirebaseConfigured.value) {
            try {
                FirebaseFirestore.getInstance()
                    .collection("users").document(userPhone)
                    .collection("callLogs").document(logId)
                    .set(log)
            } catch (_: Exception) {}
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
        fallbackCallType: CallType = CallType.AUDIO,
        recordingPath: String? = null
    ) {
        val userPhone = getEffectiveUserPhone() ?: return
        val logId = activeCallLogIds.remove(callId)
            ?: _callLogs.value.find { it.callId == callId }?.id
        val (bubbleNum, bubbleName) = resolveIdentityFromBubble(callId)

        if (logId != null) {
            var updatedNumber = ""
            var updatedName = ""
            var effectiveDirection = fallbackDirection ?: CallDirection.INCOMING

            _callLogs.value = _callLogs.value.map { log ->
                if (log.id == logId || log.callId == callId) {
                    effectiveDirection = log.direction
                    val existingCleanNum = CallIdentitySanitizer.cleanNumber(log.otherPartyNumber)
                        .ifBlank { CallIdentitySanitizer.cleanNumber(log.resolveOtherPartyNumber()) }
                    val fallbackCleanNum = CallIdentitySanitizer.cleanNumber(fallbackOtherNumber)
                        .ifBlank { bubbleNum }
                        .ifBlank { extractPhoneFromCallId(callId) }

                    val resolvedNumber = existingCleanNum
                        .ifBlank { fallbackCleanNum }
                        .ifBlank { "Unknown" }

                    val existingCleanName = CallIdentitySanitizer.cleanName(log.otherPartyName)
                    val fallbackCleanName = CallIdentitySanitizer.cleanName(fallbackOtherName)
                        .ifBlank { bubbleName }

                    val lookedUpName = if (resolvedNumber != "Unknown") resolveDisplayNameForNumber(resolvedNumber) else null
                    val resolvedName = existingCleanName
                        .ifBlank { fallbackCleanName }
                        .ifBlank { lookedUpName.orEmpty() }
                        .ifBlank { if (resolvedNumber != "Unknown") resolvedNumber else "Unknown Caller" }

                    updatedNumber = resolvedNumber
                    updatedName = resolvedName

                    log.copy(
                        status = status,
                        durationSeconds = maxOf(log.durationSeconds, durationSeconds),
                        otherPartyNumber = resolvedNumber,
                        otherPartyName = resolvedName,
                        callerNumber = if (log.direction == CallDirection.INCOMING && resolvedNumber != "Unknown") resolvedNumber else log.callerNumber,
                        calleeNumber = if (log.direction == CallDirection.OUTGOING && resolvedNumber != "Unknown") resolvedNumber else log.calleeNumber,
                        recordingPath = recordingPath ?: log.recordingPath
                    )
                } else log
            }

            if (_isFirebaseConfigured.value) {
                val updates = mutableMapOf<String, Any>(
                    "status" to status.name,
                    "durationSeconds" to durationSeconds
                )
                if (!recordingPath.isNullOrBlank()) {
                    updates["recordingPath"] = recordingPath
                }
                if (updatedNumber.isNotBlank() && !CallIdentitySanitizer.isPlaceholderNumber(updatedNumber)) {
                    updates["otherPartyNumber"] = updatedNumber
                    updates[if (effectiveDirection == CallDirection.INCOMING) "callerNumber" else "calleeNumber"] = updatedNumber
                }
                if (updatedName.isNotBlank() && !CallIdentitySanitizer.isPlaceholderName(updatedName)) {
                    updates["otherPartyName"] = updatedName
                }
                FirebaseFirestore.getInstance()
                    .collection("users").document(userPhone)
                    .collection("callLogs").document(logId)
                    .update(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "Call ended log updated in Firestore: callId=$callId")
                    }
            }
            if (updatedNumber == "Unknown" || updatedName == "Unknown Caller") {
                scheduleCallLogIdentityBackfill(callId, logId, userPhone, effectiveDirection)
            }
        } else {
            // No prior started log exists (e.g. rejected, missed, cancelled before answer)
            if (fallbackDirection != null) {
                val effNumber = CallIdentitySanitizer.cleanNumber(fallbackOtherNumber)
                    .ifBlank { bubbleNum }
                    .ifBlank { extractPhoneFromCallId(callId) }
                var effName = CallIdentitySanitizer.cleanName(fallbackOtherName)
                    .ifBlank { bubbleName }

                if (effNumber.isNotBlank()) {
                    if (effName.isBlank() || effName == effNumber) {
                        effName = resolveDisplayNameForNumber(effNumber) ?: effNumber
                    }
                    logCall(
                        direction = fallbackDirection,
                        otherPartyNumber = effNumber,
                        otherPartyName = effName,
                        callType = fallbackCallType,
                        status = status,
                        durationSeconds = durationSeconds,
                        callId = callId,
                        recordingPath = recordingPath
                    )
                } else if (_isFirebaseConfigured.value && callId.isNotBlank()) {
                    FirebaseFirestore.getInstance().collection("calls").document(callId).get()
                        .addOnSuccessListener { doc ->
                            if (doc != null && doc.exists()) {
                                val cCaller = CallIdentitySanitizer.cleanNumber(doc.getString("callerNumber"))
                                val cCallee = CallIdentitySanitizer.cleanNumber(doc.getString("calleeNumber"))
                                val cCallerName = CallIdentitySanitizer.cleanName(doc.getString("callerName"))
                                val cCalleeName = CallIdentitySanitizer.cleanName(doc.getString("calleeName"))
                                val isOutgoing = fallbackDirection == CallDirection.OUTGOING
                                val fetchedNumber = if (isOutgoing) cCallee.ifBlank { cCaller } else cCaller.ifBlank { cCallee }
                                val fetchedName = (if (isOutgoing) cCalleeName.ifBlank { cCallerName } else cCallerName.ifBlank { cCalleeName })
                                    .ifBlank { effName }
                                    .ifBlank { resolveDisplayNameForNumber(fetchedNumber) ?: fetchedNumber }
                                logCall(
                                    direction = fallbackDirection,
                                    otherPartyNumber = fetchedNumber.ifBlank { "Unknown" },
                                    otherPartyName = fetchedName.ifBlank { "Unknown Caller" },
                                    callType = fallbackCallType,
                                    status = status,
                                    durationSeconds = durationSeconds,
                                    callId = callId,
                                    recordingPath = recordingPath
                                )
                            } else {
                                logCall(
                                    direction = fallbackDirection,
                                    otherPartyNumber = "Unknown",
                                    otherPartyName = effName.ifBlank { "Unknown Caller" },
                                    callType = fallbackCallType,
                                    status = status,
                                    durationSeconds = durationSeconds,
                                    callId = callId,
                                    recordingPath = recordingPath
                                )
                            }
                        }
                        .addOnFailureListener {
                            logCall(
                                direction = fallbackDirection,
                                otherPartyNumber = "Unknown",
                                otherPartyName = effName.ifBlank { "Unknown Caller" },
                                callType = fallbackCallType,
                                status = status,
                                durationSeconds = durationSeconds,
                                callId = callId,
                                recordingPath = recordingPath
                            )
                        }
                } else {
                    logCall(
                        direction = fallbackDirection,
                        otherPartyNumber = "Unknown",
                        otherPartyName = effName.ifBlank { "Unknown Caller" },
                        callType = fallbackCallType,
                        status = status,
                        durationSeconds = durationSeconds,
                        callId = callId,
                        recordingPath = recordingPath
                    )
                }
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
        callId: String? = null,
        recordingPath: String? = null
    ) {
        val userPhone = getEffectiveUserPhone() ?: return
        val resolvedCallId = callId ?: "${userPhone}_${System.currentTimeMillis()}"

        // Deduplication: if already tracked in activeCallLogIds, update instead and preserve peer identity
        if (activeCallLogIds.containsKey(resolvedCallId)) {
            recordCallEnded(
                callId = resolvedCallId,
                status = status,
                durationSeconds = durationSeconds,
                fallbackDirection = direction,
                fallbackOtherNumber = otherPartyNumber,
                fallbackOtherName = otherPartyName,
                fallbackCallType = callType,
                recordingPath = recordingPath
            )
            return
        }

        val (bubbleNum, bubbleName) = resolveIdentityFromBubble(resolvedCallId)
        val finalNumber = CallIdentitySanitizer.cleanNumber(otherPartyNumber)
            .ifBlank { bubbleNum }
            .ifBlank { extractPhoneFromCallId(resolvedCallId) }
            .ifBlank { "Unknown" }

        var finalName = CallIdentitySanitizer.cleanName(otherPartyName)
            .ifBlank { bubbleName }

        if (finalName.isBlank() || finalName == finalNumber) {
            val lookedUp = if (finalNumber != "Unknown") resolveDisplayNameForNumber(finalNumber) else null
            finalName = lookedUp ?: if (finalNumber != "Unknown") finalNumber else finalName.ifBlank { "Unknown Caller" }
        }

        // Deduplication: check if already in local list with same callId
        val existingIndex = _callLogs.value.indexOfFirst { it.callId == resolvedCallId }
        if (existingIndex >= 0) {
            val existingLog = _callLogs.value[existingIndex]
            val existingNum = CallIdentitySanitizer.cleanNumber(existingLog.otherPartyNumber)
                .ifBlank { CallIdentitySanitizer.cleanNumber(existingLog.resolveOtherPartyNumber()) }
            val resolvedNum = existingNum
                .ifBlank { CallIdentitySanitizer.cleanNumber(finalNumber) }
                .ifBlank { "Unknown" }
            val existingNm = CallIdentitySanitizer.cleanName(existingLog.otherPartyName)
            val resolvedNm = existingNm
                .ifBlank { CallIdentitySanitizer.cleanName(finalName) }
                .ifBlank { if (resolvedNum != "Unknown") resolveDisplayNameForNumber(resolvedNum) ?: resolvedNum else "Unknown Caller" }
            val updated = existingLog.copy(
                status = status,
                durationSeconds = maxOf(existingLog.durationSeconds, durationSeconds),
                otherPartyNumber = resolvedNum,
                otherPartyName = resolvedNm,
                callerNumber = if (existingLog.direction == CallDirection.INCOMING && resolvedNum != "Unknown") resolvedNum else existingLog.callerNumber,
                calleeNumber = if (existingLog.direction == CallDirection.OUTGOING && resolvedNum != "Unknown") resolvedNum else existingLog.calleeNumber,
                recordingPath = recordingPath ?: existingLog.recordingPath
            )
            val mutable = _callLogs.value.toMutableList()
            mutable[existingIndex] = updated
            _callLogs.value = mutable
            if (_isFirebaseConfigured.value) {
                val updates = mutableMapOf<String, Any>(
                    "status" to status.name,
                    "durationSeconds" to updated.durationSeconds
                )
                if (resolvedNum.isNotBlank() && !CallIdentitySanitizer.isPlaceholderNumber(resolvedNum)) {
                    updates["otherPartyNumber"] = resolvedNum
                    updates[if (existingLog.direction == CallDirection.INCOMING) "callerNumber" else "calleeNumber"] = resolvedNum
                }
                if (resolvedNm.isNotBlank() && !CallIdentitySanitizer.isPlaceholderName(resolvedNm)) {
                    updates["otherPartyName"] = resolvedNm
                }
                if (!recordingPath.isNullOrBlank()) {
                    updates["recordingPath"] = recordingPath
                }
                FirebaseFirestore.getInstance()
                    .collection("users").document(userPhone)
                    .collection("callLogs").document(existingLog.id)
                    .update(updates)
            }
            if (resolvedNum == "Unknown" || resolvedNm == "Unknown Caller") {
                scheduleCallLogIdentityBackfill(resolvedCallId, existingLog.id, userPhone, existingLog.direction)
            }
            return
        }

        val logId = UUID.randomUUID().toString()
        val newLog = CallLogDto(
            id = logId,
            callId = resolvedCallId,
            direction = direction,
            otherPartyNumber = finalNumber,
            otherPartyName = finalName,
            callType = callType,
            status = status,
            startedAt = System.currentTimeMillis(),
            durationSeconds = durationSeconds,
            recordingPath = recordingPath,
            callerNumber = if (direction == CallDirection.INCOMING) finalNumber else userPhone,
            calleeNumber = if (direction == CallDirection.OUTGOING) finalNumber else userPhone
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

        if (finalNumber == "Unknown" || finalName == "Unknown Caller") {
            scheduleCallLogIdentityBackfill(resolvedCallId, logId, userPhone, direction)
        }
    }

    /**
     * Attaches or updates a local audio recording path to an existing call log.
     */
    fun attachRecordingToCallLog(callId: String, recordingPath: String) {
        if (recordingPath.isBlank()) return
        val current = _callLogs.value
        val index = current.indexOfFirst { it.callId == callId }
        if (index != -1) {
            val updated = current[index].copy(recordingPath = recordingPath)
            val mutable = current.toMutableList()
            mutable[index] = updated
            _callLogs.value = mutable

            val userPhone = _currentUser.value?.phoneNumber ?: return
            if (_isFirebaseConfigured.value) {
                FirebaseFirestore.getInstance()
                    .collection("users").document(userPhone)
                    .collection("callLogs").document(updated.id)
                    .update("recordingPath", recordingPath)
                    .addOnSuccessListener {
                        Log.d(TAG, "Recording path updated in Firestore for callId: $callId")
                    }
            }
        }
    }


    fun logMissedCallForOfflineUser(
        calleeNumber: String,
        callerNumber: String,
        callerName: String,
        callType: CallType,
        callId: String? = null
    ) {
        if (calleeNumber.isBlank()) return

        var effCallerNumber = CallIdentitySanitizer.cleanNumber(callerNumber)
        if (effCallerNumber.isBlank()) {
            effCallerNumber = CallIdentitySanitizer.cleanNumber(getEffectiveUserPhone())
        }
        val cleanCallerName = CallIdentitySanitizer.cleanName(callerName)

        fun saveLog(num: String, name: String) {
            val validNumber = CallIdentitySanitizer.cleanNumber(num).ifBlank { "Unknown" }
            val validName = CallIdentitySanitizer.cleanName(name).ifBlank {
                if (validNumber != "Unknown") resolveDisplayNameForNumber(validNumber) ?: validNumber
                else "Unknown Caller"
            }

            val logId = UUID.randomUUID().toString()
            val resolvedCallId = callId ?: "${calleeNumber}_${System.currentTimeMillis()}"
            val newLog = CallLogDto(
                id = logId,
                callId = resolvedCallId,
                direction = CallDirection.MISSED,
                otherPartyNumber = validNumber,
                otherPartyName = validName,
                callType = callType,
                status = CallStatus.MISSED,
                startedAt = System.currentTimeMillis(),
                durationSeconds = 0,
                callerNumber = validNumber,
                calleeNumber = calleeNumber
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

        if (effCallerNumber.isBlank() && !callId.isNullOrBlank() && _isFirebaseConfigured.value) {
            FirebaseFirestore.getInstance().collection("calls").document(callId).get()
                .addOnSuccessListener { doc ->
                    val fromDoc = CallIdentitySanitizer.cleanNumber(doc?.getString("callerNumber")).ifBlank {
                        CallIdentitySanitizer.cleanNumber(doc?.getString("calleeNumber"))
                    }
                    val fromDocName = CallIdentitySanitizer.cleanName(doc?.getString("callerName"))
                    saveLog(fromDoc, fromDocName.ifBlank { cleanCallerName })
                }
                .addOnFailureListener {
                    saveLog("", cleanCallerName)
                }
        } else {
            saveLog(effCallerNumber, cleanCallerName)
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
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
        try {
            updateUserPresence(false)
        } catch (_: Exception) {}
        usersListener?.remove()
        usersListener = null
        contactsListener?.remove()
        contactsListener = null
        callLogsListener?.remove()
        callLogsListener = null
        linkedDevicesListener?.remove()
        linkedDevicesListener = null
        _linkedDevices.value = emptyList()
        syncJob?.cancel()
        _currentUser.value = null
        _registeredUsers.value = emptyList()
        _syncedContacts.value = emptyList()
        _callLogs.value = emptyList()
        _contacts.value = emptyList()
        prefs.edit().clear().apply()
    }

    /**
     * Manually fetches the current list of non-revoked linked devices from Firestore.
     */
    fun refreshLinkedDevices() {
        val phone = _currentUser.value?.phoneNumber ?: return
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(phone)
            .collection("linked_devices")
            .get()
            .addOnSuccessListener { snapshot ->
                val devices = snapshot.documents.mapNotNull { doc ->
                    try {
                        val sessionId = doc.getString("sessionId") ?: doc.id
                        val deviceName = doc.getString("deviceName") ?: "Web Browser"
                        val userAgent = doc.getString("userAgent") ?: ""
                        val linkedAt = doc.getLong("linkedAt") ?: (doc.get("linkedAt") as? Number)?.toLong() ?: 0L
                        val lastActive = doc.getLong("lastActive") ?: (doc.get("lastActive") as? Number)?.toLong() ?: 0L
                        val revoked = doc.getBoolean("revoked") ?: false
                        if (!revoked && sessionId.isNotBlank()) {
                            LinkedDeviceDto(sessionId, deviceName, userAgent, linkedAt, lastActive, revoked)
                        } else null
                    } catch (ex: Exception) {
                        null
                    }
                }
                _linkedDevices.value = devices.sortedByDescending { it.linkedAt }
                Log.d(TAG, "Refreshed linked devices: ${devices.size} active")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to refresh linked devices: ${e.message}")
            }
    }

    /**
     * Unlinks a device remotely from the phone, which causes the Web app to log out immediately.
     */
    fun unlinkDevice(sessionId: String, onComplete: ((Boolean) -> Unit)? = null) {
        val phone = _currentUser.value?.phoneNumber ?: run {
            onComplete?.invoke(false)
            return
        }
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(phone)
            .collection("linked_devices").document(sessionId)
            .update("revoked", true)
            .addOnSuccessListener {
                _linkedDevices.value = _linkedDevices.value.filter { it.sessionId != sessionId }
                onComplete?.invoke(true)
            }
            .addOnFailureListener {
                // Try deleting if update fails
                db.collection("users").document(phone)
                    .collection("linked_devices").document(sessionId)
                    .delete()
                    .addOnCompleteListener { task ->
                        _linkedDevices.value = _linkedDevices.value.filter { it.sessionId != sessionId }
                        onComplete?.invoke(task.isSuccessful)
                    }
            }
    }

    /**
     * Verifies the scanned QR code payload and returns session details for user confirmation.
     */
    fun verifyScannedQrCode(
        rawQrContent: String,
        onValidSession: (sessionId: String, nonce: String, deviceName: String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val json = org.json.JSONObject(rawQrContent)
            val type = json.optString("type")
            if (type != "LKS_QR_LOGIN") {
                onError("Invalid QR code. Please scan the QR code displayed on LKS Dialer Web.")
                return
            }
            val sessionId = json.optString("sessionId")
            val nonce = json.optString("nonce")
            if (sessionId.isBlank() || nonce.isBlank()) {
                onError("Invalid QR code format.")
                return
            }

            val db = FirebaseFirestore.getInstance()
            db.collection("qr_sessions").document(sessionId).get()
                .addOnSuccessListener { snap ->
                    if (!snap.exists()) {
                        onError("Session not found or expired. Please refresh the QR code on your computer.")
                        return@addOnSuccessListener
                    }
                    val sessionStatus = QrSessionStatus.fromWire(snap.getString("status"))
                    val expectedNonce = snap.getString("nonce") ?: ""
                    val expiresAt = snap.getLong("expiresAt") ?: 0L
                    val deviceName = snap.getString("deviceName") ?: "Web Browser"

                    if (sessionStatus != QrSessionStatus.PENDING && sessionStatus != QrSessionStatus.SCANNED) {
                        onError("This QR code has already been used or expired.")
                        return@addOnSuccessListener
                    }
                    if (expectedNonce != nonce) {
                        onError("Security validation failed (nonce mismatch).")
                        return@addOnSuccessListener
                    }
                    if (System.currentTimeMillis() > expiresAt) {
                        snap.reference.update("status", QrSessionStatus.EXPIRED.name)
                        onError("QR code has expired. Please refresh it on your screen.")
                        return@addOnSuccessListener
                    }

                    // Mark as SCANNED so the web client shows visual feedback
                    snap.reference.update("status", QrSessionStatus.SCANNED.name)

                    onValidSession(sessionId, nonce, deviceName)
                }
                .addOnFailureListener { e ->
                    onError("Failed to check QR session: ${e.message}")
                }
        } catch (e: Exception) {
            onError("Unsupported QR code format: ${e.message}")
        }
    }

    /**
     * Approves the QR login session and grants access to the web client.
     */
    fun approveQrSession(
        sessionId: String,
        nonce: String,
        deviceName: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = _currentUser.value
        if (user == null || user.phoneNumber.isBlank()) {
            onError("You must be logged in to link a device.")
            return
        }

        val db = FirebaseFirestore.getInstance()
        val now = System.currentTimeMillis()

        // 1. Update qr_sessions/{sessionId} to APPROVED with user credentials
        val approvalPayload = mapOf(
            "status" to QrSessionStatus.APPROVED.name,
            "approvedAt" to now,
            "approvedByPhone" to user.phoneNumber,
            "userPhone" to user.phoneNumber,
            "userName" to user.displayName,
            "userProfilePic" to user.profilePictureUrl,
            "userStatus" to user.statusMessage,
            "deviceName" to deviceName
        )

        db.collection("qr_sessions").document(sessionId)
            .update(approvalPayload)
            .addOnSuccessListener {
                // 2. Record linked device in users/{phone}/linked_devices/{sessionId}
                val linkedDevice = LinkedDeviceDto(
                    sessionId = sessionId,
                    deviceName = deviceName,
                    userAgent = "",
                    linkedAt = now,
                    lastActive = now,
                    revoked = false
                )
                db.collection("users").document(user.phoneNumber)
                    .collection("linked_devices").document(sessionId)
                    .set(linkedDevice)
                    .addOnCompleteListener {
                        _linkedDevices.value = (listOf(linkedDevice) + _linkedDevices.value.filter { it.sessionId != sessionId }).sortedByDescending { it.linkedAt }
                        onSuccess()
                    }
            }
            .addOnFailureListener { e ->
                onError("Failed to approve session: ${e.message}")
            }
    }

    companion object {
        private const val TAG = "FirebaseManager"
        @Volatile
        private var INSTANCE: FirebaseManager? = null

        const val PRESENCE_TIMEOUT_MS = 40_000L // 40s timeout for presence staleness

        /**
         * Checks if a user is truly online:
         * Must have isOnline == true AND lastSeen within the last 40 seconds.
         */
        fun isUserOnline(user: UserDto?): Boolean {
            if (user == null) return false
            if (!user.isOnline) return false
            if (user.lastSeen <= 0L) return false
            return (System.currentTimeMillis() - user.lastSeen) < PRESENCE_TIMEOUT_MS
        }

        /**
         * Formats lastSeen timestamp into human-readable WhatsApp-style label:
         * e.g. "last seen just now", "last seen 1m ago", "last seen today at 11:42 AM"
         */
        fun formatLastSeen(lastSeenMs: Long): String {
            if (lastSeenMs <= 0L) return ""
            val diff = System.currentTimeMillis() - lastSeenMs
            if (diff < 60_000L) return "last seen just now"
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
