package com.hernan.mtgscanner.ui

import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/** Sonido corto del sistema ("Tock") y un toque de vibración, como el pitido de Android. */
private const val TOCK_SOUND = 1104u

actual fun scanFeedback(needsReview: Boolean) {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue()) {
        AudioServicesPlaySystemSound(TOCK_SOUND)
        UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium).impactOccurred()
    }
    // Edición dudosa: dos sonidos.
    if (needsReview) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 180_000_000), dispatch_get_main_queue()) {
            AudioServicesPlaySystemSound(TOCK_SOUND)
        }
    }
}
