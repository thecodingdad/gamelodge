package io.github.thecodingdad.gamelodge

import android.app.Activity
import android.os.Bundle

/**
 * Shown on the TV for no time at all: an activity started on a display makes that display the
 * one with the input focus, so the launcher there gets the controllers again (bringing its task
 * forward doesn't, as it is in front of that display already).
 */
class TvFocusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
