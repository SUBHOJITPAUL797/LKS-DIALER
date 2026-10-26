package com.example.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class YouTubeSongItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationText: String,
    val thumbnailUrl: String,
    val directAudioUrl: String = ""
)

/**
 * YouTubeSearchHelper
 * High-speed song search across tens of millions of songs (Bollywood, Hollywood, Punjabi, Global).
 * Resolves direct streamable/downloadable audio tracks for instant in-app preview and visual 30s trimming.
 */
object YouTubeSearchHelper {
    private const val TAG = "YouTubeSearchHelper"
    private const val SAAVN_DES_KEY = "38346591"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun searchSongs(query: String): List<YouTubeSongItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        // 1. Primary: Search vast music catalog (80M+ songs with direct audio streams)
        val saavnResults = searchSaavnMusic(cleanQuery)
        if (saavnResults.isNotEmpty()) {
            Log.i(TAG, "Found ${saavnResults.size} playable songs from music library for: '$cleanQuery'")
            return@withContext saavnResults
        }

        // 2. If user pasted a YouTube link, extract videoId
        val directVideoId = extractVideoId(cleanQuery)
        if (directVideoId != null) {
            val directItem = fetchSingleVideoMetadata(directVideoId)
            if (directItem != null) {
                return@withContext listOf(directItem)
            }
        }

        // 3. Fallback: Search YouTubei API
        val ytResults = searchYouTubeLive(cleanQuery)
        if (ytResults.isNotEmpty()) {
            return@withContext ytResults
        }

