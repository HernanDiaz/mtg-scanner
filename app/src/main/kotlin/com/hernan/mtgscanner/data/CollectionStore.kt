package com.hernan.mtgscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * La colección del usuario. Cada escaneo es una fila en `entries`; los
 * precios se guardan aparte (uno por impresión) para poder actualizarlos.
 */
class CollectionStore(context: Context, dbName: String = "collection.db") :
    SQLiteOpenHelper(context, dbName, null, 3) {

    /** Se incrementa en cada cambio para que la UI vuelva a consultar. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    data class Summary(val cards: Int, val total: Double, val pendingReview: Int)

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
                art BLOB
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: idioma de cada carta; las ya escaneadas quedan en inglés.
        if (oldVersion < 2) db.execSQL("ALTER TABLE entries ADD COLUMN lang TEXT NOT NULL DEFAULT 'en'")
        // v3: miniatura de la ilustración fotografiada.
        if (oldVersion < 3) db.execSQL("ALTER TABLE entries ADD COLUMN art BLOB")
    }

    fun add(
        card: CardPrinting, foil: Boolean, condition: String, needsReview: Boolean,
        lang: String = "en", art: ByteArray? = null,
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

    private fun Group.whereArgs() = arrayOf(cardId, if (foil) "1" else "0", condition, lang, if (needsReview) "1" else "0")

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

    fun summary(currency: Currency): Summary =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(${priceExpr(currency)}), 0), SUM(e.needs_review) " +
                "FROM entries e LEFT JOIN prices p ON p.card_id = e.card_id", null
        ).use { c ->
            c.moveToFirst()
            Summary(c.getInt(0), c.getDouble(1), c.getInt(2))
        }

    /**
     * Filas de la colección: copias de la misma impresión, acabado, estado e idioma.
     * Las pendientes de revisar van en filas aparte de las confirmadas, para que una
     * copia nueva sin revisar no vuelva a marcar las que ya se revisaron.
     */
    fun groups(
        currency: Currency,
        query: String = "",
        onlyPending: Boolean = false,
        sort: Sort = Sort.RECENT,
    ): List<Group> {
        val price = priceExpr(currency)
        val order = when (sort) {
            Sort.RECENT -> "e.needs_review DESC, MAX(e.id) DESC"
            Sort.VALUE -> "COUNT(*) * COALESCE($price, 0) DESC"
            Sort.NAME -> "e.name COLLATE NOCASE, e.set_code"
        }
        // Con un único agregado MAX(), SQLite toma el resto de columnas de esa fila:
        // la miniatura es la del último escaneo.
        return readableDatabase.rawQuery(
            """
            SELECT e.card_id, e.name, e.set_code, e.collector_number, e.foil, e.condition,
                   COUNT(*), e.needs_review, $price, MAX(e.id), e.lang, e.art
            FROM entries e LEFT JOIN prices p ON p.card_id = e.card_id
            WHERE (e.name LIKE ? OR e.set_code LIKE ?) AND (? = '0' OR e.needs_review = 1)
            GROUP BY e.card_id, e.foil, e.condition, e.lang, e.needs_review
            ORDER BY $order
            """,
            arrayOf("%${query.trim()}%", query.trim(), if (onlyPending) "1" else "0"),
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
                ))
            }
        }
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
        private const val GROUP_WHERE = "card_id = ? AND foil = ? AND condition = ? AND lang = ? AND needs_review = ?"

        /** Mismo criterio que [Prices.of]: si falta el precio del acabado, se usa el otro. */
        private fun priceExpr(currency: Currency) = when (currency) {
            Currency.EUR -> "CASE WHEN e.foil = 1 THEN COALESCE(p.eur_foil, p.eur) ELSE COALESCE(p.eur, p.eur_foil) END"
            Currency.USD -> "CASE WHEN e.foil = 1 THEN COALESCE(p.usd_foil, p.usd) ELSE COALESCE(p.usd, p.usd_foil) END"
        }
    }
}
