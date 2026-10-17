package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.desktop.stream.NvHttp.Companion.parseRoot
import io.github.thecodingdad.gamelodge.desktop.stream.NvHttp.Companion.text
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * GameStream pairing (Moonlight's, as in Artemis' PairingManager): the PIN salts an AES key; both
 * sides prove they know it with challenges signed by their certificates. Pins the host's
 * certificate on success.
 */
object NvPairing {
    enum class Result { PAIRED, PIN_WRONG, FAILED, ALREADY_IN_PROGRESS }

    /** [serverMajorVersion]: from serverinfo's appversion (7+ uses SHA-256). Returns the result and the host certificate. */
    fun pair(http: NvHttp, identity: StreamIdentity, pin: String, serverMajorVersion: Int): Pair<Result, X509Certificate?> {
        val hash = if (serverMajorVersion >= 7) "SHA-256" else "SHA-1"
        val hashLength = if (serverMajorVersion >= 7) 32 else 20
        fun digest(data: ByteArray) = MessageDigest.getInstance(hash).digest(data)

        val salt = random(16)
        val aesKey = digest(salt + pin.toByteArray()).copyOf(16)
        val certHex = hex(identity.certificatePem.toByteArray())

        // 1. The host shows the PIN dialog; the answer comes once the user typed it there.
        val getCert = parseRoot(http.pairCommand("phrase=getservercert&salt=${hex(salt)}&clientcert=$certHex", waitForUser = true))
        if (getCert.text("paired") != "1") return Result.FAILED to null
        val serverCert = getCert.text("plaincert")?.takeIf { it.isNotEmpty() }?.let {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(unhex(it))) as X509Certificate
        }
        if (serverCert == null) {
            // Another pairing is running on the host.
            http.unpair()
            return Result.ALREADY_IN_PROGRESS to null
        }
        http.serverCert = serverCert

        fun fail(result: Result = Result.FAILED): Pair<Result, X509Certificate?> {
            http.unpair()
            http.serverCert = null
            return result to null
        }

        // 2. Our challenge.
        val challenge = random(16)
        val challengeResp = parseRoot(http.pairCommand("clientchallenge=${hex(aes(challenge, aesKey, encrypt = true))}"))
        if (challengeResp.text("paired") != "1") return fail()
        val decrypted = aes(unhex(challengeResp.text("challengeresponse").orEmpty()), aesKey, encrypt = false)
        val serverResponse = decrypted.copyOfRange(0, hashLength)
        val serverChallenge = decrypted.copyOfRange(hashLength, hashLength + 16)

        // 3. Our answer to the host's challenge.
        val clientSecret = random(16)
        val answer = digest(serverChallenge + identity.certificate.signature + clientSecret)
        val secretResp = parseRoot(http.pairCommand("serverchallengeresp=${hex(aes(answer, aesKey, encrypt = true))}"))
        if (secretResp.text("paired") != "1") return fail()

        // 4. The host's secret, signed with its certificate; its answer proves the PIN.
        val pairingSecret = unhex(secretResp.text("pairingsecret").orEmpty())
        if (pairingSecret.size <= 16) return fail()
        val serverSecret = pairingSecret.copyOfRange(0, 16)
        val serverSignature = pairingSecret.copyOfRange(16, pairingSecret.size)
        if (!verify(serverSecret, serverSignature, serverCert)) return fail()
        val expected = digest(challenge + serverCert.signature + serverSecret)
        if (!MessageDigest.isEqual(expected, serverResponse)) return fail(Result.PIN_WRONG)

        // 5. Our secret, signed; then the check over HTTPS with our certificate.
        val clientPairingSecret = clientSecret + sign(clientSecret, identity.privateKey)
        if (parseRoot(http.pairCommand("clientpairingsecret=${hex(clientPairingSecret)}")).text("paired") != "1") return fail()
        if (parseRoot(http.pairCommand("phrase=pairchallenge", secure = true)).text("paired") != "1") return fail()
        return Result.PAIRED to serverCert
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    // AES-128-ECB without padding over 16-byte blocks (zero padded), as GameStream does it.
    private fun aes(data: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray {
        val padded = data.copyOf((data.size + 15) / 16 * 16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(padded)
    }

    private fun algorithm(key: java.security.Key) = if (key.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"

    private fun verify(data: ByteArray, signature: ByteArray, cert: Certificate) = runCatching {
        Signature.getInstance(algorithm(cert.publicKey)).apply { initVerify(cert.publicKey); update(data) }.verify(signature)
    }.getOrDefault(false)

    private fun sign(data: ByteArray, key: PrivateKey) = Signature.getInstance(algorithm(key)).apply { initSign(key); update(data) }.sign()

    fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }
    }
}
