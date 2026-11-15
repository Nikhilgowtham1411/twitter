package com.nikhil.xsaver

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class MediaKind { VIDEO, PHOTO, GIF }

/** One downloadable file. Videos can have several qualities; photos have a single "Original". */
data class Variant(val url: String, val label: String, val bitrate: Int, val size: Long = 0)

data class MediaItem(
    val kind: MediaKind,
    val thumbnail: String?,
    val variants: List<Variant>,
)

data class Tweet(
    val id: String,
    val authorName: String,
    val authorHandle: String,
    val text: String,
    val media: List<MediaItem>,
)

class FetchException(message: String) : Exception(message)

/**
 * Looks up a post's media without the (paid) official X API.
 * Tries three public endpoints in turn so one of them breaking doesn't break the app:
 *   1. api.fxtwitter.com   2. api.vxtwitter.com   3. X's own syndication endpoint
 */
object TweetFetcher {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    private val ID_REGEX = Regex(
        """(?:twitter\.com|x\.com|fxtwitter\.com|vxtwitter\.com|fixupx\.com)/(?:[A-Za-z0-9_]+|i/web|i)/status(?:es)?/(\d+)"""
    )
    private val SHORT_REGEX = Regex("""https?://t\.co/[A-Za-z0-9]+""")
    private val SIZE_REGEX = Regex("""/(\d{2,5})x(\d{2,5})/""")

    fun extractTweetId(text: String): String? = ID_REGEX.find(text)?.groupValues?.get(1)

    /** e.g. "someuser_1234567890_1_720p.mp4" (quality is part of the name so re-downloads don't clash). */
    fun fileName(tweet: Tweet, index: Int, variant: Variant, kind: MediaKind): String {
        val handle = tweet.authorHandle.ifEmpty { "x" }
        val ext = if (kind == MediaKind.PHOTO) photoExtension(variant.url) else "mp4"
        val quality = if (kind == MediaKind.PHOTO) "" else "_" + variant.label.filter { it.isLetterOrDigit() }
        return "${handle}_${tweet.id}_${index + 1}$quality.$ext"
    }

    fun looksLikeLink(text: String): Boolean =
        extractTweetId(text) != null || SHORT_REGEX.containsMatchIn(text)

    /** Resolve the link, fetch the post and measure how many bytes each quality will use. */
    fun load(text: String): Tweet {
        val id = resolveTweetId(text) ?: throw FetchException("That doesn't look like a link to an X post.")
        val tweet = fetch(id)
        if (tweet.media.isEmpty()) throw FetchException("No photo, video or GIF found in this post.")
        return withSizes(tweet)
    }

    /** Looks up every file's size in parallel so the app can say "1280p · 5.0 MB" before downloading. */
    private fun withSizes(tweet: Tweet): Tweet {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = tweet.media.map { m ->
                m.variants.map { v -> pool.submit(Callable { probeSize(v.url) }) }
            }
            val media = tweet.media.mapIndexed { i, m ->
                m.copy(
                    variants = m.variants.mapIndexed { j, v ->
                        val size = try {
                            futures[i][j].get(10, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            0L
                        }
                        v.copy(size = size)
                    }
                )
            }
            return tweet.copy(media = media)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probeSize(url: String): Long {
        try {
            val head = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (head.responseCode in 200..299) {
                    val length = head.contentLengthLong
                    if (length > 0) return length
                }
            } finally {
                head.disconnect()
            }
        } catch (e: Exception) {
            // fall through to the range probe
        }
        try {
            val get = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                // "Content-Range: bytes 0-0/5242880"
                val total = get.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                if (total != null && total > 0) return total
            } finally {
                get.disconnect()
            }
        } catch (e: Exception) {
            // unknown size
        }
        return 0L
    }

