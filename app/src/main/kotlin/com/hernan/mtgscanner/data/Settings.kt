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

    /** Modo de prueba: guardar el fotograma de cada carta escaneada ([TestPhotos]). */
    private val _testPhotos = MutableStateFlow(prefs.getBoolean("test_photos", false))
    val testPhotos: StateFlow<Boolean> = _testPhotos

    fun setTestPhotos(on: Boolean) {
        prefs.edit().putBoolean("test_photos", on).apply()
        _testPhotos.value = on
    }

    /** Valorar cada carta según su estado ([conditionFactors]). */
    private val _conditionPricing = MutableStateFlow(prefs.getBoolean("condition_pricing", false))
    val conditionPricing: StateFlow<Boolean> = _conditionPricing

    /** Estado -> porcentaje del precio (ver [DEFAULT_CONDITION_FACTORS]). */
    private val _conditionFactors = MutableStateFlow(readFactors())
    val conditionFactors: StateFlow<Map<String, Int>> = _conditionFactors

    fun setConditionPricing(on: Boolean) {
        prefs.edit().putBoolean("condition_pricing", on).apply()
        _conditionPricing.value = on
    }

    fun setConditionFactor(condition: String, percent: Int) {
        saveFactors(_conditionFactors.value + (condition to percent.coerceIn(0, 100)))
    }

    fun resetConditionFactors() = saveFactors(DEFAULT_CONDITION_FACTORS)

    private fun saveFactors(f: Map<String, Int>) {
        prefs.edit().putString("condition_factors", f.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
        _conditionFactors.value = f
    }

    /** "EX=85,GD=70,..." Lo que falte, el valor por defecto. */
    private fun readFactors(): Map<String, Int> {
        val saved = prefs.getString("condition_factors", null)?.split(",")?.mapNotNull { part ->
            part.split("=").takeIf { it.size == 2 }?.let { (k, v) -> v.toIntOrNull()?.let { k to it } }
        }?.toMap().orEmpty()
        return DEFAULT_CONDITION_FACTORS.mapValues { (k, v) -> saved[k] ?: v }
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
