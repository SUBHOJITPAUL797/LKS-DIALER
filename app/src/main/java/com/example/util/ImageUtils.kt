package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ImageUtils {

    /**
     * Compresses an image from a URI and encodes it as a Base64 string.
     * Shrinks the image so it takes up minimal space in Firestore (ideally < 100KB).
     */
    fun compressUriToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return null

            // Scale down the avatar to max 256x256 (crisp for 128dp circle on xxxhdpi)
            val maxDim = 256f
            val width = originalBitmap.width
            val height = originalBitmap.height
            val ratio = width.toFloat() / height.toFloat()

            var newWidth = width
            var newHeight = height

            if (width > maxDim || height > maxDim) {
                if (ratio > 1) {
                    newWidth = maxDim.toInt()
                    newHeight = (maxDim / ratio).toInt()
                } else {
                    newHeight = maxDim.toInt()
                    newWidth = (maxDim * ratio).toInt()
                }
            }

            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
            
            // Compress to JPEG and ensure it stays under 30KB (Base64 < 40KB)
            var quality = 75
            var outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            val maxSizeBytes = 30 * 1024
            while (outputStream.toByteArray().size > maxSizeBytes && quality > 30) {
                quality -= 10
                outputStream.reset()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }

            val byteArray = outputStream.toByteArray()

            // Encode to Base64 without line breaks (clean for Firestore)
            Base64.encodeToString(byteArray, Base64.NO_WRAP)

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decodes a Base64 string back to an ImageBitmap for Jetpack Compose.
     */
    fun decodeBase64ToImageBitmap(base64Str: String): ImageBitmap? {
        if (base64Str.isBlank()) return null
        return try {
            val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            bitmap?.asImageBitmap()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
