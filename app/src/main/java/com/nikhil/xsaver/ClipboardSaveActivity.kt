package com.nikhil.xsaver

import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Opened by the panel's "Paste & save" button. Android only lets an app read the clipboard while
 * one of its windows has focus, so this invisible activity reads it the moment it gets focus,
 * hands the link to the service and closes.
 */
class ClipboardSaveActivity : ComponentActivity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadStore.init(this)
        LinkStore.init(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null

        if (!text.isNullOrBlank() && TweetFetcher.looksLikeLink(text)) {
            DownloadService.enqueueSave(this, text.trim())
            Toast.makeText(this, "Saving link…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Copy an X post link first", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
