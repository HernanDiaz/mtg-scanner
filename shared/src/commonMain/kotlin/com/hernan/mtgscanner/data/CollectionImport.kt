package com.hernan.mtgscanner.data

import com.hernan.mtgscanner.scan.FrameRecognizer

/**
 * Importar cartas desde un fichero: nuestro CSV (la copia de seguridad), los CSV
 * de otras apps (Moxfield, ManaBox, Deckbox, TCGplayer, Delver...) y listas de
 * texto ("4 Lightning Bolt (M11) 149"). Como en la exportación, las funciones son
 * puras; leer el fichero y guardar las cartas lo hacen la interfaz y [CollectionStore].
 */
object CollectionImport {

    /** Una línea del fichero, tal cual viene (aún sin buscar la carta en el catálogo). */
    data class Row(
        /** Número de línea en el fichero (1 = la primera), para decir cuáles fallaron. */
        val line: Int,
        val text: String,
        val quantity: Int,
        val name: String? = null,
        val scryfallId: String? = null,
        /** Valores que pueden ser la edición: código ("m11") o nombre ("Magic 2011"). */
        val sets: List<String> = emptyList(),
        val number: String? = null,
        val lang: String? = null,
        val condition: String? = null,
        val foil: Boolean = false,
        val deck: String? = null,
        val needsReview: Boolean = false,
    )

    /** Una carta lista para guardar: [quantity] copias iguales. */
    data class Item(
        val card: CardPrinting,
        val quantity: Int,
        val foil: Boolean,
        val condition: String,
        val lang: String,
        val needsReview: Boolean,
        val deck: String?,
    )

    data class Result(val items: List<Item>, val failed: List<Row>) {
        val cards get() = items.sumOf { it.quantity }
        val pending get() = items.filter { it.needsReview }.sumOf { it.quantity }
        val decks get() = items.mapNotNull { it.deck }.distinctBy { it.lowercase() }
    }

    /** Lo que hace falta del catálogo. */
    interface Lookup {
        fun byId(id: String): CardPrinting?
        fun bySetAndNumber(setCode: String, numberNorm: String): CardPrinting?
        /** Impresiones de un nombre (inglés, en otro idioma o solo la primera cara). */
        fun printingsOf(name: String): List<CardPrinting>
        /** Código de edición a partir de un código o un nombre, o null si no existe. */
        fun setCode(value: String): String?
        /** Idioma del nombre si es una traducción ("Relámpago" -> "es"); null si es el inglés. */
        fun languageOfName(name: String): String? = null
    }

    // ------------------------------------------------------------------ leer

    /** Texto de un fichero: UTF-8 si lo es (casi todas las apps); si no, lo que guarda Excel en Windows. */
    fun decode(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        decodeWindows1252(bytes)
    }

    fun parse(text: String): List<Row> {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
        val header = splitCsv(firstLine, delimiter).map(::key)
        val isCsv = header.any { it in NAME || it in ID }
        return if (isCsv) parseCsv(clean, delimiter) else parseText(clean)
    }

    private fun parseCsv(text: String, delimiter: Char): List<Row> {
        val records = csvRecords(text, delimiter)
        val (_, headerFields) = records.firstOrNull() ?: return emptyList()
        val header = headerFields.map(::key)
        fun col(names: Set<String>) = header.indexOfFirst { it in names }
        val qty = col(QUANTITY)
        val name = col(NAME)
        val id = col(ID)
        // Varias columnas pueden ser la edición (código y nombre): se prueban por orden.
        val sets = SET_COLUMNS.mapNotNull { k -> header.indexOf(k).takeIf { it >= 0 } }.distinct()
        val number = col(NUMBER)
        val lang = col(LANGUAGE)
        val condition = col(CONDITION)
        val foil = col(FOIL)
        val deck = col(DECK)
        val review = col(REVIEW)

        return records.drop(1).mapNotNull { (line, f) ->
            fun at(i: Int) = f.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            if (f.all { it.isBlank() }) return@mapNotNull null
            Row(
                line = line,
                text = f.joinToString(delimiter.toString()),
                quantity = at(qty)?.let(::quantity) ?: 1,
                name = at(name),
                scryfallId = at(id)?.lowercase(),
                sets = sets.mapNotNull(::at),
                number = at(number),
                lang = at(lang)?.let(::language),
                condition = at(condition)?.let(::condition),
                foil = at(foil)?.let(::isFoil) ?: false,
                deck = at(deck),
                needsReview = at(review)?.let(::yes) ?: false,
            )
        }
    }

