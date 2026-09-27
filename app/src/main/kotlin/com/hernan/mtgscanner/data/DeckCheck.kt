package com.hernan.mtgscanner.data

/**
 * "¿Qué me falta para este mazo?": compara una lista de mazo con la colección.
 * Cuenta por nombre de carta: para jugar da igual la edición.
 */
object DeckCheck {

    data class Line(
        val name: String,
        /** Impresión con que se muestra y se calcula el precio (la del fichero o la más común). */
        val printing: CardPrinting,
        val wanted: Int,
        val owned: Int,
    ) {
        val missing get() = maxOf(0, wanted - owned)
    }

    data class Result(val lines: List<Line>, val failed: List<CollectionImport.Row>) {
        val missing get() = lines.filter { it.missing > 0 }
        val complete get() = lines.filter { it.missing == 0 }
        val wantedCards get() = lines.sumOf { it.wanted }
        val ownedCards get() = lines.sumOf { minOf(it.owned, it.wanted) }
        val missingCards get() = lines.sumOf { it.missing }

        /** Coste de lo que falta, con [price] por impresión; null si no se conoce ningún precio. */
        fun missingCost(price: (CardPrinting) -> Double?): Double? {
            val known = missing.mapNotNull { l -> price(l.printing)?.let { it * l.missing } }
            return if (known.isEmpty()) null else known.sum()
        }

        /**
         * Formatos en los que el mazo se puede jugar: todas sus cartas legales (las
         * restringidas, con una sola copia).
         */
        fun legalFormats(): List<String> = if (lines.isEmpty()) emptyList() else Legality.FORMATS.filter { f ->
            lines.all { l ->
                when (Legality.status(l.printing.legality, f)) {
                    Legality.Status.LEGAL -> true
                    Legality.Status.RESTRICTED -> l.wanted <= 1
                    else -> false
                }
            }
        }

        /** "4 Lightning Bolt", una por línea: se pega en la lista de deseados de Cardmarket. */
        fun missingAsText() = missing.joinToString("\n") { "${it.missing} ${it.name}" }
    }

    /** [ownedByName]: copias de cada carta en la colección (en cualquier edición y mazo). */
    fun check(resolved: CollectionImport.Result, ownedByName: Map<String, Int>): Result {
        val lines = resolved.items.groupBy { it.card.name }.map { (name, items) ->
            Line(name, items.first().card, wanted = items.sumOf { it.quantity }, owned = ownedByName[name] ?: 0)
        }.sortedWith(compareByDescending<Line> { it.missing > 0 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return Result(lines, resolved.failed)
    }
}
