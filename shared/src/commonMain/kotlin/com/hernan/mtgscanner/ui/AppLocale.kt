package com.hernan.mtgscanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue

/**
 * El idioma de la app elegido dentro de ella (Settings.appLanguage), por encima del del móvil:
 * los textos de composeResources lo toman al recomponer (MtgScannerApp vuelve a componer todo
 * al cambiarlo). null = el del móvil. Es el método de la documentación de Compose Multiplatform.
 */
expect object LocalAppLocale {
    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/** Los idiomas de la app, con su nombre en ese idioma (no se traducen). */
val APP_LANGUAGES = listOf(
    "en" to "English", "es" to "Español", "de" to "Deutsch", "fr" to "Français", "it" to "Italiano",
    "pt" to "Português", "ja" to "日本語", "ko" to "한국어", "zh" to "简体中文", "ru" to "Русский",
)
