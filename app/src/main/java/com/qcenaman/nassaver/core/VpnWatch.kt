package com.qcenaman.nassaver.core

/** Asks the VPN app to reconnect on every tick while no VPN is up; logs only the changes. */
class VpnWatch(
    private val isUp: () -> Boolean,
    private val reconnect: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var lastUp: Boolean? = null

    val connected: Boolean? get() = lastUp

    fun tick() {
        val up = isUp()
        if (up != lastUp) log(if (up) "Tailscale connected" else "Tailscale is down, asking it to reconnect")
        lastUp = up
        if (!up) reconnect()
    }
}
