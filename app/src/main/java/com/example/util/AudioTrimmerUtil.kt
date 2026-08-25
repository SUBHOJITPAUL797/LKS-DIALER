package com.example.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Universal Audio Trimmer Engine
 * 1. Native lossless MP3 frame trimmer for all MP3 files (CBR & VBR).
 * 2. MediaExtractor + MediaMuxer for AAC / M4A / MP4 audio containers.
 */
object AudioTrimmerUtil {
    private const val TAG = "AudioTrimmerUtil"

    private val MPEG1_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MPEG2_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    private val MPEG1_SAMPLING_RATES = intArrayOf(44100, 48000, 32000, 0)
    private val MPEG2_SAMPLING_RATES = intArrayOf(22050, 24000, 16000, 0)
    private val MPEG25_SAMPLING_RATES = intArrayOf(11025, 12000, 8000, 0)

    fun trimAudio(context: Context, uri: Uri, startMs: Long, endMs: Long): Uri? {
        Log.i(TAG, "Starting audio trim for $uri (from $startMs ms to $endMs ms)")

        // 1. Try MP3 lossless frame trimmer
        val mp3Result = tryTrimMp3(context, uri, startMs, endMs)
        if (mp3Result != null) {
            Log.i(TAG, "✅ Successfully trimmed MP3 audio: $mp3Result")
            return mp3Result
        }

        // 2. Try MediaMuxer (AAC, M4A, MP4)
        val muxerResult = tryTrimMediaMuxer(context, uri, startMs, endMs)
        if (muxerResult != null) {
            Log.i(TAG, "✅ Successfully trimmed via MediaMuxer: $muxerResult")
            return muxerResult
        }

        Log.w(TAG, "⚠️ Audio trimming could not complete for $uri")
        return null
    }

