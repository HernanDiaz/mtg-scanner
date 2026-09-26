package com.hernan.mtgscanner.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import com.hernan.mtgscanner.scan.ArtSignature
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

    init {
        val file = ensureCopied(context)
        db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

        val entries = ArrayList<Pair<String, String>>(40_000)
        db.rawQuery("SELECT norm, name FROM names", null).use { c ->
            while (c.moveToNext()) entries.add(c.getString(0) to c.getString(1))
        }
        names = NameMatcher(entries)

        val loaded = HashMap<String, SetInfo>()
        db.rawQuery("SELECT code, name, set_type, printed_size FROM sets", null).use { c ->
            while (c.moveToNext()) {
                loaded[c.getString(0)] = SetInfo(c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getInt(3))
            }
        }
        sets = loaded
        setCodes = loaded.keys

        db.rawQuery("SELECT id, thumb FROM arts", null).use { c ->
            while (c.moveToNext()) arts[c.getInt(0)] = ArtSignature.fromPacked(c.getBlob(1))
        }
    }

    /** Huella de una ilustración, para elegir edición por la imagen. */
    fun art(id: Int): ArtSignature? = arts[id]

    private data class SetInfo(val name: String, val type: String?, val size: Int?)

    /** Todas las impresiones de un nombre, de la más reciente a la más antigua. */
    fun printingsOf(name: String): List<CardPrinting> = printingsCache.getOrPut(name) {
        query("WHERE name = ? ORDER BY released_at DESC, promo ASC", name)
    }

    fun byId(id: String): CardPrinting? = query("WHERE id = ?", id).firstOrNull()

    fun bySetAndNumber(setCode: String, numberNorm: String): CardPrinting? =
        query("WHERE set_code = ? AND number_norm = ?", setCode, numberNorm).firstOrNull()

    fun setName(code: String) = sets[code]?.name ?: code.uppercase()

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
    )

    private fun Cursor.doubleOrNull(i: Int) = if (isNull(i)) null else getDouble(i)

    companion object {
        private const val ASSET = "catalog.sqlite"
        private const val COLUMNS = "id, name, set_code, collector_number, number_norm, rarity, " +
            "released_at, finishes, promo, lang, eur, eur_foil, usd, usd_foil, art_id, colors"

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
