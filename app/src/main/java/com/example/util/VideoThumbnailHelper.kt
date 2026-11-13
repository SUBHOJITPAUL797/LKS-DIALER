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
    private const val MIN_MEDIA_BYTES = 256L

    // 1. Dual-layer cache: Memory LRU cache for 40 video thumbnails
    private val memoryCache = object : LruCache<String, Bitmap>(40) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // In-memory cache for metadata (also caches failed/empty entries to avoid repeated NuMediaExtractor failures)
    private val metadataCache = object : LruCache<String, VideoMetadata>(120) {
        override fun sizeOf(key: String, value: VideoMetadata): Int = 1
    }

    // Negative cache shared across metadata, thumbnails, and audio durations
    private val failedMediaCache = object : LruCache<String, Boolean>(150) {
        override fun sizeOf(key: String, value: Boolean): Int = 1
    }

    // Cached durations (ms) for audio document attachments (includes negative cache = 0L)
    private val audioDurationCache = object : LruCache<String, Long>(150) {
        override fun sizeOf(key: String, value: Long): Int = 1
    }

    // Mutex to serialize concurrent decoding jobs
    private val extractionMutex = Mutex()

    /**
     * Pure-Kotlin container magic inspection to avoid invoking native NuMediaExtractor
     * on empty, truncated, corrupt, HTML/JSON, encrypted, or image files.
     */
    fun isPlausibleMediaFile(file: File?): Boolean {
        if (file == null || !file.exists() || !file.canRead() || file.length() < MIN_MEDIA_BYTES) {
            return false
        }
        return try {
            val buf = ByteArray(4096)
            val read = FileInputStream(file).use { it.read(buf) }
            if (read < 12) return false

            val headText = String(buf, 0, minOf(read, 32), Charsets.US_ASCII).trimStart()
            if (headText.startsWith("<html", ignoreCase = true) ||
                headText.startsWith("<!doctype", ignoreCase = true) ||
                headText.startsWith("<?xml", ignoreCase = true) ||
                headText.startsWith("{") ||
                headText.startsWith("[")
            ) {
                return false
            }

            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF
            val b2 = buf[2].toInt() and 0xFF
            val b3 = buf[3].toInt() and 0xFF

            // Reject common image headers (PNG, JPEG, GIF) masquerading as video/audio
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return false // PNG
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return false               // JPEG
            if (headText.startsWith("GIF8")) return false                          // GIF

            // 1. ISO BMFF / MP4 / M4A / 3GP / MOV ("ftyp", "moov", "mdat", "wide", "free", "skip")
            if (read >= 8) {
                val boxTag = String(buf, 4, 4, Charsets.US_ASCII)
                if (boxTag == "ftyp" || boxTag == "moov" || boxTag == "mdat" ||
                    boxTag == "wide" || boxTag == "free" || boxTag == "skip"
                ) {
                    return true
                }
            }
            // 2. Matroska / WebM EBML header (1A 45 DF A3)
            if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) return true
            // 3. RIFF (AVI / WAV / WebP — verify not WEBP)
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) {
                val sub = if (read >= 12) String(buf, 8, 4, Charsets.US_ASCII) else ""
                return sub != "WEBP"
            }
            // 4. Ogg / Opus / Vorbis ("OggS")
            if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return true
            // 5. MP3 ID3v2 tag ("ID3") or FLAC ("fLaC") or AMR ("#!AMR") or FLV ("FLV") or ADIF
            if ((b0 == 0x49 && b1 == 0x44 && b2 == 0x33) ||
                (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) ||
                (b0 == 0x23 && b1 == 0x21 && b2 == 0x41 && b3 == 0x4D) ||
                (b0 == 0x46 && b1 == 0x4C && b2 == 0x56) ||
                (b0 == 0x41 && b1 == 0x44 && b2 == 0x49 && b3 == 0x46)
            ) return true
            // 6. ASF / WMV / WMA (30 26 B2 75)
            if (b0 == 0x30 && b1 == 0x26 && b2 == 0xB2 && b3 == 0x75) return true
            // 7. MPEG-TS sync byte (0x47 at 0 and 188) or MPEG-PS (00 00 01 BA)
            if (b0 == 0x47 && read > 188 && (buf[188].toInt() and 0xFF) == 0x47) return true
            if (b0 == 0x00 && b1 == 0x00 && b2 == 0x01 && b3 == 0xBA) return true

            // 8. Verify 2 consecutive valid MPEG Layer-III or AAC ADTS frame headers (rejects coincidental 0xFF 0xE* pairs)
            val mpeg1Rates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
            val mpeg2Rates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
            val sr1 = intArrayOf(44100, 48000, 32000, 0)
            val sr2 = intArrayOf(22050, 24000, 16000, 0)
            val sr25 = intArrayOf(11025, 12000, 8000, 0)

            var pos = 0
            while (pos < minOf(read - 7, 512)) {
                val h0 = buf[pos].toInt() and 0xFF
                val h1 = buf[pos + 1].toInt() and 0xFF
                if (h0 == 0xFF && (h1 and 0xE0) == 0xE0) {
                    // Check AAC ADTS frame length
                    if ((h1 and 0xF6) == 0xF0) {
                        val h3 = buf[pos + 3].toInt() and 0xFF
                        val h4 = buf[pos + 4].toInt() and 0xFF
                        val h5 = buf[pos + 5].toInt() and 0xFF
                        val frameLen = ((h3 and 0x03) shl 11) or (h4 shl 3) or ((h5 and 0xE0) shr 5)
                        if (frameLen in 7..4096 && pos + frameLen + 1 < read) {
                            val n0 = buf[pos + frameLen].toInt() and 0xFF
                            val n1 = buf[pos + frameLen + 1].toInt() and 0xFF
                            if (n0 == 0xFF && (n1 and 0xF6) == 0xF0) return true
                        }
                    }
                    // Check MP3 Layer III frame length
                    val ver = (h1 shr 3) and 0x03
                    val layer = (h1 shr 1) and 0x03
                    if (ver != 1 && layer == 1) {
                        val h2 = buf[pos + 2].toInt() and 0xFF
                        val brIdx = (h2 shr 4) and 0x0F
                        val srIdx = (h2 shr 2) and 0x03
                        val pad = (h2 shr 1) and 0x01
                        if (brIdx in 1..14 && srIdx in 0..2) {
                            val br = (if (ver == 3) mpeg1Rates[brIdx] else mpeg2Rates[brIdx]) * 1000
                            val sr = when (ver) {
                                3 -> sr1[srIdx]
                                2 -> sr2[srIdx]
                                else -> sr25[srIdx]
                            }
                            if (br > 0 && sr > 0) {
                                val frameLen = ((if (ver == 3) 144 else 72) * br) / sr + pad
                                if (frameLen in 24..4096 && pos + frameLen + 1 < read) {
                                    val n0 = buf[pos + frameLen].toInt() and 0xFF
                                    val n1 = buf[pos + frameLen + 1].toInt() and 0xFF
                                    if (n0 == 0xFF && (n1 and 0xE0) == 0xE0) return true
                                }
                            }
                        }
                    }
                }
                pos++
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun setRetrieverSourceSafe(retriever: MediaMetadataRetriever, file: File) {
        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(file)
            retriever.setDataSource(fis.fd, 0L, file.length())
        } catch (openErr: java.io.IOException) {
            // Only fall back to path if opening FileInputStream itself failed (not extraction failure)
            retriever.setDataSource(file.absolutePath)
        } finally {
            try { fis?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Extracts audio duration safely off the main thread with negative caching & proper release.
     */
    suspend fun getAudioDuration(audioFile: File?): Long = withContext(Dispatchers.IO) {
        if (!isPlausibleMediaFile(audioFile)) return@withContext 0L
        val file = audioFile!!
        val cacheKey = "${file.absolutePath}_${file.length()}_${file.lastModified()}"
        synchronized(audioDurationCache) {
            audioDurationCache.get(cacheKey)?.let { return@withContext it }
        }
        synchronized(failedMediaCache) {
            if (failedMediaCache.get(cacheKey) == true) return@withContext 0L
        }

        val retriever = MediaMetadataRetriever()
        var failed = false
        val durationMs = try {
            setRetrieverSourceSafe(retriever, file)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            failed = true
            Log.w(TAG, "Skipping unreadable audio metadata for ${file.name}: ${e.message}")
            0L
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        if (failed) {
            synchronized(failedMediaCache) { failedMediaCache.put(cacheKey, true) }
        }
        synchronized(audioDurationCache) {
            audioDurationCache.put(cacheKey, durationMs)
        }
        durationMs
    }

    /**
     * Extracts video metadata (duration, width, height with rotation orientation handled, formatted strings).
     */
    suspend fun getVideoMetadata(context: Context, videoFile: File?): VideoMetadata = withContext(Dispatchers.IO) {
        if (videoFile == null || !videoFile.exists() || videoFile.length() <= 0L) {
            return@withContext VideoMetadata()
        }

        val formattedSize = formatFileSize(videoFile.length())
        val cacheKey = "${videoFile.absolutePath}_${videoFile.length()}_${videoFile.lastModified()}"
        synchronized(metadataCache) {
            metadataCache.get(cacheKey)?.let { return@withContext it }
        }
        synchronized(failedMediaCache) {
            if (failedMediaCache.get(cacheKey) == true) {
                return@withContext VideoMetadata(formattedSize = formattedSize)
            }
        }

        if (!isPlausibleMediaFile(videoFile)) {
            val fallback = VideoMetadata(formattedSize = formattedSize)
            synchronized(failedMediaCache) { failedMediaCache.put(cacheKey, true) }
            synchronized(metadataCache) { metadataCache.put(cacheKey, fallback) }
            return@withContext fallback
        }

        extractionMutex.withLock {
            synchronized(metadataCache) {
                metadataCache.get(cacheKey)?.let { return@withContext it }
            }
            synchronized(failedMediaCache) {
                if (failedMediaCache.get(cacheKey) == true) {
                    return@withContext VideoMetadata(formattedSize = formattedSize)
                }
            }

            val retriever = MediaMetadataRetriever()
            var failed = false
            val metadata = try {
                setRetrieverSourceSafe(retriever, videoFile)

                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                val (width, height) = if (rotation == 90 || rotation == 270) {
                    rawHeight to rawWidth
                } else {
                    rawWidth to rawHeight
                }

                VideoMetadata(
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    formattedDuration = formatDuration(durationMs),
                    formattedSize = formattedSize
                )
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "Failed to extract video metadata for ${videoFile.name}: ${e.message}")
                VideoMetadata(formattedSize = formattedSize)
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }

            if (failed) {
                synchronized(failedMediaCache) { failedMediaCache.put(cacheKey, true) }
            }
            synchronized(metadataCache) {
                metadataCache.put(cacheKey, metadata)
            }
            metadata
        }
    }

    /**
     * Retrieves video thumbnail bitmap with dual-layer caching (Memory LRU -> Disk Cache -> MediaMetadataRetriever).
     */
    suspend fun getVideoThumbnail(context: Context, videoFile: File?, atMs: Long = 1000L): Bitmap? = withContext(Dispatchers.IO) {
        if (!isPlausibleMediaFile(videoFile)) {
            return@withContext null
        }
        val file = videoFile!!
        val cacheKey = "${file.absolutePath}_${file.length()}_${file.lastModified()}"

        // Step 1: Memory cache or negative cache lookup (instant)
        synchronized(memoryCache) {
            memoryCache.get(cacheKey)?.let { return@withContext it }
        }
        synchronized(failedMediaCache) {
            if (failedMediaCache.get(cacheKey) == true) return@withContext null
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
            synchronized(memoryCache) {
                memoryCache.get(cacheKey)?.let { return@withContext it }
            }
            synchronized(failedMediaCache) {
                if (failedMediaCache.get(cacheKey) == true) return@withContext null
            }

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
                setRetrieverSourceSafe(retriever, file)

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
                Log.w(TAG, "Skipping unreadable video thumbnail for ${file.name}: ${e.message}")
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {}
            }

            if (extractedBitmap == null) {
                synchronized(failedMediaCache) {
                    failedMediaCache.put(cacheKey, true)
                }
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