    private fun tryTrimMp3(context: Context, uri: Uri, startMs: Long, endMs: Long): Uri? {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        val ringtonesDir = File(context.filesDir, "ringtones").apply { mkdirs() }
        val outFile = File(ringtonesDir, "trimmed_${System.currentTimeMillis()}.mp3")

        try {
            inputStream = if (uri.scheme == "file" && uri.path != null) {
                java.io.FileInputStream(File(uri.path!!))
            } else {
                context.contentResolver.openInputStream(uri)
            } ?: return null

            val bis = BufferedInputStream(inputStream, 64 * 1024)

            // Check & skip ID3v2 header if present
            bis.mark(10)
            val headerBytes = ByteArray(10)
            val readHeader = bis.read(headerBytes)
            if (readHeader == 10 && headerBytes[0] == 'I'.code.toByte() && headerBytes[1] == 'D'.code.toByte() && headerBytes[2] == '3'.code.toByte()) {
                val size = ((headerBytes[6].toInt() and 0x7F) shl 21) or
                        ((headerBytes[7].toInt() and 0x7F) shl 14) or
                        ((headerBytes[8].toInt() and 0x7F) shl 7) or
                        (headerBytes[9].toInt() and 0x7F)
                val flags = headerBytes[5].toInt()
                val hasFooter = (flags and 0x10) != 0
                val totalId3 = size + if (hasFooter) 10 else 0
                var skipped = 0L
                while (skipped < totalId3) {
                    val s = bis.skip(totalId3 - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            } else {
                bis.reset()
            }

            outputStream = FileOutputStream(outFile)
            var currentTimeMs = 0.0
            var frameCount = 0
            var writtenFrames = 0

            val frameHeader = ByteArray(4)

            while (true) {
                // Find next sync word (0xFF, 0xEx)
                var b = bis.read()
                if (b == -1) break
                if (b != 0xFF) continue

                val b2 = bis.read()
                if (b2 == -1) break
                if ((b2 and 0xE0) != 0xE0) continue

                val b3 = bis.read()
                val b4 = bis.read()
                if (b3 == -1 || b4 == -1) break

                frameHeader[0] = b.toByte()
                frameHeader[1] = b2.toByte()
                frameHeader[2] = b3.toByte()
                frameHeader[3] = b4.toByte()

                val mpegVersion = (b2 shr 3) and 0x03
                val layer = (b2 shr 1) and 0x03
                val bitrateIdx = (b3 shr 4) and 0x0F
                val samplingIdx = (b3 shr 2) and 0x03
                val padding = (b3 shr 1) and 0x01

                if (mpegVersion == 1 || layer == 0 || bitrateIdx == 0 || bitrateIdx == 15 || samplingIdx == 3) {
                    continue
                }

                val sampleRate = when (mpegVersion) {
                    3 -> MPEG1_SAMPLING_RATES[samplingIdx]
                    2 -> MPEG2_SAMPLING_RATES[samplingIdx]
                    0 -> MPEG25_SAMPLING_RATES[samplingIdx]
                    else -> 0
                }
                if (sampleRate <= 0) continue

                val bitrate = when (mpegVersion) {
                    3 -> MPEG1_BITRATES[bitrateIdx] * 1000
                    else -> MPEG2_BITRATES[bitrateIdx] * 1000
                }
                if (bitrate <= 0) continue

                val samplesPerFrame = when {
                    layer == 3 -> 384 // Layer I
                    mpegVersion == 3 -> 1152 // MPEG 1 Layer II / III
                    else -> 576 // MPEG 2 / 2.5 Layer II / III
                }

                val frameSize = if (layer == 3) {
                    ((12 * bitrate / sampleRate) + padding) * 4
                } else {
                    (samplesPerFrame / 8 * bitrate / sampleRate) + padding
                }
                if (frameSize < 4 || frameSize > 4096) continue

                val remainingBody = ByteArray(frameSize - 4)
                var bodyRead = 0
                while (bodyRead < remainingBody.size) {
                    val r = bis.read(remainingBody, bodyRead, remainingBody.size - bodyRead)
                    if (r <= 0) break
                    bodyRead += r
                }
                if (bodyRead != remainingBody.size) break

                val frameDurationMs = (samplesPerFrame.toDouble() * 1000.0) / sampleRate.toDouble()
                val frameStartMs = currentTimeMs
                val frameEndMs = currentTimeMs + frameDurationMs

                if (frameEndMs >= startMs && frameStartMs <= endMs) {
                    outputStream.write(frameHeader)
                    outputStream.write(remainingBody)
                    writtenFrames++
                }

                currentTimeMs += frameDurationMs
                frameCount++

                if (currentTimeMs > endMs) {
                    break
                }
            }

            outputStream.flush()

            if (writtenFrames > 0 && outFile.length() > 1024) {
                Log.i(TAG, "MP3 Trim succeeded: ${outFile.length()} bytes, $writtenFrames frames ($startMs ms to $endMs ms)")
                return Uri.fromFile(outFile)
            } else {
                outFile.delete()
                return null
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryTrimMp3 failed: ${e.message}")
            try { outFile.delete() } catch (_: Exception) {}
            return null
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun tryTrimMediaMuxer(context: Context, uri: Uri, startMs: Long, endMs: Long): Uri? {
        return try {
            val ringtonesDir = File(context.filesDir, "ringtones").apply { mkdirs() }
            val outFile = File(ringtonesDir, "trimmed_${System.currentTimeMillis()}.mp4")

            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val bufSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                try {
                    audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(512 * 1024)
                } catch (_: Exception) {
                    512 * 1024
                }
            } else {
                512 * 1024
            }

            val buffer = java.nio.ByteBuffer.allocate(bufSize)
            val bufInfo = android.media.MediaCodec.BufferInfo()
            val endUs = endMs * 1000L
            var written = false
            var firstSampleTimeUs = -1L

            while (true) {
                bufInfo.offset = 0
                bufInfo.size = extractor.readSampleData(buffer, 0)
                if (bufInfo.size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                if (firstSampleTimeUs == -1L) {
                    firstSampleTimeUs = sampleTimeUs
                }

                bufInfo.presentationTimeUs = (sampleTimeUs - firstSampleTimeUs).coerceAtLeast(0L)
                bufInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufInfo)
                written = true
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            if (written && outFile.length() > 0) {
                Uri.fromFile(outFile)
            } else {
                outFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryTrimMediaMuxer failed: ${e.message}")
            null
        }
    }
}
