package com.hernan.mtgscanner.ui

import com.hernan.mtgscanner.data.Currency
import java.util.Locale

private val SPAIN = Locale("es", "ES")

fun formatPrice(value: Double?, currency: Currency): String = when {
    value == null -> "—"
    currency == Currency.EUR -> String.format(SPAIN, "%,.2f €", value)
    else -> String.format(Locale.US, "$%,.2f", value)
}

/** "1 carta", "3 cartas". */
fun cardsLabel(n: Int) = if (n == 1) "1 carta" else "$n cartas"
