package com.nikhil.xsaver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A post whose link was saved, with every quality and its size, so you can download later. */
data class SavedLink(
    val tweet: Tweet,
    val time: Long,
    /** media index -> quality label that was downloaded last */
    val downloaded: Map<Int, String> = emptyMap(),
)

/**
 * Links you pasted/shared/saved from the notification panel. They stay in the app (saved to a
 * small JSON file) until you remove them, so you can come back, pick or change the quality and
 * download whenever you like.
 */
object LinkStore {
    private const val MAX_LINKS = 50

    private val _links = MutableStateFlow<List<SavedLink>>(emptyList())
    val links: StateFlow<List<SavedLink>> = _links.asStateFlow()

    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "links.json")
        file = f
        _links.value = load(f)
    }

    fun add(tweet: Tweet) {
        _links.update { list ->
            val old = list.firstOrNull { it.tweet.id == tweet.id }
            val fresh = SavedLink(tweet, System.currentTimeMillis(), old?.downloaded ?: emptyMap())
            (listOf(fresh) + list.filter { it.tweet.id != tweet.id }).take(MAX_LINKS)
        }
        save()
    }

    fun remove(tweetId: String) {
        _links.update { list -> list.filter { it.tweet.id != tweetId } }
        save()
    }

    fun markDownloaded(tweetId: String, index: Int, label: String) {
        _links.update { list ->
            list.map { if (it.tweet.id == tweetId) it.copy(downloaded = it.downloaded + (index to label)) else it }
        }
        save()
    }

    // ---- persistence ---------------------------------------------------------------------

    @Synchronized
    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            _links.value.forEach { arr.put(toJson(it)) }
            f.writeText(arr.toString())
        } catch (e: Exception) {
            // Saved links are a convenience; never crash because they couldn't be written.
        }
    }

    private fun load(f: File): List<SavedLink> {
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<SavedLink>()
            for (i in 0 until arr.length()) {
                fromJson(arr.getJSONObject(i))?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toJson(l: SavedLink): JSONObject {
        val t = l.tweet
        val media = JSONArray()
        t.media.forEach { m ->
            val variants = JSONArray()
            m.variants.forEach { v ->
                variants.put(
                    JSONObject()
                        .put("url", v.url)
                        .put("label", v.label)
                        .put("bitrate", v.bitrate)
                        .put("size", v.size)
                )
            }
            val mo = JSONObject().put("kind", m.kind.name).put("variants", variants)
            m.thumbnail?.let { mo.put("thumb", it) }
            media.put(mo)
        }
        val downloaded = JSONObject()
        l.downloaded.forEach { (index, label) -> downloaded.put(index.toString(), label) }
        return JSONObject()
            .put("time", l.time)
            .put("id", t.id)
            .put("name", t.authorName)
            .put("handle", t.authorHandle)
            .put("text", t.text)
            .put("media", media)
            .put("downloaded", downloaded)
    }

    private fun fromJson(o: JSONObject): SavedLink? = try {
        val mediaArr = o.getJSONArray("media")
        val media = mutableListOf<MediaItem>()
        for (i in 0 until mediaArr.length()) {
            val mo = mediaArr.getJSONObject(i)
            val va = mo.getJSONArray("variants")
            val variants = mutableListOf<Variant>()
            for (j in 0 until va.length()) {
                val v = va.getJSONObject(j)
                variants.add(Variant(v.getString("url"), v.getString("label"), v.optInt("bitrate"), v.optLong("size")))
            }
            if (variants.isEmpty()) continue
            media.add(
                MediaItem(
                    kind = MediaKind.valueOf(mo.getString("kind")),
                    thumbnail = mo.optString("thumb").ifEmpty { null },
                    variants = variants,
                )
            )
        }
        val downloaded = mutableMapOf<Int, String>()
        o.optJSONObject("downloaded")?.let { d ->
            d.keys().forEach { key -> key.toIntOrNull()?.let { downloaded[it] = d.getString(key) } }
        }
        if (media.isEmpty()) {
            null
        } else {
            SavedLink(
                tweet = Tweet(
                    id = o.getString("id"),
                    authorName = o.optString("name"),
                    authorHandle = o.optString("handle"),
                    text = o.optString("text"),
                    media = media,
                ),
                time = o.optLong("time"),
                downloaded = downloaded,
            )
        }
    } catch (e: Exception) {
        null
    }
}
