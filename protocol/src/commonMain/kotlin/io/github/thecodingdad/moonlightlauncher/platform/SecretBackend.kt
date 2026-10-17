package io.github.thecodingdad.moonlightlauncher.platform

/** Encrypted storage for credentials; the platform keeps the key (Android Keystore, Windows DPAPI). */
interface SecretBackend {
    fun get(key: String): String?
    /** Null or empty removes the entry. */
    fun put(key: String, value: String?)
    fun keys(): Set<String>
}
