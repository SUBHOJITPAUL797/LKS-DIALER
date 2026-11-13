package com.example.ui.screens.chat

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.ChatMediaType
import com.example.data.repository.ChatRepository
import com.example.ui.theme.GreenCall
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.LocalThemeColor
import com.example.ui.theme.TealPrimary
import com.example.util.ContactsHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ScheduledChatMessage(
    val id: String,
    val recipientNumber: String,
    val recipientName: String,
    val payloadText: String,
    val previewText: String,
    val scheduledAtMillis: Long,
    val createdAtMillis: Long,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val status: String = "PENDING"
)

object ScheduledMessageManager {
    private const val TAG = "ScheduledMsgManager"
    private const val PREFS_NAME = "scheduled_chat_messages_prefs"
    private const val KEY_QUEUE_JSON = "scheduled_messages_queue_v1"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val watcherStarted = AtomicBoolean(false)
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val inFlightIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val _scheduledMessages = MutableStateFlow<List<ScheduledChatMessage>>(emptyList())
    val scheduledMessages: StateFlow<List<ScheduledChatMessage>> = _scheduledMessages.asStateFlow()

    fun ensureStarted(context: Context) {
        val appCtx = context.applicationContext
        if (watcherStarted.compareAndSet(false, true)) {
            loadFromPrefs(appCtx)
            com.example.util.logging.LksLogger.event(
                "SCHEDULED_MSG",
                "WATCHER_STARTED",
                mapOf("pendingCount" to _scheduledMessages.value.size)
            )
            registerNetworkRecoveryCallback(appCtx)
            scope.launch {
                while (isActive) {
                    try {
                        dispatchDueMessages(appCtx)
                    } catch (e: Exception) {
                        com.example.util.logging.LksLogger.w(TAG, "Scheduled message watcher loop error: ${e.message}", e)
                    }
                    delay(1_200L)
                }
            }
        }
    }

