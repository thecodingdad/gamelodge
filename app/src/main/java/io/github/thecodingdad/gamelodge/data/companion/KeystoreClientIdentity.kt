package io.github.thecodingdad.gamelodge.data.companion

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Key in the Android Keystore: generated on the device, never leaves it (not exportable, not part
 * of backups). A new device or a reinstall means pairing again.
 */
class KeystoreClientIdentity : ClientIdentity {
    private val store: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    init {
        if (!store.containsAlias(ALIAS)) generate()
    }

    override val certificate: X509Certificate get() = store.getCertificate(ALIAS) as X509Certificate
    override val privateKey: PrivateKey get() = store.getKey(ALIAS, null) as PrivateKey

    private fun generate() {
        val now = System.currentTimeMillis()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // TLS 1.2 signs pre-hashed data (NONE), TLS 1.3 uses SHA-256 with P-256.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=GameLodge"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 86_400_000L))
                    .setCertificateNotAfter(Date(now + 30L * 365 * 86_400_000L))
                    .build(),
            )
            generateKeyPair()
        }
    }

    /** Throws the key away (all pairings become invalid); the next use creates a new one. */
    fun reset() {
        store.deleteEntry(ALIAS)
        generate()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "mlc_client_v1"
    }
}
