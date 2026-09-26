package com.hernan.mtgscanner.data

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

/** Estados estándar de Cardmarket. */
val CONDITIONS = listOf("M", "NM", "EX", "GD", "LP", "PL", "PO")
