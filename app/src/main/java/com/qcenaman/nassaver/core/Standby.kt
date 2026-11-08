package com.qcenaman.nassaver.core

/** Decides what to do about Android TV's Low Power Standby, which cuts the network while the TV is off. */
object Standby {
    enum class Action { NONE, TURN_OFF, NEEDS_PERMISSION }

    /** [enabled] is Settings.Global low_power_standby_enabled (null = unset, which counts as the TV's default: on). */
    fun decide(enabled: Int?, canWriteSettings: Boolean): Action = when {
        enabled == 0 -> Action.NONE
        canWriteSettings -> Action.TURN_OFF
        else -> Action.NEEDS_PERMISSION
    }

    fun describe(enabled: Int?, canWriteSettings: Boolean): String = when (decide(enabled, canWriteSettings)) {
        Action.NONE -> "off"
        Action.TURN_OFF -> "was ON: turning it off"
        Action.NEEDS_PERMISSION -> "is ON: network stops in standby (grant WRITE_SECURE_SETTINGS so NasSaver can turn it off)"
    }
}
