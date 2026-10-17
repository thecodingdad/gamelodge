package io.github.thecodingdad.moonlightlauncher.data.companion

import java.security.MessageDigest
import java.security.cert.X509Certificate

object Fingerprint {
    /** SHA-256 over the DER certificate, upper-case hex (as the servers write it). */
    fun of(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

    /** "AB12 CD34 …" for comparing by eye. */
    fun pretty(fp: String): String = fp.chunked(4).joinToString(" ")
}
