package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

enum class TextKind { TEXT, URL, NUMBER, PASSWORD }

data class KeyboardRequest(
    val title: String,
    val initial: String,
    val kind: TextKind,
    val onDone: (String) -> Unit,
)

@Stable
class KeyboardController {
    var request by mutableStateOf<KeyboardRequest?>(null)
        private set

    fun open(title: String, initial: String, kind: TextKind = TextKind.TEXT, onDone: (String) -> Unit) {
        request = KeyboardRequest(title, initial, kind, onDone)
    }

    fun close() {
        request = null
    }
}

val LocalKeyboardController = staticCompositionLocalOf { KeyboardController() }

/**
 * Text input that adapts to the input mode: touch users get the system keyboard inline,
 * gamepad users a focusable field that opens the controller keyboard.
 */
@Composable
fun LauncherTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    kind: TextKind = TextKind.TEXT,
    placeholder: String? = null,
) {
    val keyboard = LocalKeyboardController.current
    val colors = LauncherTheme.colors
    if (!isControllerMode()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            visualTransformation = if (kind == TextKind.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (kind) {
                    TextKind.URL -> KeyboardType.Uri
                    TextKind.NUMBER -> KeyboardType.Number
                    TextKind.PASSWORD -> KeyboardType.Password
                    TextKind.TEXT -> KeyboardType.Text
                },
            ),
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    FocusSurface(
        onClick = { keyboard.open(label, value, kind, onValueChange) },
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        color = colors.surfaceRaised,
        focusScale = 1.02f,
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
            val shown = when {
                value.isEmpty() -> placeholder ?: ""
                kind == TextKind.PASSWORD -> "•".repeat(value.length.coerceAtMost(24))
                else -> value
            }
            Text(
                shown,
                style = MaterialTheme.typography.bodyLarge,
                color = if (value.isEmpty()) colors.onSurfaceMuted else colors.onSurface,
                maxLines = 1,
            )
        }
    }
}

private val qwertz = listOf("1234567890ß", "qwertzuiopü", "asdfghjklöä", "yxcvbnm,.-")
private val qwerty = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm,.-")
private val symbols = listOf("1234567890", "@#\$%&*+=/\\", ":;!?()[]{}", "_~\"'<>|^`")
private val urlRow = listOf("https://", "http://", ".", "/", ":", ".local", "-")

/**
 * Text entry dialog: the on-screen keyboard driven by D-pad/stick + shortcut buttons, or in
 * touch mode a plain text field with the system keyboard.
 */
