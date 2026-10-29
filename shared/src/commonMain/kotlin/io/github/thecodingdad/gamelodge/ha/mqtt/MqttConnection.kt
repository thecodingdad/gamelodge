package io.github.thecodingdad.gamelodge.ha.mqtt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** A retained or plain message; the client only sends and receives QoS 0. */
class MqttMessage(val topic: String, val payload: ByteArray, val retain: Boolean = false)

/** Where and how to connect. [will] is published by the broker when the connection is lost. */
data class MqttOptions(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String?,
    val password: String?,
    val clientId: String,
    val keepAliveSeconds: Int = 60,
    val will: MqttWill? = null,
    /**
     * TLS: a certificate the system doesn't trust (self-signed) is accepted when its SHA-256
     * fingerprint is [pinnedCertificate]; without a pin such a connection fails with
     * [MqttCertificateException] carrying the fingerprint to confirm.
     */
    val allowSelfSigned: Boolean = false,
    val pinnedCertificate: String? = null,
)

data class MqttWill(val topic: String, val payload: String, val retain: Boolean = true)

open class MqttException(message: String) : IOException(message)

/**
 * The broker's certificate isn't trusted by the system: [fingerprint] (SHA-256, hex) to confirm,
 * [changed] when another certificate was pinned before.
 */
class MqttCertificateException(val fingerprint: String, val changed: Boolean) :
    MqttException(if (changed) "Certificate changed" else "Certificate not trusted")

/** The broker refused the connection (CONNACK return code, e.g. 4/5 = bad login / not authorized). */
class MqttRefusedException(val code: Int) : MqttException("Connection refused ($code)") {
    val badLogin: Boolean get() = code == 4 || code == 5
}

/**
 * The part of MQTT 3.1.1 the launcher needs: connect (login, last will), publish QoS 0
 * (retained), subscribe QoS 0, ping. Pure encoding/decoding; incoming packets are length
 * checked before anything is read into memory.
 */
object MqttPackets {
    const val CONNECT = 1
    const val CONNACK = 2
    const val PUBLISH = 3
    const val PUBACK = 4
    const val SUBSCRIBE = 8
    const val SUBACK = 9
    const val PINGREQ = 12
    const val PINGRESP = 13
    const val DISCONNECT = 14

    /** Largest packet accepted from the broker: we only get acks and small status messages. */
    const val MAX_INCOMING = 64 * 1024

    class Packet(val type: Int, val flags: Int, val body: ByteArray)

    fun connect(o: MqttOptions): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeString("MQTT")
        body.write(4) // protocol level 3.1.1
        // A password needs a user name in 3.1.1.
        val user = o.username?.takeIf { it.isNotEmpty() }
        val password = o.password?.takeIf { user != null && it.isNotEmpty() }
        var flags = 0x02 // clean session
        if (o.will != null) flags = flags or 0x04 or (if (o.will.retain) 0x20 else 0)
        if (user != null) flags = flags or 0x80
        if (password != null) flags = flags or 0x40
        body.write(flags)
        body.writeShort(o.keepAliveSeconds.coerceIn(0, 0xFFFF))
        body.writeString(o.clientId)
        o.will?.let { body.writeString(it.topic); body.writeField(it.payload.toByteArray()) }
        user?.let { body.writeString(it) }
        password?.let { body.writeField(it.toByteArray()) }
        return packet(CONNECT shl 4, body.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean): ByteArray {
        require(topic.isNotEmpty() && '+' !in topic && '#' !in topic) { "Invalid topic" }
        val body = ByteArrayOutputStream()
        body.writeString(topic)
        body.write(payload)
        return packet((PUBLISH shl 4) or (if (retain) 1 else 0), body.toByteArray())
    }

    fun subscribe(packetId: Int, topic: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeShort(packetId)
        body.writeString(topic)
        body.write(0) // QoS 0
        return packet((SUBSCRIBE shl 4) or 0x02, body.toByteArray())
    }

    fun pingReq(): ByteArray = byteArrayOf((PINGREQ shl 4).toByte(), 0)
    fun disconnect(): ByteArray = byteArrayOf((DISCONNECT shl 4).toByte(), 0)

    /** Reads one packet; a length above [max] or a truncated stream throws. */
    fun read(input: InputStream, max: Int = MAX_INCOMING): Packet {
        val first = input.read()
        if (first < 0) throw EOFException("Connection closed")
        var length = 0
        var multiplier = 1
        var digits = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed")
            length += (b and 0x7F) * multiplier
            if (b and 0x80 == 0) break
            if (++digits >= 4) throw MqttException("Malformed length")
            multiplier *= 128
        }
        if (length > max) throw MqttException("Packet too large ($length)")
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) throw EOFException("Connection closed")
            off += n
        }
        return Packet(first ushr 4, first and 0x0F, body)
    }

    /** Topic and payload of an incoming PUBLISH (any QoS: a packet id is skipped). */
    fun parsePublish(p: Packet): MqttMessage {
        if (p.body.size < 2) throw MqttException("Malformed publish")
        val len = ((p.body[0].toInt() and 0xFF) shl 8) or (p.body[1].toInt() and 0xFF)
        var pos = 2 + len
        if (pos > p.body.size) throw MqttException("Malformed publish")
        val topic = String(p.body, 2, len, Charsets.UTF_8)
        if ((p.flags shr 1) and 0x03 > 0) pos += 2
        if (pos > p.body.size) throw MqttException("Malformed publish")
        return MqttMessage(topic, p.body.copyOfRange(pos, p.body.size), retain = p.flags and 1 == 1)
    }

    fun remainingLength(n: Int): ByteArray {
        require(n in 0..268_435_455) { "Packet too large" }
        val out = ByteArrayOutputStream()
        var x = n
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            out.write(digit)
        } while (x > 0)
        return out.toByteArray()
    }

    private fun packet(header: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(header)
        out.write(remainingLength(body.size))
        out.write(body)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShort(v: Int) { write(v ushr 8 and 0xFF); write(v and 0xFF) }

    private fun ByteArrayOutputStream.writeField(b: ByteArray) {
        require(b.size <= 0xFFFF) { "Field too long" }
        writeShort(b.size)
        write(b)
    }

    private fun ByteArrayOutputStream.writeString(s: String) = writeField(s.toByteArray(Charsets.UTF_8))
}

