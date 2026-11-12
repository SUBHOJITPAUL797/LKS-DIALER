package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Data model holding extracted video metadata.
 */
data class VideoMetadata(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val formattedDuration: String = "0:00",
    val formattedSize: String = ""
)

/**
 * Robust, high-performance video thumbnail and metadata helper.
 *
 * Features:
 * - Dual-layer caching: In-memory LRU Cache (40 bitmaps) + persistent on-disk cache (JPEG).
 * - SELinux-safe file descriptor passing to bypass Android private app storage permissions in MediaMetadataRetriever.
 * - Hardware-accelerated scaled frame extraction on API 27+ (640x640) with graceful fallbacks.
 * - Thread-safe and non-blocking via Dispatchers.IO.
 */
object VideoThumbnailHelper {

    private const val TAG = "VideoThumbnailHelper"
    private const val DISK_CACHE_DIR_NAME = "video_thumbs"

    // 1. Dual-layer cache: Memory LRU cache for 40 video thumbnails
    private val memoryCache = object : LruCache<String, Bitmap>(40) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // In-memory cache for metadata to prevent repeated disk I/O on list recompositions
    private val metadataCache = object : LruCache<String, VideoMetadata>(100) {
        override fun sizeOf(key: String, value: VideoMetadata): Int = 1
    }

    // Mutex to serialize concurrent decoding jobs for identical files
    private val extractionMutex = Mutex()

