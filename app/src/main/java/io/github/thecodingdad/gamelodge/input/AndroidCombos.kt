package io.github.thecodingdad.gamelodge.input

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import io.github.thecodingdad.gamelodge.MainActivity
import io.github.thecodingdad.gamelodge.QuickMenuActivity
import io.github.thecodingdad.gamelodge.container

private fun isGamepadKey(event: KeyEvent): Boolean =
    KeyEvent.isGamepadButton(event.keyCode) ||
        (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD &&
        event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

/** Android key events into the combinations; true when [event] must not reach the app. */
fun ButtonCombos.handle(event: KeyEvent, onAction: (ComboAction, Int) -> Unit): Boolean {
    if (!isGamepadKey(event)) return false
    val isDown = when (event.action) {
        KeyEvent.ACTION_DOWN -> true
        KeyEvent.ACTION_UP -> false
        else -> return false
    }
    return handle(event.deviceId, event.keyCode, isDown, event.repeatCount, event.eventTime, event.device?.descriptor, KeyEvent(event), onAction)
}

/** What the combinations do on Android, wherever they were pressed. */
object AndroidCombos {
    private val main = Handler(Looper.getMainLooper())

    /**
     * A short press held back by a hold combination goes to GameLodge's window in front (in another
     * app it can't: no app may inject keys there); a moment later its release.
     */
    fun installReplay() {
        ButtonCombos.replay = { press, release ->
            val down = press as KeyEvent
            val up = release as KeyEvent
            val now = android.os.SystemClock.uptimeMillis()
            fun deliver(e: KeyEvent) {
                val activity = io.github.thecodingdad.gamelodge.CurrentActivity.resumed() ?: return
                ButtonCombos.replaying = true
                try { activity.dispatchKeyEvent(e) } finally { ButtonCombos.replaying = false }
            }
            main.post { deliver(KeyEvent.changeTimeRepeat(down, now, 0)) }
            main.postDelayed({
                deliver(KeyEvent(now, now + REPLAY_MS, KeyEvent.ACTION_UP, up.keyCode, 0, up.metaState, up.deviceId, up.scanCode, up.flags, up.source))
            }, REPLAY_MS)
        }
    }

    /**
     * Runs [action] of [controller] from outside the launcher activity (the accessibility service,
     * the stream): GameLodge comes to the front for Home and the quick menu, the stream actions
     * steer the running stream, the mouse is the stream's while it's in front, else GameLodge's own.
     */
    fun run(context: Context, action: ComboAction, controller: Int) {
        val streams = context.container.streamControls
        when (action) {
            ComboAction.HOME, ComboAction.QUICK_MENU -> main.post {
                val launcher = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                if (action == ComboAction.HOME) {
                    context.startActivity(launcher.putExtra(MainActivity.EXTRA_HOME, true))
                } else if (MainActivity.resumed) {
                    context.startActivity(launcher.putExtra(MainActivity.EXTRA_QUICK_MENU, true))
                } else {
                    QuickMenuActivity.toggle(context)
                }
            }
            ComboAction.DISCONNECT -> streams.disconnect()
            ComboAction.STATS -> streams.toggleOverlay()
            ComboAction.MOUSE -> main.post { PointerMode.toggle(context) }
        }
    }

    private const val REPLAY_MS = 60L
}
