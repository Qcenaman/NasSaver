package com.qcenaman.nassaver.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import com.qcenaman.nassaver.core.LogStore
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceState(private val ctx: Context) {
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)

    fun screenOn(): Boolean = power.isInteractive
    fun dozing(): Boolean = power.isDeviceIdleMode
    fun batteryExempt(): Boolean = power.isIgnoringBatteryOptimizations(ctx.packageName)

    fun allFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** True when Android TV's Low Power Standby would cut this app's network while the TV is off. */
    fun lowPowerStandbyBlocks(): Boolean = when {
        Build.VERSION.SDK_INT < 33 -> false
        !power.isLowPowerStandbyEnabled -> false
        Build.VERSION.SDK_INT >= 34 -> !power.isExemptFromLowPowerStandby
        else -> true
    }

    fun transport(): String {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "no network"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "other network"
        }
    }

    /** The TV's LAN IPv4 addresses (Wi-Fi and/or Ethernet). */
    fun ipv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    fun describeNetwork(): String {
        val ips = ipv4().joinToString(",")
        return if (ips.isEmpty()) transport() else "${transport()} $ips"
    }
}

object Logs {
    @Volatile private var store: LogStore? = null

    fun get(ctx: Context): LogStore = store ?: synchronized(this) {
        store ?: LogStore(File(ctx.applicationContext.filesDir, "heartbeat.log")).also { store = it }
    }
}