@Composable
fun ControllerKeyboardHost(controller: KeyboardController) {
    val request = controller.request
    // The mode at the moment the dialog opens decides; switching while typing doesn't swap it.
    val controllerMode = isControllerMode()
    val touch = remember(request) { !controllerMode }
    if (request != null && touch) {
        TouchTextDialog(request, controller)
        return
    }
    var text by remember(request) { mutableStateOf(request?.initial.orEmpty()) }
    var cursor by remember(request) { mutableIntStateOf(request?.initial.orEmpty().length) }
    var shift by remember(request) { mutableStateOf(false) }
    var symbolMode by remember(request) { mutableStateOf(false) }
    // A hardware keyboard (Bluetooth, adb) can type directly while the keyboard is open.
    val hardwareKeys = Modifier.onPreviewKeyEvent { e ->
        if (request == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            e.key == Key.Backspace -> {
                if (cursor > 0) { text = text.removeRange(cursor - 1, cursor); cursor-- }
                true
            }
            e.key == Key.Enter || e.key == Key.NumPadEnter -> { request.onDone(text); controller.close(); true }
            e.nativeKeyEvent.isPrintingKey && e.utf16CodePoint > 31 -> {
                text = text.substring(0, cursor) + e.utf16CodePoint.toChar() + text.substring(cursor)
                cursor++
                true
            }
            else -> false
        }
    }
    Modal(
        visible = request != null,
        onDismiss = controller::close,
        title = request?.title,
        maxWidth = 1100.dp,
        modifier = hardwareKeys,
    ) {
        if (request == null) return@Modal
        val german = LocalConfiguration.current.locales[0].language == "de"

        fun insert(s: String) {
            val str = if (shift && s.length == 1) s.uppercase() else s
            text = text.substring(0, cursor) + str + text.substring(cursor)
            cursor += str.length
            if (shift) shift = false
        }
        fun backspace() {
            if (cursor > 0) {
                text = text.removeRange(cursor - 1, cursor)
                cursor--
            }
        }
        fun done() {
            request.onDone(text)
            controller.close()
        }

        PadAction(PadButton.X) { backspace() }
        PadAction(PadButton.Y) { insert(" ") }
        PadAction(PadButton.L1) { cursor = (cursor - 1).coerceAtLeast(0) }
        PadAction(PadButton.R1) { cursor = (cursor + 1).coerceAtMost(text.length) }
        PadAction(PadButton.L2) { shift = !shift }
        PadAction(PadButton.R2) { symbolMode = !symbolMode }
        PadAction(PadButton.START) { done() }

        val colors = LauncherTheme.colors
        // Display with a visible cursor
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            val display = if (request.kind == TextKind.PASSWORD) "•".repeat(text.length) else text
            Text(
                display.substring(0, cursor.coerceAtMost(display.length)) + "▏" + display.substring(cursor.coerceAtMost(display.length)),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(16.dp))

        val rows = when {
            symbolMode -> symbols
            request.kind == TextKind.NUMBER -> listOf("123", "456", "789", ".0-")
            german -> qwertz
            else -> qwerty
        }
        // Shrinks to the room left (small or portrait screens): every key always stays visible.
        Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.TopCenter) {
        ScaleToFit {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (request.kind == TextKind.URL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    urlRow.forEach { s -> KeyCap(s, onClick = { insert(s) }, minWidth = 72.dp) }
                }
            }
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { ch ->
                        val label = if (shift) ch.uppercase() else ch.toString()
                        KeyCap(label, onClick = { insert(ch.toString()) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyCap(if (symbolMode) "abc" else "#+=", onClick = { symbolMode = !symbolMode }, minWidth = 88.dp)
                KeyCap("", icon = Icons.Filled.ArrowUpward, onClick = { shift = !shift }, minWidth = 88.dp, highlighted = shift)
                KeyCap("", icon = Icons.Filled.SpaceBar, onClick = { insert(" ") }, minWidth = 300.dp)
                if (request.kind == TextKind.URL || request.kind == TextKind.TEXT) KeyCap("@", onClick = { insert("@") }, minWidth = 64.dp)
                KeyCap("", icon = Icons.AutoMirrored.Filled.Backspace, onClick = { backspace() }, minWidth = 88.dp)
                KeyCap(stringResource(R.string.keyboard_done), icon = Icons.Filled.Check, onClick = { done() }, minWidth = 140.dp, highlighted = true)
            }
        }
        }
        }
        Spacer(Modifier.height(12.dp))
        ButtonHintBar(
            listOf(
                ButtonHint(PadButton.X, stringResource(R.string.keyboard_delete)),
                ButtonHint(PadButton.Y, stringResource(R.string.keyboard_space)),
                ButtonHint(PadButton.L2, stringResource(R.string.keyboard_shift)),
                ButtonHint(PadButton.R2, stringResource(R.string.keyboard_symbols)),
                ButtonHint(PadButton.START, stringResource(R.string.keyboard_done)),
                ButtonHint(PadButton.B, stringResource(R.string.action_cancel), onClick = controller::close),
            ),
            inPopup = true,
        )
    }
}

@Composable
private fun KeyCap(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    minWidth: androidx.compose.ui.unit.Dp = 60.dp,
    highlighted: Boolean = false,
) {
    val colors = LauncherTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(minWidth).height(56.dp),
        color = if (highlighted) colors.accent.copy(alpha = 0.35f) else colors.surfaceRaised,
        focusScale = 1.1f,
        clickSound = io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound.TOGGLE,
    ) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
            if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(6.dp))
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        }
    }
}

/** Lays [content] out at its natural size and scales it down (never up) to fit the constraints. */
@Composable
private fun ScaleToFit(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        val placeable = measurables.first().measure(androidx.compose.ui.unit.Constraints())
        val scale = minOf(
            1f,
            if (constraints.hasBoundedWidth) constraints.maxWidth / placeable.width.toFloat() else 1f,
            if (constraints.hasBoundedHeight) constraints.maxHeight / placeable.height.toFloat() else 1f,
        )
        layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Touch mode: text field with the system keyboard, opened right away. */
@Composable
private fun TouchTextDialog(request: KeyboardRequest, controller: KeyboardController) {
    var text by remember(request) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(request.initial, androidx.compose.ui.text.TextRange(request.initial.length)))
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun done() {
        request.onDone(text.text)
        controller.close()
    }
    Modal(visible = true, onDismiss = controller::close, title = request.title, maxWidth = 720.dp) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            visualTransformation = if (request.kind == TextKind.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (request.kind) {
                    TextKind.URL -> KeyboardType.Uri
                    TextKind.NUMBER -> KeyboardType.Number
                    TextKind.PASSWORD -> KeyboardType.Password
                    TextKind.TEXT -> KeyboardType.Text
                },
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        ButtonRow(spacing = 12.dp) {
            LauncherButton(stringResource(R.string.action_cancel), onClick = controller::close)
            LauncherButton(stringResource(R.string.keyboard_done), icon = Icons.Filled.Check, primary = true, onClick = { done() })
        }
    }
    androidx.compose.runtime.LaunchedEffect(request) {
        kotlinx.coroutines.delay(100)
        runCatching { focus.requestFocus() }
    }
}
