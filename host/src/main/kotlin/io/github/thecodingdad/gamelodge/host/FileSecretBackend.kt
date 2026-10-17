package io.github.thecodingdad.gamelodge.host

import io.github.thecodingdad.gamelodge.platform.SecretBackend
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts single values of the secret store. */
interface ValueCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/** AES-GCM with a random key in [keyFile] (readable by the owner only), made on first use. */
class KeyFileCipher(private val keyFile: File) : ValueCipher {
    private val key by lazy {
        if (!keyFile.exists()) {
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
            restrictToOwner(keyFile)
        }
        SecretKeySpec(keyFile.readBytes(), "AES")
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)) }
        return c.doFinal(data, 12, data.size - 12)
    }
}

/** Secrets as encrypted values in [dir]/secrets.json. */
class FileSecretBackend(dir: File, private val cipher: ValueCipher = KeyFileCipher(File(dir, "secrets.key"))) : SecretBackend {
    private val file = File(dir, "secrets.json")
    private val json = Json { prettyPrint = true }
    private val values: MutableMap<String, String> = load()

    @Synchronized
    override fun get(key: String): String? {
        val stored = values[key] ?: return null
        return runCatching { String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8) }
            .onFailure { Log.w("Secret $key unreadable", it) }.getOrNull()
    }

    @Synchronized
    override fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) values.remove(key)
        else values[key] = Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
        save()
    }

    @Synchronized
    override fun keys(): Set<String> = values.keys.toSet()

    private fun load(): MutableMap<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(values.toMap()))
        restrictToOwner(tmp)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
