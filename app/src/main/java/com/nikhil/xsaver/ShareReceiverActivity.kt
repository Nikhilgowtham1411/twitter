package com.nikhil.xsaver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * "X Saver · Quick save" share target. It has no screen: it hands the shared link to the
 * download service and closes straight away, so you stay in the X app.
 */
class ShareReceiverActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { proceed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadStore.init(this)

        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            proceed()
        }
    }

    private fun proceed() {
        val text = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if (!text.isNullOrBlank()) {
            DownloadService.enqueueShared(this, text.trim())
            Toast.makeText(this, "Saving… check notifications or the X Saver history", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
