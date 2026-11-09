package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.LauncherContainer
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.resources.Res
import io.github.thecodingdad.gamelodge.resources.host_power_no_response
import io.github.thecodingdad.gamelodge.resources.host_wake_failed
import io.github.thecodingdad.gamelodge.ui.shell.Notices
import io.github.thecodingdad.gamelodge.xbox.XboxPower
import io.github.thecodingdad.gamelodge.xbox.XboxRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wake-ups, shutdowns and restarts of hosts under way: the host cards show a spinner instead of
 * the host's icon until the host is in the expected state, or a notice once the time is up.
 */
object PowerProgress {
    /** What the host should do. */
    enum class Expect { ON, OFF, RESTART }

    private val _hosts = MutableStateFlow<Set<String>>(emptySet())
    /** Hosts (uuids) with an action under way. */
    val hosts: StateFlow<Set<String>> = _hosts.asStateFlow()

    /**
     * Runs [action] for the host [hostUuid] ([name] for the notice) and waits for it to [expect]:
     * on within the host's wake timeout, off within [OFF_TIMEOUT_MS], a restart off and on again.
     */
    fun start(c: LauncherContainer, hostUuid: String, name: String, expect: Expect, action: suspend () -> Boolean) {
        if (hostUuid in _hosts.value) return
        _hosts.update { it + hostUuid }
        c.scope.launch {
            try {
                if (!action()) {
                    if (expect != Expect.OFF) Notices.show(Res.string.host_wake_failed)
                    return@launch
                }
                val wakeMs = c.hostPower.config(hostUuid).wakeTimeoutSeconds * 1000L
                val done = when (expect) {
                    Expect.ON -> waitFor(c, hostUuid, on = true, wakeMs)
                    Expect.OFF -> waitFor(c, hostUuid, on = false, OFF_TIMEOUT_MS)
                    Expect.RESTART -> waitFor(c, hostUuid, on = false, OFF_TIMEOUT_MS) && waitFor(c, hostUuid, on = true, wakeMs)
                }
                if (!done) Notices.show(Res.string.host_power_no_response, name)
            } finally {
                _hosts.update { it - hostUuid }
            }
        }
    }

    private suspend fun waitFor(c: LauncherContainer, hostUuid: String, on: Boolean, timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (isOn(c, hostUuid) == on) return true
            delay(POLL_MS)
        }
        return false
    }

    /** Consoles by what Xbox Live says (a power action polls it often then), PCs by their answers. */
    private fun isOn(c: LauncherContainer, hostUuid: String): Boolean {
        val console = XboxRepository.consoleId(hostUuid)
        return if (console != null) c.xbox.hosts.value[console]?.console?.power == XboxPower.ON
        else c.hosts.hosts.value[hostUuid]?.status == HostStatus.ONLINE
    }

    /** Turning off: a console shows off after about a minute (Xbox Live), a PC after its shutdown and the offline grace. */
    private const val OFF_TIMEOUT_MS = 120_000L
    private const val POLL_MS = 1_000L
}
