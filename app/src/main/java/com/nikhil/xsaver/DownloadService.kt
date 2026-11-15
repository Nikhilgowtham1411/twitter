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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond >= 1_048_576) "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
    else "${bytesPerSecond / 1024} KB/s"

/**
 * Foreground service that does all the work:
 *  - downloads up to 3 files at the same time;
 *  - splits each file into 4 to 8 pieces fetched over parallel connections (uses far more of your bandwidth);
 *  - if the network drops, switches between Wi-Fi and mobile data, or a connection stalls, it waits and
 *    RESUMES each piece where it stopped instead of failing;
 *  - holds a CPU wake lock so downloads continue with the screen off;
 *  - can look up shared/pasted links (Quick save, paste panel) and keep them in the app.
 * Everything is mirrored into [DownloadStore] (history) and a progress notification with Cancel.
 */
class DownloadService : Service() {

    private sealed interface Work
    private data class FetchWork(val entryId: Long, val text: String) : Work
    private data class SaveWork(val text: String) : Work
    private data class DownloadWork(val entryId: Long) : Work

    private class UserCancelled : Exception()
    private class NoRanges : Exception()
    private class HttpError(val code: Int) : IOException("Server replied HTTP $code") {
        val retriable: Boolean get() = code >= 500 || code == 408 || code == 429
    }