    private fun photoExtension(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".png") -> "png"
            path.endsWith(".webp") -> "webp"
            else -> "jpg"
        }
    }

    /** Accepts a pasted/shared link or the full share text; also follows t.co short links. */
    fun resolveTweetId(text: String): String? {
        extractTweetId(text)?.let { return it }
        val short = SHORT_REGEX.find(text)?.value ?: return null
        return try {
            val conn = (URL(short).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                // t.co answers non-browser clients with a plain redirect header.
                setRequestProperty("User-Agent", "curl/8.5.0")
            }
            val location = try {
                conn.getHeaderField("Location")
            } finally {
                conn.disconnect()
            }
            location?.let { extractTweetId(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun fetch(id: String): Tweet {
        val sources = listOf<(String) -> Tweet>(
            { fromFx(it) },
            { fromVx(it) },
            { fromSyndication(it) },
        )
        var withoutMedia: Tweet? = null
        for (source in sources) {
            try {
                val tweet = source(id)
                if (tweet.media.isNotEmpty()) return tweet
                if (withoutMedia == null) withoutMedia = tweet
            } catch (e: Exception) {
                // try the next source
            }
        }
        return withoutMedia ?: throw FetchException(
            "Couldn't load this post. It may be private or deleted, or you may be offline. Try again in a bit."
        )
    }

    // ---- source 1: fxtwitter -------------------------------------------------------------

    private fun fromFx(id: String): Tweet {
        val root = JSONObject(httpGet("https://api.fxtwitter.com/status/$id"))
        val t = root.optJSONObject("tweet") ?: throw FetchException("fxtwitter: no tweet")
        var media = fxMedia(t.optJSONObject("media"))
        if (media.isEmpty()) media = fxMedia(t.optJSONObject("quote")?.optJSONObject("media"))
        val author = t.optJSONObject("author")
        return Tweet(
            id = id,
            authorName = author?.optString("name").orEmpty(),
            authorHandle = author?.optString("screen_name").orEmpty(),
            text = t.optString("text"),
            media = media,
        )
    }

    private fun fxMedia(media: JSONObject?): List<MediaItem> {
        if (media == null) return emptyList()
        val all = media.optJSONArray("all")
            ?: mergeArrays(media.optJSONArray("videos"), media.optJSONArray("photos"))
        val out = mutableListOf<MediaItem>()
        for (i in 0 until all.length()) {
            val m = all.optJSONObject(i) ?: continue
            val url = m.optString("url")
            if (url.isEmpty()) continue
            val type = m.optString("type").ifEmpty { if (url.contains(".mp4")) "video" else "photo" }
            when (type) {
                "photo" -> out.add(photoItem(url))
                "video", "gif" -> {
                    val variants = mp4Variants(m.optJSONArray("variants") ?: m.optJSONArray("formats"))
                        .ifEmpty { listOf(Variant(url, labelFromUrl(url, 0), 0)) }
                    val kind = if (type == "gif") MediaKind.GIF else MediaKind.VIDEO
                    out.add(MediaItem(kind, m.optString("thumbnail_url").ifEmpty { null }, variants))
                }
            }
        }
        return out
    }

    // ---- source 2: vxtwitter -------------------------------------------------------------

    private fun fromVx(id: String): Tweet {
        val t = JSONObject(httpGet("https://api.vxtwitter.com/Twitter/status/$id"))
        val arr = t.optJSONArray("media_extended") ?: JSONArray()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val url = m.optString("url")
            if (url.isEmpty()) continue
            val type = m.optString("type")
            when (type) {
                "image" -> out.add(photoItem(url))
                "video", "gif" -> {
                    val kind = if (type == "gif") MediaKind.GIF else MediaKind.VIDEO
                    out.add(
                        MediaItem(
                            kind,
                            m.optString("thumbnail_url").ifEmpty { null },
                            listOf(Variant(url, labelFromUrl(url, 0), 0)),
                        )
                    )
                }
            }
        }
        return Tweet(
            id = id,
            authorName = t.optString("user_name"),
            authorHandle = t.optString("user_screen_name"),
            text = t.optString("text"),
            media = out,
        )
    }

    // ---- source 3: X syndication (what embedded tweets use) ------------------------------

    private fun fromSyndication(id: String): Tweet {
        val url = "https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${syndicationToken(id)}"
        val root = JSONObject(httpGet(url))
        val details = root.optJSONArray("mediaDetails") ?: JSONArray()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until details.length()) {
            val m = details.optJSONObject(i) ?: continue
            val thumb = m.optString("media_url_https").ifEmpty { null }
            val type = m.optString("type")
            if (type == "photo") {
                if (thumb != null) out.add(photoItem(thumb))
            } else if (type == "video" || type == "animated_gif") {
                val variants = mp4Variants(m.optJSONObject("video_info")?.optJSONArray("variants"))
                if (variants.isNotEmpty()) {
                    val kind = if (type == "animated_gif") MediaKind.GIF else MediaKind.VIDEO
                    out.add(MediaItem(kind, thumb, variants))
                }
            }
        }
        val user = root.optJSONObject("user")
        return Tweet(
            id = id,
            authorName = user?.optString("name").orEmpty(),
            authorHandle = user?.optString("screen_name").orEmpty(),
            text = root.optString("text"),
            media = out,
        )
    }

    /** Same formula X's own embed widget uses; the endpoint is lenient but wants something plausible. */
    private fun syndicationToken(id: String): String {
        val value = id.toDouble() / 1e15 * Math.PI
        val whole = value.toLong()
        var frac = value - whole
        val sb = StringBuilder(java.lang.Long.toString(whole, 36))
        repeat(11) {
            frac *= 36
            val digit = frac.toInt()
            frac -= digit
            sb.append(Character.forDigit(digit, 36))
        }
        return sb.toString().replace("0", "")
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw FetchException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun mergeArrays(a: JSONArray?, b: JSONArray?): JSONArray {
        val merged = JSONArray()
        for (arr in listOf(a, b)) {
            if (arr == null) continue
            for (i in 0 until arr.length()) merged.put(arr.get(i))
        }
        return merged
    }

    private fun mp4Variants(arr: JSONArray?): List<Variant> {
        if (arr == null) return emptyList()
        val list = mutableListOf<Variant>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val url = v.optString("url")
            val type = v.optString("content_type").ifEmpty { v.optString("container") }
            val isMp4 = type.contains("mp4") ||
                (type.isEmpty() && url.substringBefore('?').endsWith(".mp4"))
            if (url.isEmpty() || !isMp4) continue
            val bitrate = v.optInt("bitrate")
            list.add(Variant(url, labelFromUrl(url, bitrate), bitrate))
        }
        return list.distinctBy { it.url }.sortedByDescending { it.bitrate }
    }

    /** "…/vid/avc1/720x1280/…" -> "720p" (shorter side, so portrait clips read naturally). */
    private fun labelFromUrl(url: String, bitrate: Int): String {
        SIZE_REGEX.find(url)?.let {
            val w = it.groupValues[1].toInt()
            val h = it.groupValues[2].toInt()
            return "${min(w, h)}p"
        }
        return if (bitrate > 0) "${bitrate / 1000} kbps" else "Best"
    }

    private fun photoItem(url: String): MediaItem =
        MediaItem(MediaKind.PHOTO, url, listOf(Variant(originalPhoto(url), "Original", 0)))

    /** Ask X's image server for the full-resolution file instead of the "large" preview. */
    private fun originalPhoto(url: String): String {
        if (!url.contains("pbs.twimg.com/media/")) return url
        val base = url.substringBefore('?')
        val last = base.substringAfterLast('/')
        if (last.contains('.')) {
            return base.replace(Regex(":(orig|large|medium|small|thumb)$"), "") + "?name=orig"
        }
        val format = Regex("format=(\\w+)").find(url)?.groupValues?.get(1) ?: "jpg"
        return "$base.$format?name=orig"
    }
}
