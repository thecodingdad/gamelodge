package io.github.thecodingdad.gamelodge.host

import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The admin account of the web UI as stored on disk (admin.json, read fresh on every use). */
@Serializable
data class AdminData(
    val passwordHash: String? = null,
    /** Raised on password change / reset: older sessions end. */
    val sessionVersion: Int = 1,
    val totpEnabled: Boolean = false,
    val totpLastStep: Long = 0,
    val recoveryHashes: List<String> = emptyList(),
    val setupCodeHash: String? = null,
    /** Unix ms. */
    val setupCodeExpires: Long? = null,
)

enum class LoginOutcome { OK, INVALID, CODE_REQUIRED, THROTTLED }

/**
 * The single admin account of the web UI (as in the companion): set up once with a setup code shown
 * in the launcher, then password (Argon2id) plus optional TOTP. Failed attempts slow down per
 * address (exponential) and overall. The TOTP secret lives in the encrypted secret store.
 */
class AdminAuth(private val file: File, private val secrets: SecretStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val byAddress = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val recentFailures = ArrayDeque<Long>()

    val needsSetup get() = load().passwordHash == null
    val totpEnabled get() = load().totpEnabled
    val sessionVersion get() = load().sessionVersion
    val recoveryCodesLeft get() = load().recoveryHashes.size

    /** A new setup code (replaces an earlier one); only its hash is stored. Shown in the launcher's settings. */
    fun issueSetupCode(): String {
        val code = HumanCode.new()
        update { it.copy(setupCodeHash = HumanCode.hash(code), setupCodeExpires = clock() + SETUP_CODE_LIFETIME_MS) }
        return code
    }

    /** Forgot the password: account cleared (TOTP too), all sessions end; returns a new setup code. */
    fun reset(): String {
        secrets.put(TOTP_KEY, null)
        update { it.copy(passwordHash = null, totpEnabled = false, recoveryHashes = emptyList(), sessionVersion = it.sessionVersion + 1) }
        return issueSetupCode()
    }

    /** First password; needs the setup code. Returns an error key or null. */
    @Synchronized
    fun setup(code: String?, password: String?, confirm: String?, address: String): String? {
        if (throttled(address) != null) return "too_many_attempts"
        val data = load()
        if (data.passwordHash != null) return "already_set_up"
        val until = data.setupCodeExpires
        if (until == null || clock() > until || !HumanCode.matches(code.orEmpty(), data.setupCodeHash)) {
            fail(address)
            return "setup_code_wrong"
        }
        checkPassword(password, confirm)?.let { return it }
        val hash = PasswordHash.create(password!!)
        update { it.copy(passwordHash = hash, setupCodeHash = null, setupCodeExpires = null, sessionVersion = it.sessionVersion + 1) }
        byAddress.remove(address)
        return null
    }

    @Synchronized
    fun login(password: String?, code: String?, address: String): Pair<LoginOutcome, Long> {
        throttled(address)?.let { return LoginOutcome.THROTTLED to it }
        val data = load()
        if (data.passwordHash == null || password == null || password.length > MAX_PASSWORD || !PasswordHash.verify(password, data.passwordHash)) {
            fail(address)
            return LoginOutcome.INVALID to 0
        }
        if (data.totpEnabled) {
            if (code.isNullOrBlank()) return LoginOutcome.CODE_REQUIRED to 0
            if (!checkSecondFactor(code.trim())) {
                fail(address)
                return LoginOutcome.INVALID to 0
            }
        }
        byAddress.remove(address)
        return LoginOutcome.OK to 0
    }

    /** For changes that need the password again (password, TOTP). */
    @Synchronized
    fun confirmPassword(password: String?, address: String): Boolean {
        if (throttled(address) != null) return false
        if (password != null && password.length <= MAX_PASSWORD && PasswordHash.verify(password, load().passwordHash)) return true
        fail(address)
        return false
    }

    @Synchronized
    fun changePassword(current: String?, password: String?, confirm: String?, address: String): String? {
        if (!confirmPassword(current, address)) return "password_wrong"
        checkPassword(password, confirm)?.let { return it }
        val hash = PasswordHash.create(password!!)
        update { it.copy(passwordHash = hash, sessionVersion = it.sessionVersion + 1) }
        return null
    }

    /** Turns TOTP on once the app produced a valid code for [secret]; the recovery codes, or null. */
    @Synchronized
    fun enableTotp(secret: ByteArray, code: String?, password: String?, address: String): List<String>? {
        if (!confirmPassword(password, address)) return null
        val step = Totp.match(secret, code?.trim(), clock()) ?: return null
        val codes = List(10) { HumanCode.new(2) }
        secrets.put(TOTP_KEY, Base64.getEncoder().encodeToString(secret))
        update { it.copy(totpEnabled = true, totpLastStep = step, recoveryHashes = codes.map(HumanCode::hash)) }
        return codes
    }

    @Synchronized
    fun disableTotp(password: String?, address: String): Boolean {
        if (!confirmPassword(password, address)) return false
        secrets.put(TOTP_KEY, null)
        update { it.copy(totpEnabled = false, recoveryHashes = emptyList()) }
        return true
    }

    // A TOTP code is accepted once (no replay within its window); a recovery code is used up.
    private fun checkSecondFactor(code: String): Boolean {
        val data = load()
        val secret = secrets.get(TOTP_KEY)?.let { Base64.getDecoder().decode(it) } ?: return false
        val step = Totp.match(secret, code, clock())
        if (step != null && step > data.totpLastStep) {
            update { it.copy(totpLastStep = step) }
            return true
        }
        if (HumanCode.normalize(code).length != 8) return false
        val used = data.recoveryHashes.firstOrNull { HumanCode.matches(code, it) } ?: return false
        update { it.copy(recoveryHashes = it.recoveryHashes - used) }
        return true
    }

    /** Ms to wait before this address may try again, or null. */
    private fun throttled(address: String): Long? {
        val now = clock()
        synchronized(recentFailures) {
            while (recentFailures.isNotEmpty() && now - recentFailures.first() > 60_000) recentFailures.removeFirst()
            if (recentFailures.size >= 30) return 60_000
        }
        val (failures, last) = byAddress[address] ?: return null
        if (failures < 5) return null
        val delay = minOf(Math.pow(2.0, (failures - 4).toDouble()), 15 * 60.0).toLong() * 1000
        return (last + delay - now).takeIf { it > 0 }
    }

    private fun fail(address: String) {
        val now = clock()
        synchronized(recentFailures) { recentFailures.addLast(now) }
        byAddress.merge(address, 1 to now) { old, _ -> old.first + 1 to now }
    }

    private fun load(): AdminData = runCatching { json.decodeFromString<AdminData>(file.readText()) }.getOrDefault(AdminData())

    @Synchronized
    private fun update(change: (AdminData) -> AdminData) {
        val data = change(load())
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(data))
        restrictToOwner(tmp)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val MIN_PASSWORD = 12
        const val MAX_PASSWORD = 256
        const val SETUP_CODE_LIFETIME_MS = 24 * 3_600_000L
        private const val TOTP_KEY = "web_admin_totp"

        fun checkPassword(password: String?, confirm: String?): String? = when {
            password == null || password.length < MIN_PASSWORD -> "password_short"
            password.length > MAX_PASSWORD -> "password_long"
            password != confirm -> "password_mismatch"
            else -> null
        }
    }
}

