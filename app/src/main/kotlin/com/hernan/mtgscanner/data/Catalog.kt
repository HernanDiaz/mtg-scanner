package com.hernan.mtgscanner.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import com.hernan.mtgscanner.scan.ArtSignature
import com.hernan.mtgscanner.scan.SetSymbols
import com.hernan.mtgscanner.scan.SymbolRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Catálogo de todas las impresiones, de solo lectura. Viene empaquetado en
 * assets/catalog.sqlite (lo genera tools/build_catalog.py) y se copia a
 * almacenamiento interno la primera vez y tras cada actualización de la app.
 * Construirlo es lento (~1 s): hacerlo fuera del hilo principal.
 */
class Catalog(context: Context) {

    private val db: SQLiteDatabase
    val names: NameMatcher
    val setCodes: Set<String>
    private val sets: Map<String, SetInfo>
    private val printingsCache = ConcurrentHashMap<String, List<CardPrinting>>()
    private val arts = HashMap<Int, ArtSignature>()
    /** Nombre traducido (normalizado) -> sus idiomas, el más frecuente primero. */
    private val foreignNames = HashMap<String, List<String>>(160_000)
    /** Nombre de carta -> legalidad por formato ([Legality]). */
    private val legality = HashMap<String, Int>(40_000)
    /** Símbolos de edición de referencia; null con un catálogo antiguo que no los trae. */
    val symbols: SetSymbols?

    init {
        val file = ensureCopied(context)
        db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

        val entries = ArrayList<Pair<String, String>>(200_000)
        val langLists = HashMap<String, List<String>>() // "es,pt" -> [es, pt], compartidas
        db.rawQuery("SELECT norm, name, lang FROM names", null).use { c ->
            while (c.moveToNext()) {
                entries.add(c.getString(0) to c.getString(1))
                val lang = c.getString(2)
                if (lang != "en") foreignNames[c.getString(0)] = langLists.getOrPut(lang) { lang.split(",") }
            }
        }
        names = NameMatcher(entries)

        val loaded = HashMap<String, SetInfo>()
        val langSets = HashMap<String, Set<String>>() // "fr,de" -> {fr, de}, compartidos
        db.rawQuery("SELECT code, name, set_type, printed_size, langs FROM sets", null).use { c ->
            while (c.moveToNext()) {
                val langs = c.getString(4)
                loaded[c.getString(0)] = SetInfo(c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getInt(3),
                    langSets.getOrPut(langs) { langs.split(",").filter { it.isNotEmpty() }.toSet() })
            }
        }
        sets = loaded
        setCodes = loaded.keys

        db.rawQuery("SELECT id, thumb FROM arts", null).use { c ->
            while (c.moveToNext()) arts[c.getInt(0)] = ArtSignature.fromPacked(c.getBlob(1))
        }
        runCatching {
            db.rawQuery("SELECT name, bits FROM legality", null).use { c ->
                while (c.moveToNext()) legality[c.getString(0)] = c.getInt(1)
            }
        }

        symbols = runCatching {
            val refs = HashMap<String, SymbolRef>()
            db.rawQuery("SELECT set_code, rarity, style, w, h, rgb FROM symbols", null).use { c ->
                while (c.moveToNext()) {
                    refs[SetSymbols.key(c.getString(0), c.getString(1), c.getString(2))] =
                        SymbolRef.fromBytes(c.getInt(3), c.getInt(4), c.getBlob(5))
                }
            }
            val icons = HashMap<String, String>()
            val none = HashSet<String>()
            val other = HashSet<String>()
            db.rawQuery("SELECT set_code, icon, kind FROM set_icons", null).use { c ->
                while (c.moveToNext()) {
                    val set = c.getString(0)
                    if (!c.isNull(1)) icons[set] = c.getString(1)
                    when (c.getString(2)) { "none" -> none += set; "other" -> other += set }
                }
            }
            SetSymbols(refs, none, other, icons)
        }.getOrNull()
    }

    /** Huella de una ilustración, para elegir edición por la imagen. */
    fun art(id: Int): ArtSignature? = arts[id]

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
        val full = db.rawQuery("SELECT DISTINCT name FROM cards WHERE name >= ? AND name < ? LIMIT 1",
            arrayOf(front, front + Char(0xFFFF))).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return full?.let(::printingsOf).orEmpty()
    }

    private fun query(where: String, vararg args: String): List<CardPrinting> =
        db.rawQuery("SELECT $COLUMNS FROM cards $where", args).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    private fun read(c: Cursor) = CardPrinting(
        id = c.getString(0),
        name = c.getString(1),
        setCode = c.getString(2),
        setName = setName(c.getString(2)),
        setType = sets[c.getString(2)]?.type,
        setSize = sets[c.getString(2)]?.size,
        collectorNumber = c.getString(3),
        numberNorm = c.getString(4),
        rarity = c.getString(5),
        releasedAt = c.getString(6),
        finishes = c.getInt(7),
        promo = c.getInt(8) != 0,
        lang = c.getString(9),
        prices = Prices(c.doubleOrNull(10), c.doubleOrNull(11), c.doubleOrNull(12), c.doubleOrNull(13)),
        artId = if (c.isNull(14)) null else c.getInt(14),
        colors = c.getString(15),
        style = c.getString(16),
        cardmarketId = if (c.isNull(17)) null else c.getInt(17),
        border = c.getString(18).firstOrNull() ?: 'b',
        legality = legality[c.getString(1)] ?: 0,
    )

    private fun Cursor.doubleOrNull(i: Int) = if (isNull(i)) null else getDouble(i)

    companion object {
        private const val ASSET = "catalog.sqlite"
        private const val COLUMNS = "id, name, set_code, collector_number, number_norm, rarity, " +
            "released_at, finishes, promo, lang, eur, eur_foil, usd, usd_foil, art_id, colors, style, cm_id, border"

        private fun ensureCopied(context: Context): File {
            val file = File(context.filesDir, ASSET)
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)
            if (!file.exists() || prefs.getLong("stamp", -1) != stamp) {
                val tmp = File(context.filesDir, "$ASSET.tmp")
                context.assets.open(ASSET).use { input ->
                    tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
                tmp.renameTo(file)
                prefs.edit().putLong("stamp", stamp).apply()
            }
            return file
        }
    }
}
