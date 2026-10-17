package io.github.thecodingdad.moonlightlauncher.host

import io.github.thecodingdad.moonlightlauncher.data.companion.Fingerprint
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/**
 * The server's TLS identity towards paired launchers: a self-signed ECDSA P-256 certificate,
 * created once and kept (as PKCS#12) in the encrypted secret store. Launchers know it by its
 * SHA-256 fingerprint, pinned at pairing; names and CAs play no role.
 */
class ServerIdentity private constructor(val certificate: X509Certificate, val privateKey: PrivateKey) {
    val fingerprint: String get() = Fingerprint.of(certificate)

    companion object {
        private const val KEY = "server_identity_pkcs12"
        private const val ALIAS = "server"
        private val NO_PASSWORD = CharArray(0)

        fun loadOrCreate(secrets: SecretStore, commonName: String): ServerIdentity {
            secrets.get(KEY)?.let { stored ->
                runCatching { return fromPkcs12(Base64.getDecoder().decode(stored)) }
            }
            val created = create(commonName)
            secrets.put(KEY, Base64.getEncoder().encodeToString(created.toPkcs12()))
            return created
        }

        /** An identity from a PKCS#12 file without password (e.g. the companion's, when moving over). */
        fun fromPkcs12(data: ByteArray, password: CharArray = NO_PASSWORD): ServerIdentity {
            val store = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(data), password) }
            val alias = store.aliases().toList().first { store.isKeyEntry(it) }
            return ServerIdentity(store.getCertificate(alias) as X509Certificate, store.getKey(alias, password) as PrivateKey)
        }

        fun create(commonName: String): ServerIdentity {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=${commonName.filter { it.isLetterOrDigit() || it in " -_." }.ifBlank { "Moonlight Launcher" }}")
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()).abs(), Date(now - 86_400_000L), Date(now + 30L * 365 * 86_400_000L), name, pair.public,
            )
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)))
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)))
            return ServerIdentity(cert, pair.private)
        }
    }

    fun toPkcs12(): ByteArray {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry(ALIAS, privateKey, NO_PASSWORD, arrayOf(certificate))
        return ByteArrayOutputStream().also { store.store(it, NO_PASSWORD) }.toByteArray()
    }
}
