package com.hernan.mtgscanner.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import com.hernan.mtgscanner.data.appContext

private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() }
private val vibrator by lazy { appContext.getSystemService(Vibrator::class.java) }

actual fun scanFeedback(needsReview: Boolean) {
    tone?.startTone(if (needsReview) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120)
    vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
}
