package com.hernan.mtgscanner.ui

import androidx.compose.runtime.Composable
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.data.fixed
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** "1.234,56 €" (como en España) o "$1,234.56" (como en EE. UU.), sea cual sea el idioma del móvil. */
fun formatPrice(value: Double?, currency: Currency): String = when {
    value == null -> "—"
    currency == Currency.EUR -> grouped(value, thousands = '.', decimal = ',') + " €"
    else -> (if (value < 0) "-" else "") + "$" + grouped(abs(value), thousands = ',', decimal = '.')
}

/** Dos decimales y separador de miles. */
private fun grouped(value: Double, thousands: Char, decimal: Char): String {
    val (int, frac) = abs(value).fixed(2).split('.')
    val withThousands = int.reversed().chunked(3).joinToString(thousands.toString()).reversed()
    return (if (value < 0) "-" else "") + withThousands + decimal + frac
}

/** "1 carta", "3 cartas" / "1 card", "3 cards". */
@Composable
fun cardsLabel(n: Int) = pluralStringResource(Res.plurals.cards, n, n)

/** Lo mismo fuera de la composición (mensajes de los ViewModels). */
suspend fun cardsText(n: Int) = getPluralString(Res.plurals.cards, n, n)

/** "Inglés", "Francés"... para un código de idioma ("en", "fr"...); si no se conoce, el código. */
@Composable
fun languageName(code: String): String = when (code) {
    "en" -> stringResource(Res.string.lang_en)
    "es" -> stringResource(Res.string.lang_es)
    "fr" -> stringResource(Res.string.lang_fr)
    "de" -> stringResource(Res.string.lang_de)
    "it" -> stringResource(Res.string.lang_it)
    "pt" -> stringResource(Res.string.lang_pt)
    else -> code.uppercase()
}
