package com.example.util

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log

/**
 * LksRingtoneManager
 * Industry-grade ringtone manager supporting:
 * 1. Global App Default Ringtone (System Ringtone or Local Audio Song File)
 * 2. Per-Contact Custom Ringtone (Resolved by phone number variations)
 * 3. In-App Preview Playback (MediaPlayer / Ringtone)
 * 4. Fallback to System Default Ringtone
 */
object LksRingtoneManager {
    private const val TAG = "LksRingtoneManager"
    private const val PREFS_NAME = "lks_dialer_prefs"

    private const val KEY_APP_RINGTONE_URI = "app_custom_ringtone_uri"
    private const val KEY_APP_RINGTONE_TITLE = "app_custom_ringtone_title"

    private const val PREFIX_CONTACT_URI = "contact_ringtone_uri_"
    private const val PREFIX_CONTACT_TITLE = "contact_ringtone_title_"

    private var previewPlayer: MediaPlayer? = null
    private var previewRingtone: Ringtone? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. GLOBAL APP RINGTONE
    // ─────────────────────────────────────────────────────────────────────────────

    fun getAppRingtone(context: Context): Pair<Uri, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_APP_RINGTONE_URI, null)
        val titleStr = prefs.getString(KEY_APP_RINGTONE_TITLE, null)

        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            val title = titleStr ?: getRingtoneTitle(context, uri)
            return Pair(uri, title)
        }

        val actualUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        val defaultUri = actualUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val defaultTitle = getRingtoneTitle(context, defaultUri)
        return Pair(defaultUri, defaultTitle.ifBlank { "System Default Ringtone" })
    }

    fun setAppRingtone(context: Context, uri: Uri, customTitle: String? = null) {
        takePersistablePermission(context, uri)
        val title = customTitle ?: getRingtoneTitle(context, uri)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_APP_RINGTONE_URI, uri.toString())
            .putString(KEY_APP_RINGTONE_TITLE, title)
            .apply()
        Log.i(TAG, "Global app ringtone set to: $title ($uri)")
    }

    fun resetAppRingtoneToDefault(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_APP_RINGTONE_URI)
            .remove(KEY_APP_RINGTONE_TITLE)
            .apply()
        Log.i(TAG, "Global app ringtone reset to system default")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. PER-CONTACT CUSTOM RINGTONE
    // ─────────────────────────────────────────────────────────────────────────────

    fun getContactRingtone(context: Context, phoneNumber: String): Pair<Uri, String>? {
        if (phoneNumber.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val variations = getNumberVariations(phoneNumber)

        for (varNumber in variations) {
            val uriStr = prefs.getString(PREFIX_CONTACT_URI + varNumber, null)
            if (!uriStr.isNullOrBlank()) {
                val uri = Uri.parse(uriStr)
                val title = prefs.getString(PREFIX_CONTACT_TITLE + varNumber, null)
                    ?: getRingtoneTitle(context, uri)
                return Pair(uri, title)
            }
        }
        return null
    }

    fun setContactRingtone(context: Context, phoneNumber: String, uri: Uri, customTitle: String? = null) {
        if (phoneNumber.isBlank()) return
        takePersistablePermission(context, uri)
        val title = customTitle ?: getRingtoneTitle(context, uri)
        val cleanDigits = phoneNumber.replace(Regex("[^0-9+]"), "")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREFIX_CONTACT_URI + cleanDigits, uri.toString())
            .putString(PREFIX_CONTACT_TITLE + cleanDigits, title)
            .apply()
        Log.i(TAG, "Custom ringtone for $phoneNumber set to: $title ($uri)")
    }

    fun clearContactRingtone(context: Context, phoneNumber: String) {
        if (phoneNumber.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val variations = getNumberVariations(phoneNumber)
        val editor = prefs.edit()
        for (varNumber in variations) {
            editor.remove(PREFIX_CONTACT_URI + varNumber)
            editor.remove(PREFIX_CONTACT_TITLE + varNumber)
        }
        editor.apply()
        Log.i(TAG, "Custom ringtone for $phoneNumber removed (reverting to app default)")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. RESOLUTION FOR INCOMING CALLS
    // ─────────────────────────────────────────────────────────────────────────────

    fun getRingtoneForIncomingCall(context: Context, callerNumber: String): Uri {
        // 1. Check if specific contact has a custom ringtone
        val contactRingtone = getContactRingtone(context, callerNumber)
        if (contactRingtone != null) {
            Log.d(TAG, "Playing Contact Custom Ringtone: ${contactRingtone.second}")
            return contactRingtone.first
        }

        // 2. Check if App has a custom ringtone
        val appRingtone = getAppRingtone(context)
        Log.d(TAG, "Playing App Ringtone: ${appRingtone.second}")
        return appRingtone.first
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. TITLE LOOKUP & UTILS
    // ─────────────────────────────────────────────────────────────────────────────

    fun getRingtoneTitle(context: Context, uri: Uri): String {
        try {
            // Check if it's a System Ringtone
            val ringtone = RingtoneManager.getRingtone(context, uri)
            val title = ringtone?.getTitle(context)
            if (!title.isNullOrBlank() && !title.startsWith("content://") && !title.startsWith("file://")) {
                return title
            }
        } catch (_: Exception) {}

        // Check if it's a Content URI (MediaStore / DocumentFile from storage)
        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val displayName = cursor.getString(nameIndex)
                        if (!displayName.isNullOrBlank()) {
                            return displayName
                        }
                    }
                }
            } catch (_: Exception) {}
            finally {
                cursor?.close()
            }
        }

        val lastPathSegment = uri.lastPathSegment
        if (!lastPathSegment.isNullOrBlank()) {
            return lastPathSegment
        }

        return "Custom Ringtone"
    }

    private fun takePersistablePermission(context: Context, uri: Uri) {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Log.d(TAG, "Persistable URI read permission granted for: $uri")
            } catch (e: Exception) {
                Log.w(TAG, "Could not take persistable URI permission for $uri: ${e.message}")
            }
        }
    }

    private fun getNumberVariations(phoneNumber: String): List<String> {
        val list = mutableListOf<String>()
        list.add(phoneNumber)
        val clean = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (clean.isNotBlank()) list.add(clean)
        val digitsOnly = phoneNumber.replace(Regex("[^0-9]"), "")
        if (digitsOnly.isNotBlank()) {
            list.add(digitsOnly)
            if (digitsOnly.length > 10) list.add(digitsOnly.takeLast(10))
            if (!phoneNumber.startsWith("+")) list.add("+$digitsOnly")
        }
        return list.distinct()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. PREVIEW PLAYBACK IN UI
    // ─────────────────────────────────────────────────────────────────────────────

    fun playPreview(context: Context, uri: Uri, onCompletion: (() -> Unit)? = null): Boolean {
        stopPreview()
        try {
            previewPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, uri)
                prepare()
                start()
                setOnCompletionListener {
                    stopPreview()
                    onCompletion?.invoke()
                }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer preview failed, trying Ringtone fallback: ${e.message}")
            try {
                previewRingtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    }
                    play()
                }
                return true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to play ringtone preview", e2)
                return false
            }
        }
    }

    fun isPreviewPlaying(): Boolean {
        return try {
            previewPlayer?.isPlaying == true || previewRingtone?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    fun stopPreview() {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
        } catch (_: Exception) {}

        try {
            previewRingtone?.stop()
            previewRingtone = null
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 6. INCOMING CALL MEDIA PLAYER / RINGTONE PLAYER
    // ─────────────────────────────────────────────────────────────────────────────

    fun createIncomingCallPlayer(context: Context, uri: Uri): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create MediaPlayer for incoming call ringtone, will use Ringtone fallback: ${e.message}")
            null
        }
    }
}
