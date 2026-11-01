package io.github.thecodingdad.gamelodge.ui.components

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.thecodingdad.gamelodge.ui.input.PadAction
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*

enum class TextKind { TEXT, URL, NUMBER, PASSWORD }

data class KeyboardRequest(
    val title: String,
    val initial: String,
    val kind: TextKind,
    val onDone: (String) -> Unit,
    /** Asked for with a controller button: the on-screen keyboard even while the launcher is in touch mode. */
    val controller: Boolean = false,
)

@Stable
class KeyboardController {
    var request by mutableStateOf<KeyboardRequest?>(null)
        private set

    fun open(title: String, initial: String, kind: TextKind = TextKind.TEXT, controller: Boolean = false, onDone: (String) -> Unit) {
        request = KeyboardRequest(title, initial, kind, onDone, controller)
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
    // Typing on a hardware keyboard switches to key input mode: the field being edited stays a field.
    var editing by remember { mutableStateOf(false) }
    // The text field only takes the focus from a tap or click: a D-pad move or a restored focus
    // (e.g. the first key press after touch, before the switch to controller mode) never lands
    // in it, so the system keyboard can't pop up.
    var tapped by remember { mutableStateOf(false) }
    val controllerMode = isControllerMode()
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Controller input while the field (focused by touch) is still a text field: no system keyboard.
    androidx.compose.runtime.LaunchedEffect(controllerMode, editing) { if (controllerMode && editing) softKeyboard?.hide() }
    if (!controllerMode || editing) {
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
                // Only a tap opens the system keyboard: focus arriving by itself (restored when
                // a tab or page comes back, first element) must not pop it up.
                showKeyboardOnFocus = false,
            ),
            modifier = modifier.fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        tapped = true
                    }
                }
                .focusProperties { canFocus = tapped || editing }
                .onFocusChanged {
                editing = it.isFocused
                if (!it.isFocused) tapped = false
                // Continue from the field when a controller or arrow key comes next.
                if (it.isFocused) TouchFocus.clear()
            }.onPreviewKeyEvent { e ->
                // A (arrives as D-pad centre) would open the system keyboard: the controller's instead.
                val confirm = e.key == androidx.compose.ui.input.key.Key.DirectionCenter || e.key == androidx.compose.ui.input.key.Key.ButtonA
                if (!confirm) return@onPreviewKeyEvent false
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) keyboard.open(label, value, kind, controller = true, onDone = onValueChange)
                true
            },
        )
        return
    }
    FocusSurface(
        onClick = { keyboard.open(label, value, kind, onDone = onValueChange) },
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
    val touch = remember(request) { !controllerMode && request?.controller != true }
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
            e.utf16CodePoint > 31 && e.utf16CodePoint != 127 -> {
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
        val german = androidx.compose.ui.text.intl.Locale.current.language == "de"

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
            val at = cursor.coerceAtMost(display.length)
            // The cursor is a thin bar with the same small gap on both sides (a text character
            // like "▏" sits at the left of its width and leaves a wide gap after it).
            val cursorText = androidx.compose.ui.text.buildAnnotatedString {
                append(display.substring(0, at))
                appendInlineContent("cursor", "|")
                append(display.substring(at))
            }
            val accent = colors.accent
            Text(
                cursorText,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 2,
                inlineContent = mapOf(
                    "cursor" to androidx.compose.foundation.text.InlineTextContent(
                        androidx.compose.ui.text.Placeholder(0.3.em, 1.2.em, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(accent))
                        }
                    },
                ),
            )
        }
        Spacer(Modifier.height(16.dp))

        val rows = when {
            symbolMode -> symbols
            request.kind == TextKind.NUMBER -> listOf("123", "456", "789", ".0-")
            german -> qwertz
            else -> qwerty
        }
        // Key sizes from the room left: wider keys (less empty space beside them) and up to the
        // full height on large screens; narrower and lower on small or portrait ones, so every
        // key always stays visible. Text keeps its size (no scaling, stays sharp).
        val urlRowShown = request.kind == TextKind.URL
        val atKey = request.kind == TextKind.URL || request.kind == TextKind.TEXT
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.TopCenter) {
            val gap = 8.dp
            val lines = rows.size + 1 + if (urlRowShown) 1 else 0
            // The lines share the height (up to 64 dp each), so they always fit.
            val keyHeight0 = 64.dp
            // Widths in units of a letter key: every line must fit the width.
            val bottomUnits = (88 + 88 + 300 + (if (atKey) 64 else 0) + 88 + 140) / 60f
            val bottomGaps = if (atKey) 5 else 4
            val widest = rows.maxOf { it.length }
            val fit = listOfNotNull(
                (maxWidth - gap * (widest - 1)) / widest,
                (maxWidth - gap * bottomGaps) / bottomUnits,
                if (urlRowShown) (maxWidth - gap * (urlRow.size - 1)) / (urlRow.size * 72 / 60f) else null,
            ).minOf { it }
            val unit = fit.coerceIn(28.dp, keyHeight0 * 1.6f)
            // Lines share the height (so they always fit), at most 64 dp and not much higher than
            // a letter key is wide (upright screens: narrow keys).
            val lineMax = minOf(64.dp, maxOf(unit * 1.1f, 40.dp))
            val full = lineMax * lines + gap * (lines - 1)
            val total = if (constraints.hasBoundedHeight) minOf(maxHeight, full) else full
            val keyHeight = (total - gap * (lines - 1)) / lines
            androidx.compose.runtime.CompositionLocalProvider(LocalKeySize provides KeySize(unit, keyHeight)) {
                Column(Modifier.height(total), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (urlRowShown) {
                        KeyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            urlRow.forEach { s -> KeyCap(s, onClick = { insert(s) }, minWidth = 72.dp) }
                        }
                    }
                    rows.forEach { row ->
                        KeyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { ch ->
                                val label = if (shift) ch.uppercase() else ch.toString()
                                KeyCap(label, onClick = { insert(ch.toString()) })
                            }
                        }
                    }
                    KeyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        KeyCap(if (symbolMode) "abc" else "#+=", onClick = { symbolMode = !symbolMode }, minWidth = 88.dp)
                        KeyCap("", icon = Icons.Filled.ArrowUpward, onClick = { shift = !shift }, minWidth = 88.dp, highlighted = shift)
                        KeyCap("", icon = Icons.Filled.SpaceBar, onClick = { insert(" ") }, minWidth = 300.dp)
                        if (atKey) KeyCap("@", onClick = { insert("@") }, minWidth = 64.dp)
                        KeyCap("", icon = Icons.AutoMirrored.Filled.Backspace, onClick = { backspace() }, minWidth = 88.dp)
                        KeyCap(stringResource(Res.string.keyboard_done), icon = Icons.Filled.Check, onClick = { done() }, minWidth = 140.dp, highlighted = true)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ButtonHintBar(
            listOf(
                ButtonHint(PadButton.X, stringResource(Res.string.keyboard_delete)),
                ButtonHint(PadButton.Y, stringResource(Res.string.keyboard_space)),
                ButtonHint(PadButton.L2, stringResource(Res.string.keyboard_shift)),
                ButtonHint(PadButton.R2, stringResource(Res.string.keyboard_symbols)),
                ButtonHint(PadButton.L1, stringResource(Res.string.keyboard_cursor), secondButton = PadButton.R1),
                ButtonHint(PadButton.B, stringResource(Res.string.action_cancel), onClick = controller::close),
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
    /** Width at the base size (a letter key is 60 dp), scaled with [LocalKeySize]. */
    minWidth: androidx.compose.ui.unit.Dp = 60.dp,
    highlighted: Boolean = false,
) {
    val colors = LauncherTheme.colors
    val size = LocalKeySize.current
    val width = size.unit * (minWidth / 60.dp)
    // The focused key is drawn above its neighbours and every key grows by the same few dp
    // (10 % of the space bar would reach far over the keys beside it).
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.zIndex(if (focused) 1f else 0f).onFocusChanged { focused = it.hasFocus }.width(width).fillMaxHeight(),
        color = if (highlighted) colors.accent.copy(alpha = 0.35f) else colors.surfaceRaised,
        focusScale = 1f + 12.dp / width,
        clickSound = io.github.thecodingdad.gamelodge.ui.feedback.UiSound.TOGGLE,
    ) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
            // Narrow keys (upright screens): the icon alone rather than a wrapped label.
            val showLabel = label.isNotEmpty() && (icon == null || width >= 110.dp)
            if (icon != null && showLabel) Spacer(Modifier.width(6.dp))
            if (showLabel) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A line of keys, drawn above the other lines while one of its keys has the focus (its zoomed frame). */
@Composable
private fun KeyRow(modifier: Modifier, horizontalArrangement: Arrangement.Horizontal, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.zIndex(if (focused) 1f else 0f).onFocusChanged { focused = it.hasFocus }, horizontalArrangement = horizontalArrangement, content = content)
}

/** Size of a letter key on the on-screen keyboard (the others are wider in proportion). */
private class KeySize(val unit: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp)

private val LocalKeySize = androidx.compose.runtime.staticCompositionLocalOf { KeySize(60.dp, 56.dp) }

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
            LauncherButton(stringResource(Res.string.action_cancel), onClick = controller::close)
            LauncherButton(stringResource(Res.string.keyboard_done), icon = Icons.Filled.Check, primary = true, onClick = { done() })
        }
    }
    androidx.compose.runtime.LaunchedEffect(request) {
        kotlinx.coroutines.delay(100)
        FocusSound.silence()
        runCatching { focus.requestFocus() }
    }
}