        getFallbackSuggestions(cleanQuery)
    }

    private fun searchSaavnMusic(query: String): List<YouTubeSongItem> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&p=1&n=25&q=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val resultsArray = json.optJSONArray("results") ?: return emptyList()

                val items = mutableListOf<YouTubeSongItem>()
                for (i in 0 until resultsArray.length()) {
                    val obj = resultsArray.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    val songRaw = obj.optString("song", "")
                    if (songRaw.isBlank()) continue
                    val title = cleanHtml(songRaw)
                    val artistsRaw = obj.optString("primary_artists", "")
                    val artist = cleanHtml(if (artistsRaw.isNotBlank()) artistsRaw else obj.optString("singers", "Artist"))
                    val durationSec = obj.optInt("duration", 180)
                    val durationText = "%d:%02d".format(durationSec / 60, durationSec % 60)
                    val rawImage = obj.optString("image", "")
                    val image = when {
                        rawImage.startsWith("//") -> "https:$rawImage"
                        rawImage.startsWith("http") -> rawImage
                        else -> "https://img.youtube.com/vi/default/hqdefault.jpg"
                    }

                    val encUrl = obj.optString("encrypted_media_url", "")
                    val directAudio = if (encUrl.isNotBlank()) decryptSaavnUrl(encUrl) ?: "" else ""

                    items.add(
                        YouTubeSongItem(
                            videoId = id.ifBlank { "song_$i" },
                            title = title,
                            channelName = artist,
                            durationText = durationText,
                            thumbnailUrl = image,
                            directAudioUrl = directAudio
                        )
                    )
                }
                return items
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchSaavnMusic error: ${e.message}")
            return emptyList()
        }
    }

    private fun decryptSaavnUrl(encUrl: String): String? {
        return try {
            val key = SecretKeySpec(SAAVN_DES_KEY.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decoded = android.util.Base64.decode(encUrl, android.util.Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decoded)
            var decryptedUrl = String(decryptedBytes, Charsets.UTF_8)
            if (decryptedUrl.contains("_96.mp4")) {
                decryptedUrl = decryptedUrl.replace("_96.mp4", "_160.mp4")
            }
            decryptedUrl
        } catch (e: Exception) {
            Log.w(TAG, "DES decryption failed: ${e.message}")
            null
        }
    }

    private fun cleanHtml(input: String): String {
        return input.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun searchYouTubeLive(cleanQuery: String): List<YouTubeSongItem> {
        return try {
            val payload = JSONObject().apply {
                val context = JSONObject().apply {
                    val client = JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20230522.01.00")
                        put("hl", "en")
                    }
                    put("client", client)
                }
                put("context", context)
                put("query", cleanQuery)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val results = mutableListOf<YouTubeSongItem>()
                val contents = json.optJSONObject("contents")
                    ?.optJSONObject("twoColumnSearchResultsRenderer")
                    ?.optJSONObject("primaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                if (contents != null) {
                    for (i in 0 until contents.length()) {
                        val section = contents.optJSONObject(i)?.optJSONObject("itemSectionRenderer")
                        val items = section?.optJSONArray("contents") ?: continue

                        for (j in 0 until items.length()) {
                            val vRenderer = items.optJSONObject(j)?.optJSONObject("videoRenderer") ?: continue
                            val videoId = vRenderer.optString("videoId")
                            if (videoId.isBlank()) continue

                            val title = vRenderer.optJSONObject("title")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text") ?: ""

                            val channelName = vRenderer.optJSONObject("ownerText")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                ?: vRenderer.optJSONObject("shortBylineText")
                                    ?.optJSONArray("runs")
                                    ?.optJSONObject(0)
                                    ?.optString("text")
                                ?: "Artist"

                            val durationText = vRenderer.optJSONObject("lengthText")
                                ?.optString("simpleText") ?: "Audio"

                            val thumbs = vRenderer.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")
                            val rawThumb = if (thumbs != null && thumbs.length() > 0) {
                                thumbs.optJSONObject(thumbs.length() - 1)?.optString("url") ?: ""
                            } else ""
                            val thumbUrl = when {
                                rawThumb.startsWith("//") -> "https:$rawThumb"
                                rawThumb.startsWith("http://") || rawThumb.startsWith("https://") -> rawThumb
                                else -> "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                            }

                            if (title.isNotBlank()) {
                                results.add(
                                    YouTubeSongItem(
                                        videoId = videoId,
                                        title = title,
                                        channelName = channelName,
                                        durationText = durationText,
                                        thumbnailUrl = thumbUrl,
                                        directAudioUrl = ""
                                    )
                                )
                            }
                            if (results.size >= 25) break
                        }
                        if (results.size >= 25) break
                    }
                }
                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing YouTube live search: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun downloadSongToTempFile(
        context: Context,
        audioUrl: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (audioUrl.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(audioUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val tempFile = File(context.cacheDir, "temp_song_${System.currentTimeMillis()}.m4a")
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                onProgress(downloadedBytes.toFloat() / totalBytes)
                            }
                        }
                    }
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading song: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchSingleVideoMetadata(videoId: String): YouTubeSongItem? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val title = json.optString("title", "YouTube Video")
                    val author = json.optString("author_name", "YouTube Artist")
                    val thumb = json.optString("thumbnail_url", "https://img.youtube.com/vi/$videoId/hqdefault.jpg")
                    return@withContext YouTubeSongItem(
                        videoId = videoId,
                        title = title,
                        channelName = author,
                        durationText = "Audio",
                        thumbnailUrl = thumb,
                        directAudioUrl = ""
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "oEmbed lookup failed: ${e.message}")
        }
        null
    }

    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        val patterns = listOf(
            Regex("""(?:youtube\.com/(?:[^\s]*[?&]v=|shorts/|embed/|v/)|youtu\.be/)([a-zA-Z0-9_-]{11})""", RegexOption.IGNORE_CASE),
            Regex("""youtube\.com/live/([a-zA-Z0-9_-]{11})""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(trimmed)
            if (m != null && m.groupValues.size > 1) {
                return m.groupValues[1]
            }
        }
        if (trimmed.length == 11 && trimmed.matches(Regex("""[a-zA-Z0-9_-]{11}"""))) {
            return trimmed
        }
        return null
    }

    private fun getFallbackSuggestions(query: String): List<YouTubeSongItem> {
        val all = listOf(
            YouTubeSongItem(
                videoId = "JGwWNGJdvx8",
                title = "Ed Sheeran - Shape of You",
                channelName = "Ed Sheeran",
                durationText = "4:24",
                thumbnailUrl = "https://c.saavncdn.com/286/2929d11a5d44a3592ced0383fa6f1977_150x150.jpg",
                directAudioUrl = "https://aac.saavncdn.com/286/2929d11a5d44a3592ced0383fa6f1977_160.mp4"
            ),
            YouTubeSongItem(
                videoId = "7wtfhZwyrcc",
                title = "Imagine Dragons - Believer",
                channelName = "Imagine Dragons",
                durationText = "3:37",
                thumbnailUrl = "https://c.saavncdn.com/248/Evolve-English-2018-20260605220036-150x150.jpg",
                directAudioUrl = "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_160.mp4"
            ),
            YouTubeSongItem(
                videoId = "kesariya_song",
                title = "Kesariya (From Brahmastra)",
                channelName = "Pritam, Arijit Singh",
                durationText = "4:28",
                thumbnailUrl = "https://c.saavncdn.com/871/Kesariya-From-Brahmastra-Hindi-2022-20220717092820-150x150.jpg",
                directAudioUrl = "https://aac.saavncdn.com/871/c2febd353f3a076a406fa37510f31f9f_160.mp4"
            ),
            YouTubeSongItem(
                videoId = "starboy_song",
                title = "The Weeknd - Starboy ft. Daft Punk",
                channelName = "The Weeknd",
                durationText = "3:50",
                thumbnailUrl = "https://c.saavncdn.com/372/Starboy-English-2016-150x150.jpg",
                directAudioUrl = "https://aac.saavncdn.com/372/b7f9937fefd686876e90be4a96c9a6e9_160.mp4"
            )
        )
        return all.filter {
            it.title.contains(query, ignoreCase = true) || it.channelName.contains(query, ignoreCase = true)
        }.ifEmpty { all }
    }
}
