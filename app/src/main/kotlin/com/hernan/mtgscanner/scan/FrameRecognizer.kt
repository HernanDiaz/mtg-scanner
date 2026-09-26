package com.hernan.mtgscanner.scan

import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.NameMatcher

/** Una línea de texto del OCR con su caja (coordenadas de la imagen ya girada). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val words: List<String> get() = text.split(WHITESPACE).filter { it.isNotEmpty() }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Pistas de la impresión leídas bajo el nombre. Las "fuertes" bastan por sí
 * solas; las "débiles" solo cuentan si forman una pareja edición+número que
 * existe en el catálogo (así el maná "2" o un "3/3" no engañan).
 */
data class CollectorInfo(
    val sets: List<String> = emptyList(),
    val weakSets: List<String> = emptyList(),
    val numbers: List<String> = emptyList(),
    val weakNumbers: List<String> = emptyList(),
    /** Denominadores leídos ("123/274" -> 274): el tamaño de la edición. */
    val setSizes: List<Int> = emptyList(),
    /** Año del copyright, que coincide con el de la impresión. */
    val year: Int? = null,
    /** Idioma impreso junto a la edición ("MOM • ES"), en minúsculas. */
    val language: String? = null,
    /** Posibles códigos de edición junto al idioma que no existen tal cual ("KTN"): lecturas con error. */
    val unreadSets: List<String> = emptyList(),
) {
    val setCode get() = sets.firstOrNull()
    val number get() = numbers.firstOrNull()
}

/** Lo reconocido en un fotograma. */
data class FrameResult(
    val name: String,
    val printing: CardPrinting,
    /** true si la impresión está identificada sin ambigüedad. */
    val exact: Boolean,
    val collector: CollectorInfo,
    /** Idioma de la carta física: "en" o "es". */
    val lang: String = "en",
    /** La edición se decidió comparando la ilustración. */
    val byArt: Boolean = false,
    /** Miniatura de la ilustración fotografiada, si se llegó a calcular (edición dudosa). */
    val photoArt: ArtSignature? = null,
)

/** Huella de la ilustración de la imagen actual en una región (coordenadas de la imagen derecha). */
fun interface ArtSampler {
    fun signature(left: Float, top: Float, right: Float, bottom: Float): ArtSignature?
}

/**
 * Reconoce una carta a partir de las líneas del OCR:
 *  1. El nombre es la línea superior (por encima de la línea de tipo) que
 *     coincide con un nombre del catálogo.
 *  2. La impresión se decide con edición + número (cartas desde 2014), edición
 *     sola, número + tamaño de edición (2003-2014) o por ser la única.
 *  3. Si sigue habiendo varias, se filtra por el año del copyright y se elige
 *     la más común (la más barata), marcada para revisar.
 */