/**
 * One connection to the broker. [publish] and [ping] may be called from any thread; [readLoop]
 * blocks until the connection ends (a missing ping answer ends it after 1.5 × keep alive).
 */
class MqttConnection private constructor(private val socket: Socket) : Closeable {
    private val out = BufferedOutputStream(socket.getOutputStream())
    private val input = BufferedInputStream(socket.getInputStream())
    private var packetId = 0

    fun publish(topic: String, payload: ByteArray, retain: Boolean) = send(MqttPackets.publish(topic, payload, retain))

    fun publish(topic: String, payload: String, retain: Boolean) = publish(topic, payload.toByteArray(Charsets.UTF_8), retain)

    @Synchronized
    fun subscribe(topic: String) {
        packetId = packetId % 0xFFFF + 1
        send(MqttPackets.subscribe(packetId, topic))
    }

    fun ping() = send(MqttPackets.pingReq())

    @Synchronized
    private fun send(bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }

    /** Reads until the connection ends (throws then); messages of subscriptions go to [onMessage]. */
    fun readLoop(onMessage: (MqttMessage) -> Unit) {
        while (true) {
            val p = MqttPackets.read(input)
            when (p.type) {
                MqttPackets.PUBLISH -> onMessage(MqttPackets.parsePublish(p))
                MqttPackets.SUBACK, MqttPackets.PINGRESP, MqttPackets.PUBACK -> Unit
                else -> throw MqttException("Unexpected packet ${p.type}")
            }
        }
    }

    /** Leaves cleanly (the broker drops the last will); safe to call twice. */
    override fun close() {
        runCatching { if (!socket.isClosed) send(MqttPackets.disconnect()) }
        runCatching { socket.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000

        /**
         * Connects and logs in. With TLS the broker's certificate must be trusted by [systemTrust]
         * and issued for [MqttOptions.host], or be the pinned one ([MqttOptions.allowSelfSigned]).
         * [systemTrust] only differs in tests.
         */
        fun open(o: MqttOptions, systemTrust: X509TrustManager = systemTrustManager()): MqttConnection {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(o.host, o.port), CONNECT_TIMEOUT_MS)
                plain.soTimeout = CONNECT_TIMEOUT_MS
                val socket = if (o.tls) secure(plain, o, systemTrust) else plain
                val connection = MqttConnection(socket)
                connection.send(MqttPackets.connect(o))
                val ack = MqttPackets.read(connection.input, 16)
                if (ack.type != MqttPackets.CONNACK || ack.body.size != 2) throw MqttException("No CONNACK")
                val code = ack.body[1].toInt() and 0xFF
                if (code != 0) throw MqttRefusedException(code)
                // Without traffic the broker answers our pings at least every keep alive / 2.
                socket.soTimeout = o.keepAliveSeconds * 1500
                return connection
            } catch (e: Exception) {
                runCatching { plain.close() }
                throw e
            }
        }

        private fun secure(plain: Socket, o: MqttOptions, systemTrust: X509TrustManager): Socket {
            val trust = BrokerTrust(systemTrust, o.allowSelfSigned, o.pinnedCertificate)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val s = context.socketFactory.createSocket(plain, o.host, plain.port, true) as SSLSocket
            try {
                s.startHandshake()
            } catch (e: SSLException) {
                runCatching { s.close() }
                val seen = trust.untrusted
                if (seen != null) throw MqttCertificateException(seen, changed = o.pinnedCertificate != null)
                throw e
            }
            // A certificate from a trusted authority must be issued for the broker's name; a pinned
            // one is exactly the confirmed certificate (self-signed ones often name no host at all).
            if (!trust.pinned && !okhttp3.internal.tls.OkHostnameVerifier.verify(o.host, s.session)) {
                runCatching { s.close() }
                throw SSLPeerUnverifiedException("Certificate is not for ${o.host}")
            }
            return s
        }

        fun systemTrustManager(): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()

        /** SHA-256 of a certificate, lower case hex. */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The system's trust, plus (when allowed) the one pinned certificate. Remembers how the server was
 * accepted ([pinned]) or the fingerprint of the certificate it refused ([untrusted]).
 */
private class BrokerTrust(
    private val system: X509TrustManager,
    private val allowSelfSigned: Boolean,
    private val pin: String?,
) : X509TrustManager {
    @Volatile var pinned = false
    @Volatile var untrusted: String? = null

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            if (!allowSelfSigned || chain.isEmpty()) throw e
            val fingerprint = MqttConnection.fingerprint(chain[0])
            if (pin != null && MessageDigest.isEqual(fingerprint.toByteArray(), pin.lowercase().toByteArray())) {
                pinned = true
                return
            }
            untrusted = fingerprint
            throw e
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}
