package com.hernan.mtgscanner.data

import androidx.annotation.StringRes
import com.hernan.mtgscanner.R

/** Una impresión concreta de una carta (edición + número de coleccionista). */
data class CardPrinting(
    val id: String,
    val name: String,
    val setCode: String,
    val setName: String,
    val setType: String?,
    /** Tamaño impreso de la edición (el "/274"); sirve para identificarla sin código. */
    val setSize: Int?,
    val collectorNumber: String,
    val numberNorm: String,
    val rarity: String?,
    val releasedAt: String?,
    val finishes: Int,
    val promo: Boolean,
    /** "en" salvo ediciones que solo existen en otro idioma (p. ej. la colección Salvat, "es"). */
    val lang: String = "en",
    val prices: Prices,
    /** Ilustración (Catalog.art); null si todas las impresiones de la carta comparten dibujo. */
    val artId: Int? = null,
    /** Colores "WUBRG" (en ese orden); vacío = incolora. */
    val colors: String = "",
    /** Cómo va impreso el símbolo de edición: marco ("2015"...) y "x" si es una variante. */
    val style: String = "",
    /** Producto en Cardmarket (su guía de precios), si lo tiene. */
    val cardmarketId: Int? = null,
) {
    val releaseYear: Int? get() = releasedAt?.take(4)?.toIntOrNull()

    val hasNonfoil get() = finishes and 1 != 0
    val hasFoil get() = finishes and 6 != 0 // foil o etched

    /** Imagen pequeña en la CDN de Scryfall; la URL se deduce del uuid. */
    val smallImageUrl get() = "https://cards.scryfall.io/small/front/${id[0]}/${id[1]}/$id.jpg"
    val normalImageUrl get() = "https://cards.scryfall.io/normal/front/${id[0]}/${id[1]}/$id.jpg"
}

data class Prices(
    val eur: Double?,
    val eurFoil: Double?,
    val usd: Double?,
    val usdFoil: Double?,
) {
    /** Precio aplicable; si falta el del acabado pedido se usa el otro. */
    fun of(currency: Currency, foil: Boolean): Double? = when (currency) {
        Currency.EUR -> if (foil) eurFoil ?: eur else eur ?: eurFoil
        Currency.USD -> if (foil) usdFoil ?: usd else usd ?: usdFoil
    }
}

enum class Currency(val symbol: String) { EUR("€"), USD("$") }

/**
 * Qué precio de Cardmarket se usa para valorar la colección (en euros; los
 * dólares son siempre los de TCGplayer). La tendencia es la que da Scryfall;
 * los otros vienen de la guía de precios de Cardmarket.
 */
enum class PriceKind(@StringRes val label: Int, @StringRes val description: Int) {
    TREND(R.string.price_trend, R.string.price_trend_desc),
    LOW(R.string.price_low, R.string.price_low_desc),
    AVG30(R.string.price_avg30, R.string.price_avg30_desc),
}

/** Estados estándar de Cardmarket. */
val CONDITIONS = listOf("M", "NM", "EX", "GD", "LP", "PL", "PO")

/**
 * Porcentaje del precio que se cuenta según el estado, si se activa "valor según
 * el estado". Los precios de Cardmarket y Scryfall no distinguen estados (son los
 * de cartas en buen estado): esto es una estimación, en la línea de lo que se
 * suele pagar por cartas usadas. El usuario puede cambiarlos.
 */
val DEFAULT_CONDITION_FACTORS = linkedMapOf("M" to 100, "NM" to 100, "EX" to 85, "GD" to 70, "LP" to 60, "PL" to 45, "PO" to 30)