    /**
     * Extracts video metadata (duration, width, height with rotation orientation handled, formatted strings).
     */
    suspend fun getVideoMetadata(context: Context, videoFile: File?): VideoMetadata = withContext(Dispatchers.IO) {
        if (videoFile == null || !videoFile.exists() || videoFile.length() <= 0L) {
            return@withContext VideoMetadata()
        }

        val cacheKey = "${videoFile.absolutePath}_${videoFile.length()}_${videoFile.lastModified()}"
        synchronized(metadataCache) {
            metadataCache.get(cacheKey)?.let { return@withContext it }
        }

        val formattedSize = formatFileSize(videoFile.length())
        val retriever = MediaMetadataRetriever()
        try {
            // SELinux safe data source setup using file descriptor
            try {
                FileInputStream(videoFile).use { fis ->
                    retriever.setDataSource(fis.fd, 0L, videoFile.length())
                }
            } catch (_: Exception) {
                retriever.setDataSource(videoFile.absolutePath)
            }

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            // Swap dimensions when rotated 90 or 270 degrees
            val (width, height) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }

            val formattedDuration = formatDuration(durationMs)
            val metadata = VideoMetadata(
                durationMs = durationMs,
                width = width,
                height = height,
                formattedDuration = formattedDuration,
                formattedSize = formattedSize
            )

            synchronized(metadataCache) {
                metadataCache.put(cacheKey, metadata)
            }
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract video metadata for: ${videoFile.absolutePath}", e)
            VideoMetadata(formattedSize = formattedSize)
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Retrieves video thumbnail bitmap with dual-layer caching (Memory LRU -> Disk Cache -> MediaMetadataRetriever).
     */
    suspend fun getVideoThumbnail(context: Context, videoFile: File?, atMs: Long = 1000L): Bitmap? = withContext(Dispatchers.IO) {
        if (videoFile == null || !videoFile.exists() || videoFile.length() <= 0L) {
            return@withContext null
        }

        val cacheKey = "${videoFile.absolutePath}_${videoFile.length()}_${videoFile.lastModified()}"

        // Step 1: Memory cache lookup (instant)
        synchronized(memoryCache) {
            memoryCache.get(cacheKey)?.let { return@withContext it }
        }

        // Step 2: Disk cache lookup
        val diskCacheDir = File(context.cacheDir, DISK_CACHE_DIR_NAME)
        val diskFileName = md5(cacheKey) + ".jpg"
        val diskFile = File(diskCacheDir, diskFileName)

        if (diskFile.exists() && diskFile.length() > 0L) {
            try {
                val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (diskBitmap != null) {
                    synchronized(memoryCache) {
                        memoryCache.put(cacheKey, diskBitmap)
                    }
                    return@withContext diskBitmap
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode disk-cached thumbnail: ${diskFile.absolutePath}", e)
            }
        }

        // Step 3: Extraction via MediaMetadataRetriever protected by Mutex
        extractionMutex.withLock {
            // Double-check memory cache inside lock
            synchronized(memoryCache) {
                memoryCache.get(cacheKey)?.let { return@withContext it }
            }

            // Double check disk cache inside lock
            if (diskFile.exists() && diskFile.length() > 0L) {
                try {
                    val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                    if (diskBitmap != null) {
                        synchronized(memoryCache) {
                            memoryCache.put(cacheKey, diskBitmap)
                        }
                        return@withContext diskBitmap
                    }
                } catch (_: Exception) {}
            }

            val retriever = MediaMetadataRetriever()
            var extractedBitmap: Bitmap? = null
            try {
                try {
                    FileInputStream(videoFile).use { fis ->
                        retriever.setDataSource(fis.fd, 0L, videoFile.length())
                    }
                } catch (_: Exception) {
                    retriever.setDataSource(videoFile.absolutePath)
                }

                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val targetTimeUs = if (durationMs > 0L) {
                    val safeAtMs = if (atMs in 0L..durationMs) atMs else (durationMs / 2).coerceAtLeast(0L)
                    safeAtMs * 1000L
                } else {
                    atMs * 1000L
                }

                extractedBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    try {
                        retriever.getScaledFrameAtTime(
                            targetTimeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            640,
                            640
                        ) ?: retriever.frameAtTime
                    } catch (_: Exception) {
                        retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.frameAtTime
                    }
                } else {
                    retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating thumbnail for: ${videoFile.absolutePath}", e)
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {}
            }

            if (extractedBitmap != null) {
                // Store in memory LRU cache
                synchronized(memoryCache) {
                    memoryCache.put(cacheKey, extractedBitmap)
                }

                // Persist to disk cache
                try {
                    if (!diskCacheDir.exists()) {
                        diskCacheDir.mkdirs()
                    }
                    val tempFile = File(diskCacheDir, "$diskFileName.tmp")
                    FileOutputStream(tempFile).use { out ->
                        extractedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.flush()
                    }
                    if (tempFile.exists()) {
                        if (diskFile.exists()) diskFile.delete()
                        tempFile.renameTo(diskFile)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed writing thumbnail to disk cache: ${diskFile.absolutePath}", e)
                }
            }

            extractedBitmap
        }
    }

    /**
     * Formats milliseconds duration into "m:ss" (or "h:mm:ss" for hours).
     */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSeconds = ms / 1000L
        val s = totalSeconds % 60L
        val m = (totalSeconds / 60L) % 60L
        val h = totalSeconds / 3600L
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", m, s)
        }
    }

    /**
     * Formats file size in bytes to a human-readable string (e.g. "850 KB", "5.1 MB").
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return if (bytes < 1024L) {
            "$bytes B"
        } else if (bytes < 1024L * 1024L) {
            "${bytes / 1024L} KB"
        } else if (bytes < 1024L * 1024L * 1024L) {
            String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
        } else {
            String.format(Locale.getDefault(), "%.1f GB", bytes / (1024f * 1024f * 1024f))
        }
    }

    /**
     * Optional utility to clear thumbnail disk & memory caches.
     */
    fun clearCache(context: Context) {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
        synchronized(metadataCache) {
            metadataCache.evictAll()
        }
        try {
            val diskCacheDir = File(context.cacheDir, DISK_CACHE_DIR_NAME)
            if (diskCacheDir.exists()) {
                diskCacheDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear video thumbnail disk cache", e)
        }
    }

    private fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toString()
        }
    }
}

/**
 * Top-level convenience functions mirroring the VideoThumbnailHelper methods.
 */
suspend fun getVideoMetadata(context: Context, videoFile: File?): VideoMetadata =
    VideoThumbnailHelper.getVideoMetadata(context, videoFile)

suspend fun getVideoThumbnail(context: Context, videoFile: File?, atMs: Long = 1000L): Bitmap? =
    VideoThumbnailHelper.getVideoThumbnail(context, videoFile, atMs)

fun formatDuration(ms: Long): String =
    VideoThumbnailHelper.formatDuration(ms)

fun formatFileSize(bytes: Long): String =
    VideoThumbnailHelper.formatFileSize(bytes)
