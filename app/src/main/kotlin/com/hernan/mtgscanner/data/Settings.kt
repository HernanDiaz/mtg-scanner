package com.hernan.mtgscanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Preferencias del usuario. El estado por defecto es NM. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _currency = MutableStateFlow(
        runCatching { Currency.valueOf(prefs.getString("currency", null)!!) }.getOrDefault(Currency.EUR)
    )
    val currency: StateFlow<Currency> = _currency

    private val _defaultCondition = MutableStateFlow(prefs.getString("condition", "NM")!!)
    val defaultCondition: StateFlow<String> = _defaultCondition

    /** Mazo al que van las cartas escaneadas; null = ninguno. */
    private val _scanDeck = MutableStateFlow(prefs.getLong("scan_deck", 0L).takeIf { it > 0 })
    val scanDeck: StateFlow<Long?> = _scanDeck

    fun setScanDeck(id: Long?) {
        prefs.edit().putLong("scan_deck", id ?: 0L).apply()
        _scanDeck.value = id
    }

    private val _priceKind = MutableStateFlow(
        runCatching { PriceKind.valueOf(prefs.getString("price_kind", null)!!) }.getOrDefault(PriceKind.TREND)
    )
    val priceKind: StateFlow<PriceKind> = _priceKind

    fun setPriceKind(k: PriceKind) {
        prefs.edit().putString("price_kind", k.name).apply()
        _priceKind.value = k
    }

    fun setCurrency(c: Currency) {
        prefs.edit().putString("currency", c.name).apply()
        _currency.value = c
    }

    fun setDefaultCondition(c: String) {
        prefs.edit().putString("condition", c).apply()
        _defaultCondition.value = c
    }
}
