package io.github.thecodingdad.gamelodge.host

import io.github.thecodingdad.gamelodge.data.companion.JPakeExchange
import io.github.thecodingdad.gamelodge.data.companion.PairingFailed
import io.github.thecodingdad.gamelodge.data.companion.Round1Message
import io.github.thecodingdad.gamelodge.data.companion.Round2Message
import io.github.thecodingdad.gamelodge.data.companion.Round3Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

enum class PairingState { WAITING_FOR_PIN, READY, ROUND2_DONE, PAIRED, FAILED }

/** A device asking to pair, as the pairing screens show it. */
data class PairingRequestInfo(val id: String, val deviceName: String, val address: String?, val created: Long, val state: PairingState)

/** Pairing is not possible right now (window closed or locked). */
class PairingClosedException : Exception("Pairing is closed")

/**
 * Server side of pairing (as the companion did it). Pairing works only while the user has opened
 * the pairing window (2 minutes). A device sends a request; the user picks it and types the PIN
 * the device shows; then the J-PAKE rounds run. Every request allows one attempt. After 3 failed
 * attempts within 10 minutes pairing is locked for 10 minutes. One device per opened window.
 */
class PairingManager(
    private val devices: DeviceRegistry,
    private val serverFingerprint: () -> String,
    private val grantedPermissions: Set<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Request(
        val id: String,
        val deviceName: String,
        val clientFingerprint: String,
        val address: String?,
        val created: Long,
        val clientRound1: Round1Message,
    ) {
        var state = PairingState.WAITING_FOR_PIN
        var exchange: JPakeExchange? = null
        var serverRound1: Round1Message? = null
    }

    data class Status(val openUntil: Long?, val lockedUntil: Long?, val requests: List<PairingRequestInfo>)

    private val requests = LinkedHashMap<String, Request>()
    private val failures = mutableListOf<Long>()
    private var windowUntil = 0L
    private var lockedUntil = 0L
    private val _status = MutableStateFlow(Status(null, null, emptyList()))

    /** Window, lock and pending requests (UI refresh). */
    val status: StateFlow<Status> = _status.asStateFlow()

    private val now get() = clock()
    private val locked get() = now < lockedUntil

    @get:Synchronized
    val isOpen: Boolean get() = now < windowUntil && !locked

    /** Opens the pairing window (the user pressed "Pair device"); false while locked. */
    @Synchronized
    fun open(): Boolean {
        if (locked) return false
        windowUntil = now + WINDOW_MS
        changed()
        return true
    }

    @Synchronized
    fun close() {
        windowUntil = 0
        requests.clear()
        changed()
    }

    /** Re-evaluates expiries (the UI calls it while showing the window). */
    @Synchronized
    fun refresh() {
        expire()
        changed()
    }

    /** A device asks to pair (needs an open window). Returns the request id. */
    @Synchronized
    fun start(deviceName: String, clientFingerprint: String, address: String?, clientRound1: Round1Message): String {
        expire()
        if (!(now < windowUntil) || locked) throw PairingClosedException()
        if (clientRound1.id != JPakeExchange.CLIENT_ID) throw PairingFailed("Unexpected participant")
        // The same device asking again replaces its earlier request.
        requests.values.removeAll { it.clientFingerprint == clientFingerprint }
        if (requests.size >= MAX_PENDING) throw PairingFailed("Too many pending requests")
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        requests[id] = Request(id, deviceName, clientFingerprint, address, now, clientRound1)
        changed()
        return id
    }

    /** The user typed the PIN shown on the device. False: request gone or the PIN is malformed. */
    @Synchronized
    fun enterPin(requestId: String, pin: String): Boolean {
        if (!JPakeExchange.isValidPin(pin)) return false
        expire()
        val r = requests[requestId]?.takeIf { it.state == PairingState.WAITING_FOR_PIN } ?: return false
        val exchange = JPakeExchange(JPakeExchange.SERVER_ID, pin)
        try {
            r.serverRound1 = exchange.createRound1()
            exchange.receiveRound1(r.clientRound1)
            r.exchange = exchange
            r.state = PairingState.READY
        } catch (e: PairingFailed) {
            fail(r)
        }
        changed()
        return true
    }

    /** The device polls until the PIN was typed: then the server's first round. */
    @Synchronized
    fun poll(requestId: String, clientFingerprint: String): Pair<PairingState, Round1Message?> {
        expire()
        val r = get(requestId, clientFingerprint)
        return r.state to (if (r.state == PairingState.READY) r.serverRound1 else null)
    }

    @Synchronized
    fun round2(requestId: String, clientFingerprint: String, clientRound2: Round2Message): Round2Message {
        try {
            val r = get(requestId, clientFingerprint)
            val exchange = r.exchange?.takeIf { r.state == PairingState.READY } ?: throw PairingFailed("Unexpected round")
            try {
                val own = exchange.createRound2()
                exchange.receiveRound2(clientRound2)
                r.state = PairingState.ROUND2_DONE
                return own
            } catch (e: PairingFailed) {
                fail(r)
                throw e
            }
        } finally {
            changed()
        }
    }

    /**
     * Last round: checks the device's key confirmation (fails on a wrong PIN) and its certificate
     * binding, then stores the device and answers with the server's confirmation and binding.
     */
    @Synchronized
    fun round3(requestId: String, clientFingerprint: String, clientRound3: Round3Message, bindingMac: String): Triple<Round3Message, String, Device> {
        try {
            val r = get(requestId, clientFingerprint)
            val exchange = r.exchange?.takeIf { r.state == PairingState.ROUND2_DONE } ?: throw PairingFailed("Unexpected round")
            // Created first (Bouncy Castle's order), sent only when the device's checks out.
            val own = exchange.createRound3()
            val server = serverFingerprint()
            try {
                exchange.receiveRound3(clientRound3)
                if (!exchange.checkBindingMac(JPakeExchange.CLIENT_ID, server, clientFingerprint, bindingMac)) throw PairingFailed("Certificate binding failed")
            } catch (e: PairingFailed) {
                fail(r)
                throw e
            }
            val mac = exchange.bindingMac(JPakeExchange.SERVER_ID, server, clientFingerprint)
            val device = devices.add(r.deviceName, clientFingerprint, grantedPermissions, r.address)
            r.state = PairingState.PAIRED
            requests.remove(r.id)
            windowUntil = 0
            return Triple(own, mac, device)
        } finally {
            changed()
        }
    }

    // Unknown id or another certificate than the one that started the request: same answer.
    private fun get(id: String, clientFingerprint: String): Request {
        val r = requests[id]
        if (r == null || !MessageDigest.isEqual(r.clientFingerprint.toByteArray(), clientFingerprint.toByteArray())) throw PairingFailed("Unknown request")
        return r
    }

    private fun fail(r: Request) {
        r.state = PairingState.FAILED
        requests.remove(r.id)
        failures += now
        failures.removeAll { now - it > LOCK_MS }
        if (failures.size >= MAX_FAILURES) {
            lockedUntil = now + LOCK_MS
            windowUntil = 0
            requests.clear()
            failures.clear()
        }
    }

    private fun expire() {
        requests.values.removeAll { now - it.created > REQUEST_LIFETIME_MS }
    }

    private fun changed() {
        _status.value = Status(
            openUntil = windowUntil.takeIf { now < it && !locked },
            lockedUntil = lockedUntil.takeIf { locked },
            requests = requests.values.filter { it.state != PairingState.PAIRED && it.state != PairingState.FAILED }
                .map { PairingRequestInfo(it.id, it.deviceName, it.address, it.created, it.state) },
        )
    }

    companion object {
        const val WINDOW_MS = 2 * 60_000L
        const val REQUEST_LIFETIME_MS = 5 * 60_000L
        const val LOCK_MS = 10 * 60_000L
        const val MAX_FAILURES = 3
        const val MAX_PENDING = 5
    }
}
