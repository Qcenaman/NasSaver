package com.qcenaman.nassaver.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Environment
import android.os.storage.StorageManager
import com.qcenaman.nassaver.core.PcWaker
import com.qcenaman.nassaver.core.auth.Auth
import com.qcenaman.nassaver.core.fs.Drive
import com.qcenaman.nassaver.core.fs.DriveNames
import com.qcenaman.nassaver.core.fs.DriveProvider
import android.content.ComponentName
import android.content.Intent
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Base64

/** Mounted volumes: the TV's own storage plus every USB drive and SD card, named by their label. */
class AndroidDrives(private val ctx: Context) : DriveProvider {
    override fun drives(): List<Drive> {
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return emptyList()
        val vols = sm.storageVolumes.mapNotNull { v ->
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            val dir = v.directory ?: return@mapNotNull null
            val label = if (v.isPrimary) "TV storage" else clean(v.getDescription(ctx))
            Triple(label, v.uuid, dir)
        }
        val counts = vols.groupingBy { it.first }.eachCount()
        return vols.map { (label, uuid, dir) ->
            Drive(if ((counts[label] ?: 0) > 1 && uuid != null) "$label $uuid" else label, dir)
        }
    }

    private fun clean(s: String?) = s.orEmpty().replace('/', '-').replace('\\', '-').trim().ifEmpty { "Drive" }
}

/** Drive names you chose, kept in private app preferences. */
class PrefsDriveNames(ctx: Context) : DriveNames.Store {
    private val prefs = ctx.getSharedPreferences("drive_names", Context.MODE_PRIVATE)
    override fun load(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    override fun save(names: Map<String, String>) {
        val e = prefs.edit().clear()
        names.forEach { (k, v) -> e.putString(k, v) }
        e.commit()
    }
}

/** The computer to wake, kept in private app preferences; packets go out on every LAN the TV is on. */
object Wakers {
    @Volatile private var waker: PcWaker? = null

    fun get(ctx: Context): PcWaker = waker ?: synchronized(this) {
        waker ?: PcWaker(PrefsStore(ctx.applicationContext), ::broadcast).also { waker = it }
    }

    private class PrefsStore(ctx: Context) : PcWaker.Store {
        private val prefs = ctx.getSharedPreferences("wol", Context.MODE_PRIVATE)
        override fun load() = prefs.getString("mac", null)
        override fun save(mac: String) { prefs.edit().putString("mac", mac).commit() }
    }

    private fun broadcast(packet: ByteArray): Int {
        var networks = 0
        lanInterfaces().forEach { nic ->
            nic.interfaceAddresses.filter { it.address is Inet4Address && it.broadcast != null }.forEach { a ->
                val sent = runCatching {
                    DatagramSocket(InetSocketAddress(a.address, 0)).use { s ->
                        s.broadcast = true
                        val targets = listOf(a.broadcast, InetAddress.getByName("255.255.255.255"))
                        repeat(3) { targets.forEach { t -> PORTS.forEach { p -> s.send(DatagramPacket(packet, packet.size, t, p)) } } }
                    }
                }
                if (sent.isSuccess) networks++
            }
        }
        return networks
    }

    private fun lanInterfaces() = NetworkInterface.getNetworkInterfaces().toList().filter {
        it.isUp && !it.isLoopback && !Tailscale.isVpnName(it.name)
    }

    private val PORTS = listOf(9, 7)
}

/** Reconnects the Tailscale app; TCL's boot manager stops Android from starting it by itself. */
object Tailscale {
    private const val PACKAGE = "com.tailscale.ipn"

    fun isVpnName(name: String) = name.startsWith("tun") || name.startsWith("wg") || name.startsWith("nordlynx")

    /** True for any VPN, so NordVPN isn't fought over while it's in use. */
    fun vpnUp() = runCatching { NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && isVpnName(it.name) } }.getOrDefault(false)

    fun reconnect(ctx: Context) {
        ctx.sendBroadcast(Intent("$PACKAGE.CONNECT_VPN").setComponent(ComponentName(PACKAGE, "$PACKAGE.IPNReceiver")))
    }
}

/** The login, kept in private app preferences. */
object Logins {
    @Volatile private var auth: Auth? = null

    fun get(ctx: Context): Auth = auth ?: synchronized(this) {
        auth ?: Auth(PrefsStore(ctx.applicationContext)).also { auth = it }
    }

    private class PrefsStore(ctx: Context) : Auth.Store {
        private val prefs = ctx.getSharedPreferences("login", Context.MODE_PRIVATE)

        override fun load(): Auth.Login? {
            val user = prefs.getString("user", null) ?: return null
            val salt = prefs.getString("salt", null) ?: return null
            val hash = prefs.getString("hash", null) ?: return null
            return Auth.Login(user, Base64.getDecoder().decode(salt), Base64.getDecoder().decode(hash), prefs.getInt("iterations", 0))
        }

        override fun save(login: Auth.Login?) {
            val e = prefs.edit().clear()
            if (login != null) {
                e.putString("user", login.user)
                    .putString("salt", Base64.getEncoder().encodeToString(login.salt))
                    .putString("hash", Base64.getEncoder().encodeToString(login.hash))
                    .putInt("iterations", login.iterations)
            }
            e.commit()
        }
    }
}

/** Announces the server on the local network (mDNS / DNS-SD) so file apps can find it. */
class Nsd(ctx: Context, private val port: Int) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    fun start() {
        register("_webdav._tcp", "/dav/")
        register("_http._tcp", "/")
    }

    private fun register(type: String, path: String) {
        val info = NsdServiceInfo().apply {
            serviceName = "NasSaver"
            serviceType = type
            port = this@Nsd.port
            setAttribute("path", path)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        runCatching {
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            listeners += listener
        }
    }

    fun stop() {
        listeners.forEach { runCatching { nsd?.unregisterService(it) } }
        listeners.clear()
    }
}
