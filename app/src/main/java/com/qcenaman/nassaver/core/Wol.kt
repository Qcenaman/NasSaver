package com.qcenaman.nassaver.core

/** Wake-on-LAN: the magic packet and the saved computer to wake. */
object Wol {
    fun parseMac(s: String?): ByteArray? {
        val hex = s.orEmpty().trim().replace(":", "").replace("-", "").replace(".", "")
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun format(mac: ByteArray) = mac.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }

    /** Six 0xFF bytes, then the MAC sixteen times. */
    fun packet(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(96) { mac[it % 6] }
    }
}

class PcWaker(private val store: Store, private val send: (ByteArray) -> Int) {
    interface Store {
        fun load(): String?
        fun save(mac: String)
    }

    sealed interface Result {
        data class Sent(val mac: String, val networks: Int) : Result
        object NoMac : Result
        object BadMac : Result
        object NoNetwork : Result
    }

    val mac: String? get() = store.load()

    /** Saves [newMac] when given, then sends the packet to the saved computer. */
    fun wake(newMac: String? = null): Result {
        val bytes = when {
            !newMac.isNullOrBlank() -> Wol.parseMac(newMac) ?: return Result.BadMac
            else -> Wol.parseMac(store.load()) ?: return Result.NoMac
        }
        val text = Wol.format(bytes)
        if (!newMac.isNullOrBlank()) store.save(text)
        val networks = send(Wol.packet(bytes))
        return if (networks > 0) Result.Sent(text, networks) else Result.NoNetwork
    }
}
