package com.qcenaman.nassaver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the server after the TV boots and after each app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> "TV booted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> return
        }
        KeepAliveService.start(ctx, reason)
    }
}
