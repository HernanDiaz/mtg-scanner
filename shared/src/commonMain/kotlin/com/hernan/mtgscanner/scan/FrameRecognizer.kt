package com.hernan.mtgscanner.scan

import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.NameMatcher
import com.hernan.mtgscanner.data.fixed

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
    /** "Illus. © Artista": el © va con el ilustrador, como hasta Revised (1994). */
    val artistCopyright: Boolean = false,
    /** Se leyó la línea "© 1995 Wizards of the Coast": de 4ª edición (1995) en adelante. */
    val wizardsLine: Boolean = false,
    /** Año máximo por la redacción antigua del tipo ("Summon", "Encantar criatura"), o null. */
    val maxYear: Int? = null,
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
    /** Idioma de la carta física: "en", "es", "fr", "de", "it" o "pt". */
    val lang: String = "en",
    /** La edición se decidió comparando la ilustración. */
    val byArt: Boolean = false,
    /** La edición se decidió (o se acotó) comparando el símbolo de edición. */
    val bySymbol: Boolean = false,
    /** Miniatura de la ilustración fotografiada, si se llegó a calcular (edición dudosa). */
    val photoArt: ArtSignature? = null,
)

/** Los píxeles de la imagen actual, en coordenadas de la imagen derecha (las del OCR). */
fun interface ArtSampler {
    /** Color 0xRRGGBB; fuera de la imagen, el del borde más cercano. */
    fun rgb(x: Int, y: Int): Int

    /** Huella de la ilustración en una región. */
    fun signature(left: Float, top: Float, right: Float, bottom: Float): ArtSignature? =
        ArtSignature.compute(left, top, right, bottom, ::rgb)
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
    private val symbols: SetSymbols? = null,
    /** Idiomas de un nombre traducido (clave normalizada), p. ej. ["es", "pt"]; null si es inglés. */
    private val nameLanguages: (key: String) -> List<String>? = { null },
    /** Idiomas aparte del inglés en que se imprimió una edición; null si no se sabe. */
    private val setLanguages: (set: String) -> Set<String>? = { null },
    /** Búsqueda por ilustración en todo el catálogo: (nombre, ilustración, similitud). */
    private val searchArt: (ArtSignature, Int) -> List<Triple<String, Int, Double>> = { _, _ -> emptyList() },
) {

    /** [art]: si se da, las ediciones dudosas se deciden comparando la ilustración. */
    fun recognize(lines: List<OcrLine>, art: ArtSampler? = null): FrameResult? {
        artDebug = null
        symbolDebug = null
        borderDebug = null
        nameFromArtSearch = false
        lastSymbolWindow = null
        lastPhotoArt = null
        val sorted = lines.sortedBy { it.top }
        return byName(sorted, art) ?: byCollectorOnly(sorted)
    }

    private fun byName(sorted: List<OcrLine>, art: ArtSampler?): FrameResult? {
        val zone = nameZone(sorted)

        var nameIndex = -1
        var found: NameMatcher.Match? = null
        for (i in 0 until zone) {
            // El nombre partido en dos líneas ("Víbora de" / "pantano": no es "Pantano", Swamp).
            // No la misma línea leída dos veces ("Swamp" / "Swamp" no es "Swamp // Swamp"), ni
            // las dos caras de una carta doble.
            if (i + 1 < zone && NameMatcher.normalize(sorted[i].text) != NameMatcher.normalize(sorted[i + 1].text)) {
                names.match(sorted[i].text + " " + sorted[i + 1].text)
                    ?.takeIf { m -> m.distance == 0 && m.names.none { " // " in it } }?.let {
                    found = it
                    nameIndex = i + 1
                }
                if (found != null) break
            }
            found = names.matchWords(sorted[i].words) ?: continue
            nameIndex = i
            break
        }
        // Por encima del nombre puede verse el de otra carta ("Hlí Babá" asomando sobre
        // "Comerciante enano"): si lo encontrado no es exacto y más abajo, justo sobre la línea
        // de tipo, hay un nombre exacto, es ese.
        if (found != null && found!!.distance > 0) {
            for (i in nameIndex + 1 until zone) {
                if (sorted.getOrNull(i + 1)?.let { isTypeLine(it, TYPE_WORDS) } != true) continue
                // La línea entera, no un prefijo: "Mountain Plains" (los tipos de Sacred Foundry) no es Mountain.
                names.match(sorted[i].text)?.takeIf { it.distance == 0 }?.let {
                    found = it
                    nameIndex = i
                }
            }
        }
        if (found == null && zone > 0) {
            // Solo la línea superior: en el texto de reglas daría falsos positivos
            // ("be regenerated" -> "Regenerate").
            found = names.matchRelaxed(sorted[0].text) ?: basicLand(sorted, zone)
                ?: nameInRulesText(sorted, zone) ?: art?.let { nameByArt(sorted, it) }
            nameIndex = 0
        }
        // Nombre ilegible del todo (las duales de Beta: blanco sobre gris): la ilustración
        // sobre la línea de tipo, buscada en todo el catálogo. Siempre para revisar.
        var artOnly: Int? = null
        var belowName = -1 // primera línea bajo el nombre; con el nombre por la imagen, la de tipo
        if (found == null && art != null) {
            nameBySearchedArt(sorted, art)?.let { (m, artId, typeIndex) ->
                found = m
                artOnly = artId
                nameFromArtSearch = true
                nameIndex = maxOf(typeIndex - 1, 0)
                belowName = typeIndex
            }
        }
        // Un nombre que solo explica el principio de la línea ("Atadura" -> Bandage en portugués,
        // de "Atadura del caos", la Chaoslace de 4ª, que Scryfall llama "Atadura vital") se
        // comprueba con la ilustración: si otra carta del catálogo se parece mucho más, es esa.
        val partial = found
        if (partial != null && art != null && artOnly == null && unexplainedLetters(sorted[nameIndex], partial) >= PARTIAL_NAME_LETTERS) {
            val own = artSimilarity(partial, sorted, nameIndex, art) ?: -1.0
            nameBySearchedArt(sorted, art)?.let { (m, artId, typeIndex) ->
                if (m.names.none { it in partial.names } && own < searchedArtSimilarity - PARTIAL_NAME_ART_GAP) {
                    found = m
                    artOnly = artId
                    nameFromArtSearch = true
                    nameIndex = maxOf(typeIndex - 1, 0)
                    belowName = typeIndex
                }
            }
        }
        val match = found ?: return null

        val all = wholeCardNames(match).flatMap(printingsOf)
            .let { ps -> artOnly?.let { id -> ps.filter { it.artId == id }.ifEmpty { ps } } ?: ps }
        if (all.isEmpty()) return null
        val collector = parseCollector(sorted.drop(nameIndex + 1), setCodes)
            .let { info -> oldWordingYear(sorted, nameIndex)?.let { info.copy(maxYear = it) } ?: info }
        val lang = detectLanguage(sorted.drop(if (belowName >= 0) belowName else nameIndex + 1), match, collector)
        val printings = inLanguage(all, lang)
        val foreign = lang != "en"
        var (printing, exact) = resolve(printings, collector, foreign)
        // El borde solo si la edición sigue en duda: medirlo convierte la imagen a color.
        val border = if (!exact && art != null) detectBorder(sorted, nameIndex, art) else null
        if (border != null) {
            val r = resolve(printings, collector, foreign, border)
            printing = r.first
            exact = r.second
        }
        var decidedByArt = false
        var decidedBySymbol = false
        if (!exact && art != null) {
            // El borde visto descarta las impresiones del otro color antes que nada: la
            // ilustración y el símbolo no separan 4ª de la 4ª de borde negro, ni Revised de
            // Foreign Black Border (mismo dibujo, sin símbolo).
            // Igual el año máximo por la redacción antigua ("Invocar", "Hechizo instantáneo").
            val bordered = byBorder(
                collector.maxYear?.let { max -> printings.filter { p -> p.releaseYear?.let { it <= max } == true } }
                    ?.ifEmpty { null } ?: printings,
                border.takeIf { borderIsClear },
            )
            // 1. La ilustración separa las impresiones con dibujos distintos.
            var candidates = bordered
            byArt(bordered, sorted, nameIndex, art)?.let { artId ->
                candidates = bordered.filter { it.artId == artId }
                decidedByArt = true
                val r = if (candidates.size == 1) candidates[0] to true else resolve(candidates, collector, foreign, border)
                printing = r.first
                exact = r.second
            }
            // 2. El símbolo de edición, las reimpresiones con el mismo dibujo. Solo si es
            //    seguro: acotar con un símbolo dudoso empeora la sugerencia más que la mejora.
            if (!exact) {
                bySymbol(candidates, sorted, nameIndex, art)?.let { winners ->
                    decidedBySymbol = true
                    val r = if (winners.size == 1) winners[0] to true else resolve(winners, collector, foreign, border)
                    printing = r.first
                    exact = r.second
                }
            }
        }
        if (artOnly != null) exact = false
        return FrameResult(printing.name, printing, exact, collector, lang, byArt = decidedByArt || artOnly != null,
            bySymbol = decidedBySymbol, photoArt = lastPhotoArt)
    }

    /**
     * Las impresiones de ediciones que existen en el idioma de la carta: una francesa
     * no puede ser de Antiquities (solo en inglés) ni de Salvat 2011, pero sí de Salvat
     * 2005. Si no queda ninguna (datos incompletos), todas.
     */
    private fun inLanguage(printings: List<CardPrinting>, lang: String): List<CardPrinting> {
        // Una carta en inglés no es de Foreign Black Border ni de 4ª en español (4bb).
        if (lang == "en") return printings.filter { it.lang == "en" }.ifEmpty { printings }
        return printings.filter { p -> p.lang == lang || setLanguages(p.setCode)?.contains(lang) != false }
            .ifEmpty { printings }
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

    /** La ilustración ganadora (su id), o null si ninguna destaca claramente. */
    private fun byArt(printings: List<CardPrinting>, sorted: List<OcrLine>, nameIndex: Int, art: ArtSampler): Int? {
        val artIds = printings.mapNotNull { it.artId }.distinct()
        if (artIds.size < 2) return null.also { artDebug = "una_ilustracion" }
        val region = artRegion(sorted, nameIndex) ?: return null.also { artDebug = "sin_region" }
        val photo = art.signature(region.left, region.top, region.right, region.bottom) ?: return null
        lastPhotoArt = photo
        val ranked = artIds.mapNotNull { id -> artOf(id)?.let { id to photo.similarity(it) } }.sortedByDescending { it.second }
        artDebug = "region=${region.left.toInt()},${region.top.toInt()},${region.right.toInt()},${region.bottom.toInt()} " +
            ranked.joinToString(" ") { "${it.first}:${it.second.fixed(2)}" }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: -1.0
        if (best.second < ART_MIN_SIMILARITY || best.second - second < ART_MIN_MARGIN) return null
        return best.first
    }

    /** Perfil del borde de la última carta (solo para las evaluaciones): ver [borderProfile]. */
    var borderDebug: String? = null
        private set

    /** El nombre salió de buscar la ilustración en todo el catálogo (no se leyó). */
    private var nameFromArtSearch = false

    /** Similitud de la ganadora de la última [nameBySearchedArt] que encontró algo. */
    private var searchedArtSimilarity = 0.0

    /** Letras de la línea del nombre que el nombre encontrado no explica ("del caos"). */
    private fun unexplainedLetters(line: OcrLine, match: NameMatcher.Match): Int =
        NameMatcher.normalize(line.text).length - match.key.length

    /** La mayor similitud entre la ilustración de la foto y las de la carta [match]. */
    private fun artSimilarity(match: NameMatcher.Match, sorted: List<OcrLine>, nameIndex: Int, art: ArtSampler): Double? {
        val region = artRegion(sorted, nameIndex) ?: return null
        val photo = art.signature(region.left, region.top, region.right, region.bottom) ?: return null
        return wholeCardNames(match).flatMap(printingsOf).mapNotNull { it.artId }.distinct()
            .mapNotNull { id -> artOf(id)?.let(photo::similarity) }.maxOrNull()
    }

    /**
     * Borde de la carta, mirando el lado izquierdo en tres franjas cortas a distintas alturas
     * (con la carta algo girada, el borde se desplaza a lo largo de una franja alta y se
     * emborrona). En cada una, de fuera adentro, el borde es la primera meseta (franjas casi
     * iguales y sin color): negra si es oscura y más que lo de fuera; blanca si es más clara
     * que lo de fuera y que el marco. Así no importa la luz ni el fondo (un mantel claro no es
     * plano; un borde blanco con poca luz sigue siendo más claro que lo que lo rodea).
     * 'w' o 'k' si alguna franja lo ve y ninguna ve el contrario; null si no se puede medir.
     */
    private fun detectBorder(sorted: List<OcrLine>, nameIndex: Int, art: ArtSampler): Char? {
        borderIsClear = false
        val slices = leftBorderSlices(sorted, nameIndex, art) ?: return null
        borderDebug = slices.joinToString(" | ") { s -> s.joinToString(",") { "${it.first}/${it.second}" } }
        val votes = slices.mapNotNull(::borderInSlice)
        if ('k' in votes && 'w' in votes) return null
        if (votes.isNotEmpty()) return votes.first().also { borderIsClear = true }
        // Sin meseta clara (mesa oscura y poca luz: el borde negro se confunde con la mesa):
        // negro si en dos franjas no hay nada claro en toda la zona del borde. Es una pista
        // débil: solo orienta la elección final, no descarta impresiones (ver [borderIsClear]).
        val dark = slices.count { bins -> bins.take(WEAK_BLACK_ZONE_BINS).all { it.first < WEAK_BLACK_MAX_LUM } } >= 2
        return if (dark) 'k' else null
    }

    /** El último [detectBorder] vio el borde con claridad (una meseta), no solo una zona oscura. */
    private var borderIsClear = false

    /** El borde visto en una franja ([detectBorder]), o null si no hay meseta que lo sea. */
    private fun borderInSlice(bins: List<Pair<Int, Int>>): Char? {
        val lum = bins.map { it.first }
        for (start in BORDER_FIRST_BIN until BORDER_LAST_BIN + 1) {
            if (bins[start].second > BORDER_MAX_SAT) continue
            var end = start
            var lo = lum[start]
            var hi = lum[start]
            while (end + 1 < bins.size && bins[end + 1].second <= BORDER_MAX_SAT &&
                maxOf(hi, lum[end + 1]) - minOf(lo, lum[end + 1]) <= BORDER_FLAT
            ) {
                end++
                lo = minOf(lo, lum[end]); hi = maxOf(hi, lum[end])
            }
            if (end - start + 1 < BORDER_RUN) continue
            val level = lum.subList(start, end + 1).average()
            val outside = lum.subList(maxOf(0, start - 2), start).ifEmpty { listOf(level.toInt()) }
            val inside = lum.subList(end + 1, minOf(lum.size, end + 3)).ifEmpty { listOf(level.toInt()) }
            if (level <= BLACK_BORDER_MAX_LUM && outside.max() >= level + BLACK_BORDER_CONTRAST) return 'k'
            if (level >= WHITE_BORDER_MIN_LUM && outside.min() <= level - WHITE_BORDER_CONTRAST &&
                inside.min() <= level - WHITE_BORDER_CONTRAST
            ) return 'w'
        }
        return null
    }

    /**
     * Luminancia y saturación medias en franjas verticales que cruzan el borde izquierdo de la
     * carta (de 10 % fuera a 10 % dentro del ancho estimado, cada 1 %), de fuera adentro, en
     * tres alturas bajo la línea de tipo. (El borde derecho no sirve: su posición estimada
     * falla mucho más.)
     */
    private fun leftBorderSlices(sorted: List<OcrLine>, nameIndex: Int, art: ArtSampler): List<List<Pair<Int, Int>>>? {
        val name = sorted.getOrNull(nameIndex) ?: return null
        // Sin línea de tipo tras el nombre (carta reconocida por la ilustración, donde la línea
        // "nombre" ya es la de tipo o el texto): se mide desde esa misma línea.
        val typeIndex = typeLineIndex(sorted, nameIndex) ?: nameIndex.takeIf { nameFromArtSearch } ?: return null
        val type = sorted[typeIndex]
        val body = sorted.drop(typeIndex + 1)
        if (body.isEmpty()) return null
        val widest = body.maxOf { it.right - it.left }
        val wide = body.filter { it.right - it.left >= widest * 0.6f }
        val left = minOf(wide.minOf { it.left }, name.left, type.left).toFloat()
        val right = maxOf(wide.maxOf { it.right }, type.right).toFloat()
        val cardW = (right - left) / TEXT_WIDTH_FRACTION
        if (cardW < 100) return null
        val cardLeft = left - (1 - TEXT_WIDTH_FRACTION) / 2 * cardW
        fun bin(x0: Float, x1: Float, y0: Int, y1: Int): Pair<Int, Int>? {
            var lum = 0.0; var sat = 0.0; var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = art.rgb(x.toInt(), y)
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                    lum += 0.299 * r + 0.587 * g + 0.114 * b; sat += (mx - mn).toDouble(); n++
                    x += 2f
                }
                y += 2
            }
            return if (n == 0) null else (lum / n).toInt() to (sat / n).toInt()
        }
        return listOf(0.05f, 0.17f, 0.29f).map { from ->
            val y0 = (type.bottom + from * cardW).toInt()
            val y1 = (type.bottom + (from + 0.06f) * cardW).toInt()
            (0 until 20).map { i -> bin(cardLeft + (i - 10) * 0.01f * cardW, cardLeft + (i - 9) * 0.01f * cardW, y0, y1) ?: return null }
        }
    }

    /** Diagnóstico de la última comparación de símbolos (solo para las evaluaciones). */
    var symbolDebug: String? = null
        private set

    /** Zona de la foto que se comparó con los símbolos (solo para las evaluaciones). */
    var lastSymbolWindow: SymbolWindow? = null
        private set

    /**
     * Compara el símbolo de edición de la foto con el de cada edición candidata y
     * devuelve las impresiones de la ganadora, solo si es segura. No lo es si otra
     * candidata tiene un símbolo casi igual (o el mismo icono), imprime el de otra
     * edición o no tiene referencia: entonces no se compara nada (y no se gasta tiempo).
     */
    private fun bySymbol(
        candidates: List<CardPrinting>, sorted: List<OcrLine>, nameIndex: Int, art: ArtSampler,
    ): List<CardPrinting>? {
        val symbols = symbols ?: return null
        if (candidates.map { it.setCode }.distinct().size < 2) return null
        val groups = candidates.groupBy { Triple(it.setCode, it.rarity, it.style) }
        if (groups.size > SYMBOL_MAX_CANDIDATES) return null.also { symbolDebug = "demasiadas" }
        val kinds = groups.keys.associateWith { (set, rarity, style) -> symbols.kind(set, rarity, style) }
        // The List lleva el símbolo de la edición original: casi nunca es la que se tiene.
        val unknown = kinds.filter { (key, kind) ->
            (kind == SetSymbols.Kind.OTHER || kind == SetSymbols.Kind.UNKNOWN) && key.first != "plst"
        }
        if (unknown.isNotEmpty()) {
            return null.also { symbolDebug = "indecidible " + unknown.keys.joinToString(" ") { "${it.first}/${it.second?.take(1)}${it.third}" } }
        }
        val refs = groups.keys.mapNotNull { key -> symbols.ref(key.first, key.second, key.third)?.let { key to it } }
        if (refs.size < 2) return null
        val window = symbolWindow(sorted, nameIndex, art) ?: return null.also { symbolDebug = "sin_ventana" }
        lastSymbolWindow = window
        val (cx, cy) = SetSymbols.locate(window, refs.map { it.second }.distinct().take(SYMBOL_LOCATE_REFS))
            ?: return null
        val scored = refs.map { (key, ref) -> Triple(key, ref, SetSymbols.scoreNear(window, ref, cx, cy)) }
            .sortedByDescending { it.third }
        symbolDebug = scored.joinToString(" ") {
            "${it.first.first}/${it.first.second?.take(1)}${it.first.third}:${it.third.fixed(2)}"
        }
        val best = scored.first()
        if (best.third < SYMBOL_MIN) return null
        // Sin símbolo impreso: solo se descartan si el de la foto coincide muy bien con otro.
        if (kinds.values.any { it == SetSymbols.Kind.NONE } && best.third < SYMBOL_NONE_MIN) return null
        val second = scored.getOrNull(1)?.third ?: -1.0
        if (best.third - second < SYMBOL_MARGIN) return null
        // Casi iguales a la ganadora: la foto no las distingue.
        val twin = scored.drop(1).any {
            symbols.sameIcon(best.first.first, it.first.first) || symbols.similarity(best.second, it.second) >= SYMBOL_TWIN
        }
        if (twin) return null.also { symbolDebug += " gemelas" }
        return groups.getValue(best.first)
    }

    /**
     * La zona de la foto a la derecha de la línea de tipo, donde va el símbolo,
     * remuestreada con la carta a [SetSymbols.CARD_W] px. El ancho de la carta se
     * estima por el del texto de reglas (ocupa ~84% de la carta).
     */
    private fun symbolWindow(sorted: List<OcrLine>, nameIndex: Int, art: ArtSampler): SymbolWindow? {
        val name = sorted.getOrNull(nameIndex) ?: return null
        val typeIndex = typeLineIndex(sorted, nameIndex) ?: return null
        val type = sorted[typeIndex]
        val body = sorted.drop(typeIndex + 1)
        if (body.isEmpty()) return null
        val widest = body.maxOf { it.right - it.left }
        val wide = body.filter { it.right - it.left >= widest * 0.6f }
        val left = minOf(wide.minOf { it.left }, name.left, type.left).toFloat()
        val right = maxOf(wide.maxOf { it.right }, type.right).toFloat()
        val cardW = (right - left) / TEXT_WIDTH_FRACTION
        if (cardW < 100) return null
        val cardLeft = left - (1 - TEXT_WIDTH_FRACTION) / 2 * cardW
        val centerY = (type.top + type.bottom) / 2f
        val k = SetSymbols.CARD_W / cardW // px de la ventana por px de la foto
        // Holgada: con la carta un poco girada, el símbolo queda bastante más arriba o abajo
        // que el centro de la línea de tipo (medido en las fotos de prueba).
        val x0 = cardLeft + 0.60f * cardW
        val y0 = centerY - 0.13f * cardW
        val w = (0.44f * SetSymbols.CARD_W).toInt()
        val h = (0.26f * SetSymbols.CARD_W).toInt()
        val rgb = FloatArray(w * h * 3)
        // Cada píxel de la ventana, media de 2×2 muestras de la foto (reduce el ruido).
        for (gy in 0 until h) for (gx in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (sy in 0..1) for (sx in 0..1) {
                val c = art.rgb((x0 + (gx + 0.25f + 0.5f * sx) / k).toInt(), (y0 + (gy + 0.25f + 0.5f * sy) / k).toInt())
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            val i = (gy * w + gx) * 3
            rgb[i] = r / 1020f; rgb[i + 1] = g / 1020f; rgb[i + 2] = b / 1020f
        }
        return SymbolWindow(w, h, rgb)
    }

    private fun typeLineIndex(sorted: List<OcrLine>, nameIndex: Int) = typeIndexAfter(sorted, nameIndex)

    /**
     * Sin nombre legible (tierras de arte completo, Secret Lair con letras
     * estilizadas): basta con una edición y un número fiables que existan juntos.
     */
    private fun byCollectorOnly(sorted: List<OcrLine>): FrameResult? {
        val info = parseCollector(sorted, setCodes)
        for (set in info.sets) for (num in info.numbers) {
            bySetAndNumber(set, num)?.let {
                return FrameResult(it.name, it, true, info, info.language ?: typeLineLanguage(sorted) ?: "en")
            }
        }
        return null
    }

    /**
     * Idioma de la carta, por orden de fiabilidad: el código impreso junto a la
     * edición ("MOM • FR"), un nombre leído que es una traducción (y en qué idioma:
     * si se escribe igual en varios, decide la línea de tipo) y la línea de tipo.
     */
    private fun detectLanguage(belowName: List<OcrLine>, match: NameMatcher.Match, info: CollectorInfo): String {
        info.language?.let { return it }
        val englishKeys = match.names.flatMap { name ->
            listOf(name) + name.split(" // ")
        }.map(NameMatcher::normalize).toSet()
        val typeLangs = typeLineLanguages(belowName)
        val typeLang = typeLangs?.first()
        if (match.key !in englishKeys) {
            // "Devastarion" se parece tanto a "Devastation" como a "Devastación": "Sorcery" decide.
            if (typeLang == "en") return "en"
            val langs = nameLanguages(match.key) ?: return typeLang ?: "es"
            // Idioma común al nombre y a la línea de tipo ("Choque" + "Criatura": español).
            typeLangs?.firstOrNull { it in langs }?.let { return it }
            // Una línea de tipo de un solo idioma manda: "Apatía" también se escribe así en
            // portugués, pero "Encantar criatura" es español; y "Montafña" (mal leído) se parece
            // a "Montagna", pero "Tierra básica" es español.
            if (typeLangs?.size == 1) return typeLangs.first()
            // Sin línea de tipo legible ("Iyocar alquimista"), el texto de reglas: "Alquimista
            // samita" se escribe igual en portugués, pero "Previene hasta 4 puntos de daño" no.
            bodyLanguage(belowName)?.takeIf { it in langs }?.let { return it }
            return langs.first()
        }
        // Un nombre igual que en inglés ("Ali Baba", "Chandler"): el tipo, o si no se lee, el texto.
        return typeLang ?: bodyLanguage(belowName) ?: "en"
    }

    /**
     * Idioma del texto de reglas por sus palabras más comunes y propias de cada idioma
     * ("hasta", "jugador" / "até", "jogador" / "fino", "giocatore"...), solo si uno destaca
     * claramente: al menos [BODY_LANGUAGE_MIN] palabras y el doble que el siguiente.
     */
    private fun bodyLanguage(lines: List<OcrLine>): String? {
        val counts = HashMap<String, Int>()
        for (line in lines) for (word in line.words) {
            BODY_WORD_LANGS[NameMatcher.normalize(word)]?.let { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val ranked = counts.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0
        return best.key.takeIf { best.value >= BODY_LANGUAGE_MIN && best.value >= 2 * second }
    }

    /**
     * Idioma de la línea de tipo, por su primera palabra ("Kreatur" alemán, "Sorcery"
     * inglés). Una palabra de varios idiomas decide solo si ninguno es el inglés, por el
     * más común ("Criatura": español antes que portugués); "Creature" (inglés o francés)
     * no decide. Solo las palabras largas admiten errores de lectura ("Beschyürung"):
     * en las cortas uno cambia de idioma ("basic" / "basica").
     */
    private fun typeLineLanguage(lines: List<OcrLine>): String? = typeLineLanguages(lines)?.first()

    /** Los idiomas posibles de la línea de tipo (el más común primero), o null si no decide. */
    private fun typeLineLanguages(lines: List<OcrLine>): List<String>? {
        for (line in lines) {
            val first = NameMatcher.normalize(line.words.firstOrNull() ?: continue)
            TYPE_WORD_LANGS[first]?.let { langs -> if (langs.size == 1 || "en" !in langs) return langs }
            if (first.length >= FUZZY_TYPE_WORD_MIN) closestTypeLanguage(first)?.let { return listOf(it) }
        }
        return null
    }

    /** El idioma de la palabra de tipo más cercana (hasta 2 letras), si es única y no es inglesa. */
    private fun closestTypeLanguage(word: String): String? {
        var best = 3
        val langs = HashSet<String>()
        for ((candidate, lang) in TYPE_WORD_LANGUAGES) {
            val d = NameMatcher.boundedLevenshtein(word, candidate, best)
            if (d < best) { best = d; langs.clear() }
            if (d == best) langs.add(lang)
        }
        return langs.singleOrNull()?.takeIf { best <= 2 && it != "en" }
    }

    /**
     * Cuántas líneas superiores pueden contener el nombre: las que hay antes
     * de la línea de tipo ("Creature — Goblin"), como mucho [NAME_SEARCH_LINES].
     * Evita tomar como nombre una palabra clave del texto ("Flying" -> "Fling").
     */
    /**
     * Año máximo por cómo está redactada la línea de tipo: "Summon Zombie"/"Invocar zombi"
     * (e "Interrupt") desaparecieron en 1999; "Enchant Creature"/"Encantar criatura" como tipo,
     * en 2004. Así "Inspirar temor" con "Encantar criatura" no se sugiere de 10ª edición.
     * (En las cartas modernas "Encantar criatura" es la primera línea del texto, no el tipo.)
     */
    private fun oldWordingYear(sorted: List<OcrLine>, nameIndex: Int): Int? {
        // "Surnrnon Djirnn" no se reconoce como línea de tipo: se busca en las de debajo del nombre.
        val type = typeLineIndex(sorted, nameIndex)?.let(sorted::get)
            ?: sorted.drop(nameIndex + 1).take(3).firstOrNull { line ->
                line.words.firstOrNull()?.let { looksLikeSummon(NameMatcher.normalize(it)) } == true
            } ?: return null
        val words = type.words.map(NameMatcher::normalize)
        val first = words.firstOrNull() ?: return null
        return when {
            first in SUMMON_WORDS || looksLikeSummon(first) -> 1999
            first in ENCHANT_WORDS && words.size >= 2 -> 2004
            // "Hechizo instantáneo": las de marco moderno (8ª edición, 2003) dicen "Instantáneo".
            // Así la "Niebla" de 4ª no se toma por la de 2010.
            first == "hechizo" && words.getOrNull(1)?.startsWith("instant") == true -> 2003
            else -> null
        }
    }

    /** "Summon"/"Interrupt" mal leídos, típico en el tipo pequeño: "Surnrnon", "IiterrupE". */
    private fun looksLikeSummon(word: String): Boolean {
        val w = word.replace("rn", "m")
        return (w.length in 5..9 && w.first() == 's' && NameMatcher.boundedLevenshtein(w, "summon", 3) <= 3) ||
            (w.length in 7..11 && NameMatcher.boundedLevenshtein(w, "interrupt", 2) <= 2)
    }

    /**
     * Nombre muy mal leído ("Mox Derl", "Blackk Lutob", típico en las cartas antiguas): entre
     * los nombres más parecidos, el de la ilustración que coincide con la de la foto, solo si
     * destaca con claridad. Exige que lo leído parezca una carta (nombre arriba y texto debajo),
     * para no inventarse cartas al apuntar a otra cosa.
     */
    private fun nameByArt(sorted: List<OcrLine>, art: ArtSampler): NameMatcher.Match? {
        if (sorted.size < 3) return null
        val first = sorted[0]
        val words = first.words
        // La línea entera y sin la última palabra (el coste de maná suele leerse como letras).
        val candidates = (listOf(first.text) + if (words.size > 1) listOf(words.dropLast(1).joinToString(" ")) else emptyList())
            .flatMap { names.closest(it, NAME_BY_ART_CANDIDATES) }
            .groupBy { it.key }.map { (_, ms) -> ms.minBy { it.distance } }
        if (candidates.isEmpty()) return null
        val region = artRegion(sorted, 0) ?: looseArtRegion(sorted) ?: return null
        val photo = art.signature(region.left, region.top, region.right, region.bottom) ?: return null
        // Por carta, no por nombre leído: "Adarkar Unicorn" y "Unicornio de Adarkar" son la misma
        // y no compiten entre sí (si no, la segunda empataría con la primera y no decidiría).
        val scored = candidates.map { m ->
            val ids = wholeCardNames(m).flatMap(printingsOf).mapNotNull { it.artId }.distinct()
            m to (ids.mapNotNull { id -> artOf(id)?.let(photo::similarity) }.maxOrNull() ?: -1.0)
        }.groupBy { wholeCardNames(it.first).sorted() }.map { (_, ms) -> ms.maxBy { it.second } }
            .sortedByDescending { it.second }
        artDebug = "nombre_por_ilustracion " + scored.take(4).joinToString(" ") {
            "${it.first.names.first()}:${it.second.fixed(2)}"
        }
        val (best, sim) = scored.first()
        val second = scored.getOrNull(1)?.second ?: -1.0
        if (sim < NAME_BY_ART_MIN || sim - second < NAME_BY_ART_MARGIN) return null
        return best
    }

    /**
     * Título muy mal leído ("tareblast", "Merscinc", "HbadesaL"): las cartas antiguas se nombran
     * a sí mismas en el texto ("Fireblast deals 4 damage", "las abadesas son..."). Entre los
     * nombres más parecidos al título, el único que aparece escrito tal cual en el texto.
     */
    private fun nameInRulesText(sorted: List<OcrLine>, zone: Int): NameMatcher.Match? {
        val body = NameMatcher.normalize(sorted.drop(zone).joinToString(" ") { it.text })
        if (body.length < RULES_NAME_MIN) return null
        val hits = sorted.take(minOf(zone, 2)).flatMap { line ->
            val words = line.words
            // La línea entera y sin la última palabra (el coste de maná leído como letras).
            (listOf(line.text) + if (words.size > 1) listOf(words.dropLast(1).joinToString(" ")) else emptyList())
                .flatMap { names.closest(it, RULES_NAME_CANDIDATES) }
        }.filter { it.key.length >= RULES_NAME_MIN && it.key in body }
            .distinctBy { wholeCardNames(it).sorted() }
        return hits.singleOrNull()
    }

    /**
     * Sin ningún nombre leído: la línea de tipo ("Land", "Encantamiento") entre las primeras,
     * con texto debajo; la ilustración es el recuadro de encima, tan ancho como el texto.
     * Se busca en todas las ilustraciones del catálogo y solo vale si una carta destaca
     * con claridad. Devuelve el nombre, la ilustración y el índice de la línea de tipo.
     */
    private fun nameBySearchedArt(sorted: List<OcrLine>, art: ArtSampler): Triple<NameMatcher.Match, Int, Int>? {
        val typeIndex = (0 until minOf(3, sorted.size)).firstOrNull { isLooseTypeLine(sorted[it]) }
        // Sin línea de tipo leída: si lo primero es ya texto de reglas, la ilustración queda
        // por encima, pasada la línea de tipo.
        val type = typeIndex?.let(sorted::get)
            ?: sorted.firstOrNull()?.takeIf { sorted.size >= 3 && it.words.size >= 4 } ?: return null
        val body = if (typeIndex == null) sorted else sorted.drop(typeIndex + 1).filter { it.top > type.bottom }
        if (body.size < 2) return null
        val widest = body.maxOf { it.right - it.left }
        val wide = body.filter { it.right - it.left >= widest * 0.6f }
        val left = minOf(wide.minOf { it.left }, type.left).toFloat()
        val right = wide.maxOf { it.right }.toFloat()
        val w = right - left
        val lineH = (type.bottom - type.top).toFloat()
        if (w < 8 * lineH) return null
        val bottom = type.top - (if (typeIndex == null) RULES_TO_ART_LINES else 0.4f) * lineH
        val top = bottom - w / SEARCHED_ART_RATIO
        if (top < 0) return null
        val inset = 0.06f
        val photo = art.signature(left + w * inset, top + (bottom - top) * inset, right - w * inset, bottom - (bottom - top) * inset)
            ?: return null
        val found = searchArt(photo, 3)
        artDebug = "busqueda_ilustracion " + found.joinToString(" ") { "${it.first}:${it.third.fixed(2)}" }
        val (name, artId, sim) = found.firstOrNull() ?: return null
        val second = found.getOrNull(1)?.third ?: -1.0
        // Muy parecida (> 0,9) basta con algo de ventaja: los paisajes antiguos se parecen
        // entre sí (Savannah, Mountain, Scrubland), pero ninguna otra llega tan alto.
        val clear = sim - second >= SEARCHED_ART_MARGIN || (sim >= SEARCHED_ART_HIGH && sim - second >= SEARCHED_ART_HIGH_MARGIN)
        if (sim < SEARCHED_ART_MIN || !clear) return null
        val m = names.match(name)?.takeIf { it.distance == 0 } ?: return null
        searchedArtSimilarity = sim
        return Triple(m, artId, typeIndex ?: 0)
    }

    /**
     * Línea de tipo leída con algún error más de lo normal ("Laud", "Encanemiento"; en las
     * cartas blancas, con el tipo claro sobre fondo claro: "Encharit", "Erncartar",
     * "Encantarrniento", con "rn" por "m").
     */
    private fun isLooseTypeLine(line: OcrLine): Boolean {
        val raw = NameMatcher.normalize(line.words.firstOrNull() ?: return false)
        if (line.words.size == 1 && raw.length == 4 && NameMatcher.boundedLevenshtein(raw, "land", 1) <= 1) return true
        return listOf(raw, raw.replace("rn", "m")).distinct().any { first ->
            TYPE_WORDS.any { t ->
                val max = when {
                    t.length >= 7 -> 2
                    t.length >= 6 -> 1
                    else -> 0
                }
                first == t || (max > 0 && NameMatcher.boundedLevenshtein(first, t, max) <= max)
            }
        }
    }

    /**
     * Zona de la ilustración sin línea de tipo (no se leyó): bajo el nombre, tan ancha como
     * el texto de reglas y con la proporción de las ilustraciones antiguas (~1,3:1).
     */
    private fun looseArtRegion(sorted: List<OcrLine>): GuideRegion.Rect? {
        val name = sorted[0]
        val body = sorted.drop(1)
        val widest = body.maxOf { it.right - it.left }
        val wide = body.filter { it.right - it.left >= widest * 0.6f }
        val left = minOf(wide.minOf { it.left }, name.left).toFloat()
        val right = wide.maxOf { it.right }.toFloat()
        val w = right - left
        val lineH = (name.bottom - name.top).toFloat()
        val top = name.bottom + 0.5f * lineH
        val bottom = top + w / 1.3f
        // Las líneas de texto deben quedar por debajo de la ilustración.
        if (w < 8 * lineH || body.none { it.top > bottom - lineH }) return null
        val inset = 0.06f
        return GuideRegion.Rect(left + w * inset, top + (bottom - top) * inset, right - w * inset, bottom - (bottom - top) * inset)
    }

    /**
     * Tierras básicas con el nombre leído con un error ("Tsla" -> "Isla"): los nombres cortos
     * solo se aceptan exactos, pero si debajo está la línea de tipo de tierra ("Tierra", "Land")
     * a una letra de distancia de una básica no hay otra carta posible.
     */
    private fun basicLand(sorted: List<OcrLine>, zone: Int): NameMatcher.Match? {
        if (zone !in 1..2) return null // el nombre arriba y la línea de tipo una o dos líneas debajo
        val type = sorted.getOrNull(zone) ?: return null
        val typeWord = NameMatcher.normalize(type.words.firstOrNull() ?: return null)
        if (typeWord !in LAND_TYPE_WORDS && LAND_TYPE_WORDS.none { NameMatcher.boundedLevenshtein(typeWord, it, 1) <= 1 }) return null
        val read = NameMatcher.normalize(sorted[0].text)
        if (read.length < 3) return null
        val hits = BASIC_LAND_NAMES.filterKeys { NameMatcher.boundedLevenshtein(read, it, 1) <= 1 }
        val key = hits.keys.singleOrNull() ?: return null
        return names.match(key)?.takeIf { hits.getValue(key) in it.names }?.copy(distance = 1)
    }

    /**
     * Si el nombre leído es el de una carta entera, no cuentan las caras de otras que se
     * llaman igual: "Braingeyser" (Beta) no es "Dirgur Focusmage // Braingeyser".
     */
    private fun wholeCardNames(match: NameMatcher.Match): List<String> {
        if (match.names.size < 2) return match.names
        // "Anointed Procession // Anointed Procession" (las dos caras iguales) es la carta entera.
        return match.names.filter { n -> n.split(" // ").all { NameMatcher.normalize(it) == match.key } }
            .ifEmpty { match.names }
    }

    private fun nameZone(sorted: List<OcrLine>): Int {
        val limit = minOf(NAME_SEARCH_LINES, sorted.size)
        for (i in 0 until limit) {
            // Una línea de tipo que coincide con un nombre solo puede ser el nombre si es la
            // primera: "Tierra" (tipo de las tierras en español) es también la cara "Earth".
            if (isTypeLine(sorted[i], TYPE_WORDS) && (i > 0 || names.match(sorted[i].text)?.distance != 0)) return i
            // Si no se leyó la línea de tipo, el texto de reglas tampoco es el nombre:
            // "Retira del juego..." no es "Retirar" (Withdraw), ni "Flying" es "Fling".
            if (i > 0 && isRulesLine(sorted[i])) return i
            // Una línea que empieza en minúscula sigue una frase: "either. Tap to add..." no es "Ether".
            // Salvo justo encima de la línea de tipo: ahí está el nombre ("bill Gíant" / "Summon Giant").
            if (i > 0 && sorted[i].text.firstOrNull()?.isLowerCase() == true &&
                sorted.getOrNull(i + 1)?.let { isTypeLine(it, TYPE_WORDS) } != true
            ) return i
        }
        return limit
    }

    /** Una frase ("... objetivo.") o una habilidad sola ("Flying", "Vuela"): texto de reglas. */
    private fun isRulesLine(line: OcrLine): Boolean =
        line.text.trimEnd().endsWith('.') || NameMatcher.normalize(line.text) in KEYWORD_LINES

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
        /** Nombre por la ilustración: candidatos, similitud mínima y ventaja sobre el segundo. */
        private const val NAME_BY_ART_CANDIDATES = 8
        private const val NAME_BY_ART_MIN = 0.6
        private const val NAME_BY_ART_MARGIN = 0.2
        /** Búsqueda en todo el catálogo: más exigente (hay ~28.000 ilustraciones). */
        private const val SEARCHED_ART_MIN = 0.7
        private const val SEARCHED_ART_MARGIN = 0.15
        private const val SEARCHED_ART_HIGH = 0.9
        private const val SEARCHED_ART_HIGH_MARGIN = 0.04
        /** Ancho/alto de las ilustraciones antiguas. */
        private const val SEARCHED_ART_RATIO = 1.25f
        /** Del texto de reglas al pie de la ilustración, en alturas de línea (salta la de tipo). */
        private const val RULES_TO_ART_LINES = 4f
        private const val ART_MIN_MARGIN = 0.15
        /**
         * Un nombre que deja sin explicar tantas letras de la línea se comprueba con la
         * ilustración (el coste de maná leído como letras suele dejar 1-3), y se cambia por la
         * carta encontrada buscando la ilustración si esta se parece tanto más.
         */
        private const val PARTIAL_NAME_LETTERS = 5
        /** Nombre buscado en el texto de reglas: candidatos y letras mínimas (ni "Fog" ni "Shock"). */
        private const val RULES_NAME_CANDIDATES = 8
        private const val RULES_NAME_MIN = 6
        private const val PARTIAL_NAME_ART_GAP = 0.3

        /**
         * Umbrales de la comparación de símbolos de edición (correlación). Medidos con
         * cartas de prueba: por debajo, el símbolo no se ve bien o se parece a otro.
         */
        private const val SYMBOL_MIN = 0.6
        private const val SYMBOL_MARGIN = 0.15
        /** Con esta coincidencia seguro que hay un símbolo: descarta las ediciones sin él. */
        private const val SYMBOL_NONE_MIN = 0.7
        /** Más ediciones candidatas que estas (tierras básicas...) no se comparan: sería lento. */
        private const val SYMBOL_MAX_CANDIDATES = 40
        /** Referencias con las que se busca dónde está el símbolo en la foto. */
        private const val SYMBOL_LOCATE_REFS = 8
        /** Dos referencias así de parecidas no se distinguen en una foto. */
        private const val SYMBOL_TWIN = 0.8
        /** Fracción del ancho de la carta que ocupa el texto de reglas. */
        private const val TEXT_WIDTH_FRACTION = 0.84f
        /**
         * El borde es una meseta: al menos 3 franjas seguidas (3 % del ancho) que no varían más
         * de [BORDER_FLAT] de luminancia, sin color, buscada entre las franjas 3 y 16 (de 7 %
         * fuera a 6 % dentro). Medido con 546 cartas de las fotos del móvil (etiquetadas a mano):
         * decide 454 y se equivoca en 4; con 1228 simuladas, decide 823 y se equivoca en 20.
         * (La regla anterior, con umbrales fijos, fallaba en 28 de las del móvil: tomaba un
         * mantel claro por borde blanco y un borde blanco con poca luz por negro.)
         */
        private const val BORDER_FIRST_BIN = 3
        private const val BORDER_LAST_BIN = 16
        private const val BORDER_RUN = 3
        private const val BORDER_FLAT = 6
        private const val BORDER_MAX_SAT = 60
        /** Negra: así de oscura y al menos [BLACK_BORDER_CONTRAST] más que lo de fuera. */
        private const val BLACK_BORDER_MAX_LUM = 90
        private const val BLACK_BORDER_CONTRAST = 20
        /** Blanca: así de clara y al menos [WHITE_BORDER_CONTRAST] más que lo de fuera y que el marco. */
        private const val WHITE_BORDER_MIN_LUM = 120
        private const val WHITE_BORDER_CONTRAST = 30
        /**
         * Pista débil de borde negro cuando no hay meseta: en las 12 primeras franjas (de 10 %
         * fuera a 2 % dentro) nada pasa de esta luminancia. Con la meseta antes, en las fotos del
         * móvil decide 515 de 546 y falla 17 (sin ella, 454 y 4).
         */
        private const val WEAK_BLACK_ZONE_BINS = 12
        private const val WEAK_BLACK_MAX_LUM = 170

        /**
         * Dónde está la ilustración en la foto: entre el nombre y la línea de tipo,
         * y a lo ancho del texto de reglas (que ocupa casi todo el ancho del marco).
         * Se recorta un poco hacia dentro para no coger el marco. null si no se puede
         * estimar (sin línea de tipo, cartas de arte completo, proporciones raras).
         */
        fun artRegion(sorted: List<OcrLine>, nameIndex: Int): GuideRegion.Rect? {
            val name = sorted.getOrNull(nameIndex) ?: return null
            val typeIndex = typeIndexAfter(sorted, nameIndex) ?: return null
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

        private fun typeIndexAfter(sorted: List<OcrLine>, nameIndex: Int): Int? {
            val name = sorted[nameIndex]
            return (nameIndex + 1 until sorted.size).firstOrNull { i -> sorted[i].top > name.bottom && isTypeWord(sorted[i]) }
        }

        private fun isTypeWord(line: OcrLine): Boolean {
            val first = NameMatcher.normalize(line.words.firstOrNull() ?: return false)
            return first == "land" || TYPE_WORDS.any { t ->
                first == t || (t.length >= 6 && NameMatcher.boundedLevenshtein(first, t, 1) <= 1)
            }
        }

        private val ENGLISH_TYPE_WORDS = listOf(
            "legendary", "creature", "instant", "sorcery", "artifact", "enchantment", "land",
            "planeswalker", "battle", "tribal", "kindred", "basic", "snow", "summon", "enchant",
            "interrupt", "token", "mono", "poly", // antiguas: "Mono Artifact", "Poly Artifact"
        )

        /** Primera palabra de la línea de tipo en cada idioma (normalizada, sin tildes). */
        private val FOREIGN_TYPE_WORDS = mapOf(
            "es" to listOf(
                "criatura", "instantaneo", "conjuro", "artefacto", "encantamiento", "tierra",
                "legendaria", "legendario", "batalla", "basica", "invocar", "encantar", "interrupcion",
                "hechizo", // antiguas: "Hechizo instantáneo"
            ),
            "fr" to listOf(
                "creature", "ephemere", "rituel", "artefact", "enchantement", "terrain", "legendaire",
                "bataille", "tribal", "neigeux", "invocation", "invoquer", "interruption",
            ),
            "de" to listOf(
                "kreatur", "spontanzauber", "hexerei", "artefakt", "verzauberung", "land", "legendare",
                "legendarer", "legendares", "standardland", "schlacht", "stammesverwandt", "verzauberungszauber",
                "beschworung", "unterbrechung",
            ),
            "it" to listOf(
                "creatura", "istantaneo", "stregoneria", "artefatto", "incantesimo", "terra", "leggendaria",
                "leggendario", "battaglia", "tribale", "evoca", "interruzione",
            ),
            "pt" to listOf(
                "criatura", "magica", "feitico", "artefato", "encantamento", "terreno", "lendaria", "lendario",
                "batalha", "basico", "tribal",
            ),
        )

        /** Todas las palabras de tipo con su idioma (las compartidas, una vez por idioma). */
        private val TYPE_WORD_LANGUAGES: List<Pair<String, String>> =
            ENGLISH_TYPE_WORDS.map { it to "en" } + FOREIGN_TYPE_WORDS.flatMap { (lang, words) -> words.map { it to lang } }

        /** "Summon"/"Interrupt" en los seis idiomas (tipos antiguos, hasta 1999). */
        private val SUMMON_WORDS = setOf(
            "summon", "interrupt", "invocar", "interrupcion", "invocation", "interruption", "beschworung",
            "unterbrechung", "evoca", "interruzione", "mono", "poly",
        )

        /** "Enchant ..." como tipo (hasta 2004): "Enchant Creature", "Encantar criatura"... */
        private val ENCHANT_WORDS = setOf("enchant", "encantar", "enchanter", "verzaubert", "incanta", "encantarcriatura")

        /** Primera palabra de la línea de tipo de las tierras, en los seis idiomas. */
        private val LAND_TYPE_WORDS = setOf("land", "tierra", "terrain", "terra", "terreno", "basic", "basica", "standardland")

        /** Nombres de las tierras básicas en los seis idiomas (normalizados) -> nombre inglés. */
        private val BASIC_LAND_NAMES = mapOf(
            "plains" to "Plains", "llanura" to "Plains", "plaine" to "Plains", "ebene" to "Plains", "pianura" to "Plains", "planicie" to "Plains",
            "island" to "Island", "isla" to "Island", "ile" to "Island", "insel" to "Island", "isola" to "Island", "ilha" to "Island",
            "swamp" to "Swamp", "pantano" to "Swamp", "marais" to "Swamp", "sumpf" to "Swamp", "palude" to "Swamp",
            "mountain" to "Mountain", "montana" to "Mountain", "montagne" to "Mountain", "gebirge" to "Mountain",
            "montagna" to "Mountain", "montanha" to "Mountain",
            "forest" to "Forest", "bosque" to "Forest", "foret" to "Forest", "wald" to "Forest", "foresta" to "Forest", "floresta" to "Forest",
        )

        /** Habilidades que ocupan una línea sola, normalizadas, en los seis idiomas. */
        private val KEYWORD_LINES = setOf(
            "flying", "trample", "firststrike", "doublestrike", "vigilance", "haste", "reach", "deathtouch",
            "lifelink", "defender", "menace", "hexproof", "indestructible", "banding", "fear", "shroud", "intimidate",
            "vuela", "arrolla", "danaprimero", "dananprimero", "dobleataque", "vigilancia", "prisa", "alcance",
            "toquemortal", "vinculovital", "defensor", "amenaza", "antimaleficio", "indestructible", "miedo",
            "vol", "pietinement", "initiative", "doubleinitiative", "celerite", "portee", "contactmortel",
            "lienvital", "defenseur", "menace",
            "fliegend", "trampelschaden", "erstschlag", "doppelschlag", "wachsamkeit", "eile", "reichweite",
            "todesberuhrung", "lebensverknupfung", "verteidiger", "bedrohlich", "fluchsicher", "unzerstorbar",
            "volare", "travolgere", "attaccoprimo", "doppioattacco", "cautela", "rapidita", "raggiungere",
            "toccoletale", "legamevitale", "difensore", "minacciare", "antimalocchio", "indistruttibile",
            "voar", "atropelar", "iniciativa", "golpeduplo", "impeto", "toquemortifero", "vinculoavida",
            "ameaca", "resistenciaamagia", "indestrutivel",
        )

        /** Letras mínimas de una palabra de tipo para admitir errores de lectura. */
        private const val FUZZY_TYPE_WORD_MIN = 10

        /** Idiomas de cada palabra de tipo, el más común primero (inglés, español, francés...). */
        /**
         * Palabras frecuentes en el texto de reglas que son de un solo idioma (normalizadas: sin
         * tildes). Se dejan fuera las compartidas: "de", "la", "que", "del", "con", "no", "se"...
         */
        private val BODY_WORD_LANGS: Map<String, String> = buildMap {
            // Cada palabra, de un solo idioma: repetida, la segunda pisaría a la primera.
            fun add(lang: String, words: String) = words.split(' ').forEach { check(put(it, lang) == null) { it } }
            add("en", "the of and to target until end your you each player damage play creatures")
            add("es", "el los las hasta jugador puntos juego mano puedes roba mientras cualquier sus otra")
            add("pt", "do da dos nao ate uma um jogador voce seu alvo ao pontos mao jogo")
            add("it", "il di della delle dello fino giocatore danni danno bersaglio gioco ogni alla nel puoi")
            add("fr", "les des une et jusqu joueur cible vous degats votre chaque carte tour sur")
            add("de", "der die und eine einen bis zum kreatur spieler schaden karte deine wenn ist")
        }
        private const val BODY_LANGUAGE_MIN = 3

        private val TYPE_WORD_LANGS: Map<String, List<String>> =
            TYPE_WORD_LANGUAGES.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }

        private val TYPE_WORDS = (ENGLISH_TYPE_WORDS + FOREIGN_TYPE_WORDS.values.flatten()).distinct()

        /**
         * [foreign]: la carta no está en inglés. Entonces se prefieren las ediciones
         * que no existen en inglés (colección Salvat: "58/60" en la carta, "A58" en
         * Scryfall) y no se da por segura una inglesa que solo coincide por el número.
         */
        fun resolve(
            printings: List<CardPrinting>,
            info: CollectorInfo,
            foreign: Boolean = false,
            border: Char? = null,
        ): Pair<CardPrinting, Boolean> {
            if (printings.size == 1) return printings[0] to true
            val foreignOnly = if (foreign) printings.filter { it.lang != "en" } else emptyList()
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

            // 2b. Ediciones que no existen en inglés, por número (con o sin letra delante).
            if (foreignOnly.isNotEmpty() && info.numbers.isNotEmpty()) {
                val m = foreignOnly.filter { p -> info.numbers.any { numberMatchesWithPrefix(p.numberNorm, it) } }
                if (m.size == 1) return m[0] to true
                if (m.isNotEmpty()) return defaultPick(m) to false
            }
            val certain = foreignOnly.isEmpty()

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
            return defaultPick(byYear(byBorder(byEra(printings, info), border), info.year)) to false
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

        /**
         * Época por el pie de la carta, sin año leído: "Illus. ©" sin línea de Wizards es de
         * Revised o antes (1994); con línea de Wizards, de 4ª edición (1995) en adelante.
         * Así Revised no se toma por 4ª edición. Si no queda ninguna, no filtra.
         */
        private fun byEra(printings: List<CardPrinting>, info: CollectorInfo): List<CardPrinting> {
            if (info.year != null) return printings
            info.maxYear?.let { max ->
                val old = printings.filter { p -> p.releaseYear?.let { it <= max } == true }
                if (old.isNotEmpty() && old.size < printings.size) return byEra(old, info.copy(maxYear = null))
            }
            val keep: (Int) -> Boolean = when {
                info.wizardsLine -> { y -> y >= 1995 }
                info.artistCopyright -> { y -> y <= 1994 }
                else -> return printings
            }
            return printings.filter { p -> p.releaseYear?.let(keep) == true }.ifEmpty { printings }
        }

        /**
         * Por el borde visto en la foto ([detectBorder]): con borde blanco, solo las de borde
         * blanco (Revised, no Beta; 4ª edición, no la de borde negro); sin él, las de borde
         * negro primero. Solo orienta la sugerencia: nunca da una edición por segura.
         */
        private fun byBorder(printings: List<CardPrinting>, border: Char?): List<CardPrinting> = when (border) {
            'w' -> printings.filter { it.border == 'w' }.ifEmpty { printings }
            'k' -> printings.filter { it.border != 'w' }.ifEmpty { printings }
            else -> printings
        }

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
        fun defaultPick(printings: List<CardPrinting>): CardPrinting {
            val regular = printings.filter {
                !it.promo && it.setCode !in RARE_SETS && it.setType !in SPECIAL_SET_TYPES
            }.ifEmpty { printings }
            return regular.minByOrNull { it.prices.eur ?: it.prices.usd ?: Double.MAX_VALUE } ?: printings[0]
        }

        /** The List y Summer Magic (apenas se distribuyó): nunca la opción por defecto. */
        private val RARE_SETS = setOf("plst", "sum")

        private val SPECIAL_SET_TYPES = setOf("promo", "box", "masterpiece", "from_the_vault", "funny", "memorabilia", "starter")

        // ---------------------------------------------------------------- línea de coleccionista

        /** "Wizards of the Coast" y "Todos los derechos reservados"/"All rights reserved", con errores de lectura. */
        private val WIZARDS_LINE = Regex("(?i)w[i1l]?z[a-z]{0,2}r?ds|wizards|\\bc[o0][au]st|derech|rights res|reservad|reserved")

        /**
         * "Illus. © Douglas Shuler", leído "IIus. ODouglas", "Ius. O-Mark", "Iis, �-Ron", "Ilus, © Margaret":
         * una palabra tipo "Illus" seguida de ©, �, o una O pegada o suelta ante el nombre.
         */
        // También sin la primera letra o con ella mal leída: "us. ODouglas", "flus. Brian"; y el ©
        // leído como "O" más una letra pegada al nombre: "IIfus. OrAnson" (no "Omaha Perez").
        private val ARTIST_COPYRIGHT = Regex("^\\W{0,2}(?:[IlLT1|f][a-zA-Z|]{0,4}|[a-z]{2,3}(?=\\.))[.,]?\\s*(?:[©�]|O(?=-|\\s*[A-Z])|O[a-z](?=[A-Z]))")

        private val SLASH_NUMBER = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})")
        private val YEAR = Regex("(?<!\\d)(199[3-9]|20[0-4]\\d)(?!\\d)")
        private val TOKEN_SPLIT = Regex("[\\s•·*.,>\\-]+")
        private val DIGITS = Regex("^\\d{1,4}$")
        // Las cartas recientes imprimen "SP" para español; algunas antiguas, "ES".
        private val LANGUAGES = setOf("EN", "ES", "SP", "FR", "DE", "IT", "PT", "JP", "JA", "RU", "KO", "CS", "CT", "ZHS", "ZHT")
        /** Códigos de idioma impresos que la app distingue. */
        private val PRINTED_LANGUAGES = mapOf("EN" to "en", "ES" to "es", "SP" to "es", "FR" to "fr", "DE" to "de", "IT" to "it", "PT" to "pt")
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
            var artistCopyright = false
            var wizardsLine = false
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
                if (WIZARDS_LINE.containsMatchIn(line.text)) wizardsLine = true
                if (ARTIST_COPYRIGHT.containsMatchIn(line.text)) artistCopyright = true

                val tokens = line.text.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
                val hasLanguage = tokens.any { it in LANGUAGES || (it == it.uppercase() && gluedLanguageAt(it) != null) }
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
                    setScores[code] = maxOf(setScores[code] ?: score, score)
                    printedLanguage(tokens, i)?.let { language = it }
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
                artistCopyright = artistCopyright,
                wizardsLine = wizardsLine,
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

        /**
         * El idioma impreso tras el código de edición [i]: pegado ("MOMFR"), en el
         * token siguiente ("M20 • DE") o pegado al artista ("RNA ENTITUS": solo
         * EN/ES/SP). En otro sitio "DE" o "IT" pueden ser palabras del texto.
         */
        private fun printedLanguage(tokens: List<String>, i: Int): String? {
            val token = tokens[i]
            gluedLanguageAt(token)?.let { at -> PRINTED_LANGUAGES[token.substring(at, at + 2)]?.let { return it } }
            val next = tokens.getOrNull(i + 1)?.takeIf { it == it.uppercase() } ?: return null
            PRINTED_LANGUAGES[next]?.let { return it }
            return next.take(2).takeIf { next.length > 2 && it in GLUED_TO_ARTIST }?.let(PRINTED_LANGUAGES::get)
        }

        private val GLUED_TO_ARTIST = setOf("EN", "ES", "SP")

        /** Posición de un "EN"/"ES" pegado tras un código de 3-5 caracteres ("MOMEN", "MOMES"), o null. */
        private fun gluedLanguageAt(token: String): Int? =
            (3..5).firstOrNull { i ->
                token.length >= i + 2 && token.length <= i + 4 &&
                    PRINTED_LANGUAGES.keys.any { token.startsWith(it, i) }
            }

        private fun stripZeros(s: String) = s.trimStart('0').ifEmpty { "0" }

        /** Corrige letras que el OCR confunde con dígitos dentro de un número. */
        private fun digitFix(s: String): String {
            if (s.none { it.isDigit() }) return s
            return s.replace(Regex("(?<=\\d)[Oo]|[Oo](?=\\d)"), "0")
                .replace(Regex("(?<=\\d)[Il|]|[Il|](?=\\d)"), "1")
                .replace(Regex("(?<=\\d)S|S(?=\\d)"), "5")
                .replace(Regex("(?<![A-Za-z])L(?=99\\d)"), "1") // "L995 Wizards"
        }

        private fun setVariants(token: String): List<String> {
            val t = token.lowercase()
            return listOf(t, t.replace('0', 'o'), t.replace('o', '0'), t.replace('1', 'i'), t.replace('i', '1'))
                .distinct()
        }
    }
}