/** Argon2id password hashes: "argon2id$m$t$p$salt$hash" (base64), the companion's format. */
object PasswordHash {
    private const val MEMORY_KB = 64 * 1024
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1

    fun create(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = compute(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM, 32)
        val b64 = Base64.getEncoder()
        return "argon2id\$$MEMORY_KB\$$ITERATIONS\$$PARALLELISM\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    fun verify(password: String, stored: String?): Boolean {
        val parts = stored?.split('$') ?: return false
        if (parts.size != 6 || parts[0] != "argon2id") return false
        return runCatching {
            val expected = Base64.getDecoder().decode(parts[5])
            val actual = compute(password, Base64.getDecoder().decode(parts[4]), parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), expected.size)
            MessageDigest.isEqual(expected, actual)
        }.getOrDefault(false)
    }

    private fun compute(password: String, salt: ByteArray, memoryKb: Int, iterations: Int, parallelism: Int, length: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt).withMemoryAsKB(memoryKb).withIterations(iterations).withParallelism(parallelism)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).build()
        return ByteArray(length).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
    }
}

/** RFC 6238 one-time codes (SHA-1, 30 s, 6 digits), as authenticator apps expect them. */
object Totp {
    fun newSecret() = ByteArray(20).also { SecureRandom().nextBytes(it) }

    /** Time step a code is valid for, or null. One step of clock drift either way is accepted. */
    fun match(secret: ByteArray, code: String?, nowMs: Long): Long? {
        if (code == null || code.length != 6 || !code.all { it in '0'..'9' }) return null
        val step = nowMs / 1000 / 30
        return (-1L..1L).map { step + it }.firstOrNull { MessageDigest.isEqual(code(secret, it).toByteArray(), code.toByteArray()) }
    }

    fun code(secret: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val value = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        return "%06d".format(value % 1_000_000)
    }

    fun uri(secret: ByteArray, issuer: String, account: String): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "otpauth://totp/${e(issuer)}:${e(account)}?secret=${base32(secret)}&issuer=${e(issuer)}&digits=6&period=30"
    }

    fun base32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(alphabet[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}

/** Random codes for people to type (setup code, recovery codes): no 0/O/1/I. */
object HumanCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Groups of four, e.g. "7KQ4-M9XD-2PLA" (12 characters ≈ 60 bit). */
    fun new(groups: Int = 3): String {
        val random = SecureRandom()
        return (0 until groups).joinToString("-") { (0 until 4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("") }
    }

    fun normalize(code: String?) = code.orEmpty().uppercase().filter { it != '-' && it != ' ' }

    /** SHA-256 of the normalized code (codes are random, a slow hash isn't needed). */
    fun hash(code: String) = MessageDigest.getInstance("SHA-256").digest(normalize(code).toByteArray()).joinToString("") { "%02X".format(it) }

    fun matches(code: String, hash: String?) = hash != null && MessageDigest.isEqual(hash(code).toByteArray(), hash.toByteArray())
}
