package com.qcenaman.nassaver.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The single login: PBKDF2-hashed password, Basic auth checks, and a lockout after repeated wrong passwords. */
class Auth(
    private val store: Store,
    private val now: () -> Long = System::currentTimeMillis,
    private val iterations: Int = 60_000,
) {
    class Login(val user: String, val salt: ByteArray, val hash: ByteArray, val iterations: Int)

    interface Store {
        fun load(): Login?
        fun save(login: Login?)
    }

    enum class Result { OK, MISSING, BAD, LOCKED }

    @Volatile private var login: Login? = store.load()
    private val verified = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()
    private val lockedUntil = ConcurrentHashMap<String, Long>()

    val isConfigured: Boolean get() = login != null
    val user: String? get() = login?.user

    /** Returns an error message, or null when the login was saved. */
    @Synchronized
    fun setLogin(user: String, password: String): String? {
        val u = user.trim()
        when {
            u.isEmpty() || u.length > 64 -> return "Choose a username of 1 to 64 characters."
            u.contains(':') -> return "The username can't contain a colon."
            password.length < 6 -> return "Use a password of at least 6 characters."
            password.length > 256 -> return "That password is too long."
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val l = Login(u, salt, hash(password, salt, iterations), iterations)
        store.save(l)
        login = l
        verified.clear()
        return null
    }

    @Synchronized
    fun reset() {
        store.save(null)
        login = null
        verified.clear()
    }

    fun check(authorization: String?, remote: String): Result {
        val l = login ?: return Result.MISSING
        val t = now()
        lockedUntil[remote]?.let { if (t < it) return Result.LOCKED else lockedUntil.remove(remote) }
        if (authorization == null || !authorization.startsWith("Basic ", ignoreCase = true)) return Result.MISSING
        val key = sha256(authorization)
        verified[key]?.let { if (t < it) return Result.OK }
        val decoded = runCatching {
            String(Base64.getDecoder().decode(authorization.substring(6).trim()), Charsets.UTF_8)
        }.getOrNull()
        val ok = decoded != null && decoded.contains(':') &&
            decoded.substringBefore(':') == l.user &&
            MessageDigest.isEqual(hash(decoded.substringAfter(':'), l.salt, l.iterations), l.hash)
        if (ok) {
            verified[key] = t + CACHE_MS
            failures.remove(remote)
            return Result.OK
        }
        val recent = failures.getOrPut(remote) { mutableListOf() }
        synchronized(recent) {
            recent.removeAll { t - it > WINDOW_MS }
            recent.add(t)
            if (recent.size >= MAX_FAILURES) {
                recent.clear()
                lockedUntil[remote] = t + LOCK_MS
                return Result.LOCKED
            }
        }
        return Result.BAD
    }

    companion object {
        const val MAX_FAILURES = 10
        const val WINDOW_MS = 10 * 60_000L
        const val LOCK_MS = 5 * 60_000L
        private const val CACHE_MS = 15 * 60_000L

        fun hash(password: String, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
