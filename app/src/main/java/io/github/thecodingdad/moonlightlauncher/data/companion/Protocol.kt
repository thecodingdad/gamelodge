package io.github.thecodingdad.moonlightlauncher.data.companion

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// API of the companion / metadata server (Companion.Protocol, version 1). JSON in camelCase.

@Serializable data class Round1Message(val id: String, val gx1: String, val gx2: String, val zkp1: List<String>, val zkp2: List<String>)
@Serializable data class Round2Message(val id: String, val a: String, val zkp: List<String>)
@Serializable data class Round3Message(val id: String, val mac: String)
@Serializable data class PairStartRequest(val deviceName: String, val round1: Round1Message)
@Serializable data class PairStartResponse(val requestId: String)
@Serializable data class PairPollResponse(val state: String, val round1: Round1Message? = null)
@Serializable data class PairRound3Request(val round3: Round3Message, val binding: String)
@Serializable data class PairRound3Response(val round3: Round3Message, val binding: String, val serverName: String, val kind: String)
@Serializable data class HelloResponse(val version: Int, val kind: String, val pairingOpen: Boolean)
@Serializable data class ErrorResponse(val error: String)

@Serializable
data class InfoResponse(
    val version: Int,
    val kind: String,
    val name: String,
    val deviceName: String,
    val permissions: List<String> = emptyList(),
    val steamGridDb: Boolean = false,
    val igdb: Boolean = false,
    val hostId: String? = null,
)

/**
 * The PIN exchange (J-PAKE, RFC 8236, as Bouncy Castle implements it on both sides): the PIN is
 * never sent and a recording allows no offline guessing. Afterwards both TLS certificate
 * fingerprints are bound to the agreed key, so a man in the middle fails.
 */
class JPakeExchange(participantId: String, pin: String) {
    private val participant: JPAKEParticipant
    private var keying: BigInteger? = null

    init {
        require(isValidPin(pin)) { "PIN must be 6 digits" }
        participant = JPAKEParticipant(participantId, pin.toCharArray(), JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), SecureRandom())
    }

    fun createRound1(): Round1Message = participant.createRound1PayloadToSend().let {
        Round1Message(it.participantId, hex(it.gx1), hex(it.gx2), it.knowledgeProofForX1.map(::hex), it.knowledgeProofForX2.map(::hex))
    }

    fun receiveRound1(m: Round1Message) = guard {
        participant.validateRound1PayloadReceived(JPAKERound1Payload(m.id, big(m.gx1), big(m.gx2), bigs(m.zkp1), bigs(m.zkp2)))
    }

    fun createRound2(): Round2Message = participant.createRound2PayloadToSend().let {
        Round2Message(it.participantId, hex(it.a), it.knowledgeProofForX2s.map(::hex))
    }

    fun receiveRound2(m: Round2Message) = guard {
        participant.validateRound2PayloadReceived(JPAKERound2Payload(m.id, big(m.a), bigs(m.zkp)))
        keying = participant.calculateKeyingMaterial()
    }

    fun createRound3(): Round3Message = participant.createRound3PayloadToSend(key()).let { Round3Message(it.participantId, hex(it.macTag)) }

    /** Fails when the server used another PIN. */
    fun receiveRound3(m: Round3Message) = guard { participant.validateRound3PayloadReceived(JPAKERound3Payload(m.id, big(m.mac)), key()) }

    fun bindingMac(role: String, serverFingerprint: String, clientFingerprint: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey(), "HmacSHA256"))
        return mac.doFinal("mlc-bind-v1|$role|$serverFingerprint|$clientFingerprint".toByteArray()).joinToString("") { "%02X".format(it) }
    }

    fun checkBindingMac(role: String, serverFingerprint: String, clientFingerprint: String, mac: String): Boolean =
        MessageDigest.isEqual(bindingMac(role, serverFingerprint, clientFingerprint).toByteArray(), mac.uppercase().toByteArray())

    private fun key() = keying ?: throw PairingFailed("Round 2 not completed")

    private fun sessionKey(): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("mlc-pair-v1".toByteArray() + BigIntegers.asUnsignedByteArray(key()))

    companion object {
        const val CLIENT_ID = "client"
        const val SERVER_ID = "server"

        fun isValidPin(pin: String?) = pin != null && pin.length == 6 && pin.all { it in '0'..'9' }

        fun newPin(): String = "%06d".format(SecureRandom().nextInt(1_000_000))

        private fun hex(v: BigInteger) = v.toString(16)

        private fun big(hex: String): BigInteger {
            val digits = hex.removePrefix("-")
            if (digits.isEmpty() || digits.length > 1024 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw PairingFailed("Malformed number")
            return BigInteger(hex, 16)
        }

        private fun bigs(values: List<String>): Array<BigInteger> {
            if (values.size != 2) throw PairingFailed("Malformed proof")
            return values.map(::big).toTypedArray()
        }

        private fun guard(block: () -> Unit) {
            try { block() } catch (e: PairingFailed) { throw e } catch (e: Exception) { throw PairingFailed(e.message ?: "Invalid message") }
        }
    }
}

class PairingFailed(message: String) : Exception(message)