    private fun registerNetworkRecoveryCallback(context: Context) {
        if (!networkCallbackRegistered.compareAndSet(false, true)) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (_scheduledMessages.value.isNotEmpty()) {
                        scope.launch {
                            delay(350L)
                            try {
                                dispatchDueMessages(context, forceRetryDue = true)
                            } catch (_: Exception) {}
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback for scheduled messages: ${e.message}")
        }
    }

    @Synchronized
    private fun loadFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE_JSON, null)
            if (raw.isNullOrBlank()) {
                _scheduledMessages.value = emptyList()
                return
            }
            val arr = JSONArray(raw)
            val parsed = mutableListOf<ScheduledChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val recipientNumber = obj.optString("recipientNumber", "")
                val payloadText = obj.optString("payloadText", "")
                if (id.isBlank() || recipientNumber.isBlank() || payloadText.isBlank()) continue
                parsed.add(
                    ScheduledChatMessage(
                        id = id,
                        recipientNumber = recipientNumber,
                        recipientName = obj.optString("recipientName", recipientNumber),
                        payloadText = payloadText,
                        previewText = obj.optString("previewText", payloadText),
                        scheduledAtMillis = obj.optLong("scheduledAtMillis", 0L),
                        createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                        retryCount = obj.optInt("retryCount", 0),
                        lastError = obj.optString("lastError", "").ifBlank { null },
                        status = obj.optString("status", "PENDING").ifBlank { "PENDING" }
                    )
                )
            }
            _scheduledMessages.value = parsed.sortedBy { it.scheduledAtMillis }
        } catch (e: Exception) {
            com.example.util.logging.LksLogger.w(TAG, "Failed loading scheduled messages: ${e.message}", e)
        }
    }

    @Synchronized
    private fun persistToPrefs(context: Context, list: List<ScheduledChatMessage>) {
        try {
            val sorted = list.sortedBy { it.scheduledAtMillis }
            val arr = JSONArray()
            sorted.forEach { item ->
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("recipientNumber", item.recipientNumber)
                obj.put("recipientName", item.recipientName)
                obj.put("payloadText", item.payloadText)
                obj.put("previewText", item.previewText)
                obj.put("scheduledAtMillis", item.scheduledAtMillis)
                obj.put("createdAtMillis", item.createdAtMillis)
                obj.put("retryCount", item.retryCount)
                obj.put("lastError", item.lastError ?: "")
                obj.put("status", item.status)
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUEUE_JSON, arr.toString())
                .commit()
            _scheduledMessages.value = sorted
        } catch (e: Exception) {
            com.example.util.logging.LksLogger.w(TAG, "Failed saving scheduled messages: ${e.message}", e)
        }
    }

    fun scheduleMessage(
        context: Context,
        recipientNumber: String,
        recipientName: String,
        payloadText: String,
        previewText: String,
        scheduledAtMillis: Long,
        existingId: String? = null
    ): ScheduledChatMessage {
        val appCtx = context.applicationContext
        ensureStarted(appCtx)
        val normPeer = if (recipientNumber.startsWith("group_")) {
            recipientNumber
        } else {
            ContactsHelper.normalizePhoneNumber(recipientNumber)
        }
        val item = ScheduledChatMessage(
            id = existingId ?: UUID.randomUUID().toString(),
            recipientNumber = normPeer,
            recipientName = recipientName.ifBlank { normPeer },
            payloadText = payloadText,
            previewText = previewText.ifBlank { payloadText },
            scheduledAtMillis = scheduledAtMillis,
            createdAtMillis = System.currentTimeMillis(),
            retryCount = 0,
            lastError = null,
            status = "PENDING"
        )
        synchronized(this) {
            val updated = _scheduledMessages.value
                .filterNot { it.id == item.id } + item
            persistToPrefs(appCtx, updated)
        }
        com.example.util.logging.LksLogger.event(
            "SCHEDULED_MSG",
            if (existingId != null) "MESSAGE_RESCHEDULED" else "MESSAGE_SCHEDULED",
            mapOf(
                "id" to item.id,
                "recipient" to normPeer,
                "scheduledAt" to formatFriendlyScheduleTime(scheduledAtMillis)
            )
        )
        return item
    }

    fun cancelScheduledMessage(context: Context, id: String) {
        val appCtx = context.applicationContext
        synchronized(this) {
            val updated = _scheduledMessages.value.filterNot { it.id == id }
            persistToPrefs(appCtx, updated)
        }
        com.example.util.logging.LksLogger.event("SCHEDULED_MSG", "MESSAGE_CANCELLED", mapOf("id" to id))
    }

    @Synchronized
    private fun updateItemState(context: Context, updatedItem: ScheduledChatMessage) {
        val updated = _scheduledMessages.value.map { if (it.id == updatedItem.id) updatedItem else it }
        persistToPrefs(context.applicationContext, updated)
    }

    fun sendNow(context: Context, id: String) {
        val appCtx = context.applicationContext
        val target = _scheduledMessages.value.firstOrNull { it.id == id } ?: return
        if (!inFlightIds.add(id)) return
        updateItemState(appCtx, target.copy(status = "SENDING", lastError = null))
        scope.launch {
            try {
                val res = ChatRepository.getInstance(appCtx).sendMessage(
                    recipientNumber = target.recipientNumber,
                    recipientName = target.recipientName,
                    text = target.payloadText,
                    mediaType = ChatMediaType.TEXT
                )
                if (res.isSuccess) {
                    cancelScheduledMessage(appCtx, id)
                    com.example.util.logging.LksLogger.event(
                        "SCHEDULED_MSG",
                        "SEND_NOW_SUCCESS",
                        mapOf("id" to id, "recipient" to target.recipientNumber)
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "✅ Scheduled message sent to ${target.recipientName}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Delivery error"
                    val nextRetry = target.retryCount + 1
                    updateItemState(
                        appCtx,
                        target.copy(
                            retryCount = nextRetry,
                            lastError = err,
                            status = "RETRYING",
                            scheduledAtMillis = System.currentTimeMillis() + 4_000L
                        )
                    )
                    com.example.util.logging.LksLogger.errorEvent(
                        subsystem = "SCHEDULED_MSG",
                        errorName = "SEND_NOW_FAILED_WILL_RETRY",
                        details = mapOf("id" to id, "recipient" to target.recipientNumber, "retry" to nextRetry, "error" to err),
                        throwable = res.exceptionOrNull(),
                        autoUploadToCloud = true
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "Network busy — retrying scheduled message automatically...", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                val nextRetry = target.retryCount + 1
                updateItemState(
                    appCtx,
                    target.copy(
                        retryCount = nextRetry,
                        lastError = e.message ?: "Exception",
                        status = "RETRYING",
                        scheduledAtMillis = System.currentTimeMillis() + 5_000L
                    )
                )
                com.example.util.logging.LksLogger.errorEvent(
                    subsystem = "SCHEDULED_MSG",
                    errorName = "SEND_NOW_EXCEPTION",
                    details = mapOf("id" to id, "recipient" to target.recipientNumber, "retry" to nextRetry),
                    throwable = e,
                    autoUploadToCloud = true
                )
            } finally {
                inFlightIds.remove(id)
            }
        }
    }

    private suspend fun dispatchDueMessages(context: Context, forceRetryDue: Boolean = false) {
        val now = System.currentTimeMillis()
        val dueList = _scheduledMessages.value.filter {
            (it.scheduledAtMillis <= now || (forceRetryDue && it.status == "RETRYING")) && !inFlightIds.contains(it.id)
        }
        if (dueList.isEmpty()) return

        for (item in dueList) {
            if (!inFlightIds.add(item.id)) continue
            updateItemState(context, item.copy(status = "SENDING"))
            try {
                com.example.util.logging.LksLogger.event(
                    "SCHEDULED_MSG",
                    "AUTO_DISPATCH_ATTEMPT",
                    mapOf("id" to item.id, "recipient" to item.recipientNumber, "retryCount" to item.retryCount)
                )
                val sendResult = ChatRepository.getInstance(context).sendMessage(
                    recipientNumber = item.recipientNumber,
                    recipientName = item.recipientName,
                    text = item.payloadText,
                    mediaType = ChatMediaType.TEXT
                )
                if (sendResult.isSuccess) {
                    cancelScheduledMessage(context, item.id)
                    com.example.util.logging.LksLogger.event(
                        "SCHEDULED_MSG",
                        "AUTO_DISPATCH_SUCCESS",
                        mapOf("id" to item.id, "recipient" to item.recipientNumber, "retriesUsed" to item.retryCount)
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "⏰ Scheduled message sent to ${item.recipientName}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    val errMsg = sendResult.exceptionOrNull()?.message ?: "Send failed"
                    val nextRetry = item.retryCount + 1
                    val backoffMs = (nextRetry * 5_000L).coerceAtMost(60_000L)
                    updateItemState(
                        context,
                        item.copy(
                            retryCount = nextRetry,
                            lastError = errMsg,
                            status = "RETRYING",
                            scheduledAtMillis = System.currentTimeMillis() + backoffMs
                        )
                    )
                    com.example.util.logging.LksLogger.errorEvent(
                        subsystem = "SCHEDULED_MSG",
                        errorName = "AUTO_DISPATCH_RETRY_SCHEDULED",
                        details = mapOf(
                            "id" to item.id,
                            "recipient" to item.recipientNumber,
                            "retryCount" to nextRetry,
                            "backoffMs" to backoffMs,
                            "error" to errMsg
                        ),
                        throwable = sendResult.exceptionOrNull(),
                        autoUploadToCloud = (nextRetry >= 2)
                    )
                }
            } catch (e: Exception) {
                val nextRetry = item.retryCount + 1
                val backoffMs = (nextRetry * 5_000L).coerceAtMost(60_000L)
                updateItemState(
                    context,
                    item.copy(
                        retryCount = nextRetry,
                        lastError = e.message ?: "Unexpected error",
                        status = "RETRYING",
                        scheduledAtMillis = System.currentTimeMillis() + backoffMs
                    )
                )
                com.example.util.logging.LksLogger.errorEvent(
                    subsystem = "SCHEDULED_MSG",
                    errorName = "AUTO_DISPATCH_EXCEPTION",
                    details = mapOf("id" to item.id, "recipient" to item.recipientNumber, "retryCount" to nextRetry),
                    throwable = e,
                    autoUploadToCloud = true
                )
            } finally {
                inFlightIds.remove(item.id)
            }
        }
    }

    fun matchesPeer(itemPeer: String, targetPeer: String): Boolean {
        if (itemPeer == targetPeer) return true
        if (itemPeer.startsWith("group_") || targetPeer.startsWith("group_")) {
            return itemPeer == targetPeer
        }
        return ContactsHelper.numbersMatch(itemPeer, targetPeer)
    }

    fun formatFriendlyScheduleTime(targetMs: Long): String {
        val nowCal = Calendar.getInstance()
        val targetCal = Calendar.getInstance().apply { timeInMillis = targetMs }
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(targetMs))

        val isSameDay = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)
        if (isSameDay) return "Today at $timeFmt"

        val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val isTomorrow = tomorrowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
            tomorrowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)
        if (isTomorrow) return "Tomorrow at $timeFmt"

        val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(targetMs))
        return "$dateFmt • $timeFmt"
    }

    fun formatRelativeCountdown(targetMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffMs = (targetMs - nowMs).coerceAtLeast(0L)
        val totalSec = diffMs / 1000L
        if (totalSec == 0L) return "Sending now..."
        if (totalSec < 60L) return "in ${totalSec}s"
        val totalMin = totalSec / 60L
        if (totalMin < 60L) return "in ${totalMin}m"
        val hours = totalMin / 60L
        val mins = totalMin % 60L
        if (hours < 24L) {
            return if (mins > 0L) "in ${hours}h ${mins}m" else "in ${hours}h"
        }
        val days = hours / 24L
        val remHours = hours % 24L
        return if (remHours > 0L) "in ${days}d ${remHours}h" else "in ${days}d"
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 1. Live In-Chat Scheduled Message Strip (Appears above the Input Bar)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun ScheduledMessagesInChatBanner(
    scheduledList: List<ScheduledChatMessage>,
    onOpenQueueClick: () -> Unit,
    onSendNowClick: (ScheduledChatMessage) -> Unit
) {
    if (scheduledList.isEmpty()) return

    val themeColor = LocalThemeColor.current
    val isDark = LocalIsDarkTheme.current
    val nextMsg = scheduledList.first()

    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(scheduledList) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val countdownText = remember(nextMsg.scheduledAtMillis, nowTick) {
        ScheduledMessageManager.formatRelativeCountdown(nextMsg.scheduledAtMillis, nowTick)
    }
    val friendlyTime = remember(nextMsg.scheduledAtMillis) {
        ScheduledMessageManager.formatFriendlyScheduleTime(nextMsg.scheduledAtMillis)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenQueueClick),
        shape = RoundedCornerShape(18.dp),
        color = if (isDark) Color(0xFF192734) else Color(0xFFF0F7FF),
        border = BorderStroke(1.dp, themeColor.primary.copy(alpha = 0.35f)),
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(themeColor.primary, themeColor.primaryDark)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ScheduleSend,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (scheduledList.size == 1) "Scheduled • $friendlyTime"
                        else "${scheduledList.size} Scheduled • Next $friendlyTime",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.5.sp
                        ),
                        color = themeColor.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = themeColor.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = countdownText,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = themeColor.primary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = nextMsg.previewText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Surface(
                onClick = { onSendNowClick(nextMsg) },
                shape = RoundedCornerShape(12.dp),
                color = GreenCall.copy(alpha = 0.16f),
                border = BorderStroke(0.8.dp, GreenCall.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Now",
                        tint = GreenCall,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Send Now",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = GreenCall
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 2. Polished Schedule Message Date & Time Picker Dialog
// ──────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleMessageDialog(
    recipientName: String,
    initialMessageText: String,
    editingItem: ScheduledChatMessage? = null,
    onDismiss: () -> Unit,
    onConfirmSchedule: (messageText: String, scheduledAtMillis: Long, existingId: String?) -> Unit
) {
    val themeColor = LocalThemeColor.current
    val isDark = LocalIsDarkTheme.current
    val haptic = LocalHapticFeedback.current

    var messageText by remember(initialMessageText, editingItem) {
        mutableStateOf(editingItem?.previewText ?: initialMessageText)
    }

    // Default to +30 minutes from now (rounded to nearest 5 minutes)
    val initialTargetMs = remember(editingItem) {
        if (editingItem != null && editingItem.scheduledAtMillis > System.currentTimeMillis() + 30_000L) {
            editingItem.scheduledAtMillis
        } else {
            Calendar.getInstance().apply {
                add(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }

    var selectedTimestampMs by remember { mutableLongStateOf(initialTargetMs) }
    var showDatePickerModal by remember { mutableStateOf(false) }
    var showTimePickerModal by remember { mutableStateOf(false) }
    var useKeyboardTimeInput by remember { mutableStateOf(false) }

    val selectedCal = remember(selectedTimestampMs) {
        Calendar.getInstance().apply { timeInMillis = selectedTimestampMs }
    }

    val isFutureValid = selectedTimestampMs > System.currentTimeMillis() + 15_000L

    val dateLabel = remember(selectedTimestampMs) {
        val nowCal = Calendar.getInstance()
        val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val dayFmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date(selectedTimestampMs))
        when {
            nowCal.get(Calendar.YEAR) == selectedCal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) == selectedCal.get(Calendar.DAY_OF_YEAR) ->
                "Today • ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(selectedTimestampMs))}"
            tomorrowCal.get(Calendar.YEAR) == selectedCal.get(Calendar.YEAR) &&
                tomorrowCal.get(Calendar.DAY_OF_YEAR) == selectedCal.get(Calendar.DAY_OF_YEAR) ->
                "Tomorrow • ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(selectedTimestampMs))}"
            else -> dayFmt
        }
    }

    val timeLabel = remember(selectedTimestampMs) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(selectedTimestampMs))
    }

    val friendlySummary = remember(selectedTimestampMs) {
        ScheduledMessageManager.formatFriendlyScheduleTime(selectedTimestampMs)
    }

    val relativeCountdown = remember(selectedTimestampMs) {
        ScheduledMessageManager.formatRelativeCountdown(selectedTimestampMs)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 460.dp),
            shape = RoundedCornerShape(28.dp),
            color = if (isDark) Color(0xFF131D28) else Color(0xFFFBFDFF),
            tonalElevation = 8.dp,
            shadowElevation = 20.dp,
            border = BorderStroke(
                1.dp,
                if (isDark) Color.White.copy(alpha = 0.10f) else themeColor.primary.copy(alpha = 0.18f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // ── Top Header ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(themeColor.primary, themeColor.primaryDark)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.ScheduleSend,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (editingItem != null) "Reschedule Message" else "Schedule Message",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "To $recipientName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Message Text Input Box ──
                Text(
                    text = "MESSAGE TO SEND",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = {
                        Text(
                            "Type the message you want to schedule…",
                            fontSize = 13.5.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 76.dp, max = 140.dp),
                    shape = RoundedCornerShape(18.dp),
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = themeColor.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        focusedContainerColor = if (isDark) Color(0xFF1B2836) else Color(0xFFF3F6FA),
                        unfocusedContainerColor = if (isDark) Color(0xFF1B2836) else Color(0xFFF3F6FA)
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── Quick 1-Tap Smart Presets ──
                Text(
                    text = "QUICK SCHEDULE PRESETS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SchedulePresetChip(
                        label = "⚡ In 15 Mins",
                        accentColor = themeColor.primary,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTimestampMs = Calendar.getInstance().apply {
                                add(Calendar.MINUTE, 15)
                                set(Calendar.SECOND, 0)
                            }.timeInMillis
                        }
                    )
                    SchedulePresetChip(
                        label = "🕐 In 1 Hour",
                        accentColor = themeColor.primary,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTimestampMs = Calendar.getInstance().apply {
                                add(Calendar.HOUR_OF_DAY, 1)
                                set(Calendar.SECOND, 0)
                            }.timeInMillis
                        }
                    )
                    SchedulePresetChip(
                        label = "🌙 Tonight 9:00 PM",
                        accentColor = themeColor.primary,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val cal = Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, 21)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                            }
                            if (cal.timeInMillis <= System.currentTimeMillis() + 60_000L) {
                                cal.add(Calendar.DAY_OF_YEAR, 1)
                            }
                            selectedTimestampMs = cal.timeInMillis
                        }
                    )
                    SchedulePresetChip(
                        label = "☀️ Tomorrow 9:00 AM",
                        accentColor = themeColor.primary,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTimestampMs = Calendar.getInstance().apply {
                                add(Calendar.DAY_OF_YEAR, 1)
                                set(Calendar.HOUR_OF_DAY, 9)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                            }.timeInMillis
                        }
                    )
                    SchedulePresetChip(
                        label = "🌆 Tomorrow 6:00 PM",
                        accentColor = themeColor.primary,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTimestampMs = Calendar.getInstance().apply {
                                add(Calendar.DAY_OF_YEAR, 1)
                                set(Calendar.HOUR_OF_DAY, 18)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                            }.timeInMillis
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Custom Date & Time Selector Cards ──
                Text(
                    text = "CUSTOM DATE & TIME",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 1. Date Card with Calendar Picker + Quick Day Steppers
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showDatePickerModal = true
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = if (isDark) Color(0xFF1B2836) else Color(0xFFF3F6FA),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(themeColor.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.CalendarMonth,
                                    contentDescription = "Select Date",
                                    tint = themeColor.primary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Delivery Date",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = dateLabel,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SmallTimeAdjustButton(
                                label = "+1d",
                                accentColor = themeColor.primary,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTimestampMs = (selectedCal.clone() as Calendar).apply {
                                        add(Calendar.DAY_OF_YEAR, 1)
                                    }.timeInMillis
                                }
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = themeColor.primary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "Change",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = themeColor.primary,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Time Card with Clock Picker + Quick Minute/Hour Steppers
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showTimePickerModal = true
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = if (isDark) Color(0xFF1B2836) else Color(0xFFF3F6FA),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(themeColor.primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.AccessTime,
                                        contentDescription = "Select Time",
                                        tint = themeColor.primary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Delivery Time",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = timeLabel,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = themeColor.primary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "Pick Time",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = themeColor.primary,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Quick Fine-Tune Time Stepper Pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmallTimeAdjustButton(
                                label = "+5m",
                                accentColor = themeColor.primary,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTimestampMs = (selectedCal.clone() as Calendar).apply {
                                        add(Calendar.MINUTE, 5)
                                    }.timeInMillis
                                }
                            )
                            SmallTimeAdjustButton(
                                label = "+15m",
                                accentColor = themeColor.primary,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTimestampMs = (selectedCal.clone() as Calendar).apply {
                                        add(Calendar.MINUTE, 15)
                                    }.timeInMillis
                                }
                            )
                            SmallTimeAdjustButton(
                                label = "+30m",
                                accentColor = themeColor.primary,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTimestampMs = (selectedCal.clone() as Calendar).apply {
                                        add(Calendar.MINUTE, 30)
                                    }.timeInMillis
                                }
                            )
                            SmallTimeAdjustButton(
                                label = "+1h",
                                accentColor = themeColor.primary,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTimestampMs = (selectedCal.clone() as Calendar).apply {
                                        add(Calendar.HOUR_OF_DAY, 1)
                                    }.timeInMillis
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Live Schedule Confirmation Summary Banner ──
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isFutureValid) themeColor.primary.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    border = BorderStroke(
                        0.8.dp,
                        if (isFutureValid) themeColor.primary.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isFutureValid) Icons.Default.AutoAwesome else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = if (isFutureValid) themeColor.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isFutureValid) {
                                "Will auto-send $friendlySummary ($relativeCountdown)"
                            } else {
                                "Please pick a future time (at least 15 seconds ahead)"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isFutureValid) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ── Action Buttons ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(0.38f)
                            .height(48.dp)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            val trimmed = messageText.trim()
                            if (trimmed.isNotEmpty() && isFutureValid) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onConfirmSchedule(trimmed, selectedTimestampMs, editingItem?.id)
                            }
                        },
                        enabled = messageText.isNotBlank() && isFutureValid,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = themeColor.primary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(0.62f)
                            .height(48.dp)
                    ) {
                        Icon(
                            Icons.Default.ScheduleSend,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (editingItem != null) "Save Schedule" else "Schedule Send",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    // ── Material 3 DatePicker Modal ──
    if (showDatePickerModal) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedTimestampMs
        )
        DatePickerDialog(
            onDismissRequest = { showDatePickerModal = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pickedUtcMs = datePickerState.selectedDateMillis
                        if (pickedUtcMs != null) {
                            val utcCal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = pickedUtcMs
                            }
                            val updatedCal = (selectedCal.clone() as Calendar).apply {
                                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                            }
                            selectedTimestampMs = updatedCal.timeInMillis
                        }
                        showDatePickerModal = false
                    }
                ) {
                    Text("Confirm Date", fontWeight = FontWeight.Bold, color = themeColor.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerModal = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ── Material 3 TimePicker Modal (Dial + Keyboard Input Switch) ──
    if (showTimePickerModal) {
        val timePickerState = rememberTimePickerState(
            initialHour = selectedCal.get(Calendar.HOUR_OF_DAY),
            initialMinute = selectedCal.get(Calendar.MINUTE),
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { showTimePickerModal = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Delivery Time",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = { useKeyboardTimeInput = !useKeyboardTimeInput }) {
                        Icon(
                            imageVector = if (useKeyboardTimeInput) Icons.Default.AccessTime else Icons.Default.Keyboard,
                            contentDescription = "Switch input mode",
                            tint = themeColor.primary
                        )
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (useKeyboardTimeInput) {
                        TimeInput(state = timePickerState)
                    } else {
                        TimePicker(state = timePickerState)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updatedCal = (selectedCal.clone() as Calendar).apply {
                            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                            set(Calendar.MINUTE, timePickerState.minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        selectedTimestampMs = updatedCal.timeInMillis
                        showTimePickerModal = false
                    }
                ) {
                    Text("Set Time", fontWeight = FontWeight.Bold, color = themeColor.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePickerModal = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SchedulePresetChip(
    label: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = accentColor.copy(alpha = 0.12f),
        border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.32f))
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SmallTimeAdjustButton(
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(0.7.dp, accentColor.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 3. Scheduled Messages Queue Manager Dialog (View, Send Now, Edit, Cancel)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun ScheduledMessagesQueueDialog(
    recipientName: String,
    scheduledList: List<ScheduledChatMessage>,
    onDismiss: () -> Unit,
    onScheduleNewClick: () -> Unit,
    onEditItemClick: (ScheduledChatMessage) -> Unit,
    onSendNowClick: (ScheduledChatMessage) -> Unit,
    onCancelItemClick: (ScheduledChatMessage) -> Unit
) {
    val themeColor = LocalThemeColor.current
    val isDark = LocalIsDarkTheme.current

    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 460.dp)
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(28.dp),
            color = if (isDark) Color(0xFF131D28) else Color(0xFFFBFDFF),
            tonalElevation = 8.dp,
            shadowElevation = 20.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(themeColor.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.ScheduleSend,
                                contentDescription = null,
                                tint = themeColor.primary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Scheduled Messages (${scheduledList.size})",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Text(
                                text = "Auto-sends to $recipientName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (scheduledList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ScheduleSend,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No scheduled messages pending",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Schedule birthday wishes, reminders, or timed follow-ups.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(scheduledList, key = { it.id }) { item ->
                            val friendlyTime = ScheduledMessageManager.formatFriendlyScheduleTime(item.scheduledAtMillis)
                            val countdown = ScheduledMessageManager.formatRelativeCountdown(item.scheduledAtMillis, nowTick)

                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (isDark) Color(0xFF1B2836) else Color(0xFFF3F6FA),
                                border = BorderStroke(0.8.dp, themeColor.primary.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.AccessTime,
                                                contentDescription = null,
                                                tint = themeColor.primary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = friendlyTime,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = themeColor.primary
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = themeColor.primary.copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = countdown,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = themeColor.primary,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = item.previewText,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { onCancelItemClick(item) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Cancel",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Cancel",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        TextButton(
                                            onClick = { onEditItemClick(item) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.EditCalendar,
                                                contentDescription = "Reschedule",
                                                tint = themeColor.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Reschedule",
                                                fontSize = 12.sp,
                                                color = themeColor.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        Surface(
                                            onClick = { onSendNowClick(item) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = GreenCall
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.Send,
                                                    contentDescription = "Send Now",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Send Now",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onScheduleNewClick,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = themeColor.primary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(Icons.Default.AddAlarm, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Schedule New Message", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
