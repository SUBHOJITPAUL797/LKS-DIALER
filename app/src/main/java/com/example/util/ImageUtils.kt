package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageUtils {
    private const val TAG = "ImageUtils"

    /**
     * Safely decodes a bitmap from file using inSampleSize to prevent OutOfMemory on high-res camera photos (50MP/108MP).
     * Also inspects EXIF orientation and applies the correct rotation.
     */
    fun decodeSampledBitmapFromFile(
        filePath: String,
        reqWidth: Int = 1600,
        reqHeight: Int = 1600
    ): Bitmap? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null

            // 1. Inspect bounds only (0 memory allocation)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)

            val rawWidth = options.outWidth
            val rawHeight = options.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return null

            // 2. Compute power-of-two inSampleSize
            var inSampleSize = 1
            if (rawHeight > reqHeight || rawWidth > reqWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            // 3. Decode actual bitmap with downsampling
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null

            // 4. Handle EXIF rotation (crucial for Xiaomi / Samsung portrait shots)
            val orientation = try {
                val exif = ExifInterface(filePath)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Throwable) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }

            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated != decoded) {
                    decoded.recycle()
                }
                rotated
            } else {
                decoded
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to safely decode sampled bitmap: ${e.message}", e)
            null
        }
    }

    /**
     * Compresses and optimizes any chat photo to WhatsApp HD standard:
     * - Downsamples largest dimension to maxDimension (default 1600px).
     * - Compresses to JPEG with iterative quality adjustment to stay under targetMaxBytes (default 450 KB).
     * - Guaranteed to stay below Firestore's 1 MiB hard document limit when Base64-encoded.
     */
    fun compressAndSaveChatImage(
        inputFile: File,
        outputFile: File,
        maxDimension: Int = 1600,
        targetMaxBytes: Int = 450 * 1024
    ): File {
        try {
            val bitmap = decodeSampledBitmapFromFile(inputFile.absolutePath, maxDimension, maxDimension)
                ?: run {
                    inputFile.copyTo(outputFile, overwrite = true)
                    return outputFile
                }

            // Scale down smoothly if larger than maxDimension
            val width = bitmap.width
            val height = bitmap.height
            val largestDim = maxOf(width, height)

            val scaledBitmap = if (largestDim > maxDimension) {
                val scale = maxDimension.toFloat() / largestDim.toFloat()
                val targetW = (width * scale).toInt().coerceAtLeast(1)
                val targetH = (height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (scaled != bitmap) {
                    bitmap.recycle()
                }
                scaled
            } else {
                bitmap
            }

            // Iteratively compress to JPEG to fit under targetMaxBytes
            var quality = 82
            var stream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)

            while (stream.size() > targetMaxBytes && quality > 40) {
                quality -= 10
                stream.reset()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }

            FileOutputStream(outputFile).use { fos ->
                fos.write(stream.toByteArray())
            }

            scaledBitmap.recycle()
            Log.d(TAG, "Chat photo compressed: ${inputFile.length() / 1024}KB -> ${outputFile.length() / 1024}KB (quality=$quality)")
            return outputFile
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to compress chat image: ${e.message}", e)
            inputFile.copyTo(outputFile, overwrite = true)
            return outputFile
        }
    }

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