class FrameRecognizer(
    private val names: NameMatcher,
    private val setCodes: Set<String>,
    private val printingsOf: (String) -> List<CardPrinting>,
    private val bySetAndNumber: (set: String, number: String) -> CardPrinting? = { _, _ -> null },
    private val artOf: (Int) -> ArtSignature? = { null },
) {

    /** [art]: si se da, las ediciones dudosas se deciden comparando la ilustración. */
    fun recognize(lines: List<OcrLine>, art: ArtSampler? = null): FrameResult? {
        artDebug = null
        lastPhotoArt = null
        val sorted = lines.sortedBy { it.top }
        return byName(sorted, art) ?: byCollectorOnly(sorted)
    }

    private fun byName(sorted: List<OcrLine>, art: ArtSampler?): FrameResult? {
        val zone = nameZone(sorted)
        if (zone == 0) return null

        var nameIndex = -1
        var match: NameMatcher.Match? = null
        for (i in 0 until zone) {
            match = names.matchWords(sorted[i].words) ?: continue
            nameIndex = i
            break
        }
        if (match == null) {
            // Solo la línea superior: en el texto de reglas daría falsos positivos
            // ("be regenerated" -> "Regenerate").
            match = names.matchRelaxed(sorted[0].text)
            nameIndex = 0
        }
        if (match == null) return null

        val printings = match.names.flatMap(printingsOf)
        if (printings.isEmpty()) return null
        val collector = parseCollector(sorted.drop(nameIndex + 1), setCodes)
        val lang = detectLanguage(sorted, match, collector)
        val (printing, exact) = resolve(printings, collector, lang == "es")
        if (!exact && art != null) {
            byArt(printings, collector, lang == "es", sorted, nameIndex, art)?.let { (p, e) ->
                return FrameResult(p.name, p, e, collector, lang, byArt = true, photoArt = lastPhotoArt)
            }
        }
        return FrameResult(printing.name, printing, exact, collector, lang, photoArt = lastPhotoArt)
    }

    /**
     * Elige entre las ilustraciones de la carta la más parecida a la de la foto.
     * Solo decide si hay una clara ganadora; luego, entre las impresiones con esa
     * ilustración (reimpresiones con el mismo dibujo), vuelve a aplicar las pistas de texto.
     */
    private var lastPhotoArt: ArtSignature? = null

    /** Diagnóstico de la última comparación de ilustraciones (solo para las evaluaciones). */
    var artDebug: String? = null
        private set

    private fun byArt(
        printings: List<CardPrinting>, info: CollectorInfo, spanish: Boolean,
        sorted: List<OcrLine>, nameIndex: Int, art: ArtSampler,
    ): Pair<CardPrinting, Boolean>? {
        val artIds = printings.mapNotNull { it.artId }.distinct()
        if (artIds.size < 2) return null.also { artDebug = "una_ilustracion" }
        val region = artRegion(sorted, nameIndex) ?: return null.also { artDebug = "sin_region" }
        val photo = art.signature(region.left, region.top, region.right, region.bottom) ?: return null
        lastPhotoArt = photo
        val ranked = artIds.mapNotNull { id -> artOf(id)?.let { id to photo.similarity(it) } }.sortedByDescending { it.second }
        artDebug = "region=${region.left.toInt()},${region.top.toInt()},${region.right.toInt()},${region.bottom.toInt()} " +
            ranked.joinToString(" ") { "${it.first}:${"%.2f".format(java.util.Locale.ROOT, it.second)}" }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: -1.0
        if (best.second < ART_MIN_SIMILARITY || best.second - second < ART_MIN_MARGIN) return null
        val sameArt = printings.filter { it.artId == best.first }
        if (sameArt.size == 1) return sameArt[0] to true
        return resolve(sameArt, info, spanish)
    }

    /**
     * Sin nombre legible (tierras de arte completo, Secret Lair con letras
     * estilizadas): basta con una edición y un número fiables que existan juntos.
     */
    private fun byCollectorOnly(sorted: List<OcrLine>): FrameResult? {
        val info = parseCollector(sorted, setCodes)
        for (set in info.sets) for (num in info.numbers) {
            bySetAndNumber(set, num)?.let {
                val spanish = info.language == "es" ||
                    (info.language == null && sorted.any { l -> isTypeLine(l, SPANISH_TYPE_WORDS, fuzzy = false) })
                return FrameResult(it.name, it, true, info, if (spanish) "es" else "en")
            }
        }
        return null
    }

    /**
     * Idioma de la carta, por orden de fiabilidad: el código impreso junto a la
     * edición, un nombre leído que es la traducción (no el nombre inglés) y la
     * línea de tipo en español.
     */
    private fun detectLanguage(sorted: List<OcrLine>, match: NameMatcher.Match, info: CollectorInfo): String {
        when (info.language) {
            "es" -> return "es"
            "en" -> return "en"
        }
        val englishKeys = match.names.flatMap { name ->
            listOf(name) + name.split(" // ")
        }.map(NameMatcher::normalize).toSet()
        if (match.key !in englishKeys) return "es"
        // Sin tolerancia a errores: "basic" está a una letra de "basica".
        return if (sorted.any { isTypeLine(it, SPANISH_TYPE_WORDS, fuzzy = false) }) "es" else "en"
    }

    /**
     * Cuántas líneas superiores pueden contener el nombre: las que hay antes
     * de la línea de tipo ("Creature — Goblin"), como mucho [NAME_SEARCH_LINES].
     * Evita tomar como nombre una palabra clave del texto ("Flying" -> "Fling").
     */
    private fun nameZone(sorted: List<OcrLine>): Int {
        val limit = minOf(NAME_SEARCH_LINES, sorted.size)
        for (i in 0 until limit) {
            if (isTypeLine(sorted[i], TYPE_WORDS) && names.match(sorted[i].text)?.distance != 0) return i
        }
        return limit
    }

    private fun isTypeLine(line: OcrLine, words: List<String>, fuzzy: Boolean = true): Boolean {
        val first = NameMatcher.normalize(line.words.firstOrNull() ?: return false)
        if (first.length < 4) return first == "land" && "land" in words
        return words.any { t ->
            first == t || (fuzzy && t.length >= 6 && NameMatcher.boundedLevenshtein(first, t, 1) <= 1)
        }
    }

    companion object {
        private const val NAME_SEARCH_LINES = 4

        /**
         * Umbrales de la comparación de ilustraciones (correlación). Medido con fotos
         * simuladas: con un margen de 0,15 sobre la segunda, acierta 110 de 111 veces.
         */
        private const val ART_MIN_SIMILARITY = 0.4
        private const val ART_MIN_MARGIN = 0.15

        /**
         * Dónde está la ilustración en la foto: entre el nombre y la línea de tipo,
         * y a lo ancho del texto de reglas (que ocupa casi todo el ancho del marco).
         * Se recorta un poco hacia dentro para no coger el marco. null si no se puede
         * estimar (sin línea de tipo, cartas de arte completo, proporciones raras).
         */
        fun artRegion(sorted: List<OcrLine>, nameIndex: Int): GuideRegion.Rect? {
            val name = sorted.getOrNull(nameIndex) ?: return null
            val typeIndex = (nameIndex + 1 until sorted.size).firstOrNull { i ->
                sorted[i].top > name.bottom && isTypeWord(sorted[i])
            } ?: return null
            val type = sorted[typeIndex]
            val lineH = (name.bottom - name.top).toFloat()
            val top = name.bottom + 0.4f * lineH
            val bottom = type.top - 0.4f * lineH
            if (bottom - top < 3 * lineH) return null

            val body = sorted.drop(typeIndex + 1).ifEmpty { listOf(type) }
            val widest = body.maxOf { it.right - it.left }
            val wide = body.filter { it.right - it.left >= widest * 0.5f }
            val left = minOf(wide.minOf { it.left }, name.left, type.left).toFloat()
            val right = wide.maxOf { it.right }.toFloat()
            val w = right - left
            val h = bottom - top
            if (w / h !in 0.9f..2.2f) return null
            val inset = 0.04f
            return GuideRegion.Rect(left + w * inset, top + h * inset, right - w * inset, bottom - h * inset)
        }

        private fun isTypeWord(line: OcrLine): Boolean {
            val first = NameMatcher.normalize(line.words.firstOrNull() ?: return false)
            return first == "land" || TYPE_WORDS.any { t ->
                first == t || (t.length >= 6 && NameMatcher.boundedLevenshtein(first, t, 1) <= 1)
            }
        }

        /** Primera palabra de la línea de tipo en español (normalizada, sin tildes). */
        private val SPANISH_TYPE_WORDS = listOf(
            "criatura", "instantaneo", "conjuro", "artefacto", "encantamiento", "tierra",
            "legendaria", "legendario", "batalla", "basica", "invocar", "encantar", "interrupcion",
        )

        private val TYPE_WORDS = listOf(
            "legendary", "creature", "instant", "sorcery", "artifact", "enchantment", "land",
            "planeswalker", "battle", "tribal", "kindred", "basic", "snow", "summon", "enchant",
            "interrupt", "token",
        ) + SPANISH_TYPE_WORDS

        /**
         * [spanish]: la carta está impresa en español. Entonces se prefieren las
         * ediciones que solo existen en español (colección Salvat: "58/60" en la
         * carta, "A58" en Scryfall) y no se da por segura una inglesa que solo
         * coincide por el número.
         */
        fun resolve(
            printings: List<CardPrinting>,
            info: CollectorInfo,
            spanish: Boolean = false,
        ): Pair<CardPrinting, Boolean> {
            if (printings.size == 1) return printings[0] to true
            val spanishOnly = if (spanish) printings.filter { it.lang == "es" } else emptyList()
            // Códigos de edición leídos con una letra mal ("KTN" -> "KTK"), solo entre las
            // ediciones de esta carta: fuera de ellas sería adivinar.
            val candidateSets = printings.map { it.setCode }.distinct()
            // Solo si la corrección es única: "TOC" está a una letra de "TDC" y de "EOC".
            val fuzzySets = info.unreadSets.mapNotNull { tok ->
                candidateSets.filter { it.length == tok.length && NameMatcher.boundedLevenshtein(it, tok, 1) <= 1 }
                    .singleOrNull()
            }
            val sets = (info.sets + fuzzySets).distinct()

            // 1. Edición + número: la pareja debe existir, así que se admiten lecturas débiles.
            val anyNumber = info.numbers + info.weakNumbers
            for (set in sets + info.weakSets) for (num in anyNumber) {
                printings.firstOrNull { it.setCode == set && it.numberNorm == num }?.let { return exactUnlessVariants(it, printings) }
            }

            // 2. Edición sola.
            for (set in sets) {
                val inSet = printings.filter { it.setCode == set }
                if (inSet.size == 1) return inSet[0] to true
                if (inSet.isNotEmpty()) return defaultPick(byYear(inSet, info.year)) to false
            }

            // 2b. Ediciones exclusivas en español, por número (con o sin letra delante).
            if (spanishOnly.isNotEmpty() && info.numbers.isNotEmpty()) {
                val m = spanishOnly.filter { p -> info.numbers.any { numberMatchesWithPrefix(p.numberNorm, it) } }
                if (m.size == 1) return m[0] to true
                if (m.isNotEmpty()) return defaultPick(m) to false
            }
            val certain = spanishOnly.isEmpty()

            // 3. Número (fuerte), confirmado con el tamaño de la edición y sin contradecir el año.
            if (info.numbers.isNotEmpty()) {
                var withNum = printings.filter { it.numberNorm in info.numbers }
                if (info.setSizes.isNotEmpty()) {
                    val sized = withNum.filter { p -> p.setSize != null && info.setSizes.any { sizeMatches(p.setSize, it) } }
                    if (sized.size == 1 && yearFits(sized[0], info.year)) return sized[0] to certain
                    if (sized.isNotEmpty() && sized.any { yearFits(it, info.year) }) withNum = sized
                }
                val fitting = withNum.filter { yearFits(it, info.year) }
                val only = fitting.singleOrNull()
                if (only?.setSize != null && info.setSizes.any { sizeMatches(only.setSize, it) }) return only to certain
                withNum = byYear(withNum, info.year)
                if (withNum.isNotEmpty()) return defaultPick(withNum) to false
            }

            // 4. Sin pistas de edición: año del copyright y la impresión más común.
            return defaultPick(byYear(printings, info.year)) to false
        }

        /**
         * Variantes con el mismo número impreso ("163" y "163z", "254" y "254a"):
         * el texto no las distingue, así que la elegida queda para revisar.
         */
        private fun exactUnlessVariants(p: CardPrinting, printings: List<CardPrinting>): Pair<CardPrinting, Boolean> {
            val base = p.numberNorm.trimEnd { !it.isDigit() }
            val variants = printings.any {
                it.id != p.id && it.setCode == p.setCode && it.numberNorm.trimEnd { c -> !c.isDigit() } == base
            }
            return p to !variants
        }

        /** El año del copyright (si se leyó) coincide con el de la impresión, ±1. */
        private fun yearFits(p: CardPrinting, year: Int?): Boolean =
            year == null || p.releaseYear?.let { it in (year - 1)..(year + 1) } == true

        /** "a58" coincide con "58": Scryfall antepone la letra del mazo en la colección Salvat. */
        private fun numberMatchesWithPrefix(number: String, read: String): Boolean =
            number == read || (number.length > read.length && number.endsWith(read) &&
                number.substring(0, number.length - read.length).all { it.isLetter() })

        /** El tamaño impreso a veces difiere un poco del de Scryfall (cartas extra, ★). */
        private fun sizeMatches(setSize: Int, read: Int) = read in (setSize - 10)..(setSize + 2)

        /** Filtra por año de impresión (±1); si no queda ninguna, no filtra. */
        private fun byYear(printings: List<CardPrinting>, year: Int?): List<CardPrinting> {
            if (year == null) return printings
            val near = printings.filter { p -> p.releaseYear?.let { it in (year - 1)..(year + 1) } == true }
            return near.ifEmpty { printings }
        }

        /**
         * Ante la duda, la impresión más común: evita reimpresiones especiales
         * (The List, Secret Lair, promos) y elige la más barata. Es la opción más
         * probable y la que no infla el valor de la colección.
         */
        private fun defaultPick(printings: List<CardPrinting>): CardPrinting {
            val regular = printings.filter {
                !it.promo && it.setCode != "plst" && it.setType !in SPECIAL_SET_TYPES
            }.ifEmpty { printings }
            return regular.minByOrNull { it.prices.eur ?: it.prices.usd ?: Double.MAX_VALUE } ?: printings[0]
        }

        private val SPECIAL_SET_TYPES = setOf("promo", "box", "masterpiece", "from_the_vault", "funny", "memorabilia")

        // ---------------------------------------------------------------- línea de coleccionista

        private val SLASH_NUMBER = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})")
        private val YEAR = Regex("(?<!\\d)(199[3-9]|20[0-4]\\d)(?!\\d)")
        private val TOKEN_SPLIT = Regex("[\\s•·*.,>\\-]+")
        private val DIGITS = Regex("^\\d{1,4}$")
        // Las cartas recientes imprimen "SP" para español; algunas antiguas, "ES".
        private val LANGUAGES = setOf("EN", "ES", "SP", "FR", "DE", "IT", "PT", "JP", "JA", "RU", "KO", "CS", "CT", "ZHS", "ZHT")
        private const val RARITY_LETTERS = "CURMSLTPB"

        /**
         * Busca edición, número, tamaño de edición y año en las líneas bajo el nombre.
         * Formatos:  "123/274 R" + "M20 • EN"   (2014-2022)
         *            "R 0123"    + "MOM • EN"   (2023 en adelante; el OCR suele pegar "R0123", "MOMEN")
         *            "123/350"   sin código     (2003-2014)
         */
        fun parseCollector(lines: List<OcrLine>, setCodes: Set<String>): CollectorInfo {
            val numbers = LinkedHashSet<String>()
            val weakNumbers = LinkedHashSet<String>()
            val setSizes = LinkedHashSet<Int>()
            val setScores = HashMap<String, Int>()
            var year: Int? = null
            var language: String? = null
            val unreadSets = LinkedHashSet<String>()

            for (line in lines) {
                val fixed = digitFix(line.text)
                for (m in SLASH_NUMBER.findAll(fixed)) {
                    val den = m.groupValues[2].toInt()
                    if (den >= 20) { // descarta fuerza/resistencia ("3/3")
                        numbers.add(stripZeros(m.groupValues[1]))
                        setSizes.add(den)
                    }
                }
                YEAR.findAll(fixed).forEach { y -> year = maxOf(year ?: 0, y.value.toInt()) }

                val tokens = line.text.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
                val hasLanguage = tokens.any { it in LANGUAGES || (it == it.uppercase() && gluedLanguageAt(it) != null) }
                // Solo cuenta junto a un código de edición: "EN"/"ES" sueltos pueden ser otra cosa.
                val langToken = tokens.firstOrNull { it == "EN" || it == "ES" || it == "SP" }
                    ?: tokens.firstNotNullOfOrNull { t -> gluedLanguageAt(t)?.let { t.substring(it, it + 2) } }
                val hasBullet = line.text.any { it == '•' || it == '·' || it == '*' }

                for ((i, token) in tokens.withIndex()) {
                    // "R 1946": la letra de rareza suelta delante también lo hace fiable.
                    val afterRarity = i > 0 && tokens[i - 1].length == 1 && tokens[i - 1][0] in RARITY_LETTERS
                    numberToken(token, afterRarity)?.let { (num, strong) -> (if (strong) numbers else weakNumbers).add(num) }
                    val code = setToken(token, setCodes)
                    if (code == null) {
                        if (hasLanguage) unreadSetToken(token)?.let { unreadSets.add(it) }
                        continue
                    }
                    val score = 1 + (if (hasLanguage) 2 else 0) + (if (hasBullet) 1 else 0) +
                        (if (tokens.size <= 4) 1 else 0)
                    setScores.merge(code, score, ::maxOf)
                    if (langToken != null) language = if (langToken == "EN") "en" else "es"
                }
            }
            val ranked = setScores.entries.sortedByDescending { it.value }
            return CollectorInfo(
                // Una palabra suelta en mayúsculas no basta: debe ir con el idioma, un punto o en una línea corta.
                sets = ranked.filter { it.value >= 2 }.map { it.key },
                weakSets = ranked.filter { it.value < 2 }.map { it.key },
                numbers = numbers.toList(),
                weakNumbers = (weakNumbers - numbers).toList(),
                setSizes = setSizes.toList(),
                year = year,
                language = language,
                unreadSets = unreadSets.toList(),
            )
        }

        /**
         * "0123", "R0123", "R 0123" (tras separar) -> fuerte; "1080", "47" -> débil.
         * Devuelve null si el token no parece un número de coleccionista.
         */
        private fun numberToken(token: String, afterRarity: Boolean): Pair<String, Boolean>? {
            if ('/' in token) return null
            var t = token
            var rarityPrefix = afterRarity
            if (t.length >= 2 && t[0] in RARITY_LETTERS && (t[1].isDigit() || t[1] == 'O' || t[1] == 'o')) {
                t = t.substring(1)
                rarityPrefix = true
            }
            if (t.count { it.isDigit() } == 0) return null
            t = t.map { c ->
                when (c) { 'O', 'o' -> '0'; 'I', 'l', '|' -> '1'; else -> c }
            }.joinToString("")
            if (!DIGITS.matches(t)) return null
            val strong = (t.length == 4 && t[0] == '0') || (rarityPrefix && t.length >= 3)
            return stripZeros(t) to strong
        }

        /** Un token con pinta de código de edición (3-5 mayúsculas/dígitos, con letras), en minúsculas. */
        private fun unreadSetToken(token: String): String? {
            if (token != token.uppercase() || token in LANGUAGES) return null
            val t = gluedLanguageAt(token)?.let { token.substring(0, it) } ?: token
            if (t.length !in 3..5 || t.none { it.isLetter() } || !t.all { it.isLetterOrDigit() }) return null
            return t.lowercase()
        }

        private fun setToken(token: String, setCodes: Set<String>): String? {
            if (token != token.uppercase() || token in LANGUAGES) return null
            val candidates = buildList {
                if (token.length in 3..5) add(token)
                // "MOMEN" = "MOM" + "EN", "INRENK" = "INR" + "EN" + inicial del artista
                gluedLanguageAt(token)?.let { add(token.substring(0, it)) }
            }
            for (c in candidates) {
                if (c.none { it.isLetter() || it == '0' }) continue
                setVariants(c).firstOrNull { it in setCodes }?.let { return it }
            }
            return null
        }

        /** Posición de un "EN"/"ES" pegado tras un código de 3-5 caracteres ("MOMEN", "MOMES"), o null. */
        private fun gluedLanguageAt(token: String): Int? =
            (3..5).firstOrNull { i ->
                token.length >= i + 2 && token.length <= i + 4 &&
                    (token.startsWith("EN", i) || token.startsWith("ES", i) || token.startsWith("SP", i))
            }

        private fun stripZeros(s: String) = s.trimStart('0').ifEmpty { "0" }

        /** Corrige letras que el OCR confunde con dígitos dentro de un número. */
        private fun digitFix(s: String): String {
            if (s.none { it.isDigit() }) return s
            return s.replace(Regex("(?<=\\d)[Oo]|[Oo](?=\\d)"), "0")
                .replace(Regex("(?<=\\d)[Il|]|[Il|](?=\\d)"), "1")
                .replace(Regex("(?<=\\d)S|S(?=\\d)"), "5")
        }

        private fun setVariants(token: String): List<String> {
            val t = token.lowercase()
            return listOf(t, t.replace('0', 'o'), t.replace('o', '0'), t.replace('1', 'i'), t.replace('i', '1'))
                .distinct()
        }
    }
}
