package com.hernan.mtgscanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

actual object LocalAppLocale {
    private const val LANGUAGES_KEY = "AppleLanguages"
    /** El del móvil, antes de cambiar nada. */
    private val system: String = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
    private val current = staticCompositionLocalOf { system }

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val defaults = NSUserDefaults.standardUserDefaults
        if (value == null) defaults.removeObjectForKey(LANGUAGES_KEY)
        else defaults.setObject(listOf(value), LANGUAGES_KEY)
        return current.provides(value ?: system)
    }
}
