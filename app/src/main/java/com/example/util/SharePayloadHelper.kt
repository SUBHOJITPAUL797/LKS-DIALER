package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

private const val TAG = "SharePayloadHelper"

enum class SharedPayloadType {
    TEXT,
    IMAGES,
    DOCUMENTS
}

data class SharedIncomingPayload(
    val type: SharedPayloadType,
    val text: String? = null,
    val files: List<File> = emptyList(),
    val originalNames: List<String> = emptyList(),
    val mimeType: String? = null,
    val directTargetPeerNumber: String? = null
)

object SharePayloadHelper {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /**
     * Extracts shared content (text, single or multiple photos, documents, videos)
     * safely from an incoming ACTION_SEND or ACTION_SEND_MULTIPLE Intent.
     */
    fun extractSharedPayload(context: Context, intent: Intent): SharedIncomingPayload? {
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return null
        }

        val directPeer = intent.getStringExtra("chat_peer_number")
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("peer_phone_number")
                ?.takeIf { it.isNotBlank() }

        val mimeType = intent.type ?: "*/*"
        Log.d(TAG, "extractSharedPayload: action=$action, type=$mimeType, directPeer=$directPeer")

        return try {
            if (action == Intent.ACTION_SEND) {
                handleSingleSend(context, intent, mimeType, directPeer)
            } else {
                handleMultipleSend(context, intent, mimeType, directPeer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract shared payload", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun handleSingleSend(
        context: Context,
        intent: Intent,
        mimeType: String,
        directPeer: String?
    ): SharedIncomingPayload? {
        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)

        // If no stream URI was provided, treat as plain text/URL share
        if (streamUri == null) {
            return if (!extraText.isNullOrBlank()) {
                SharedIncomingPayload(
                    type = SharedPayloadType.TEXT,
                    text = extraText.trim(),
                    mimeType = mimeType,
                    directTargetPeerNumber = directPeer
                )
            } else {
                null
            }
        }

        // Copy incoming stream to cache file immediately while permission is active
        val copyResult = copyUriToCache(context, streamUri, "shared") ?: return null
        val (cachedFile, originalName) = copyResult

        val isImage = mimeType.startsWith("image/", ignoreCase = true) ||
                cachedFile.extension.lowercase() in IMAGE_EXTENSIONS

        return if (isImage) {
            SharedIncomingPayload(
                type = SharedPayloadType.IMAGES,
                text = extraText?.trim(),
                files = listOf(cachedFile),
                originalNames = listOf(originalName),
                mimeType = mimeType,
                directTargetPeerNumber = directPeer
            )
        } else {
            SharedIncomingPayload(
                type = SharedPayloadType.DOCUMENTS,
                text = extraText?.trim(),
                files = listOf(cachedFile),
                originalNames = listOf(originalName),
                mimeType = mimeType,
                directTargetPeerNumber = directPeer
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun handleMultipleSend(
        context: Context,
        intent: Intent,
        mimeType: String,
        directPeer: String?
    ): SharedIncomingPayload? {
        val rawUris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }.ifEmpty {
            val clip = intent.clipData
            if (clip != null && clip.itemCount > 0) {
                val list = mutableListOf<Uri>()
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { list.add(it) }
                }
                list
            } else emptyList()
        }

        if (rawUris.isEmpty()) return null

        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        val files = mutableListOf<File>()
        val originalNames = mutableListOf<String>()

        for ((idx, uri) in rawUris.withIndex()) {
            copyUriToCache(context, uri, "shared_multi_$idx")?.let { (file, name) ->
                files.add(file)
                originalNames.add(name)
            }
        }

        if (files.isEmpty()) return null

        val areAllImages = files.all { f ->
            mimeType.startsWith("image/", ignoreCase = true) || f.extension.lowercase() in IMAGE_EXTENSIONS
        }

        return if (areAllImages) {
            SharedIncomingPayload(
                type = SharedPayloadType.IMAGES,
                text = extraText?.trim(),
                files = files,
                originalNames = originalNames,
                mimeType = mimeType,
                directTargetPeerNumber = directPeer
            )
        } else {
            SharedIncomingPayload(
                type = SharedPayloadType.DOCUMENTS,
                text = extraText?.trim(),
                files = files,
                originalNames = originalNames,
                mimeType = mimeType,
                directTargetPeerNumber = directPeer
            )
        }
    }

    /**
     * Securely streams content from ContentResolver into the app's cache directory.
     * Extracts original filename and resolves appropriate file extensions.
     */
    private fun copyUriToCache(context: Context, uri: Uri, prefix: String): Pair<File, String>? {
        return try {
            val sharedDir = File(context.cacheDir, "shared_incoming").apply {
                if (!exists()) mkdirs()
            }

            var displayName = "file_${System.currentTimeMillis()}"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        val n = cursor.getString(nameIdx)
                        if (!n.isNullOrBlank()) displayName = n
                    }
                }
            }

            val rawExt = displayName.substringAfterLast('.', "")
            val resolvedExt = if (rawExt.isNotBlank() && rawExt.length <= 5) {
                rawExt
            } else {
                val resolvedMime = context.contentResolver.getType(uri)
                MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime) ?: "bin"
            }

            val sanitizedBase = displayName.substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                .take(30).ifBlank { "item" }
            val fileName = "${prefix}_${System.currentTimeMillis()}_$sanitizedBase.$resolvedExt"
            val targetFile = File(sharedDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                Pair(targetFile, displayName)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyUriToCache failed for uri=$uri", e)
            null
        }
    }
}
