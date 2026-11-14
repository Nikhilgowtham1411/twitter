package com.nikhil.xsaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that works through a queue: it can look up a shared post link
 * (quick save) and then downloads files one after another. Every step is mirrored into
 * [DownloadStore] (the in-app history) and shown as a progress notification with Cancel.
 * Files go into MediaStore (Movies/XSaver or Pictures/XSaver), so no storage permission is needed.
 */
class DownloadService : Service() {

    private sealed interface Work
    private data class FetchWork(val entryId: Long, val text: String) : Work
    private data class DownloadWork(val entryId: Long) : Work

    private class UserCancelled : Exception()

    companion object {
        private const val CHANNEL_PROGRESS = "xsaver_progress"
        private const val CHANNEL_DONE = "xsaver_done"
        private const val FG_ID = 1
        private const val ACTION_CANCEL = "com.nikhil.xsaver.CANCEL"
        private const val EXTRA_ID = "entry_id"
        private const val EXTRA_TEXT = "text"

        /** Queue files from an already-fetched post (the in-app flow). */
        fun enqueueDownloads(context: Context, tweet: Tweet, picks: List<Pair<Int, Variant>>) {
            DownloadStore.init(context)
            val now = System.currentTimeMillis()
            for ((index, variant) in picks) {
                val media = tweet.media[index]
                val entry = HistoryItem(
                    id = DownloadStore.newId(),
                    title = titleFor(tweet, media.kind, index),
                    fileName = TweetFetcher.fileName(tweet, index, variant.url, media.kind),
                    kind = media.kind,
                    thumb = media.thumbnail,
                    url = variant.url,
                    status = Status.QUEUED,
                    time = now,
                )
                DownloadStore.add(entry)
                start(context, Intent(context, DownloadService::class.java).putExtra(EXTRA_ID, entry.id))
            }
        }

        /** Quick save: hand over the shared text, the service looks the post up and downloads everything. */
        fun enqueueShared(context: Context, text: String) {
            DownloadStore.init(context)
            val entry = HistoryItem(
                id = DownloadStore.newId(),
                title = "Fetching post…",
                fileName = "",
                kind = MediaKind.VIDEO,
                thumb = null,
                url = null,
                status = Status.FETCHING,
            )
            DownloadStore.add(entry)
            start(
                context,
                Intent(context, DownloadService::class.java)
                    .putExtra(EXTRA_ID, entry.id)
                    .putExtra(EXTRA_TEXT, text),
            )
        }

        fun retry(context: Context, item: HistoryItem) {
            if (item.url == null) return
            DownloadStore.update(item.id) {
                it.copy(status = Status.QUEUED, progress = 0, bytes = 0, error = null)
            }
            start(context, Intent(context, DownloadService::class.java).putExtra(EXTRA_ID, item.id))
        }

        private fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent)
        }

        private fun titleFor(tweet: Tweet, kind: MediaKind, index: Int): String {
            val label = when (kind) {
                MediaKind.VIDEO -> "Video"
                MediaKind.GIF -> "GIF"
                MediaKind.PHOTO -> "Photo"
            }
            val who = if (tweet.authorHandle.isNotEmpty()) "@${tweet.authorHandle} · " else ""
            return "$who$label ${index + 1}"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<Work>()
    private var worker: Job? = null
    private val nextDoneId = AtomicInteger(1000)
    private var lastForeground: Notification? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Required within a few seconds of startForegroundService().
        val initial = lastForeground ?: progressNotification("Starting", "Preparing…", 0, true)
        ServiceCompat.startForeground(this, FG_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (intent?.action == ACTION_CANCEL) {
            queue.clear()
            DownloadStore.cancelAll()
            return START_NOT_STICKY
        }

        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        if (id != -1L) {
            val text = intent?.getStringExtra(EXTRA_TEXT)
            queue.add(if (text != null) FetchWork(id, text) else DownloadWork(id))
        }
        if (worker?.isActive != true) launchWorker()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        DownloadStore.items.value.filter { it.isActive }.forEach { item ->
            DownloadStore.update(item.id) {
                it.copy(status = Status.FAILED, error = "Interrupted. Tap Retry.")
            }
        }
        super.onDestroy()
    }

    private fun launchWorker() {
        worker = scope.launch {
            while (true) {
                val work = queue.poll() ?: break
                when (work) {
                    is FetchWork -> fetch(work)
                    is DownloadWork -> download(work.entryId)
                }
            }
            // Decide on the main thread so work enqueued right now isn't lost.
            withContext(Dispatchers.Main) {
                if (queue.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    launchWorker()
                }
            }
        }
    }

    // ---- quick save: look the post up, then queue its media ------------------------------

    private fun fetch(w: FetchWork) {
        val entry = DownloadStore.get(w.entryId) ?: return
        if (entry.status != Status.FETCHING) return
        updateProgress("Fetching post", "Looking up media…", 0, true)
        try {
            val id = TweetFetcher.resolveTweetId(w.text)
                ?: throw FetchException("That doesn't look like a link to an X post.")
            val tweet = TweetFetcher.fetch(id)
            if (tweet.media.isEmpty()) throw FetchException("No photo, video or GIF found in this post.")

            if (DownloadStore.get(w.entryId)?.status == Status.CANCELLED) return
            DownloadStore.remove(w.entryId)

            val now = System.currentTimeMillis()
            tweet.media.forEachIndexed { index, media ->
                val variant = media.variants.first()
                val item = HistoryItem(
                    id = DownloadStore.newId(),
                    title = titleFor(tweet, media.kind, index),
                    fileName = TweetFetcher.fileName(tweet, index, variant.url, media.kind),
                    kind = media.kind,
                    thumb = media.thumbnail,
                    url = variant.url,
                    status = Status.QUEUED,
                    time = now,
                )
                DownloadStore.add(item)
                queue.add(DownloadWork(item.id))
            }
        } catch (e: Exception) {
            val reason = e.message ?: "Couldn't fetch this post."
            DownloadStore.update(w.entryId) {
                it.copy(title = "Couldn't fetch post", status = Status.FAILED, error = reason)
            }
            notifyFailedText("Couldn't fetch post", reason)
        }
    }

    // ---- downloading ---------------------------------------------------------------------

    private fun download(entryId: Long) {
        val entry = DownloadStore.get(entryId) ?: return
        val url = entry.url ?: return
        if (entry.status != Status.QUEUED) return

        DownloadStore.update(entryId) {
            it.copy(status = Status.DOWNLOADING, progress = 0, bytes = 0, error = null)
        }
        val waiting = queue.size
        val title = if (waiting > 0) "Downloading (+$waiting waiting)" else "Downloading"
        updateProgress(title, entry.fileName, 0, true)

        val resolver = contentResolver
        val isVideo = entry.kind != MediaKind.PHOTO
        val mime = mimeFor(entry)
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/XSaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        var created: Uri? = null
        try {
            val target = resolver.insert(collection, values) ?: throw IOException("Couldn't create the file")
            created = target

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", TweetFetcher.USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("Server replied HTTP $code")
                val length = conn.contentLengthLong
                conn.inputStream.use { input ->
                    val out = resolver.openOutputStream(target) ?: throw IOException("Couldn't open the file")
                    out.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var sum = 0L
                        var lastUpdate = 0L
                        while (true) {
                            if (DownloadStore.isCancelRequested(entryId)) throw UserCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            stream.write(buffer, 0, n)
                            sum += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 400) {
                                lastUpdate = now
                                val pct = if (length > 0) ((sum * 100) / length).toInt() else 0
                                DownloadStore.update(entryId, persist = false) {
                                    it.copy(progress = pct, bytes = sum)
                                }
                                val text = if (length > 0) "${entry.fileName} · $pct%" else "${entry.fileName} · ${formatSize(sum)}"
                                updateProgress(title, text, pct, length <= 0)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
            DownloadStore.update(entryId) {
                it.copy(status = Status.DONE, progress = 100, uri = target.toString(), mime = mime, error = null)
            }
            notifyDone(target, mime, entry)
        } catch (e: UserCancelled) {
            created?.let { runCatching { resolver.delete(it, null, null) } }
            DownloadStore.update(entryId) { it.copy(status = Status.CANCELLED, error = null) }
        } catch (e: Exception) {
            created?.let { runCatching { resolver.delete(it, null, null) } }
            val reason = e.message ?: "unknown error"
            DownloadStore.update(entryId) { it.copy(status = Status.FAILED, error = reason) }
            notifyFailedText("Download failed", "${entry.fileName}: $reason")
        } finally {
            DownloadStore.clearCancelFlag(entryId)
        }
    }

    private fun mimeFor(item: HistoryItem): String = when {
        item.kind != MediaKind.PHOTO -> "video/mp4"
        item.fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        item.fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }

    // ---- notifications -------------------------------------------------------------------

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(title: String, text: String, percent: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent, indeterminate)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel all", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        lastForeground = notification
        return notification
    }

    private fun updateProgress(title: String, text: String, percent: Int, indeterminate: Boolean) {
        nm.notify(FG_ID, progressNotification(title, text, percent, indeterminate))
    }

    private fun notifyDone(uri: Uri, mime: String, item: HistoryItem) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = nextDoneId.getAndIncrement()
        val pending = PendingIntent.getActivity(
            this,
            id,
            view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val folder = if (item.kind == MediaKind.PHOTO) "Pictures/XSaver" else "Movies/XSaver"
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText("${item.fileName} · $folder")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }

    private fun notifyFailedText(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(nextDoneId.getAndIncrement(), notification)
    }
}
