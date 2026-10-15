package io.github.thecodingdad.moonlightlauncher.data.companion

import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * This launcher's identity towards companions and metadata servers: an EC key pair with a
 * self-signed certificate, presented on every TLS connection (mutual TLS). The servers know the
 * launcher by the certificate's SHA-256 fingerprint, stored at pairing.
 */
interface ClientIdentity {
    val certificate: X509Certificate
    val privateKey: PrivateKey
    val fingerprint: String get() = Fingerprint.of(certificate)

    /** Hands out the identity whenever a server asks for a client certificate. */
    val keyManager: X509ExtendedKeyManager
        get() = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getCertificateChain(alias: String?) = arrayOf(certificate)
            override fun getPrivateKey(alias: String?) = privateKey
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }

    private companion object {
        const val ALIAS = "mlc"
    }
}

object Fingerprint {
    /** SHA-256 over the DER certificate, upper-case hex (as the servers write it). */
    fun of(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

    /** "AB12 CD34 …" for comparing by eye. */
    fun pretty(fp: String): String = fp.chunked(4).joinToString(" ")
}