    companion object {
        private const val CHANNEL_PROGRESS = "xsaver_progress"
        private const val CHANNEL_DONE = "xsaver_done"
        private const val FG_ID = 1
        private const val ACTION_CANCEL = "com.nikhil.xsaver.CANCEL"
        private const val EXTRA_ID = "entry_id"
        private const val EXTRA_TEXT = "text"

        private const val MAX_PARALLEL_FILES = 3
        private const val MIN_SPLIT_BYTES = 1_000_000L
        private const val MAX_FAILURES_IN_A_ROW = 8
        private const val NETWORK_WAIT_MS = 10 * 60_000L

        /** Queue files from an already-fetched post (the in-app flow). */
        fun enqueueDownloads(context: Context, tweet: Tweet, picks: List<Pair<Int, Variant>>) {
            DownloadStore.init(context)
            val now = System.currentTimeMillis()
            for ((index, variant) in picks) {
                val media = tweet.media[index]
                val entry = HistoryItem(
                    id = DownloadStore.newId(),
                    title = titleFor(tweet, media.kind, index),
                    fileName = TweetFetcher.fileName(tweet, index, variant, media.kind),
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

        /** Paste panel / clipboard: look the link up and keep it in the app (nothing is downloaded yet). */
        fun enqueueSave(context: Context, text: String) {
            LinkStore.init(context)
            start(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(PanelNotification.ACTION_SAVE_LINK)
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
    private val slots = Semaphore(MAX_PARALLEL_FILES)
    private val pending = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextDoneId = AtomicInteger(1000)
    private var lastForeground: Notification? = null
    private lateinit var nm: NotificationManager

    @Volatile
    private var lastStartId = 0

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // live speed for the notification
    private val totalBytes = AtomicLong(0)
    private val notifyLock = Any()
    private var lastNotify = 0L
    private var lastBytes = 0L
    private var speed = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(this)
        LinkStore.init(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Required within a few seconds of startForegroundService().
        val initial = lastForeground ?: progressNotification("Starting", "Preparing…", 0, true)
        ServiceCompat.startForeground(this, FG_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        acquireLocks()

        if (intent != null) {
            when (intent.action) {
                ACTION_CANCEL -> DownloadStore.cancelAll()

                PanelNotification.ACTION_SAVE_LINK -> {
                    val typed = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(PanelNotification.KEY_LINK)?.toString()
                    val text = typed ?: intent.getStringExtra(EXTRA_TEXT)
                    PanelNotification.show(this) // also stops the reply spinner on the panel
                    if (!text.isNullOrBlank()) launchWork(SaveWork(text.trim()))
                }

                else -> {
                    val id = intent.getLongExtra(EXTRA_ID, -1L)
                    if (id != -1L) {
                        val text = intent.getStringExtra(EXTRA_TEXT)
                        launchWork(if (text != null) FetchWork(id, text) else DownloadWork(id))
                    }
                }
            }
        }
        if (pending.get() == 0) finishIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        DownloadStore.items.value.filter { it.isActive }.forEach { item ->
            DownloadStore.update(item.id) {
                it.copy(status = Status.FAILED, error = "Interrupted. Tap Retry.")
            }
        }
        super.onDestroy()
    }

    // ---- work queue ----------------------------------------------------------------------

    private fun launchWork(work: Work) {
        pending.incrementAndGet()
        scope.launch {
            try {
                when (work) {
                    is FetchWork -> fetchAndQueue(work)
                    is SaveWork -> saveLink(work)
                    is DownloadWork -> slots.withPermit { download(work.entryId) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Each worker reports its own errors; this only stops a stray one from killing the app.
            } finally {
                if (pending.decrementAndGet() == 0) mainHandler.post { finishIfIdle() }
            }
        }
    }

    /** Runs on the main thread, so it can't race with a new start command. */
    private fun finishIfIdle() {
        if (pending.get() != 0) return
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    // ---- keep the CPU/Wi-Fi awake while downloading --------------------------------------

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XSaver:download")
                    .apply { setReferenceCounted(false) }
            }
            // The timeout is renewed on every new command; it only protects against a stuck service.
            wakeLock?.acquire(60 * 60 * 1000L)
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "XSaver:wifi")
                    ?.apply { setReferenceCounted(false) }
            }
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        } catch (e: Exception) {
            // Locks are a bonus; downloads still work without them.
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---- quick save: look the post up, then queue its media ------------------------------

    private fun fetchAndQueue(w: FetchWork) {
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
                    fileName = TweetFetcher.fileName(tweet, index, variant, media.kind),
                    kind = media.kind,
                    thumb = media.thumbnail,
                    url = variant.url,
                    status = Status.QUEUED,
                    time = now,
                )
                DownloadStore.add(item)
                launchWork(DownloadWork(item.id))
            }
        } catch (e: Exception) {
            val reason = e.message ?: "Couldn't fetch this post."
            DownloadStore.update(w.entryId) {
                it.copy(title = "Couldn't fetch post", status = Status.FAILED, error = reason)
            }
            notifyFailedText("Couldn't fetch post", reason)
        }
    }

    // ---- paste panel: look the link up and keep it in the app ----------------------------

    private fun saveLink(w: SaveWork) {
        updateProgress("Saving link", "Looking up qualities and sizes…", 0, true)
        try {
            val tweet = TweetFetcher.load(w.text)
            LinkStore.add(tweet)
            notifyLinkSaved(tweet)
        } catch (e: Exception) {
            notifyFailedText("Couldn't save link", e.message ?: "Something went wrong.")
        }
    }

    // ---- downloading ---------------------------------------------------------------------

    private suspend fun download(entryId: Long) {
        val entry = DownloadStore.get(entryId) ?: return
        val url = entry.url ?: return
        if (entry.status != Status.QUEUED) return

        DownloadStore.update(entryId) {
            it.copy(status = Status.DOWNLOADING, progress = 0, bytes = 0, error = null)
        }
        refreshNotification(force = true)

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

            transfer(url, target, Progress(entryId))

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
            DownloadStore.update(entryId) {
                it.copy(status = Status.DONE, progress = 100, uri = target.toString(), mime = mime, error = null)
            }
            notifyDone(target, mime, entry)
        } catch (e: UserCancelled) {
            created?.let { runCatching { resolver.delete(it, null, null) } }
            DownloadStore.update(entryId) { it.copy(status = Status.CANCELLED, error = null) }
        } catch (e: CancellationException) {
            created?.let { runCatching { resolver.delete(it, null, null) } }
            throw e
        } catch (e: Exception) {
            created?.let { runCatching { resolver.delete(it, null, null) } }
            val reason = e.message ?: "unknown error"
            DownloadStore.update(entryId) { it.copy(status = Status.FAILED, error = reason) }
            notifyFailedText("Download failed", "${entry.fileName}: $reason")
        } finally {
            DownloadStore.clearCancelFlag(entryId)
        }
    }

    /** Tracks one file's progress (shared by all of its connections) and refreshes the UI/notification. */
    private inner class Progress(val entryId: Long) {
        @Volatile
        var total: Long = 0
        private val done = AtomicLong(0)
        private val lastPush = AtomicLong(0)

        fun reset() {
            done.set(0)
        }

        fun note(text: String?) {
            DownloadStore.update(entryId, persist = false) { it.copy(error = text) }
            refreshNotification(force = true)
        }

        fun add(n: Int) {
            val sum = done.addAndGet(n.toLong())
            totalBytes.addAndGet(n.toLong())
            val now = SystemClock.elapsedRealtime()
            val prev = lastPush.get()
            if (now - prev >= 400 && lastPush.compareAndSet(prev, now)) {
                val t = total
                val pct = if (t > 0) ((sum * 100) / t).toInt().coerceIn(0, 100) else 0
                // error = null clears a "Waiting for network…" note as soon as data flows again.
                DownloadStore.update(entryId, persist = false) { it.copy(progress = pct, bytes = sum, error = null) }
                refreshNotification()
            }
        }
    }

    private suspend fun transfer(url: String, target: Uri, p: Progress) {
        val total = probeRange(url, p) // null = this server can't send pieces
        if (total != null) {
            p.total = total
            try {
                downloadSegmented(url, target, total, p)
                return
            } catch (e: UserCancelled) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p.reset() // fall back to a single connection
            }
        }
        downloadSingle(url, target, p)
    }

    /** Asks for byte 0 only: proves the server supports ranges and tells us the full size. */
    private fun probeRange(url: String, p: Progress): Long? {
        var failures = 0
        while (true) {
            try {
                waitForNetwork(p)
                val conn = open(url)
                try {
                    conn.setRequestProperty("Range", "bytes=0-0")
                    val code = conn.responseCode
                    if (code == 206) {
                        return conn.getHeaderField("Content-Range")
                            ?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
                    }
                    if (code in 200..299) return null
                    throw HttpError(code)
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                if (e is HttpError && !e.retriable) throw e
                if (++failures >= 5) throw e
                Thread.sleep(1000L * failures)
            }
        }
    }

    private suspend fun downloadSegmented(url: String, target: Uri, total: Long, p: Progress) {
        val parts = when {
            total >= 30_000_000L -> 8
            total >= 8_000_000L -> 6
            total >= MIN_SPLIT_BYTES -> 4
            else -> 1
        }
        val chunk = total / parts
        val pfd = contentResolver.openFileDescriptor(target, "rw") ?: throw IOException("Couldn't open the file")
        pfd.use {
            val channel = FileOutputStream(it.fileDescriptor).channel
            coroutineScope {
                (0 until parts).map { i ->
                    val start = i * chunk
                    val end = if (i == parts - 1) total - 1 else start + chunk - 1
                    async(Dispatchers.IO) { fetchRange(url, channel, start, end, p) }
                }.awaitAll()
            }
        }
    }

    /**
     * Downloads bytes [start, end] and writes them at the right place in the file. If the connection
     * dies or stalls, it waits for the network and continues from the exact byte where it stopped.
     */
    private fun CoroutineScope.fetchRange(url: String, channel: FileChannel, start: Long, end: Long, p: Progress) {
        var pos = start
        var failures = 0
        val buffer = ByteArray(128 * 1024)
        while (pos <= end) {
            checkStop(p.entryId)
            val before = pos
            var conn: HttpURLConnection? = null
            try {
                waitForNetwork(p)
                conn = open(url)
                conn.setRequestProperty("Range", "bytes=$pos-$end")
                val code = conn.responseCode
                if (code == 200) throw NoRanges()
                if (code != 206) throw HttpError(code)
                conn.inputStream.use { input ->
                    while (pos <= end) {
                        checkStop(p.entryId)
                        val want = minOf(buffer.size.toLong(), end - pos + 1).toInt()
                        val n = input.read(buffer, 0, want)
                        if (n < 0) break
                        writeFully(channel, ByteBuffer.wrap(buffer, 0, n), pos)
                        pos += n
                        p.add(n)
                    }
                }
                if (pos <= end) throw IOException("Connection closed early")
            } catch (e: IOException) {
                if (e is HttpError && !e.retriable) throw e
                failures = if (pos > before) 0 else failures + 1
                if (failures >= MAX_FAILURES_IN_A_ROW) throw e
                Thread.sleep(minOf(500L * (failures + 1), 4000L))
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** Single connection, used only when the server can't send pieces. Restarts the file if it breaks. */
    private fun downloadSingle(url: String, target: Uri, p: Progress) {
        var failures = 0
        while (true) {
            p.reset()
            try {
                waitForNetwork(p)
                val conn = open(url)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw HttpError(code)
                    if (p.total <= 0) p.total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        val out = contentResolver.openOutputStream(target, "wt")
                            ?: throw IOException("Couldn't open the file")
                        out.use { stream ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (DownloadStore.isCancelRequested(p.entryId)) throw UserCancelled()
                                val n = input.read(buffer)
                                if (n < 0) break
                                stream.write(buffer, 0, n)
                                p.add(n)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                return
            } catch (e: IOException) {
                if (e is HttpError && !e.retriable) throw e
                if (++failures >= 4) throw e
                Thread.sleep(1500L * failures)
            }
        }
    }

    private fun CoroutineScope.checkStop(entryId: Long) {
        if (!isActive) throw CancellationException("stopped")
        if (DownloadStore.isCancelRequested(entryId)) throw UserCancelled()
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var pos = position
        while (buffer.hasRemaining()) {
            pos += channel.write(buffer, pos)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            // Short read timeout: a dead/stalled connection is noticed in 15 s and then resumed.
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", TweetFetcher.USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
        }

    // ---- network -------------------------------------------------------------------------

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** If there is no connection, pause (up to 10 minutes) and carry on as soon as it is back. */
    private fun waitForNetwork(p: Progress) {
        if (isOnline()) return
        p.note("Waiting for network…")
        val deadline = SystemClock.elapsedRealtime() + NETWORK_WAIT_MS
        while (!isOnline()) {
            if (DownloadStore.isCancelRequested(p.entryId)) throw UserCancelled()
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("No internet connection")
            Thread.sleep(1000)
        }
        p.note(null)
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

    /** One combined notification: "Downloading 3 files · 64% · 4.2 MB/s". */
    private fun refreshNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        synchronized(notifyLock) {
            val dt = now - lastNotify
            if (!force && dt < 500) return
            val bytes = totalBytes.get()
            if (lastNotify != 0L && dt > 0) {
                val instant = (bytes - lastBytes) * 1000 / dt
                speed = if (speed == 0L) instant else (speed * 6 + instant * 4) / 10
            }
            lastNotify = now
            lastBytes = bytes
        }
        val items = DownloadStore.items.value
        val active = items.filter { it.status == Status.DOWNLOADING }
        if (active.isEmpty()) return
        val waiting = items.count { it.status == Status.QUEUED || it.status == Status.FETCHING }

        val title = if (active.size == 1) "Downloading" else "Downloading ${active.size} files"
        val avg = active.sumOf { it.progress } / active.size
        val known = active.any { it.progress > 0 }
        val name = if (active.size == 1) active[0].fileName + " · " else ""
        val extra = if (waiting > 0) " · $waiting waiting" else ""
        val text = if (active.any { it.error != null }) {
            name + "Waiting for network…"
        } else {
            name + (if (known) "$avg%" else "Starting…") + " · " + formatSpeed(speed) + extra
        }
        updateProgress(title, text, avg, !known)
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

    private fun notifyLinkSaved(tweet: Tweet) {
        val lines = tweet.media.mapIndexed { i, m ->
            val kind = when (m.kind) {
                MediaKind.VIDEO -> "Video"
                MediaKind.GIF -> "GIF"
                MediaKind.PHOTO -> "Photo"
            }
            val qualities = m.variants.joinToString(", ") { v ->
                if (v.size > 0) "${v.label} ${formatSize(v.size)}" else v.label
            }
            "$kind ${i + 1}: $qualities"
        }
        val who = if (tweet.authorHandle.isNotEmpty()) " · @${tweet.authorHandle}" else ""
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Link saved$who")
            .setContentText("Tap to choose quality and download")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(nextDoneId.getAndIncrement(), notification)
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
