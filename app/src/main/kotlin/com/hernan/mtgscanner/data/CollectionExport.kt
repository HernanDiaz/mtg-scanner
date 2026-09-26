package com.hernan.mtgscanner.data

import androidx.annotation.StringRes
import com.hernan.mtgscanner.R
import java.util.Locale

/**
 * Exportar la colección. Las funciones son puras (texto de entrada, texto de
 * salida) para poder probarlas; compartir el resultado lo hace la interfaz.
 */
object CollectionExport {

    enum class Format(@StringRes val label: Int, val extension: String, val mime: String) {
        CSV(R.string.format_csv, "csv", "text/csv"),
        MOXFIELD(R.string.format_moxfield, "csv", "text/csv"),
        TEXT(R.string.format_text, "txt", "text/plain"),
    }

    /** Una línea exportada: copias de la misma impresión, acabado, estado e idioma. */
    data class Line(
        val cardId: String,
        val name: String,
        val setCode: String,
        val setName: String,
        val collectorNumber: String,
        val foil: Boolean,
        val condition: String,
        val lang: String,
        val quantity: Int,
        val unitPrice: Double?,
        val deck: String? = null,
        val needsReview: Boolean = false,
    )

    /**
     * Líneas a exportar. Con [detailed] (nuestro CSV, que sirve de copia de
     * seguridad) se separan por mazo y por revisar; si no, se juntan: a Moxfield
     * o a una lista de texto no les importa.
     */
    fun lines(
        groups: List<CollectionStore.Group>,
        setName: (String) -> String,
        deckName: (Long?) -> String? = { null },
        detailed: Boolean = false,
    ): List<Line> =
        groups.groupBy {
            listOf(it.cardId, it.foil, it.condition, it.lang) + if (detailed) listOf(it.deckId, it.needsReview) else emptyList()
        }.values.map { same ->
            val g = same.first()
            Line(g.cardId, g.name, g.setCode, setName(g.setCode), g.collectorNumber, g.foil, g.condition,
                g.lang, same.sumOf { it.quantity }, g.unitPrice,
                deck = if (detailed) deckName(g.deckId) else null, needsReview = detailed && g.needsReview)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Line::name).thenBy { it.setCode }.thenBy { it.deck.orEmpty() })

    fun render(format: Format, lines: List<Line>, currency: Currency, english: Boolean = false): String = when (format) {
        Format.CSV -> csv(lines, currency, english)
        Format.MOXFIELD -> moxfield(lines)
        Format.TEXT -> text(lines)
    }

    /**
     * Todo, con precios. Separador coma y punto decimal: lo abre bien cualquier hoja
     * de cálculo. Lleva todo lo necesario para volver a importarlo (CollectionImport),
     * así que es también la copia de seguridad.
     */
    fun csv(lines: List<Line>, currency: Currency, english: Boolean = false): String = buildString {
        val c = currency.name
        if (english) {
            row("Quantity", "Name", "Set name", "Set code", "Number", "Language", "Condition", "Foil", "Deck", "Needs review",
                "Unit price ($c)", "Total price ($c)", "Scryfall ID")
        } else {
            row("Cantidad", "Nombre", "Edición", "Código", "Número", "Idioma", "Estado", "Foil", "Mazo", "Por revisar",
                "Precio unidad ($c)", "Precio total ($c)", "Scryfall ID")
        }
        val yes = if (english) "yes" else "sí"
        for (l in lines) {
            row(l.quantity.toString(), l.name, l.setName, l.setCode.uppercase(), l.collectorNumber,
                l.lang.uppercase(), l.condition, if (l.foil) yes else "no", l.deck.orEmpty(), if (l.needsReview) yes else "",
                l.unitPrice?.let(::money) ?: "", l.unitPrice?.let { money(it * l.quantity) } ?: "", l.cardId)
        }
    }

    /** Formato de importación de colecciones de Moxfield. */
    fun moxfield(lines: List<Line>): String = buildString {
        row("Count", "Tradelist Count", "Name", "Edition", "Condition", "Language", "Foil", "Tags",
            "Last Modified", "Collector Number")
        for (l in lines) {
            row(l.quantity.toString(), "0", l.name, l.setCode.lowercase(), MOXFIELD_CONDITION[l.condition] ?: "Near Mint",
                LANGUAGE_NAME[l.lang] ?: "English", if (l.foil) "foil" else "", "", "", l.collectorNumber)
        }
    }

    /** "2 Lightning Bolt (M11) 149", una línea por impresión, como las listas de mazos. */
    fun text(lines: List<Line>): String = lines.joinToString("\n") { l ->
        val extras = listOfNotNull(if (l.foil) "*F*" else null, if (l.lang != "en") l.lang.uppercase() else null)
        "${l.quantity} ${l.name} (${l.setCode.uppercase()}) ${l.collectorNumber}" +
            if (extras.isEmpty()) "" else " " + extras.joinToString(" ")
    } + "\n"

    private fun StringBuilder.row(vararg fields: String) {
        append(fields.joinToString(",") { quote(it) }).append("\r\n")
    }

    /** Comillas si hace falta ("Borborygmos, Enraged" lleva coma). */
    private fun quote(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun money(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    /** Estados de Cardmarket -> nombres de Moxfield. */
    private val MOXFIELD_CONDITION = mapOf(
        "M" to "Mint", "NM" to "Near Mint", "EX" to "Good (Lightly Played)", "GD" to "Good (Lightly Played)",
        "LP" to "Played", "PL" to "Heavily Played", "PO" to "Damaged",
    )

    private val LANGUAGE_NAME = mapOf("en" to "English", "es" to "Spanish")
}
