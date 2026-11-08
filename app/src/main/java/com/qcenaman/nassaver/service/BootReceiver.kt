package com.qcenaman.nassaver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the server after the TV boots, after each app update, and when a drive is plugged in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> "TV booted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            Intent.ACTION_MEDIA_MOUNTED -> "drive mounted"
            else -> return
        }
        Watchdog.schedule(ctx)
        if (KeepAliveService.current == null) KeepAliveService.start(ctx, reason)
    }
}
