package com.nikhil.xsaver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * The optional "paste panel" that lives in the notification shade. Turn it on in the app.
 * It has a box to type/paste a link into, and a one-tap "Paste & save" that reads the clipboard.
 * Saved links show up inside the app with every quality and size.
 */
object PanelNotification {
    const val ACTION_SAVE_LINK = "com.nikhil.xsaver.SAVE_LINK"
    const val KEY_LINK = "link_text"

    private const val CHANNEL = "xsaver_panel"
    private const val ID = 2
    private const val PREFS = "xsaver_prefs"
    private const val KEY_ENABLED = "panel_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) show(context) else hide(context)
    }

    fun hide(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    /** Posts (or refreshes) the panel. Does nothing while the panel is switched off. */
    fun show(context: Context, status: String? = null) {
        if (!isEnabled(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Paste panel", NotificationManager.IMPORTANCE_LOW)
        )

        val remoteInput = RemoteInput.Builder(KEY_LINK).setLabel("Paste X link here").build()
        val replyIntent = PendingIntent.getForegroundService(
            context,
            10,
            Intent(context, DownloadService::class.java).setAction(ACTION_SAVE_LINK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(0, "Paste link", replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val clipboardIntent = PendingIntent.getActivity(
            context,
            11,
            Intent(context, ClipboardSaveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            context,
            12,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("X Saver")
            .setContentText(status ?: "Copy a post link, then tap Paste & save")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .addAction(0, "Paste & save", clipboardIntent)
            .addAction(replyAction)
            .build()
        nm.notify(ID, notification)
    }
}
