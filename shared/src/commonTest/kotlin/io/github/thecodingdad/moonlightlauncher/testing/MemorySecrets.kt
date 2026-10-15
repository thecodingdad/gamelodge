package io.github.thecodingdad.moonlightlauncher.testing

import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.platform.SecretBackend

/** Secrets kept in memory, for tests. */
class MemorySecretBackend : SecretBackend {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) { if (value.isNullOrEmpty()) values -= key else values[key] = value }
    override fun keys() = values.keys.toSet()
}

fun memorySecrets() = SecretStore(MemorySecretBackend())
