package com.nikhil.xsaver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) handleShare(intent)

        setContent {
            XSaverApp(
                vm = vm,
                onDownload = { tweet, index, variant -> download(tweet, index, variant) },
                onDownloadAll = { tweet -> downloadAll(tweet) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** X app -> Share -> X Saver: the shared text carries the post link. */
    private fun handleShare(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        vm.input = text.trim()
        vm.fetch()
    }

    private fun download(tweet: Tweet, index: Int, variant: Variant) {
        val media = tweet.media[index]
        DownloadService.enqueue(this, variant.url, fileName(tweet, index, variant.url, media.kind), media.kind)
        Toast.makeText(this, "Download started. Progress is in your notifications.", Toast.LENGTH_SHORT).show()
    }

    private fun downloadAll(tweet: Tweet) {
        tweet.media.forEachIndexed { index, media ->
            val best = media.variants.first()
            DownloadService.enqueue(this, best.url, fileName(tweet, index, best.url, media.kind), media.kind)
        }
        Toast.makeText(this, "Downloading ${tweet.media.size} files", Toast.LENGTH_SHORT).show()
    }

    private fun fileName(tweet: Tweet, index: Int, url: String, kind: MediaKind): String {
        val handle = tweet.authorHandle.ifEmpty { "x" }
        val ext = if (kind == MediaKind.PHOTO) photoExtension(url) else "mp4"
        return "${handle}_${tweet.id}_${index + 1}.$ext"
    }

    private fun photoExtension(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".png") -> "png"
            path.endsWith(".webp") -> "webp"
            else -> "jpg"
        }
    }
}
