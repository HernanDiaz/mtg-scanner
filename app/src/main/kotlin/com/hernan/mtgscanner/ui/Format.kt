package com.hernan.mtgscanner.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.hernan.mtgscanner.R
import com.hernan.mtgscanner.data.Currency
import java.util.Locale

private val SPAIN = Locale("es", "ES")

fun formatPrice(value: Double?, currency: Currency): String = when {
    value == null -> "—"
    currency == Currency.EUR -> String.format(SPAIN, "%,.2f €", value)
    else -> String.format(Locale.US, "$%,.2f", value)
}

/** "1 carta", "3 cartas" / "1 card", "3 cards". */
@Composable
fun cardsLabel(n: Int) = pluralStringResource(R.plurals.cards, n, n)

fun Context.cardsLabel(n: Int): String = resources.getQuantityString(R.plurals.cards, n, n)

/** "Inglés", "Francés"... para un código de idioma ("en", "fr"...); si no se conoce, el código. */
@Composable
fun languageName(code: String): String = when (code) {
    "en" -> stringResource(R.string.lang_en)
    "es" -> stringResource(R.string.lang_es)
    "fr" -> stringResource(R.string.lang_fr)
    "de" -> stringResource(R.string.lang_de)
    "it" -> stringResource(R.string.lang_it)
    "pt" -> stringResource(R.string.lang_pt)
    else -> code.uppercase()
}
