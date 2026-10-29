package io.github.thecodingdad.gamelodge.ui.screens.ha

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.ha.mqtt.MqttState
import io.github.thecodingdad.gamelodge.ha.mqtt.MqttStatus
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Settings › Home Assistant › MQTT: the row with the connection state, the settings behind it. */
@Composable
fun MqttRow(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val state by io.github.thecodingdad.gamelodge.LocalContainer.current.mqtt.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    NavRow(stringResource(Res.string.mqtt_title), { open = true }, mqttStatusLabel(state), Icons.Filled.Hub)
    if (open) MqttDialog(settings, update) { open = false }
}

@Composable
private fun mqttStatusLabel(state: MqttState): String = when (state.status) {
    MqttStatus.OFF -> stringResource(Res.string.mqtt_status_off)
    MqttStatus.CONNECTING -> stringResource(Res.string.mqtt_status_connecting)
    MqttStatus.CONNECTED -> stringResource(Res.string.mqtt_status_connected)
    MqttStatus.BAD_LOGIN -> stringResource(Res.string.mqtt_status_bad_login)
    MqttStatus.FAILED -> stringResource(Res.string.mqtt_status_failed, state.error.orEmpty())
    MqttStatus.CERT_UNTRUSTED -> stringResource(Res.string.mqtt_status_cert_untrusted)
    MqttStatus.CERT_CHANGED -> stringResource(Res.string.mqtt_status_cert_changed)
}

/** "ab:cd:…" in groups, easier to compare with the broker's certificate. */
private fun formatFingerprint(hex: String) = hex.uppercase().chunked(2).joinToString(":")

