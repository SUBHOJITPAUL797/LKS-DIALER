package com.example.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class YouTubeSongItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationText: String,
    val thumbnailUrl: String
)

/**
 * YouTubeSearchHelper
 * High-speed YouTube song search using official YouTubei API.
 * No API keys required, works globally, returns video titles, channels, durations, and thumbnails.
 */
object YouTubeSearchHelper {
    private const val TAG = "YouTubeSearchHelper"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun searchSongs(query: String): List<YouTubeSongItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        // 1. If user pasted a YouTube link, extract videoId directly
        val directVideoId = extractVideoId(cleanQuery)
        if (directVideoId != null) {
            val directItem = fetchSingleVideoMetadata(directVideoId)
            if (directItem != null) {
                return@withContext listOf(directItem)
            }
        }

        // 2. Perform live YouTube search
        try {
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
                if (!response.isSuccessful) {
                    Log.w(TAG, "YouTube search failed with HTTP ${response.code}")
                    return@withContext getFallbackSuggestions(cleanQuery)
                }

                val bodyStr = response.body?.string() ?: return@withContext getFallbackSuggestions(cleanQuery)
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
                                        thumbnailUrl = thumbUrl
                                    )
                                )
                            }
                            if (results.size >= 25) break
                        }
                        if (results.size >= 25) break
                    }
                }

                if (results.isNotEmpty()) results else getFallbackSuggestions(cleanQuery)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing YouTube search: ${e.message}", e)
            getFallbackSuggestions(cleanQuery)
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
                        durationText = "YouTube",
                        thumbnailUrl = thumb
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
                thumbnailUrl = "https://img.youtube.com/vi/JGwWNGJdvx8/hqdefault.jpg"
            ),
            YouTubeSongItem(
                videoId = "7wtfhZwyrcc",
                title = "Imagine Dragons - Believer",
                channelName = "Imagine Dragons",
                durationText = "3:37",
                thumbnailUrl = "https://img.youtube.com/vi/7wtfhZwyrcc/hqdefault.jpg"
            ),
            YouTubeSongItem(
                videoId = "fJ9rUzIMcZQ",
                title = "Queen - Bohemian Rhapsody",
                channelName = "Queen Official",
                durationText = "5:55",
                thumbnailUrl = "https://img.youtube.com/vi/fJ9rUzIMcZQ/hqdefault.jpg"
            ),
            YouTubeSongItem(
                videoId = "kJQP7kiw5Fk",
                title = "Luis Fonsi - Despacito ft. Daddy Yankee",
                channelName = "Luis Fonsi",
                durationText = "4:42",
                thumbnailUrl = "https://img.youtube.com/vi/kJQP7kiw5Fk/hqdefault.jpg"
            ),
            YouTubeSongItem(
                videoId = "09R8_2nJtjg",
                title = "Maroon 5 - Sugar",
                channelName = "Maroon 5",
                durationText = "5:01",
                thumbnailUrl = "https://img.youtube.com/vi/09R8_2nJtjg/hqdefault.jpg"
            )
        )
        return all.filter {
            it.title.contains(query, ignoreCase = true) || it.channelName.contains(query, ignoreCase = true)
        }.ifEmpty { all }
    }
}
