package com.nikhil.xsaver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Permission just arrived: make sure the panel shows up if it is switched on.
            if (vm.panelOn) PanelNotification.show(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadStore.init(this)
        LinkStore.init(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        vm.panelOn = PanelNotification.isEnabled(this)
        if (vm.panelOn) PanelNotification.show(this)
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) handleShare(intent)

        setContent {
            XSaverApp(
                vm = vm,
                onDownload = { tweet, index, variant -> download(tweet, index, variant) },
                onDownloadAll = { tweet -> downloadAll(tweet) },
                onPanelToggle = { on -> setPanel(on) },
                onBatterySettings = { openBatterySettings() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        vm.batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** X app -> Share -> "X Saver": the shared link is kept in "Saved links" with all qualities and sizes. */
    private fun handleShare(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        vm.input = text.trim()
        vm.fetch()
    }

    private fun download(tweet: Tweet, index: Int, variant: Variant) {
        DownloadService.enqueueDownloads(this, tweet, listOf(index to variant))
        LinkStore.markDownloaded(tweet.id, index, variant.label)
        Toast.makeText(this, "Added to downloads", Toast.LENGTH_SHORT).show()
    }

    private fun downloadAll(tweet: Tweet) {
        val picks = tweet.media.mapIndexed { index, media -> index to media.variants.first() }
        DownloadService.enqueueDownloads(this, tweet, picks)
        picks.forEach { (index, variant) -> LinkStore.markDownloaded(tweet.id, index, variant.label) }
        Toast.makeText(this, "Added ${picks.size} files to downloads", Toast.LENGTH_SHORT).show()
    }

    private fun setPanel(on: Boolean) {
        vm.panelOn = on
        PanelNotification.setEnabled(this, on)
        if (on) requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Phones that kill background apps (realme, Xiaomi, Oppo…) stop downloads unless this is allowed. */
    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Open Settings > Battery and allow X Saver to run in background", Toast.LENGTH_LONG).show()
            }
        }
    }
}
