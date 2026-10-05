package com.hernan.mtgscanner.data

/** Precios de un producto en la guía de precios de Cardmarket (en euros). */
data class CardmarketGuide(
    val trend: Double? = null, val trendFoil: Double? = null,
    val low: Double? = null, val lowFoil: Double? = null,
    val avg30: Double? = null, val avg30Foil: Double? = null,
)
