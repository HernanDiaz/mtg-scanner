package com.hernan.mtgscanner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * La copia de seguridad: nuestro CSV con toda la colección (sin filtros), con mazos
 * y cartas por revisar. La usan la copia manual y la automática (AutoBackup);
 * se restaura con Importar -> Sustituir.
 */
object CollectionBackup {

    /**
     * [fingerprint]: resumen de las cartas sin precios, para no repetir una copia igual.
     * [photoNames]: con fotos, qué foto (por copia) va en el zip y con qué nombre.
     */
    class Content(val csv: String, val cards: Int, val fingerprint: String, val photoNames: Map<Long, String> = emptyMap())

    /**
     * [photoIds]: copias con foto, para que el CSV nombre las fotos de cada línea (zip con
     * las fotos); null = sin fotos. [english]: cabeceras en inglés (el importador entiende ambas).
     */
    suspend fun build(
        catalog: Catalog, store: CollectionStore, settings: Settings, english: Boolean, photoIds: Set<Long>? = null,
    ): Content = withContext(Dispatchers.IO) {
        val currency = settings.currency.value
        val decks = store.decks().associate { it.id to it.name }
        val deckName = { id: Long? -> id?.let(decks::get) }
        val plain = CollectionExport.lines(
            store.groups(currency, kind = settings.priceKind.value), catalog::setName, deckName, detailed = true,
        )
        val (lines, photoNames) = if (photoIds == null) plain to emptyMap() else {
            CollectionExport.withPhotos(plain, store.entryKeys(), detailed = true, deckName, photoIds::contains)
        }
        Content(Char(0xFEFF) + CollectionExport.csv(lines, currency, english), lines.sumOf { it.quantity }, fingerprint(plain), photoNames)
    }

    /**
     * Cambia si cambian las cartas, sus mazos o estados; no si solo cambian los precios.
     * (FNV-1a de 64 bits: solo hace falta notar cambios, no seguridad.)
     */
    fun fingerprint(lines: List<CollectionExport.Line>): String {
        var hash = -0x340d631b7bdddcdbL // 14695981039346656037
        for (line in lines) {
            for (b in line.copy(unitPrice = null).toString().encodeToByteArray()) {
                hash = (hash xor (b.toLong() and 0xFF)) * 0x100000001b3L
            }
            hash = (hash xor 0x0A) * 0x100000001b3L // separador entre líneas
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}
