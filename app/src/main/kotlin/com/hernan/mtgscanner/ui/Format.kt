package com.hernan.mtgscanner.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
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
