package com.hernan.mtgscanner.data

/**
 * Legalidad de una carta en los formatos principales, empaquetada en un entero
 * (2 bits por formato, en el orden de [FORMATS]), igual que en el catálogo
 * (tools/build_catalog.py).
 */
object Legality {
    val FORMATS = listOf("standard", "pioneer", "modern", "legacy", "vintage", "commander", "pauper")

    enum class Status { NOT_LEGAL, LEGAL, RESTRICTED, BANNED }

    fun status(bits: Int, format: String): Status {
        val i = FORMATS.indexOf(format)
        if (i < 0) return Status.NOT_LEGAL
        return Status.entries[(bits shr (2 * i)) and 3]
    }

    /** Se puede jugar (legal o restringida, de la que solo cabe una copia). */
    fun playable(bits: Int, format: String) = status(bits, format).let { it == Status.LEGAL || it == Status.RESTRICTED }

    /** Condición SQL: la columna [column] (bits) permite jugar la carta en [format]. */
    fun sqlPlayable(column: String, format: String): String {
        val i = FORMATS.indexOf(format).coerceAtLeast(0)
        return "((COALESCE($column, 0) >> ${2 * i}) & 3) IN (1, 2)"
    }

    fun displayName(format: String) = format.replaceFirstChar { it.uppercase() }
}
