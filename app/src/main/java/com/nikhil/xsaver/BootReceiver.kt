package com.nikhil.xsaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Puts the paste panel back after a restart or an app update, if it is switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PanelNotification.isEnabled(context)) PanelNotification.show(context)
    }
}
