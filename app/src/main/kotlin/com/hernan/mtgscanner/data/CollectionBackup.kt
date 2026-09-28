package com.hernan.mtgscanner.data

import com.hernan.mtgscanner.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * La copia de seguridad: nuestro CSV con toda la colección (sin filtros), con mazos
 * y cartas por revisar. La usan la copia manual y la automática ([AutoBackup]);
 * se restaura con Importar -> Sustituir.
 */
object CollectionBackup {

    /** [fingerprint]: resumen de las cartas sin precios, para no repetir una copia igual. */
    class Content(val csv: String, val cards: Int, val fingerprint: String)

    suspend fun build(app: App): Content = withContext(Dispatchers.IO) {
        val catalog = app.catalog.await()
        val store = app.collection
        val currency = app.settings.currency.value
        val decks = store.decks().associate { it.id to it.name }
        val lines = CollectionExport.lines(
            store.groups(currency, kind = app.settings.priceKind.value), catalog::setName,
            { id -> id?.let(decks::get) }, detailed = true,
        )
        // Cabeceras en inglés salvo con la app en español (el importador entiende ambas).
        val english = app.resources.configuration.locales[0].language != "es"
        Content(Char(0xFEFF) + CollectionExport.csv(lines, currency, english), lines.sumOf { it.quantity }, fingerprint(lines))
    }

    /** Cambia si cambian las cartas, sus mazos o estados; no si solo cambian los precios. */
    fun fingerprint(lines: List<CollectionExport.Line>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach { digest.update(it.copy(unitPrice = null).toString().toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
