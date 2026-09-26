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

    fun setCurrency(c: Currency) {
        prefs.edit().putString("currency", c.name).apply()
        _currency.value = c
    }

    fun setDefaultCondition(c: String) {
        prefs.edit().putString("condition", c).apply()
        _defaultCondition.value = c
    }
}
