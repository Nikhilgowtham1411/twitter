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
 * Foreground service that downloads queued files one after another, shows a live progress
 * notification (with Cancel), saves into MediaStore (Movies/XSaver or Pictures/XSaver, so no
 * storage permission is needed) and posts a "tap to open" notification for each finished file.
 */
class DownloadService : Service() {

    private data class Request(val url: String, val fileName: String, val kind: MediaKind)

    private class UserCancelled : Exception()

    companion object {
        private const val CHANNEL_PROGRESS = "xsaver_progress"
        private const val CHANNEL_DONE = "xsaver_done"
        private const val FG_ID = 1
        private const val ACTION_CANCEL = "com.nikhil.xsaver.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_KIND = "kind"

        fun enqueue(context: Context, url: String, fileName: String, kind: MediaKind) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, fileName)
                .putExtra(EXTRA_KIND, kind.name)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<Request>()
    private var worker: Job? = null

    @Volatile
    private var cancelled = false
    private val completed = AtomicInteger(0)
    private val total = AtomicInteger(0)
    private val nextDoneId = AtomicInteger(1000)
    private var lastForeground: Notification? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        val initial = lastForeground ?: progressNotification("Starting download", "Preparing…", 0, true)
        ServiceCompat.startForeground(this, FG_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            queue.clear()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        val name = intent?.getStringExtra(EXTRA_NAME)
        val kind = intent?.getStringExtra(EXTRA_KIND)?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
        if (url != null && name != null && kind != null) {
            if (worker?.isActive != true) {
                cancelled = false
                completed.set(0)
                total.set(0)
            }
            queue.add(Request(url, name, kind))
            total.incrementAndGet()
        }
        if (worker?.isActive != true) launchWorker()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        scope.cancel()
        super.onDestroy()
    }

    private fun launchWorker() {
        worker = scope.launch {
            while (true) {
                val request = queue.poll() ?: break
                download(request)
            }
            // Decide on the main thread so a download enqueued right now isn't lost.
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

    private fun download(r: Request) {
        val title = if (total.get() > 1) "Downloading ${completed.get() + 1} of ${total.get()}" else "Downloading"
        updateProgress(title, r.fileName, 0, true)

        val resolver = contentResolver
        val isVideo = r.kind != MediaKind.PHOTO
        val mime = mimeFor(r)
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, r.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/XSaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        var created: Uri? = null
        try {
            val target = resolver.insert(collection, values) ?: throw IOException("Couldn't create the file")
            created = target

            val conn = (URL(r.url).openConnection() as HttpURLConnection).apply {
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
                            if (cancelled) throw UserCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            stream.write(buffer, 0, n)
                            sum += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 400) {
                                lastUpdate = now
                                val pct = if (length > 0) ((sum * 100) / length).toInt() else 0
                                updateProgress(title, "${r.fileName} · ${formatSize(sum)}", pct, length <= 0)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
            completed.incrementAndGet()
            notifyDone(target, mime, r)
        } catch (e: UserCancelled) {
            created?.let { runCatching { resolver.delete(it, null, null) } }
        } catch (e: Exception) {
            created?.let { runCatching { resolver.delete(it, null, null) } }
            completed.incrementAndGet()
            notifyFailed(r, e.message)
        }
    }

    private fun mimeFor(r: Request): String = when {
        r.kind != MediaKind.PHOTO -> "video/mp4"
        r.fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        r.fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${bytes / 1024} KB"

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
            .addAction(0, "Cancel", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        lastForeground = notification
        return notification
    }

    private fun updateProgress(title: String, text: String, percent: Int, indeterminate: Boolean) {
        nm.notify(FG_ID, progressNotification(title, text, percent, indeterminate))
    }

    private fun notifyDone(uri: Uri, mime: String, r: Request) {
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
        val folder = if (r.kind == MediaKind.PHOTO) "Pictures/XSaver" else "Movies/XSaver"
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText("${r.fileName} · $folder")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }

    private fun notifyFailed(r: Request, reason: String?) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download failed")
            .setContentText("${r.fileName}: ${reason ?: "unknown error"}")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(nextDoneId.getAndIncrement(), notification)
    }
}