/** Broker and device name; everything applies with Save (the password only when typed). */
@Composable
fun MqttDialog(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit, onDismiss: () -> Unit) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val scope = rememberCoroutineScope()
    val state by c.mqtt.state.collectAsState()
    // A new broker is most likely Home Assistant's own (the Mosquitto add-on).
    val haHost = remember { c.haAuth.baseUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty() }
    var enabled by remember { mutableStateOf(settings.mqttEnabled || settings.mqttHost.isEmpty()) }
    var host by remember { mutableStateOf(settings.mqttHost.ifEmpty { haHost }) }
    var port by remember { mutableStateOf(settings.mqttPort.toString()) }
    var tls by remember { mutableStateOf(settings.mqttTls) }
    var selfSigned by remember { mutableStateOf(settings.mqttAllowSelfSigned) }
    var user by remember { mutableStateOf(settings.mqttUser) }
    var password by remember { mutableStateOf("") }
    val hasPassword = remember { c.secrets.get(SecretStore.Keys.MQTT_PASSWORD) != null }
    var deviceName by remember { mutableStateOf(settings.mqttDeviceName) }
    // Confirmed certificate: applies with Save like everything else here.
    var pin by remember { mutableStateOf(settings.mqttCertPin) }
    val portNumber = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<io.github.thecodingdad.gamelodge.ha.mqtt.MqttBridge.TestResult?>(null) }
    // A changed entry makes an earlier test result stale.
    androidx.compose.runtime.LaunchedEffect(host, port, tls, selfSigned, user, password) { testResult = null }

    Modal(visible = true, onDismiss = onDismiss, title = stringResource(Res.string.mqtt_title), maxWidth = 680.dp, closeButton = false) {
        // Scrolls when it doesn't fit; the buttons below always stay in view.
        Column(Modifier.weight(1f, fill = false).popupHeightIn(460.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.mqtt_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            SwitchRow(stringResource(Res.string.mqtt_enabled), enabled, { enabled = it }, initialFocus = true)
            LauncherTextField(stringResource(Res.string.mqtt_host), host, { host = it.trim() }, kind = TextKind.URL, placeholder = "homeassistant.local")
            LauncherTextField(stringResource(Res.string.mqtt_port), port, { port = it.filter(Char::isDigit).take(5) }, kind = TextKind.NUMBER, placeholder = "1883")
            SwitchRow(
                stringResource(Res.string.mqtt_tls), tls,
                { on -> tls = on; if (port == "1883" && on) port = "8883" else if (port == "8883" && !on) port = "1883" },
                subtitle = stringResource(if (selfSigned) Res.string.mqtt_tls_sub_pinned else Res.string.mqtt_tls_sub),
            )
            if (tls) {
                SwitchRow(stringResource(Res.string.mqtt_self_signed), selfSigned, { selfSigned = it }, subtitle = stringResource(Res.string.mqtt_self_signed_sub))
            }
            if (tls && selfSigned && pin.isNotEmpty()) {
                Text(
                    stringResource(Res.string.mqtt_cert_pinned, formatFingerprint(pin)),
                    style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
                )
            }
            LauncherTextField(stringResource(Res.string.mqtt_user), user, { user = it })
            LauncherTextField(
                stringResource(Res.string.mqtt_password), password, { password = it }, kind = TextKind.PASSWORD,
                placeholder = if (hasPassword) stringResource(Res.string.mqtt_password_kept) else null,
            )
            if (!tls && (password.isNotEmpty() || hasPassword) && user.isNotBlank()) {
                Text(stringResource(Res.string.mqtt_plain_warning), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.warning)
            }
            LauncherTextField(stringResource(Res.string.mqtt_device_name), deviceName, { deviceName = it }, placeholder = c.platform.deviceName)
            Text(stringResource(Res.string.mqtt_status, mqttStatusLabel(state)), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            if (state.status == MqttStatus.CONNECTED) {
                HoldToConfirmButton(
                    stringResource(Res.string.mqtt_remove), icon = Icons.Filled.Delete,
                    onConfirmed = { scope.launch { if (c.mqtt.removeFromHa()) onDismiss() } },
                )
            }
        }
        // Outside the scroll area: the outcome of a test is seen at once.
        testResult?.let { r ->
            val (text, color) = when (r) {
                io.github.thecodingdad.gamelodge.ha.mqtt.MqttBridge.TestResult.Ok -> stringResource(Res.string.mqtt_test_ok) to LauncherTheme.colors.success
                io.github.thecodingdad.gamelodge.ha.mqtt.MqttBridge.TestResult.BadLogin -> stringResource(Res.string.mqtt_status_bad_login) to LauncherTheme.colors.danger
                is io.github.thecodingdad.gamelodge.ha.mqtt.MqttBridge.TestResult.Certificate ->
                    stringResource(if (selfSigned) Res.string.mqtt_status_cert_untrusted else Res.string.mqtt_test_cert_untrusted) to LauncherTheme.colors.warning
                is io.github.thecodingdad.gamelodge.ha.mqtt.MqttBridge.TestResult.Failed -> stringResource(Res.string.mqtt_status_failed, r.message) to LauncherTheme.colors.danger
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        // A certificate to confirm: from the test, else from the running connection.
        val tested = testResult as? io.github.thecodingdad.gamelodge.ha.mqtt.MqttBridge.TestResult.Certificate
        val certToConfirm = (tested?.fingerprint to (tested?.changed == true)).takeIf { tested != null }
            ?: state.fingerprint?.takeIf { state.status == MqttStatus.CERT_UNTRUSTED || state.status == MqttStatus.CERT_CHANGED }
                ?.let { it to (state.status == MqttStatus.CERT_CHANGED) }
        if (tls && selfSigned && certToConfirm != null && certToConfirm.first != pin) {
            val (fingerprint, changed) = certToConfirm
            Text(
                stringResource(if (changed) Res.string.mqtt_cert_changed_warning else Res.string.mqtt_cert_confirm),
                style = MaterialTheme.typography.bodyMedium, color = if (changed) LauncherTheme.colors.danger else LauncherTheme.colors.onSurface,
            )
            Text(formatFingerprint(fingerprint!!), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            LauncherButton(stringResource(Res.string.mqtt_cert_trust), onClick = { pin = fingerprint; testResult = null })
        }
        ButtonRow {
            LauncherButton(stringResource(Res.string.action_save), primary = true, enabled = !enabled || (host.isNotBlank() && portNumber != null), onClick = {
                if (password.isNotEmpty()) c.secrets.put(SecretStore.Keys.MQTT_PASSWORD, password)
                // Without a user name no password is sent: a stored one goes with it.
                if (user.isBlank()) c.secrets.put(SecretStore.Keys.MQTT_PASSWORD, null)
                update {
                    it.copy(
                        mqttEnabled = enabled, mqttHost = host.trim(), mqttPort = portNumber ?: it.mqttPort, mqttTls = tls,
                        mqttAllowSelfSigned = tls && selfSigned,
                        // A pin belongs to one broker with self-signed certificates allowed.
                        mqttCertPin = if (tls && selfSigned && (pin != it.mqttCertPin || host.trim() == it.mqttHost)) pin else "",
                        mqttUser = user.trim(), mqttDeviceName = deviceName.trim(),
                    )
                }
                c.mqtt.restart()
                onDismiss()
            })
            LauncherButton(stringResource(Res.string.action_cancel), onClick = onDismiss)
            LauncherButton(
                stringResource(if (testing) Res.string.mqtt_testing else Res.string.mqtt_test),
                enabled = !testing && host.isNotBlank() && portNumber != null,
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = c.mqtt.test(host, portNumber!!, tls, user, password.ifEmpty { null }, selfSigned, pin.ifEmpty { null })
                        testing = false
                    }
                },
            )
        }
    }
}
