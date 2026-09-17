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
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class LinkPreviewData(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val domain: String = ""
)

object LinkPreviewHelper {

    private const val TAG = "LinkPreviewHelper"
    private val memoryCache = LruCache<String, LinkPreviewData>(200)

    val URL_REGEX = Regex(
        """https?://[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '"', '\'', ';', ':', '>', '}')

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

    fun getCachedPreview(url: String): LinkPreviewData? {
        return memoryCache.get(url)
    }

    fun getPreviewFlow(url: String): Flow<LinkPreviewData?> = flow {
        val cached = memoryCache.get(url)
        if (cached != null) {
            emit(cached)
            return@flow
        }
        emit(null)
        val preview = fetchPreview(url)
        emit(preview)
    }.flowOn(Dispatchers.IO)

    suspend fun fetchPreview(rawUrl: String): LinkPreviewData? = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim().trimEnd(*TRAILING_PUNCTUATION)
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext null
        }

        memoryCache.get(cleanUrl)?.let { return@withContext it }

        val domain = extractDomain(cleanUrl)

        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 (compatible; WhatsApp/2.24)")
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
                // If the link directly points to an image
                if (contentType.startsWith("image/")) {
                    val imgPreview = LinkPreviewData(
                        url = cleanUrl,
                        title = domain,
                        imageUrl = cleanUrl,
                        domain = domain
                    )
                    memoryCache.put(cleanUrl, imgPreview)
                    return@withContext imgPreview
                }

                val bodySource = response.body?.source() ?: return@withContext LinkPreviewData(url = cleanUrl, domain = domain)

                // Read up to 80KB of HTML head to parse meta tags quickly without downloading large files
                val maxBytes = 80 * 1024L
                val htmlHead = bodySource.buffer.clone().readUtf8(minOf(bodySource.buffer.size, maxBytes)).takeIf { it.isNotBlank() }
                    ?: bodySource.readUtf8(maxBytes)

                val ogTitle = extractMetaContent(htmlHead, "og:title")
                    ?: extractMetaContent(htmlHead, "twitter:title")
                    ?: extractTagContent(htmlHead, "title")

                val ogDesc = extractMetaContent(htmlHead, "og:description")
                    ?: extractMetaContent(htmlHead, "twitter:description")
                    ?: extractMetaContent(htmlHead, "description")

                var ogImage = extractMetaContent(htmlHead, "og:image")
                    ?: extractMetaContent(htmlHead, "twitter:image")
                    ?: extractMetaContent(htmlHead, "image")

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
                    domain = domain
                )

                memoryCache.put(cleanUrl, preview)
                Log.d(TAG, "Fetched preview for $domain: title='${cleanTitle?.take(30)}' hasImage=${preview.imageUrl != null}")
                preview
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch link preview for $cleanUrl: ${e.message}")
            val fallback = LinkPreviewData(url = cleanUrl, domain = domain)
            memoryCache.put(cleanUrl, fallback)
            fallback
        }
    }

    private fun extractMetaContent(html: String, propertyOrName: String): String? {
        // Match <meta property="prop" content="val"> or <meta content="val" property="prop">
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
