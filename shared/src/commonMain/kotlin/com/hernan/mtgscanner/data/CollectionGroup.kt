package com.hernan.mtgscanner.data

/** Una fila de la colección: copias de la misma impresión, acabado, estado, idioma, revisión y mazo. */
data class CollectionGroup(
    val cardId: String,
    val name: String,
    val setCode: String,
    val collectorNumber: String,
    val foil: Boolean,
    val condition: String,
    /** Idioma de la carta física: "en", "es". */
    val lang: String,
    val quantity: Int,
    val needsReview: Boolean,
    val unitPrice: Double?,
    val lastEntryId: Long,
    /** Miniatura de la ilustración de la foto (la del último escaneo), para ordenar ediciones por parecido. */
    val art: ByteArray? = null,
    val deckId: Long? = null,
    val colors: String = "",
) {
    val smallImageUrl get() = "https://cards.scryfall.io/small/front/${cardId[0]}/${cardId[1]}/$cardId.jpg"
}

/** Una copia y lo que la agrupa en una línea exportada ([CollectionExport.lines]). */
data class EntryKey(
    val id: Long, val cardId: String, val foil: Boolean, val condition: String, val lang: String,
    val deckId: Long?, val needsReview: Boolean,
)
