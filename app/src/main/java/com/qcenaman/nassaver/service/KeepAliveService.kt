package com.qcenaman.nassaver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.qcenaman.nassaver.BuildConfig
import com.qcenaman.nassaver.R
import com.qcenaman.nassaver.core.Clock
import com.qcenaman.nassaver.core.Fmt
import com.qcenaman.nassaver.core.Heartbeat
import com.qcenaman.nassaver.core.LogStore
import com.qcenaman.nassaver.core.StatusPage
import com.qcenaman.nassaver.core.StatusServer
import com.qcenaman.nassaver.ui.MainActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Standby test: stays in the foreground with a wake lock and a Wi-Fi lock, logs a tick every minute,
 * and serves the log on port 8080 so a phone can check whether the TV stayed alive and reachable.
 */
class KeepAliveService : Service() {
    private lateinit var log: LogStore
    private lateinit var device: DeviceState
    private lateinit var server: StatusServer
    private var executor: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var announced = false
    private var reason = "unknown"
    private var startedWall = 0L
    @Volatile private var last: Clock? = null
    @Volatile private var ticks = 0
    @Volatile private var lateTicks = 0
    @Volatile private var longestGapMs = 0L

    private val events = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            log.add(
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen OFF (standby or picture off)"
                    Intent.ACTION_SCREEN_ON -> "screen ON"
                    Intent.ACTION_DREAMING_STARTED -> "screensaver started"
                    Intent.ACTION_DREAMING_STOPPED -> "screensaver stopped"
                    Intent.ACTION_SHUTDOWN -> "TV shutting down"
                    else -> return
                }
            )
        }
    }

    private val pages = object : StatusServer.Pages {
        override fun html() = StatusPage.render("NasSaver standby test", summary(), this@KeepAliveService.log.tail(300).asReversed())
        override fun log() = this@KeepAliveService.log.text()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log = Logs.get(this)
        device = DeviceState(this)
        startedWall = System.currentTimeMillis()
        goForeground()
        acquireLocks()
        registerEvents()
        server = StatusServer(PORT, pages, onError = { log.add(it) })
        server.start()
        current = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!announced) {
            announced = true
            reason = intent?.getStringExtra(EXTRA_REASON) ?: "restarted by Android"
            announceStart()
            executor = Executors.newSingleThreadScheduledExecutor().also {
                it.scheduleWithFixedDelay({ safeTick() }, Heartbeat.INTERVAL_MS, Heartbeat.INTERVAL_MS, TimeUnit.MILLISECONDS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        log.add("service stopped")
        current = null
        executor?.shutdownNow()
        server.stop()
        runCatching { unregisterReceiver(events) }
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    fun summary(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val ip = device.ipv4().firstOrNull()
        return listOf(
            "App" to "NasSaver ${BuildConfig.VERSION_NAME} (standby test build)",
            "TV" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Running since" to "${Fmt.timestamp(startedWall)} (${Fmt.duration(now - startedWall)})",
            "Started because" to reason,
            "Ticks" to "$ticks total, $lateTicks late, longest gap ${Fmt.duration(longestGapMs)}",
            "Screen" to if (device.screenOn()) "on" else "off",
            "Doze" to if (device.dozing()) "yes" else "no",
            "Network" to device.describeNetwork(),
            "Battery optimisation" to if (device.batteryExempt()) "exempt" else "not exempt",
            "Locks" to "wake ${held(wakeLock?.isHeld)}, Wi-Fi ${held(wifiLock?.isHeld)}",
            "Status page" to if (ip != null) "http://$ip:$PORT" else "no network address",
        )
    }

    private fun held(b: Boolean?) = if (b == true) "held" else "NOT held"

    private fun announceStart() {
        val now = readClock()
        val prev = Clock.parse(lastTickFile().takeIf { it.exists() }?.readText())
        val history = when {
            prev == null -> "first run"
            else -> Heartbeat.gap(prev, now).let { g ->
                if (g.rebooted) "TV rebooted, last tick ${Fmt.duration(g.sinceMs)} ago"
                else "last tick ${Fmt.duration(g.sinceMs)} ago"
            }
        }
        log.add("service started ($reason) | v${BuildConfig.VERSION_NAME} | $history | ${device.describeNetwork()}")
        last = now
        saveLast(now)
    }

    private fun safeTick() {
        try {
            tick()
        } catch (e: Exception) {
            log.add("tick failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun tick() {
        val now = readClock()
        val gap = Heartbeat.gap(last ?: now, now)
        ticks++
        if (gap.late) lateTicks++
        if (gap.sinceMs > longestGapMs) longestGapMs = gap.sinceMs
        last = now
        saveLast(now)
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
        if (wifiLock?.isHeld == false) wifiLock?.acquire()
        if (!server.isRunning && server.start()) log.add("status page restarted")
        log.add(
            "tick #$ticks | ${Heartbeat.describe(gap)} | screen ${if (device.screenOn()) "on" else "off"}" +
                " | doze ${if (device.dozing()) "yes" else "no"} | ${device.describeNetwork()}"
        )
    }

    private fun readClock() = Clock(System.currentTimeMillis(), SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
    private fun lastTickFile() = File(filesDir, "last_tick.txt")
    private fun saveLast(c: Clock) = runCatching { lastTickFile().writeText(c.serialize()) }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "NasSaver server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("NasSaver is running")
            .setContentText("Status page on port $PORT")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NasSaver:keepalive").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NasSaver:wifi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun registerEvents() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(events, filter)
    }

    companion object {
        const val PORT = 8080
        const val EXTRA_REASON = "reason"
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1

        @Volatile var current: KeepAliveService? = null
            private set

        fun start(ctx: Context, reason: String) {
            try {
                ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java).putExtra(EXTRA_REASON, reason))
            } catch (e: Exception) {
                Logs.get(ctx).add("service start failed ($reason): ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }
}
