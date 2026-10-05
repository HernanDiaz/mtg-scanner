package com.hernan.mtgscanner.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.hernan.mtgscanner.scan.ArtSignature
import com.hernan.mtgscanner.scan.SetSymbols
import com.hernan.mtgscanner.scan.SymbolRef

/**
 * Catálogo de todas las impresiones, de solo lectura. Lo genera tools/build_catalog.py
 * (catalog.sqlite) y va dentro de la app: en Android se copia de los assets al
 * almacenamiento interno (openCatalog); en iPhone se lee directamente del paquete.
 * Construirlo es lento (~1 s): hacerlo fuera del hilo principal.
 *
 * La conexión no admite varios hilos a la vez: cada consulta toma [lock].
 */
class Catalog(private val db: SQLiteConnection) {

    private val lock = CatalogLock()
    val names: NameMatcher
    val setCodes: Set<String>
    private val sets: Map<String, SetInfo>
    private val printingsCache = ConcurrentCache<String, List<CardPrinting>>()
    private val arts = HashMap<Int, ArtSignature>()
    /** Nombre traducido (normalizado) -> sus idiomas, el más frecuente primero. */
    private val foreignNames = HashMap<String, List<String>>(160_000)
    /** Nombre de carta -> legalidad por formato ([Legality]). */
    private val legality = HashMap<String, Int>(40_000)
    /** Símbolos de edición de referencia; null con un catálogo antiguo que no los trae. */
    val symbols: SetSymbols?

    init {
        val entries = ArrayList<Pair<String, String>>(200_000)
        val langLists = HashMap<String, List<String>>() // "es,pt" -> [es, pt], compartidas
        rows("SELECT norm, name, lang FROM names") { c ->
            entries.add(c.getText(0) to c.getText(1))
            val lang = c.getText(2)
            if (lang != "en") foreignNames[c.getText(0)] = langLists.getOrPut(lang) { lang.split(",") }
        }
        names = NameMatcher(entries)

        val loaded = HashMap<String, SetInfo>()
        val langSets = HashMap<String, Set<String>>() // "fr,de" -> {fr, de}, compartidos
        rows("SELECT code, name, set_type, printed_size, langs FROM sets") { c ->
            val langs = c.getText(4)
            loaded[c.getText(0)] = SetInfo(c.getText(1), c.textOrNull(2), c.intOrNull(3),
                langSets.getOrPut(langs) { langs.split(",").filter { it.isNotEmpty() }.toSet() })
        }
        sets = loaded
        setCodes = loaded.keys

        rows("SELECT id, thumb FROM arts") { c -> arts[c.getInt(0)] = ArtSignature.fromPacked(c.getBlob(1)) }
        runCatching {
            rows("SELECT name, bits FROM legality") { c -> legality[c.getText(0)] = c.getInt(1) }
        }

        symbols = runCatching {
            val refs = HashMap<String, SymbolRef>()
            rows("SELECT set_code, rarity, style, w, h, rgb FROM symbols") { c ->
                refs[SetSymbols.key(c.getText(0), c.getText(1), c.getText(2))] =
                    SymbolRef.fromBytes(c.getInt(3), c.getInt(4), c.getBlob(5))
            }
            val icons = HashMap<String, String>()
            val none = HashSet<String>()
            val other = HashSet<String>()
            rows("SELECT set_code, icon, kind FROM set_icons") { c ->
                val set = c.getText(0)
                if (!c.isNull(1)) icons[set] = c.getText(1)
                when (c.getText(2)) { "none" -> none += set; "other" -> other += set }
            }
            SetSymbols(refs, none, other, icons)
        }.getOrNull()
    }

    /** Huella de una ilustración, para elegir edición por la imagen. */
    fun art(id: Int): ArtSignature? = arts[id]

    /** Ilustración -> nombre de la carta (se carga la primera vez que se busca por imagen). */
    private val artNames: Map<Int, String> by lazy {
        HashMap<Int, String>(arts.size * 2).apply {
            rows("SELECT art_id, name FROM cards WHERE art_id IS NOT NULL GROUP BY art_id") { c ->
                put(c.getInt(0), c.getText(1))
            }
        }
    }

    /**
     * Las cartas cuya ilustración más se parece a [photo], buscando en todo el catálogo:
     * (nombre, ilustración, similitud), la mejor de cada nombre primero.
     */
    fun searchArt(photo: ArtSignature, keep: Int): List<Triple<String, Int, Double>> =
        photo.mostSimilar(arts, keep * 4)
            .mapNotNull { (id, sim) -> artNames[id]?.let { Triple(it, id, sim) } }
            .distinctBy { it.first }.take(keep)

    private data class SetInfo(val name: String, val type: String?, val size: Int?, val langs: Set<String>)

    /** Todas las impresiones de un nombre, de la más reciente a la más antigua. */
    fun printingsOf(name: String): List<CardPrinting> = printingsCache.getOrPut(name) {
        query("WHERE name = ? ORDER BY released_at DESC, promo ASC", name)
    }

    fun byId(id: String): CardPrinting? = query("WHERE id = ?", id).firstOrNull()

    fun bySetAndNumber(setCode: String, numberNorm: String): CardPrinting? =
        query("WHERE set_code = ? AND number_norm = ?", setCode, numberNorm).firstOrNull()

    fun setName(code: String) = sets[code]?.name ?: code.uppercase()

    /** Legalidad de una carta por su nombre ([Legality]); null si no se conoce. */
    fun legalityOf(name: String): Int? = legality[name]