    /**
     * Listas de texto como las de los mazos: "4 Lightning Bolt", "4x Lightning Bolt (M11) 149 *F*",
     * "1 Llanowar Elves [DOM] ES". Se ignoran las cabeceras de sección ("Sideboard", "// Comentario").
     */
    private fun parseText(text: String): List<Row> =
        text.lines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#") || key(line.trimEnd(':')) in SECTIONS) {
                return@mapIndexedNotNull null
            }
            val m = TEXT_LINE.matchEntire(line)
            var rest = m?.groupValues?.get(2) ?: line
            val qty = m?.groupValues?.get(1)?.toInt() ?: 1
            var foil = false
            var lang: String? = null
            // Marcas al final, en cualquier orden: *F*, *E* (etched), idioma.
            while (true) {
                val tail = TAIL_TOKEN.find(rest) ?: break
                val tok = tail.groupValues[1]
                when {
                    tok.startsWith("*") -> foil = true
                    else -> lang = language(tok) ?: break
                }
                rest = rest.substring(0, tail.range.first).trimEnd()
            }
            val printing = PRINTING.find(rest)
            Row(
                line = i + 1, text = line, quantity = qty,
                name = (if (printing != null) rest.substring(0, printing.range.first) else rest).trim(),
                sets = listOfNotNull(printing?.groupValues?.get(1)),
                number = printing?.groupValues?.get(2)?.takeIf { it.isNotEmpty() },
                lang = lang, foil = foil,
            )
        }

    // ------------------------------------------------------------------ buscar las cartas

    /** Busca cada línea en el catálogo. Lo que no se encuentra va a [Result.failed]. */
    fun resolve(rows: List<Row>, lookup: Lookup, defaultCondition: String = "NM"): Result {
        val items = ArrayList<Item>(rows.size)
        val failed = ArrayList<Row>()
        for (row in rows) {
            val found = find(row, lookup)
            if (found == null) {
                failed += row
                continue
            }
            val (card, certain) = found
            items += Item(
                card = card,
                quantity = row.quantity,
                foil = if (row.foil) card.hasFoil || !card.hasNonfoil else !card.hasNonfoil,
                condition = row.condition ?: defaultCondition,
                // Sin idioma en el fichero: el del nombre ("Relámpago" -> "es", "Foudre" -> "fr") o el de la impresión
                // (las ediciones que solo existen en español).
                lang = row.lang ?: row.name?.let(lookup::languageOfName) ?: card.lang,
                needsReview = row.needsReview || !certain,
                deck = row.deck,
            )
        }
        return Result(items, failed)
    }

    /** La impresión de una línea y si es segura (si no, queda por revisar). */
    private fun find(row: Row, lookup: Lookup): Pair<CardPrinting, Boolean>? {
        row.scryfallId?.let(lookup::byId)?.let { return it to true }
        val sets = row.sets.mapNotNull(lookup::setCode).distinct()
        val number = row.number?.let(::normNumber)
        if (number != null) {
            for (set in sets) {
                val p = lookup.bySetAndNumber(set, number) ?: continue
                // Si el nombre no cuadra, el número está mal: mejor buscar por nombre.
                if (row.name == null || sameCard(row.name, p.name)) return p to true
            }
        }
        val printings = row.name?.let(lookup::printingsOf).orEmpty()
        if (printings.isEmpty()) return null
        var candidates = printings
        if (sets.isNotEmpty()) printings.filter { it.setCode in sets }.takeIf { it.isNotEmpty() }?.let { candidates = it }
        if (number != null) candidates.filter { it.numberNorm == number }.takeIf { it.isNotEmpty() }?.let { candidates = it }
        return if (candidates.size == 1) candidates[0] to true else FrameRecognizer.defaultPick(candidates) to false
    }

    /** "Delver of Secrets" vale para "Delver of Secrets // Insectile Aberration". */
    private fun sameCard(given: String, catalog: String): Boolean {
        val g = NameMatcher.normalize(given)
        return g == NameMatcher.normalize(catalog) || g == NameMatcher.normalize(catalog.substringBefore(" // "))
    }

    // ------------------------------------------------------------------ valores

    /** Cabecera normalizada: "Collector's Number" -> "collectorsnumber", "Código" -> "codigo". */
    private fun key(s: String) = NameMatcher.normalize(s)

    private fun quantity(s: String) = s.trim().trimEnd('x', 'X').trim().toIntOrNull()?.coerceIn(1, 999)

    private fun normNumber(s: String): String {
        val l = s.trim().lowercase()
        return l.trimStart('0').ifEmpty { l }
    }

    private fun yes(s: String) = key(s) in setOf("si", "s", "yes", "y", "true", "1", "x")

    private fun isFoil(s: String) = yes(s) || key(s) in setOf("foil", "etched", "foiled", "holo")

    fun language(s: String): String? = LANGUAGES[key(s)]

    fun condition(s: String): String? = CONDITION_NAMES[key(s)]

    // ------------------------------------------------------------------ CSV

    private fun splitCsv(line: String, delimiter: Char) = csvRecords(line, delimiter).firstOrNull()?.second.orEmpty()

    /** Registros CSV (RFC 4180: comillas, comillas dobladas y saltos de línea dentro de comillas) con su línea. */
    private fun csvRecords(text: String, delimiter: Char): List<Pair<Int, List<String>>> {
        val records = ArrayList<Pair<Int, List<String>>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var line = 1
        var recordLine = 1
        var i = 0
        fun endRecord() {
            fields.add(field.toString()); field.clear()
            if (fields.size > 1 || fields[0].isNotBlank()) records += recordLine to fields
            fields = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> { if (c == '\n') line++; field.append(c) }
                c == delimiter -> { fields.add(field.toString()); field.clear() }
                c == '\r' -> {}
                c == '\n' -> { endRecord(); line++; recordLine = line }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }

    // ------------------------------------------------------------------ nombres de columnas y valores

    private val QUANTITY = setOf("cantidad", "count", "quantity", "qty", "amount", "copies", "copias")
    private val NAME = setOf("nombre", "name", "cardname", "card")
    private val ID = setOf("scryfallid", "scryfalluuid", "scryfall")
    /** Por orden de preferencia: primero las que suelen ser el código. */
    private val SET_COLUMNS = listOf(
        "codigo", "setcode", "editioncode", "expansioncode", "set", "edition", "edicion", "expansion",
        "setname", "editionname", "expansionname",
    )
    private val NUMBER = setOf("numero", "collectornumber", "collectorsnumber", "cardnumber", "number", "cn", "no")
    private val LANGUAGE = setOf("idioma", "language", "lang")
    private val CONDITION = setOf("estado", "condition")
    private val FOIL = setOf("foil", "printing", "finish", "isfoil")
    private val DECK = setOf("mazo", "deck", "deckname")
    private val REVIEW = setOf("porrevisar", "revisar", "needsreview", "review")

    private val SECTIONS = setOf("deck", "mazo", "sideboard", "banquillo", "commander", "comandante", "companion", "maybeboard")

    private val TEXT_LINE = Regex("""^(\d{1,3})\s*[xX]?\s+(.+)$""")
    /** En mayúsculas, para no confundir el final del nombre ("Leave It") con un idioma. */
    private val TAIL_TOKEN = Regex("""\s(\*[A-Za-z]\*|[A-Z]{2,3})$""")
    /** "(M11) 149", "[DOM]", "(PLST) LRW-256". */
    private val PRINTING = Regex("""\s[(\[]([A-Za-z0-9]{2,6})[)\]]\s*(\S+)?$""")

    private val LANGUAGES = mapOf(
        "en" to "en", "english" to "en", "ingles" to "en",
        "es" to "es", "sp" to "es", "spanish" to "es", "espanol" to "es", "castellano" to "es",
        "fr" to "fr", "french" to "fr", "frances" to "fr",
        "de" to "de", "german" to "de", "aleman" to "de",
        "it" to "it", "italian" to "it", "italiano" to "it",
        "pt" to "pt", "portuguese" to "pt", "portugues" to "pt",
        "ja" to "ja", "jp" to "ja", "japanese" to "ja", "japones" to "ja",
        "ko" to "ko", "kr" to "ko", "korean" to "ko", "coreano" to "ko",
        "ru" to "ru", "russian" to "ru", "ruso" to "ru",
        "zhs" to "zhs", "chinesesimplified" to "zhs", "simplifiedchinese" to "zhs",
        "zht" to "zht", "chinesetraditional" to "zht", "traditionalchinese" to "zht",
    )

    /** Estados de Cardmarket (los nuestros), Moxfield, ManaBox y TCGplayer. */
    private val CONDITION_NAMES = mapOf(
        "m" to "M", "mint" to "M",
        "nm" to "NM", "nearmint" to "NM",
        "ex" to "EX", "excellent" to "EX", "goodlightlyplayed" to "EX", "lightlyplayed" to "EX",
        "gd" to "GD", "good" to "GD",
        "lp" to "LP", "lightplayed" to "LP", "moderatelyplayed" to "LP",
        "pl" to "PL", "played" to "PL", "heavilyplayed" to "PL",
        "po" to "PO", "poor" to "PO", "damaged" to "PO",
    )
}
