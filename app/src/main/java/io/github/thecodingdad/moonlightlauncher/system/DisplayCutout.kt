package io.github.thecodingdad.moonlightlauncher.system

import android.view.Window
import android.view.WindowManager

/**
 * Keeps the window out of a camera notch/hole: the bars are hidden, so content would otherwise be
 * drawn under it (status icons covered). Android leaves a black strip on that edge instead;
 * screens without a cutout are unaffected.
 */
fun avoidDisplayCutout(window: Window) {
    window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
    }
}