    private val setsByName: Map<String, String> by lazy {
        // Si dos ediciones se llaman igual, gana la de código más corto (la principal, no la de fichas).
        sets.entries.sortedByDescending { it.key.length }.associate { NameMatcher.normalize(it.value.name) to it.key }
    }

    /** Para importar listas: busca las cartas como las escribe una persona u otra app. */
    val importLookup = object : CollectionImport.Lookup {
        override fun byId(id: String) = this@Catalog.byId(id)
        override fun bySetAndNumber(setCode: String, numberNorm: String) = this@Catalog.bySetAndNumber(setCode, numberNorm)
        override fun printingsOf(name: String) = printingsOfAnyName(name)
        override fun setCode(value: String) = setCodeFor(value)
        override fun languageOfName(name: String) = this@Catalog.languageOfName(name)
    }

    /** Código de edición a partir de un código ("M11", "m11") o un nombre ("Magic 2011"). */
    fun setCodeFor(value: String): String? {
        val code = value.trim().lowercase()
        return if (code in sets) code else setsByName[NameMatcher.normalize(value)]
    }

    /** Idiomas aparte del inglés en que se imprimió una edición (vacío: solo inglés); null si no se conoce. */
    fun setLanguages(code: String): Set<String>? = sets[code]?.langs

    /** Idiomas de un nombre traducido ("relampago" -> [es]); null si no es un nombre traducido. */
    fun nameLanguages(key: String): List<String>? = foreignNames[key]

    /**
     * Idioma de un nombre escrito por una persona si es una traducción ("Relámpago" -> "es",
     * "Foudre" -> "fr"), o null si es el nombre inglés de alguna carta (o de una de sus caras).
     */
    fun languageOfName(name: String): String? {
        val key = NameMatcher.normalize(name)
        val match = names.match(name)?.takeIf { it.distance == 0 } ?: return null
        val english = match.names.any { full ->
            NameMatcher.normalize(full) == key || full.split(" // ").any { NameMatcher.normalize(it) == key }
        }
        return if (english) null else foreignNames[key]?.first()
    }

    /**
     * Impresiones de un nombre escrito por una persona o por otra app: el nombre
     * exacto, con otras mayúsculas o tildes, en español, o solo la primera cara
     * ("Delver of Secrets" por "Delver of Secrets // Insectile Aberration").
     */
    fun printingsOfAnyName(name: String): List<CardPrinting> {
        printingsOf(name).takeIf { it.isNotEmpty() }?.let { return it }
        // Varios nombres pueden compartir clave ("Lightning Bolt" y "Emeritus of Conflict // Lightning Bolt"):
        // primero el que es exactamente ese nombre.
        val key = NameMatcher.normalize(name)
        val candidates = names.match(name)?.takeIf { it.distance == 0 }?.names.orEmpty()
        (candidates.firstOrNull { NameMatcher.normalize(it) == key } ?: candidates.firstOrNull())?.let { exact ->
            printingsOf(exact).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val front = name.trim() + " // "
        var full: String? = null
        rows("SELECT DISTINCT name FROM cards WHERE name >= ? AND name < ? LIMIT 1", front, front + Char(0xFFFF)) { c ->
            full = c.getText(0)
        }
        return full?.let(::printingsOf).orEmpty()
    }

    private fun query(where: String, vararg args: String): List<CardPrinting> = buildList {
        rows("SELECT $COLUMNS FROM cards $where", *args) { c -> add(read(c)) }
    }

    /** Ejecuta [sql] con [args] y llama a [row] con cada fila. */
    private fun rows(sql: String, vararg args: String, row: (SQLiteStatement) -> Unit) = lock.withLock {
        db.prepare(sql).use { st ->
            args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
            while (st.step()) row(st)
        }
    }

    private fun read(c: SQLiteStatement) = CardPrinting(
        id = c.getText(0),
        name = c.getText(1),
        setCode = c.getText(2),
        setName = setName(c.getText(2)),
        setType = sets[c.getText(2)]?.type,
        setSize = sets[c.getText(2)]?.size,
        collectorNumber = c.getText(3),
        numberNorm = c.getText(4),
        rarity = c.textOrNull(5),
        releasedAt = c.textOrNull(6),
        finishes = c.getInt(7),
        promo = c.getInt(8) != 0,
        lang = c.getText(9),
        prices = Prices(c.doubleOrNull(10), c.doubleOrNull(11), c.doubleOrNull(12), c.doubleOrNull(13)),
        artId = c.intOrNull(14),
        colors = c.getText(15),
        style = c.getText(16),
        cardmarketId = c.intOrNull(17),
        border = c.getText(18).firstOrNull() ?: 'b',
        legality = legality[c.getText(1)] ?: 0,
    )

    private fun SQLiteStatement.getInt(i: Int) = getLong(i).toInt()
    private fun SQLiteStatement.intOrNull(i: Int) = if (isNull(i)) null else getLong(i).toInt()
    private fun SQLiteStatement.textOrNull(i: Int) = if (isNull(i)) null else getText(i)
    private fun SQLiteStatement.doubleOrNull(i: Int) = if (isNull(i)) null else getDouble(i)

    companion object {
        /** Nombre del fichero del catálogo dentro de la app. */
        const val FILE_NAME = "catalog.sqlite"
        private const val COLUMNS = "id, name, set_code, collector_number, number_norm, rarity, " +
            "released_at, finishes, promo, lang, eur, eur_foil, usd, usd_foil, art_id, colors, style, cm_id, border"
    }
}
