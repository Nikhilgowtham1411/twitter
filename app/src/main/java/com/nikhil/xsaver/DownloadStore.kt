package com.nikhil.xsaver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class Status { FETCHING, QUEUED, DOWNLOADING, DONE, FAILED, CANCELLED }

data class HistoryItem(
    val id: Long,
    val title: String,
    val fileName: String,
    val kind: MediaKind,
    val thumb: String?,
    val url: String?,
    val status: Status,
    val progress: Int = 0,
    val bytes: Long = 0,
    val uri: String? = null,
    val mime: String? = null,
    val error: String? = null,
    val time: Long = System.currentTimeMillis(),
) {
    val isActive: Boolean
        get() = status == Status.FETCHING || status == Status.QUEUED || status == Status.DOWNLOADING
}

fun formatSize(bytes: Long): String =
    if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${bytes / 1024} KB"

/**
 * Process-wide list of everything the app has downloaded (or is downloading), shown in the app
 * as "Downloads". The service writes progress here; the UI just observes [items].
 * Saved to a small JSON file so the history survives the app being closed.
 */
object DownloadStore {
    private const val MAX_ITEMS = 100

    private val order = compareByDescending<HistoryItem> { it.time }.thenBy { it.id }

    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    private val ids = AtomicLong(System.currentTimeMillis())
    private val cancelFlags: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "history.json")
        file = f
        _items.value = load(f)
    }

    fun newId(): Long = ids.incrementAndGet()

    fun get(id: Long): HistoryItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: HistoryItem) {
        _items.update { (it + item).sortedWith(order).take(MAX_ITEMS) }
        save()
    }

    fun update(id: Long, persist: Boolean = true, change: (HistoryItem) -> HistoryItem) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
        if (persist) save()
    }

    fun remove(id: Long) {
        _items.update { list -> list.filter { it.id != id } }
        save()
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.isActive } }
        save()
    }

    // ---- cancelling ----------------------------------------------------------------------

    fun requestCancel(id: Long) {
        val item = get(id) ?: return
        when (item.status) {
            Status.QUEUED, Status.FETCHING -> update(id) { it.copy(status = Status.CANCELLED) }
            Status.DOWNLOADING -> cancelFlags.add(id)
            else -> Unit
        }
    }

    fun cancelAll() {
        _items.value.filter { it.isActive }.forEach { requestCancel(it.id) }
    }

    fun isCancelRequested(id: Long): Boolean = cancelFlags.contains(id)

    fun clearCancelFlag(id: Long) {
        cancelFlags.remove(id)
    }

    // ---- persistence ---------------------------------------------------------------------

    @Synchronized
    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            _items.value.forEach { arr.put(toJson(it)) }
            f.writeText(arr.toString())
        } catch (e: Exception) {
            // History is a convenience; never crash a download because it couldn't be saved.
        }
    }

    private fun load(f: File): List<HistoryItem> {
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val item = fromJson(arr.getJSONObject(i)) ?: continue
                // The app was closed mid-download last time.
                list += if (item.isActive) {
                    item.copy(status = Status.FAILED, error = "Interrupted. Tap Retry.")
                } else {
                    item
                }
            }
            list.sortedWith(order).take(MAX_ITEMS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toJson(i: HistoryItem): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("title", i.title)
        put("fileName", i.fileName)
        put("kind", i.kind.name)
        put("status", i.status.name)
        put("progress", i.progress)
        put("bytes", i.bytes)
        put("time", i.time)
        i.thumb?.let { put("thumb", it) }
        i.url?.let { put("url", it) }
        i.uri?.let { put("uri", it) }
        i.mime?.let { put("mime", it) }
        i.error?.let { put("error", it) }
    }

    private fun fromJson(o: JSONObject): HistoryItem? = try {
        HistoryItem(
            id = o.getLong("id"),
            title = o.getString("title"),
            fileName = o.getString("fileName"),
            kind = MediaKind.valueOf(o.getString("kind")),
            thumb = o.optString("thumb").ifEmpty { null },
            url = o.optString("url").ifEmpty { null },
            status = Status.valueOf(o.getString("status")),
            progress = o.optInt("progress"),
            bytes = o.optLong("bytes"),
            uri = o.optString("uri").ifEmpty { null },
            mime = o.optString("mime").ifEmpty { null },
            error = o.optString("error").ifEmpty { null },
            time = o.optLong("time"),
        )
    } catch (e: Exception) {
        null
    }
}
