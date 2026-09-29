package com.example.util

import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class LinkPreviewData(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val domain: String = "",
    val isVideo: Boolean = false,
    val siteName: String? = null
)

object LinkPreviewHelper {

    private const val TAG = "LinkPreviewHelper"
    private val memoryCache = LruCache<String, LinkPreviewData>(300)

    val URL_REGEX = Regex(
        """https?://[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE
    )

    private val YOUTUBE_VIDEO_PATTERNS = listOf(
        Regex("""(?:youtube\.com/(?:[^\s]*[?&]v=|shorts/|embed/|v/)|youtu\.be/)([a-zA-Z0-9_-]{11})""", RegexOption.IGNORE_CASE),
        Regex("""youtube\.com/live/([a-zA-Z0-9_-]{11})""", RegexOption.IGNORE_CASE)
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '"', '\'', ';', ':', '>', '}', '!', '?', '*', '~')

    fun extractUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return URL_REGEX.findAll(text).map { it.value.trim().trimEnd(*TRAILING_PUNCTUATION) }.toList()
    }

    fun extractFirstUrl(text: String): String? {
        if (text.isBlank()) return null
        return URL_REGEX.find(text)?.value?.trim()?.trimEnd(*TRAILING_PUNCTUATION)
    }

    fun extractDomain(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return ""
            host.removePrefix("www.")
        } catch (_: Exception) { "" }
    }

    fun extractYouTubeVideoId(url: String): String? {
        for (pattern in YOUTUBE_VIDEO_PATTERNS) {
            val match = pattern.find(url)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun getCachedPreview(url: String): LinkPreviewData? {
        return memoryCache.get(url.trim().trimEnd(*TRAILING_PUNCTUATION))
    }

    fun getPreviewFlow(url: String): Flow<LinkPreviewData?> = flow {
        val cleanUrl = url.trim().trimEnd(*TRAILING_PUNCTUATION)
        val cached = memoryCache.get(cleanUrl)
        if (cached != null) {
            emit(cached)
            return@flow
        }
        emit(null)
        val preview = fetchPreview(cleanUrl)
        emit(preview)
    }.flowOn(Dispatchers.IO)

    suspend fun fetchPreview(rawUrl: String): LinkPreviewData? = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim().trimEnd(*TRAILING_PUNCTUATION)
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext null
        }

        memoryCache.get(cleanUrl)?.let { return@withContext it }

        val domain = extractDomain(cleanUrl)

        // 1. Specialized High-Speed YouTube / Shorts Handler (Instant, Official oEmbed, No Scraping)
        val ytVideoId = extractYouTubeVideoId(cleanUrl)
        if (ytVideoId != null) {
            val ytPreview = fetchYouTubePreview(cleanUrl, ytVideoId, domain)
            if (ytPreview != null) {
                memoryCache.put(cleanUrl, ytPreview)
                return@withContext ytPreview
            }
        }

        // 2. Direct Image Link Detection
        if (cleanUrl.endsWith(".jpg", ignoreCase = true) ||
            cleanUrl.endsWith(".jpeg", ignoreCase = true) ||
            cleanUrl.endsWith(".png", ignoreCase = true) ||
            cleanUrl.endsWith(".webp", ignoreCase = true) ||
            cleanUrl.endsWith(".gif", ignoreCase = true)) {
            val imgPreview = LinkPreviewData(
                url = cleanUrl,
                title = domain,
                imageUrl = cleanUrl,
                domain = domain,
                isVideo = false
            )
            memoryCache.put(cleanUrl, imgPreview)
            return@withContext imgPreview
        }

        // 3. Twitter / X Fast Handler via FxTwitter API
        if (domain.contains("twitter.com", ignoreCase = true) || domain.contains("x.com", ignoreCase = true)) {
            val fxPreview = fetchFxTwitterPreview(cleanUrl, domain)
            if (fxPreview != null) {
                memoryCache.put(cleanUrl, fxPreview)
                return@withContext fxPreview
            }
        }

        // 4. Standard OpenGraph Web Scraper with WhatsApp User-Agent
        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "WhatsApp/2.24.1.76 A")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val fallback = LinkPreviewData(url = cleanUrl, domain = domain)
                    memoryCache.put(cleanUrl, fallback)
                    return@withContext fallback
                }

                val contentType = response.header("Content-Type", "") ?: ""
                if (contentType.startsWith("image/")) {
                    val imgPreview = LinkPreviewData(
                        url = cleanUrl,
                        title = domain,
                        imageUrl = cleanUrl,
                        domain = domain,
                        isVideo = false
                    )
                    memoryCache.put(cleanUrl, imgPreview)
                    return@withContext imgPreview
                }

                val body = response.body ?: return@withContext LinkPreviewData(url = cleanUrl, domain = domain)
                val bodySource = body.source()

                // Read up to 100KB of HTML head safely without throwing EOFException on smaller pages
                val maxBytes = 100 * 1024L
                bodySource.request(maxBytes)
                val buffer = bodySource.buffer
                val bytesToRead = minOf(buffer.size, maxBytes)
                val htmlHead = buffer.clone().readUtf8(bytesToRead)

                val ogTitle = extractMetaContent(htmlHead, "og:title")
                    ?: extractMetaContent(htmlHead, "twitter:title")
                    ?: extractTagContent(htmlHead, "title")

                val ogDesc = extractMetaContent(htmlHead, "og:description")
                    ?: extractMetaContent(htmlHead, "twitter:description")
                    ?: extractMetaContent(htmlHead, "description")

                var ogImage = extractMetaContent(htmlHead, "og:image")
                    ?: extractMetaContent(htmlHead, "og:image:url")
                    ?: extractMetaContent(htmlHead, "twitter:image")
                    ?: extractMetaContent(htmlHead, "twitter:image:src")
                    ?: extractMetaContent(htmlHead, "image")

                val ogType = extractMetaContent(htmlHead, "og:type") ?: ""
                val ogSiteName = extractMetaContent(htmlHead, "og:site_name")

                val isVideo = ogType.contains("video", ignoreCase = true) ||
                              htmlHead.contains("<video", ignoreCase = true) ||
                              domain.contains("vimeo", ignoreCase = true) ||
                              domain.contains("dailymotion", ignoreCase = true)

                // Resolve relative image URLs if needed
                if (ogImage != null && !ogImage.startsWith("http://") && !ogImage.startsWith("https://")) {
                    try {
                        val baseUri = Uri.parse(cleanUrl)
                        ogImage = if (ogImage.startsWith("//")) {
                            "${baseUri.scheme ?: "https"}:$ogImage"
                        } else if (ogImage.startsWith("/")) {
                            "${baseUri.scheme ?: "https"}://${baseUri.host}$ogImage"
                        } else {
                            "${baseUri.scheme ?: "https"}://${baseUri.host}/$ogImage"
                        }
                    } catch (_: Exception) {}
                }

                val cleanTitle = ogTitle?.let { unescapeHtml(it).trim() }?.takeIf { it.isNotBlank() }
                val cleanDesc = ogDesc?.let { unescapeHtml(it).trim() }?.takeIf { it.isNotBlank() }

                val preview = LinkPreviewData(
                    url = cleanUrl,
                    title = cleanTitle,
                    description = cleanDesc,
                    imageUrl = ogImage?.trim()?.takeIf { it.isNotBlank() },
                    domain = domain,
                    isVideo = isVideo,
                    siteName = ogSiteName?.let { unescapeHtml(it).trim() }
                )

                memoryCache.put(cleanUrl, preview)
                Log.d(TAG, "Fetched preview for $domain: title='${cleanTitle?.take(30)}' isVideo=$isVideo")
                preview
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch link preview for $cleanUrl: ${e.message}")
            val fallback = LinkPreviewData(url = cleanUrl, domain = domain)
            memoryCache.put(cleanUrl, fallback)
            fallback
        }
    }

    private fun fetchYouTubePreview(cleanUrl: String, videoId: String, domain: String): LinkPreviewData? {
        val hqThumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        val maxResThumbnail = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

        var title = if (cleanUrl.contains("/shorts/", ignoreCase = true)) "YouTube Shorts" else "YouTube Video"
        var description = "youtube.com"
        var thumbUrl = hqThumbnail
        var authorName = ""

        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isNotBlank()) {
                        val json = JSONObject(bodyString)
                        title = json.optString("title", title)
                        authorName = json.optString("author_name", "")
                        thumbUrl = json.optString("thumbnail_url", hqThumbnail)
                        description = if (cleanUrl.contains("/shorts/", ignoreCase = true)) {
                            if (authorName.isNotBlank()) "YouTube Shorts • $authorName" else "YouTube Shorts"
                        } else {
                            if (authorName.isNotBlank()) "YouTube • $authorName" else "YouTube"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "YouTube oEmbed error: ${e.message}")
        }

        // Fetch actual YouTube page with social bot user-agent to get rich description snippet (matches WhatsApp)
        try {
            val ytWatchUrl = "https://www.youtube.com/watch?v=$videoId"
            val pageRequest = Request.Builder()
                .url(ytWatchUrl)
                .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                .build()

            httpClient.newCall(pageRequest).execute().use { pageResponse ->
                if (pageResponse.isSuccessful) {
                    val pageHtml = pageResponse.body?.string() ?: ""
                    val descMatcher = Pattern.compile(""""description":\{"simpleText":"([^"]*)"""").matcher(pageHtml)
                    if (descMatcher.find()) {
                        val simpleDesc = descMatcher.group(1)
                        if (!simpleDesc.isNullOrBlank()) {
                            description = unescapeUnicode(unescapeHtml(simpleDesc)).take(180)
                        }
                    } else {
                        val shortDescMatcher = Pattern.compile(""""shortDescription":"([^"]*)"""").matcher(pageHtml)
                        if (shortDescMatcher.find()) {
                            val shortDesc = shortDescMatcher.group(1)
                            if (!shortDesc.isNullOrBlank()) {
                                description = unescapeUnicode(unescapeHtml(shortDesc)).take(140)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return LinkPreviewData(
            url = cleanUrl,
            title = unescapeUnicode(unescapeHtml(title)),
            description = description,
            imageUrl = thumbUrl,
            domain = "youtube.com",
            isVideo = true,
            siteName = "YouTube"
        )
    }

    private fun unescapeUnicode(input: String): String {
        return try {
            val regex = Regex("""\\u([0-9a-fA-F]{4})""")
            regex.replace(input) { matchResult ->
                val codePoint = matchResult.groupValues[1].toInt(16)
                codePoint.toChar().toString()
            }.replace("\\n", " ").replace("\\\"", "\"")
        } catch (_: Exception) { input }
    }

    private fun fetchFxTwitterPreview(cleanUrl: String, domain: String): LinkPreviewData? {
        try {
            val statusPattern = Regex("""/(?:twitter|x)\.com/([^/]+)/status/(\d+)""", RegexOption.IGNORE_CASE)
            val match = statusPattern.find(cleanUrl) ?: return null
            val user = match.groupValues[1]
            val statusId = match.groupValues[2]

            val apiUrl = "https://api.fxtwitter.com/$user/status/$statusId"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "WhatsApp/2.24.1.76 A")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    val json = JSONObject(body)
                    val tweet = json.optJSONObject("tweet") ?: return null
                    val text = tweet.optString("text", "")
                    val author = tweet.optJSONObject("author")
                    val authorName = author?.optString("name", user) ?: user

                    var imgUrl: String? = null
                    var isVideo = false
                    val media = tweet.optJSONObject("media")
                    if (media != null) {
                        val photos = media.optJSONArray("photos")
                        if (photos != null && photos.length() > 0) {
                            val p = photos.getJSONObject(0)
                            if (p.has("url")) imgUrl = p.optString("url")
                        }
                        val videos = media.optJSONArray("videos")
                        if (videos != null && videos.length() > 0) {
                            isVideo = true
                            val v = videos.getJSONObject(0)
                            if (v.has("thumbnail_url")) imgUrl = v.optString("thumbnail_url")
                        }
                    }

                    return LinkPreviewData(
                        url = cleanUrl,
                        title = "$authorName on X",
                        description = text.ifBlank { "Post on X" },
                        imageUrl = imgUrl,
                        domain = domain,
                        isVideo = isVideo,
                        siteName = "X"
                    )
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractMetaContent(html: String, propertyOrName: String): String? {
        val p1 = Pattern.compile(
            """<meta\s+[^>]*?(?:property|name)=["']${Pattern.quote(propertyOrName)}["'][^>]*?content=["']([^"']*)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val m1 = p1.matcher(html)
        if (m1.find()) return m1.group(1)

        val p2 = Pattern.compile(
            """<meta\s+[^>]*?content=["']([^"']*)["'][^>]*?(?:property|name)=["']${Pattern.quote(propertyOrName)}["']""",
            Pattern.CASE_INSENSITIVE
        )
        val m2 = p2.matcher(html)
        if (m2.find()) return m2.group(1)

        return null
    }

    private fun extractTagContent(html: String, tagName: String): String? {
        val p = Pattern.compile("""<$tagName[^>]*>([^<]*)</$tagName>""", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(html)
        return if (m.find()) m.group(1) else null
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#x2F;", "/")
            .replace("&nbsp;", " ")
    }
}
