package com.qcenaman.nassaver.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.qcenaman.nassaver.core.Standby

/**
 * TV updates and restarts can switch Low Power Standby back on. With WRITE_SECURE_SETTINGS
 * (granted once over ADB) NasSaver switches it off again by itself.
 */
class StandbyFixer(private val ctx: Context) {
    fun canWrite(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun enabledValue(): Int? = runCatching {
        Settings.Global.getString(ctx.contentResolver, KEY)?.toIntOrNull()
    }.getOrNull()

    fun status(): String = Standby.describe(enabledValue(), canWrite())

    /** Returns a log line when something was done or is wrong, null when all is fine. */
    fun fix(): String? = when (Standby.decide(enabledValue(), canWrite())) {
        Standby.Action.NONE -> null
        Standby.Action.TURN_OFF -> runCatching {
            Settings.Global.putInt(ctx.contentResolver, KEY, 0)
            "Low Power Standby was ON: turned it off"
        }.getOrElse { "Low Power Standby is ON and turning it off failed: ${it.javaClass.simpleName} ${it.message}" }
        Standby.Action.NEEDS_PERMISSION -> "Low Power Standby is ON: the network stops while the TV is in standby"
    }

    companion object {
        const val KEY = "low_power_standby_enabled"
    }
}
