package com.hernan.mtgscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Qué parte de la colección se está viendo (y se exporta). */
data class CollectionFilter(
    val query: String = "",
    val onlyPending: Boolean = false,
    /** null = toda la colección; [CollectionStore.NO_DECK] = cartas sin mazo; si no, ese mazo. */
    val deckId: Long? = null,
    /** Colores "WUBRG" y "C" (incolora): se muestran las cartas de cualquiera de ellos. */
    val colors: Set<Char> = emptySet(),
)

/**
 * La colección del usuario. Cada escaneo es una fila en `entries` (una carta
 * física); los precios se guardan aparte (uno por impresión) para actualizarlos.
 * Cada carta física puede estar en un mazo (`decks`) o en ninguno.
 */
class CollectionStore(context: Context, dbName: String = "collection.db") :
    SQLiteOpenHelper(context, dbName, null, 4) {

    /** Se incrementa en cada cambio para que la UI vuelva a consultar. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    data class Summary(val cards: Int, val total: Double, val pendingReview: Int)

    data class Deck(val id: Long, val name: String, val cards: Int)

    enum class Sort { RECENT, VALUE, NAME }

    data class Group(
        val cardId: String,
        val name: String,
        val setCode: String,
        val collectorNumber: String,
        val foil: Boolean,
        val condition: String,
        /** Idioma de la carta física: "en", "es". */
        val lang: String,
        val quantity: Int,
        val needsReview: Boolean,
        val unitPrice: Double?,
        val lastEntryId: Long,
        /** Miniatura de la ilustración de la foto (la del último escaneo), para ordenar ediciones por parecido. */
        val art: ByteArray? = null,
        val deckId: Long? = null,
        val colors: String = "",
    ) {
        val smallImageUrl get() = "https://cards.scryfall.io/small/front/${cardId[0]}/${cardId[1]}/$cardId.jpg"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                card_id TEXT NOT NULL,
                name TEXT NOT NULL,
                set_code TEXT NOT NULL,
                collector_number TEXT NOT NULL,
                foil INTEGER NOT NULL,
                condition TEXT NOT NULL,
                lang TEXT NOT NULL DEFAULT 'en',
                needs_review INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                art BLOB,
                deck_id INTEGER,
                colors TEXT
            )
            """
        )
        db.execSQL("CREATE INDEX entries_card ON entries (card_id)")
        db.execSQL(
            """
            CREATE TABLE prices (
                card_id TEXT PRIMARY KEY,
                eur REAL, eur_foil REAL, usd REAL, usd_foil REAL,
                updated_at INTEGER NOT NULL
            )
            """
        )
        createDecksTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: idioma de cada carta; las ya escaneadas quedan en inglés.
        if (oldVersion < 2) db.execSQL("ALTER TABLE entries ADD COLUMN lang TEXT NOT NULL DEFAULT 'en'")
        // v3: miniatura de la ilustración fotografiada.
        if (oldVersion < 3) db.execSQL("ALTER TABLE entries ADD COLUMN art BLOB")
        // v4: mazos y colores (los colores de las cartas ya escaneadas los rellena backfillColors).
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE entries ADD COLUMN deck_id INTEGER")
            db.execSQL("ALTER TABLE entries ADD COLUMN colors TEXT")
            createDecksTable(db)
        }
    }

    private fun createDecksTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE decks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
    }

    // ------------------------------------------------------------------ cartas

    fun add(
        card: CardPrinting, foil: Boolean, condition: String, needsReview: Boolean,
        lang: String = "en", art: ByteArray? = null, deckId: Long? = null,
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("entries", null, ContentValues().apply {
                put("card_id", card.id)
                put("name", card.name)
                put("set_code", card.setCode)
                put("collector_number", card.collectorNumber)
                put("foil", if (foil) 1 else 0)
                put("condition", condition)
                put("lang", lang)
                put("needs_review", if (needsReview) 1 else 0)
                put("added_at", System.currentTimeMillis())
                put("art", art)
                put("deck_id", deckId)
                put("colors", card.colors)
            })
            seedPrice(db, card)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
            changed()
        }
    }

    fun delete(entryId: Long) {
        writableDatabase.delete("entries", "id = ?", arrayOf(entryId.toString()))
        changed()
    }

    /** Cambia la impresión de todas las entradas de un grupo y las da por revisadas. */
    fun reassign(group: Group, card: CardPrinting) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("entries", ContentValues().apply {
                put("card_id", card.id)
                put("name", card.name)
                put("set_code", card.setCode)
                put("collector_number", card.collectorNumber)
                put("colors", card.colors)
                put("needs_review", 0)
            }, GROUP_WHERE, group.whereArgs())
            seedPrice(db, card)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    /** Da por buena la edición sugerida de un grupo. */
    fun confirm(group: Group) {
        writableDatabase.update("entries", ContentValues().apply { put("needs_review", 0) }, GROUP_WHERE, group.whereArgs())
        changed()
    }

    /** Cambia el idioma de todas las entradas de un grupo. */
    fun setLanguage(group: Group, lang: String) {
        writableDatabase.update("entries", ContentValues().apply { put("lang", lang) }, GROUP_WHERE, group.whereArgs())
        changed()
    }

    /** Mueve [count] copias de un grupo (las últimas escaneadas) a un mazo, o a ninguno si [deckId] es null. */
    fun moveToDeck(group: Group, deckId: Long?, count: Int) {
        writableDatabase.execSQL(
            "UPDATE entries SET deck_id = ? WHERE id IN (SELECT id FROM entries WHERE $GROUP_WHERE ORDER BY id DESC LIMIT ?)",
            arrayOf<Any?>(deckId, *group.whereArgs(), count),
        )
        changed()
    }

    private fun Group.whereArgs() = arrayOf(
        cardId, if (foil) "1" else "0", condition, lang, if (needsReview) "1" else "0", (deckId ?: NO_DECK).toString(),
    )

    /**
     * Rellena los colores de las cartas escaneadas antes de que se guardaran
     * (versión 3 o anterior). [colorsOf] los busca en el catálogo.
     */
    fun backfillColors(colorsOf: (cardId: String) -> String?) {
        val ids = readableDatabase.rawQuery("SELECT DISTINCT card_id FROM entries WHERE colors IS NULL", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                val colors = colorsOf(id) ?: continue
                db.update("entries", ContentValues().apply { put("colors", colors) }, "card_id = ? AND colors IS NULL", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    // ------------------------------------------------------------------ mazos

    fun decks(): List<Deck> =
        readableDatabase.rawQuery(
            "SELECT d.id, d.name, COUNT(e.id) FROM decks d LEFT JOIN entries e ON e.deck_id = d.id " +
                "GROUP BY d.id ORDER BY d.name COLLATE NOCASE", null
        ).use { c -> buildList { while (c.moveToNext()) add(Deck(c.getLong(0), c.getString(1), c.getInt(2))) } }

    fun createDeck(name: String): Long =
        writableDatabase.insertOrThrow("decks", null, ContentValues().apply { put("name", name.trim()) }).also { changed() }

    fun renameDeck(id: Long, name: String) {
        writableDatabase.update("decks", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    /** Borra el mazo; sus cartas no se borran, vuelven a la colección sin mazo. */
    fun deleteDeck(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE entries SET deck_id = NULL WHERE deck_id = ?", arrayOf<Any>(id))
            db.delete("decks", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    // ------------------------------------------------------------------ precios

    fun distinctCardIds(): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT card_id FROM entries", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun updatePrices(prices: Map<String, Prices>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for ((id, p) in prices) db.insertWithOnConflict("prices", null, p.toValues(id, now),
                SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    /** Fecha (ms) del precio más antiguo de la colección, o null si está vacía. */
    fun oldestPriceUpdate(): Long? =
        readableDatabase.rawQuery(
            "SELECT MIN(p.updated_at) FROM prices p WHERE p.card_id IN (SELECT card_id FROM entries)", null
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    // ------------------------------------------------------------------ consultas

    /** Totales de la parte de la colección que se está viendo. */
    fun summary(currency: Currency, filter: CollectionFilter = CollectionFilter()): Summary {
        val (where, args) = filterSql(filter)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(${priceExpr(currency)}), 0), COALESCE(SUM(e.needs_review), 0) " +
                "FROM entries e LEFT JOIN prices p ON p.card_id = e.card_id WHERE $where", args
        ).use { c ->
            c.moveToFirst()
            Summary(c.getInt(0), c.getDouble(1), c.getInt(2))
        }
    }

    /**
     * Filas de la colección: copias de la misma impresión, acabado, estado, idioma
     * y mazo. Las pendientes de revisar van en filas aparte de las confirmadas, para
     * que una copia nueva sin revisar no vuelva a marcar las que ya se revisaron.
     */
    fun groups(currency: Currency, filter: CollectionFilter = CollectionFilter(), sort: Sort = Sort.RECENT): List<Group> {
        val price = priceExpr(currency)
        val order = when (sort) {
            Sort.RECENT -> "e.needs_review DESC, MAX(e.id) DESC"
            Sort.VALUE -> "COUNT(*) * COALESCE($price, 0) DESC"
            Sort.NAME -> "e.name COLLATE NOCASE, e.set_code"
        }
        val (where, args) = filterSql(filter)
        // Con un único agregado MAX(), SQLite toma el resto de columnas de esa fila:
        // la miniatura es la del último escaneo.
        return readableDatabase.rawQuery(
            """
            SELECT e.card_id, e.name, e.set_code, e.collector_number, e.foil, e.condition,
                   COUNT(*), e.needs_review, $price, MAX(e.id), e.lang, e.art, e.deck_id, e.colors
            FROM entries e LEFT JOIN prices p ON p.card_id = e.card_id
            WHERE $where
            GROUP BY e.card_id, e.foil, e.condition, e.lang, e.needs_review, COALESCE(e.deck_id, 0)
            ORDER BY $order
            """,
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Group(
                    cardId = c.getString(0),
                    name = c.getString(1),
                    setCode = c.getString(2),
                    collectorNumber = c.getString(3),
                    foil = c.getInt(4) != 0,
                    condition = c.getString(5),
                    lang = c.getString(10),
                    quantity = c.getInt(6),
                    needsReview = c.getInt(7) != 0,
                    unitPrice = if (c.isNull(8)) null else c.getDouble(8),
                    lastEntryId = c.getLong(9),
                    art = if (c.isNull(11)) null else c.getBlob(11),
                    deckId = if (c.isNull(12)) null else c.getLong(12),
                    colors = c.getString(13).orEmpty(),
                ))
            }
        }
    }

    /** Condición SQL (sobre `entries e`) y sus argumentos para un filtro. */
    private fun filterSql(f: CollectionFilter): Pair<String, Array<String>> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (f.query.isNotBlank()) {
            parts += "(e.name LIKE ? OR e.set_code LIKE ?)"
            args += "%${f.query.trim()}%"; args += f.query.trim()
        }
        if (f.onlyPending) parts += "e.needs_review = 1"
        f.deckId?.let { parts += "COALESCE(e.deck_id, 0) = CAST(? AS INTEGER)"; args += it.toString() }
        if (f.colors.isNotEmpty()) {
            val any = f.colors.filter { it in "WUBRGC" }.map { c ->
                if (c == 'C') "COALESCE(e.colors, '') = ''" else "e.colors LIKE '%$c%'"
            }
            parts += any.joinToString(" OR ", "(", ")")
        }
        return (parts.joinToString(" AND ").ifEmpty { "1" }) to args.toTypedArray()
    }

    private fun seedPrice(db: SQLiteDatabase, card: CardPrinting) {
        // Precio del catálogo como punto de partida; la actualización online lo sustituye.
        db.insertWithOnConflict("prices", null, card.prices.toValues(card.id, 0),
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun Prices.toValues(id: String, updatedAt: Long) = ContentValues().apply {
        put("card_id", id)
        put("eur", eur); put("eur_foil", eurFoil); put("usd", usd); put("usd_foil", usdFoil)
        put("updated_at", updatedAt)
    }

    private fun changed() {
        _version.value++
    }

    companion object {
        /** Valor de [CollectionFilter.deckId] para "cartas que no están en ningún mazo". */
        const val NO_DECK = 0L

        private const val GROUP_WHERE =
            "card_id = ? AND foil = ? AND condition = ? AND lang = ? AND needs_review = ? AND COALESCE(deck_id, 0) = CAST(? AS INTEGER)"

        /** Mismo criterio que [Prices.of]: si falta el precio del acabado, se usa el otro. */
        private fun priceExpr(currency: Currency) = when (currency) {
            Currency.EUR -> "CASE WHEN e.foil = 1 THEN COALESCE(p.eur_foil, p.eur) ELSE COALESCE(p.eur, p.eur_foil) END"
            Currency.USD -> "CASE WHEN e.foil = 1 THEN COALESCE(p.usd_foil, p.usd) ELSE COALESCE(p.usd, p.usd_foil) END"
        }
    }
}
