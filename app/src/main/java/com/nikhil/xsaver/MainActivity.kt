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
        DownloadStore.init(this)
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

    /** X app -> Share -> "X Saver": the shared text carries the post link. */
    private fun handleShare(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        vm.input = text.trim()
        vm.fetch()
    }

    private fun download(tweet: Tweet, index: Int, variant: Variant) {
        DownloadService.enqueueDownloads(this, tweet, listOf(index to variant))
        vm.markAdded(listOf(index))
        Toast.makeText(this, "Added to downloads", Toast.LENGTH_SHORT).show()
    }

    private fun downloadAll(tweet: Tweet) {
        val picks = tweet.media.mapIndexed { index, media -> index to media.variants.first() }
        DownloadService.enqueueDownloads(this, tweet, picks)
        vm.markAdded(picks.map { it.first })
        Toast.makeText(this, "Added ${picks.size} files to downloads", Toast.LENGTH_SHORT).show()
    }
}
