package org.futo.inputmethod.latin.uix.actions.gif

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class GifSource { Klipy, RedGifs, Giphy }

data class GifMedia(val url: String, val width: Int, val height: Int)

data class GifResult(
    val id: String,
    val title: String,
    val source: GifSource,
    /** Shown in the grid. Animated for Klipy (webp/gif), a still image for RedGIFs */
    val preview: GifMedia,
    /** Sent as image/gif when the app accepts it */
    val gif: GifMedia?,
    /** Sent as video/mp4 when the app accepts it, otherwise converted to a GIF */
    val mp4: GifMedia?
)

data class GifPage(val results: List<GifResult>, val hasMore: Boolean)

class GifApiException(message: String, val code: Int = 0) : IOException(message)

private const val USER_AGENT = "FUTO-Keyboard-Cow/1.0 (Android)"

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

internal fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 20_000
    conn.setRequestProperty("User-Agent", USER_AGENT)
    conn.setRequestProperty("Accept", "application/json")
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw GifApiException("HTTP $code", code)
        return body
    } finally {
        conn.disconnect()
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

/** Regular GIFs. Needs a free API key from https://partner.klipy.com */
object KlipyProvider {
    private const val BASE = "https://api.klipy.com/api/v1"

    suspend fun fetch(apiKey: String, customerId: String, query: String?, page: Int): GifPage = withContext(Dispatchers.IO) {
        val endpoint = if (query.isNullOrBlank()) "trending" else "search"
        val url = buildString {
            append("$BASE/${enc(apiKey)}/gifs/$endpoint?page=$page&per_page=30&customer_id=${enc(customerId)}")
            if (!query.isNullOrBlank()) append("&q=${enc(query.trim())}")
        }

        val root = try {
            Json.parseToJsonElement(httpGet(url)).jsonObject
        } catch (e: GifApiException) {
            if (e.code == 401 || e.code == 403) throw GifApiException("Klipy rejected the API key", e.code)
            throw e
        }

        if ((root["result"] as? JsonPrimitive)?.booleanOrNull == false) {
            throw GifApiException(root.str("message") ?: "Klipy returned an error")
        }

        val data = root["data"] as? JsonObject ?: throw GifApiException("Unexpected Klipy response")
        val items = (data["data"] as? JsonArray) ?: JsonArray(emptyList())

        GifPage(
            results = items.mapNotNull { (it as? JsonObject)?.let(::parseItem) },
            hasMore = (data["has_next"] as? JsonPrimitive)?.booleanOrNull ?: false
        )
    }

    private fun media(file: JsonObject, size: String, format: String): GifMedia? {
        val o = (file[size] as? JsonObject)?.get(format) as? JsonObject ?: return null
        val url = o.str("url") ?: return null
        return GifMedia(url, o.int("width"), o.int("height"))
    }

    private fun parseItem(item: JsonObject): GifResult? {
        // Ad slots and other non-GIF items are skipped
        if (item.str("type").let { it != null && it != "gif" }) return null
        val file = (item["file"] ?: item["files"]) as? JsonObject ?: return null

        val preview = media(file, "sm", "webp") ?: media(file, "sm", "gif")
            ?: media(file, "xs", "gif") ?: media(file, "md", "gif") ?: return null
        val gif = media(file, "md", "gif") ?: media(file, "sm", "gif") ?: media(file, "hd", "gif")
        val mp4 = media(file, "md", "mp4") ?: media(file, "sm", "mp4") ?: media(file, "hd", "mp4")
        if (gif == null && mp4 == null) return null

        return GifResult(
            id = item.str("slug") ?: item.str("id") ?: preview.url,
            title = item.str("title") ?: "",
            source = GifSource.Klipy,
            preview = preview,
            gif = gif,
            mp4 = mp4
        )
    }
}

/**
 * GIPHY, used in 18+ mode for its R-rated (suggestive, not explicit) GIFs alongside RedGIFs.
 * Needs a free API key from https://developers.giphy.com (beta keys allow 100 calls/hour).
 */
object GiphyProvider {
    private const val BASE = "https://api.giphy.com/v1/gifs"
    private const val PAGE_SIZE = 30
    // GIPHY rejects offsets past this
    private const val MAX_OFFSET = 4999

    suspend fun fetch(apiKey: String, query: String?, page: Int, rating: String = "r"): GifPage = withContext(Dispatchers.IO) {
        val offset = (page - 1) * PAGE_SIZE
        if (offset > MAX_OFFSET) return@withContext GifPage(emptyList(), false)

        val url = buildString {
            if (query.isNullOrBlank()) {
                append("$BASE/trending?")
            } else {
                append("$BASE/search?q=${enc(query.trim())}&lang=en&")
            }
            append("api_key=${enc(apiKey)}&limit=$PAGE_SIZE&offset=$offset&rating=$rating")
        }

        val root = try {
            Json.parseToJsonElement(httpGet(url)).jsonObject
        } catch (e: GifApiException) {
            throw when (e.code) {
                401, 403 -> GifApiException("GIPHY rejected the API key", e.code)
                429 -> GifApiException("GIPHY limit reached (free keys allow 100 searches an hour)", e.code)
                else -> e
            }
        }

        val items = root["data"] as? JsonArray ?: JsonArray(emptyList())
        val pagination = root["pagination"] as? JsonObject
        val total = pagination?.str("total_count")?.toIntOrNull() ?: 0
        val count = pagination?.str("count")?.toIntOrNull() ?: items.size

        GifPage(
            results = items.mapNotNull { (it as? JsonObject)?.let(::parseItem) },
            hasMore = count > 0 && offset + count < minOf(total, MAX_OFFSET)
        )
    }

    // GIPHY sends sizes as strings
    private fun rendition(images: JsonObject, name: String, urlKey: String): GifMedia? {
        val o = images[name] as? JsonObject ?: return null
        val url = o.str(urlKey) ?: return null
        return GifMedia(url, o.str("width")?.toIntOrNull() ?: 0, o.str("height")?.toIntOrNull() ?: 0)
    }

    private fun parseItem(item: JsonObject): GifResult? {
        val id = item.str("id") ?: return null
        val images = item["images"] as? JsonObject ?: return null

        val preview = rendition(images, "fixed_width", "webp") ?: rendition(images, "fixed_width", "url") ?: return null
        val gif = rendition(images, "downsized_medium", "url") ?: rendition(images, "original", "url")
        val mp4 = rendition(images, "original_mp4", "mp4") ?: rendition(images, "original", "mp4")
        if (gif == null && mp4 == null) return null

        return GifResult(
            id = id,
            title = item.str("title") ?: "",
            source = GifSource.Giphy,
            preview = preview,
            gif = gif,
            mp4 = mp4
        )
    }
}

/** Adult (18+) GIFs. Uses RedGIFs' free temporary tokens, no account needed. Media is MP4 only. */
object RedGifsProvider {
    private const val BASE = "https://api.redgifs.com/v2"

    private val tokenLock = Mutex()
    private var token: String? = null
    private var tokenExpiresAt = 0L

    private suspend fun getToken(forceRefresh: Boolean): String = tokenLock.withLock {
        val now = System.currentTimeMillis()
        token?.let { if (!forceRefresh && now < tokenExpiresAt) return@withLock it }

        val obj = Json.parseToJsonElement(httpGet("$BASE/auth/temporary")).jsonObject
        val newToken = obj.str("token") ?: throw GifApiException("RedGIFs did not return a token")
        token = newToken
        // Tokens last 24 hours, refresh a bit early
        tokenExpiresAt = now + 20L * 60L * 60L * 1000L
        newToken
    }

    suspend fun fetch(query: String?, page: Int): GifPage = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$BASE/gifs/search?count=30&page=$page")
            if (query.isNullOrBlank()) {
                append("&order=top7")
            } else {
                append("&order=top&query=${enc(query.trim())}")
            }
        }

        val body = try {
            httpGet(url, mapOf("Authorization" to "Bearer ${getToken(false)}"))
        } catch (e: GifApiException) {
            if (e.code != 401) throw e
            httpGet(url, mapOf("Authorization" to "Bearer ${getToken(true)}"))
        }

        val root = Json.parseToJsonElement(body).jsonObject
        val gifs = root["gifs"]?.jsonArray ?: JsonArray(emptyList())
        val pages = root.int("pages")

        GifPage(
            results = gifs.mapNotNull { (it as? JsonObject)?.let(::parseItem) },
            hasMore = page < pages
        )
    }

    private fun parseItem(item: JsonObject): GifResult? {
        val id = item.str("id") ?: return null
        val urls = item["urls"] as? JsonObject ?: return null
        val width = item.int("width")
        val height = item.int("height")

        val preview = urls.str("thumbnail") ?: urls.str("poster") ?: return null
        val mp4 = urls.str("sd") ?: urls.str("hd") ?: return null

        return GifResult(
            id = id,
            title = (item["tags"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.take(3)?.joinToString(", ") ?: "",
            source = GifSource.RedGifs,
            preview = GifMedia(preview, width, height),
            gif = null,
            mp4 = GifMedia(mp4, width, height)
        )
    }
}
