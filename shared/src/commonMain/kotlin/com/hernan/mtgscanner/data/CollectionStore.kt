package com.hernan.mtgscanner.data

import kotlin.concurrent.Volatile
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
    /** Solo cartas que se pueden jugar en este formato ([Legality.FORMATS]); null = todas. */
    val format: String? = null,
)

/**
 * La colección del usuario. Cada escaneo es una fila en `entries` (una carta
 * física); los precios se guardan aparte (uno por impresión) para actualizarlos.
 * Cada carta física puede estar en un mazo (`decks`) o en ninguno.
 */
class CollectionStore(private val db: Db) : AutoCloseable {

    init {
        db.migrate(VERSION, ::onCreate, ::onUpgrade)
    }

    // Los mismos nombres que en SQLiteOpenHelper: es una única conexión.
    /** Como en SQLiteOpenHelper (las pruebas lo usan para preparar datos); es una única conexión. */
    val writableDatabase get() = db
    private val readableDatabase get() = db

    override fun close() = db.close()

    /**
     * Porcentaje del precio según el estado ("EX" -> 85); null = el precio tal cual.
     * Lo fija la app según los ajustes; afecta al valor de la colección, no a los
     * precios por carta guardados.
     */
    @Volatile
    var conditionFactors: Map<String, Int>? = null
        set(value) {
            if (field != value) { field = value; changed() }
        }

    /** Se incrementa en cada cambio para que la UI vuelva a consultar. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    data class Summary(val cards: Int, val total: Double, val pendingReview: Int)

    data class Deck(val id: Long, val name: String, val cards: Int)

    enum class Sort { RECENT, VALUE, NAME }

    private fun onCreate(db: Db) {
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
                colors TEXT,
                legal INTEGER
            )
            """
        )
        db.execSQL("CREATE INDEX entries_card ON entries (card_id)")
        db.execSQL(
            """
            CREATE TABLE prices (
                card_id TEXT PRIMARY KEY,
                eur REAL, eur_foil REAL, usd REAL, usd_foil REAL,
                updated_at INTEGER NOT NULL,
                cm_id INTEGER,
                eur_low REAL, eur_low_foil REAL, eur_avg30 REAL, eur_avg30_foil REAL
            )
            """
        )
        createDecksTable(db)
        createWishlistTable(db)
        createHistoryTables(db)
    }

    private fun onUpgrade(db: Db, oldVersion: Int, @Suppress("UNUSED_PARAMETER") newVersion: Int) {
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
        // v5: precios de la guía de Cardmarket (el id lo rellena backfillCardmarketIds).
        if (oldVersion < 5) {
            for (col in listOf("cm_id INTEGER", "eur_low REAL", "eur_low_foil REAL", "eur_avg30 REAL", "eur_avg30_foil REAL")) {
                db.execSQL("ALTER TABLE prices ADD COLUMN $col")
            }
        }
        // v6: lista de deseos.
        if (oldVersion < 6) createWishlistTable(db)
        // v7: evolución del valor y de los precios.
        if (oldVersion < 7) createHistoryTables(db)
        // v8: legalidad por formato (la rellena updateLegality).
        if (oldVersion < 8) db.execSQL("ALTER TABLE entries ADD COLUMN legal INTEGER")
    }

    /**
     * Cartas que se quieren: por nombre (cualquier edición vale). [card_id] es la
     * impresión con que se muestra y se calcula el precio (la más común).
     */
    private fun createWishlistTable(db: Db) {
        db.execSQL(
            """
            CREATE TABLE wishlist (
                name TEXT PRIMARY KEY,
                card_id TEXT NOT NULL,
                set_code TEXT NOT NULL,
                collector_number TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                added_at INTEGER NOT NULL
            )
            """
        )
    }

    /**
     * Un registro por día: el valor de toda la colección con cada precio, y los
     * precios de cada carta (para ver qué ha subido o bajado).
     */
    private fun createHistoryTables(db: Db) {
        db.execSQL(
            """
            CREATE TABLE value_history (
                day TEXT PRIMARY KEY,        -- yyyy-MM-dd
                cards INTEGER NOT NULL,
                eur_trend REAL, eur_low REAL, eur_avg30 REAL, usd REAL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE price_history (
                card_id TEXT NOT NULL,
                day TEXT NOT NULL,
                eur REAL, eur_foil REAL, eur_low REAL, eur_low_foil REAL,
                eur_avg30 REAL, eur_avg30_foil REAL, usd REAL, usd_foil REAL,
                PRIMARY KEY (card_id, day)
            ) WITHOUT ROWID
            """
        )
    }

    private fun createDecksTable(db: Db) {
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
            val id = insertEntry(db, card, foil, condition, needsReview, lang, art, deckId)
            seedPrice(db, card)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
            changed()
        }
    }

    private fun insertEntry(
        db: Db, card: CardPrinting, foil: Boolean, condition: String, needsReview: Boolean,
        lang: String, art: ByteArray?, deckId: Long?,
    ): Long = db.insertOrThrow("entries", null, ContentValues().apply {
        put("card_id", card.id)
        put("name", card.name)
        put("set_code", card.setCode)
        put("collector_number", card.collectorNumber)
        put("foil", if (foil) 1 else 0)
        put("condition", condition)
        put("lang", lang)
        put("needs_review", if (needsReview) 1 else 0)
        put("added_at", nowMillis())
        put("art", art)
        put("deck_id", deckId)
        put("colors", card.colors)
        put("legal", card.legality)
    })

    /**
     * Guarda cartas importadas de un fichero, todo o nada. Con [replace] antes se
     * borra la colección entera (cartas y mazos). Los mazos del fichero se reutilizan
     * si ya hay uno con ese nombre y si no se crean; las cartas sin mazo van a [defaultDeck].
     * Devuelve cuántas cartas se han guardado.
     */
    fun import(items: List<CollectionImport.Item>, replace: Boolean, defaultDeck: Long? = null): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replace) {
                db.delete("entries", null, null)
                db.delete("decks", null, null)
            }
            val deckIds = HashMap<String, Long>()
            db.rawQuery("SELECT id, name FROM decks", null).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(1).trim().lowercase()
                    if (key !in deckIds) deckIds[key] = c.getLong(0)
                }
            }
            var count = 0
            for (item in items) {
                val deck = item.deck?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    deckIds.getOrPut(name.lowercase()) {
                        db.insertOrThrow("decks", null, ContentValues().apply { put("name", name) })
                    }
                } ?: defaultDeck.takeUnless { replace }
                repeat(item.quantity) {
                    insertEntry(db, item.card, item.foil, item.condition, item.needsReview, item.lang, null, deck)
                }
                seedPrice(db, item.card)
                count += item.quantity
            }
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
            changed()
        }
    }

    /** Cómo está ahora una entrada (fotos de prueba: la edición corregida es la buena). */
    data class EntryNow(
        val cardId: String, val name: String, val setCode: String, val collectorNumber: String,
        val lang: String, val needsReview: Boolean,
    )

    fun entriesNow(ids: Collection<Long>): Map<Long, EntryNow> = buildMap {
        ids.chunked(500).forEach { chunk ->
            readableDatabase.rawQuery(
                "SELECT id, card_id, name, set_code, collector_number, lang, needs_review FROM entries " +
                    "WHERE id IN (${chunk.joinToString(",")})", null
            ).use { c ->
                while (c.moveToNext()) {
                    put(c.getLong(0), EntryNow(c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                        c.getString(5), c.getInt(6) != 0))
                }
            }
        }
    }

    /**
     * Borra todas las copias de estas filas. Devuelve lo borrado, tal cual, para
     * poder deshacerlo con [restore].
     */
    fun deleteGroups(groups: Collection<CollectionGroup>): List<ContentValues> {
        val db = writableDatabase
        val deleted = ArrayList<ContentValues>()
        db.beginTransaction()
        try {
            for (g in groups) {
                db.query("entries", null, GROUP_WHERE, g.whereArgs(), null, null, null).use { c ->
                    while (c.moveToNext()) deleted.add(c.rowValues())
                }
                db.delete("entries", GROUP_WHERE, g.whereArgs())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
        return deleted
    }

    /** Vuelve a poner lo que borró [deleteGroups] (con los mismos ids: las fotos de prueba siguen enlazadas). */
    fun restore(rows: List<ContentValues>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { db.insertWithOnConflict("entries", null, it, Db.CONFLICT_IGNORE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    /** Mueve todas las copias de estas filas a un mazo (o las saca del suyo). */
    fun moveGroupsToDeck(groups: Collection<CollectionGroup>, deckId: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { if (deckId == null) putNull("deck_id") else put("deck_id", deckId) }
            groups.forEach { db.update("entries", values, GROUP_WHERE, it.whereArgs()) }
            db.setTransactionSuccessful()
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
    fun reassign(group: CollectionGroup, card: CardPrinting) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("entries", ContentValues().apply {
                put("card_id", card.id)
                put("name", card.name)
                put("set_code", card.setCode)
                put("collector_number", card.collectorNumber)
                put("colors", card.colors)
                put("legal", card.legality)
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
    fun confirm(group: CollectionGroup) {
        writableDatabase.update("entries", ContentValues().apply { put("needs_review", 0) }, GROUP_WHERE, group.whereArgs())
        changed()
    }

    /** Cambia el estado (NM, EX...) de todas las entradas de un grupo. */
    fun setCondition(group: CollectionGroup, condition: String) {
        writableDatabase.update("entries", ContentValues().apply { put("condition", condition) }, GROUP_WHERE, group.whereArgs())
        changed()
    }

    /** Marca o desmarca como foil todas las entradas de un grupo. */
    fun setFoil(group: CollectionGroup, foil: Boolean) {
        writableDatabase.update("entries", ContentValues().apply { put("foil", if (foil) 1 else 0) }, GROUP_WHERE, group.whereArgs())
        changed()
    }

    /** Copias de una carta en la colección, contando todas sus ediciones. */
    fun copiesOf(name: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM entries WHERE name = ?", arrayOf(name)).use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    /** Todos los precios guardados de una impresión (null si aún no hay). */
    fun storedPrices(cardId: String): StoredPrices? =
        readableDatabase.rawQuery(
            "SELECT eur, eur_foil, eur_low, eur_low_foil, eur_avg30, eur_avg30_foil, usd, usd_foil, updated_at " +
                "FROM prices WHERE card_id = ?", arrayOf(cardId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            fun d(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
            StoredPrices(d(0), d(1), d(2), d(3), d(4), d(5), d(6), d(7), c.getLong(8))
        }

    data class StoredPrices(
        val eur: Double?, val eurFoil: Double?,
        val eurLow: Double?, val eurLowFoil: Double?,
        val eurAvg30: Double?, val eurAvg30Foil: Double?,
        val usd: Double?, val usdFoil: Double?,
        /** 0 = precio del catálogo (sin actualizar todavía). */
        val updatedAt: Long,
    )

    /** Cambia el idioma de todas las entradas de un grupo. */
    fun setLanguage(group: CollectionGroup, lang: String) {
        writableDatabase.update("entries", ContentValues().apply { put("lang", lang) }, GROUP_WHERE, group.whereArgs())
        changed()
    }

    /** Mueve [count] copias de un grupo (las últimas escaneadas) a un mazo, o a ninguno si [deckId] es null. */
    fun moveToDeck(group: CollectionGroup, deckId: Long?, count: Int) {
        writableDatabase.execSQL(
            "UPDATE entries SET deck_id = ? WHERE id IN (SELECT id FROM entries WHERE $GROUP_WHERE ORDER BY id DESC LIMIT ?)",
            arrayOf<Any?>(deckId, *group.whereArgs(), count),
        )
        changed()
    }

    /** Las copias (entradas) de una fila. */
    fun entryIds(group: CollectionGroup): List<Long> =
        readableDatabase.rawQuery("SELECT id FROM entries WHERE $GROUP_WHERE", group.whereArgs()).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

    /** Todas las copias, para saber qué fotos siguen teniendo carta. */
    fun allEntryIds(): Set<Long> =
        readableDatabase.rawQuery("SELECT id FROM entries", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }


    fun entryKeys(): List<EntryKey> =
        readableDatabase.rawQuery("SELECT id, card_id, foil, condition, lang, deck_id, needs_review FROM entries", null).use { c ->
            buildList {
                while (c.moveToNext()) add(EntryKey(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getString(3),
                    c.getString(4), if (c.isNull(5)) null else c.getLong(5), c.getInt(6) != 0))
            }
        }

    private fun CollectionGroup.whereArgs() = arrayOf(
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

    /**
     * Pone al día la legalidad de las cartas guardadas: las prohibiciones cambian y
     * el catálogo nuevo llega con cada versión de la app. [legalityOf] la busca en el catálogo.
     */
    fun updateLegality(legalityOf: (name: String) -> Int?) {
        val names = readableDatabase.rawQuery("SELECT DISTINCT name, legal FROM entries", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to if (c.isNull(1)) null else c.getInt(1)) }
        }
        val changes = names.mapNotNull { (name, old) -> legalityOf(name)?.takeIf { it != old }?.let { name to it } }
        if (changes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((name, bits) in changes) {
                db.update("entries", ContentValues().apply { put("legal", bits) }, "name = ?", arrayOf(name))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    /**
     * Para cada formato, cuántas cartas de las que se ven (un mazo, normalmente) no se
     * pueden jugar en él: prohibidas, no legales, o restringidas con más de una copia.
     */
    fun formatProblems(filter: CollectionFilter): Map<String, Int> {
        val (where, args) = filterSql(filter.copy(format = null))
        val rows = readableDatabase.rawQuery(
            "SELECT e.name, COALESCE(e.legal, 0), COUNT(*) FROM entries e WHERE $where GROUP BY e.name", args
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getInt(1), c.getInt(2))) } }
        return Legality.FORMATS.associateWith { f ->
            rows.sumOf { (_, bits, n) ->
                when (Legality.status(bits, f)) {
                    Legality.Status.LEGAL -> 0
                    Legality.Status.RESTRICTED -> n - 1
                    else -> n
                }
            }
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

    /** Rellena el producto de Cardmarket de las cartas guardadas antes de la versión 5. */
    fun backfillCardmarketIds(idOf: (cardId: String) -> Int?) {
        val ids = readableDatabase.rawQuery("SELECT card_id FROM prices WHERE cm_id IS NULL", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                val cm = idOf(id) ?: continue
                db.update("prices", ContentValues().apply { put("cm_id", cm) }, "card_id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Productos de Cardmarket de las cartas de la colección. */
    fun cardmarketIds(): Set<Int> =
        readableDatabase.rawQuery(
            "SELECT DISTINCT cm_id FROM prices WHERE cm_id IS NOT NULL AND " +
                "card_id IN (SELECT card_id FROM entries UNION SELECT card_id FROM wishlist)", null
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getInt(0)) } }

    /** Guarda los precios de la guía de Cardmarket (por producto). */
    fun updateCardmarketPrices(prices: Map<Int, CardmarketGuide>) {
        val db = writableDatabase
        val now = nowMillis()
        db.beginTransaction()
        try {
            for ((cm, g) in prices) {
                db.update("prices", ContentValues().apply {
                    // La tendencia es la misma que da Scryfall, pero de hoy.
                    g.trend?.let { put("eur", it) }
                    g.trendFoil?.let { put("eur_foil", it) }
                    put("eur_low", g.low); put("eur_low_foil", g.lowFoil)
                    put("eur_avg30", g.avg30); put("eur_avg30_foil", g.avg30Foil)
                    put("updated_at", now)
                }, "cm_id = ?", arrayOf(cm.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    /** Impresiones cuyos precios hay que actualizar: las de la colección y las de la lista de deseos. */
    fun distinctCardIds(): List<String> =
        readableDatabase.rawQuery("SELECT card_id FROM entries UNION SELECT card_id FROM wishlist", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    // ------------------------------------------------------------------ evolución

    /** Guarda (o actualiza) el valor de hoy de toda la colección, con cada precio. */
    fun recordValue(day: String) {
        val trend = summary(Currency.EUR)
        val values = ContentValues().apply {
            put("day", day)
            put("cards", trend.cards)
            put("eur_trend", trend.total)
            put("eur_low", summary(Currency.EUR, kind = PriceKind.LOW).total)
            put("eur_avg30", summary(Currency.EUR, kind = PriceKind.AVG30).total)
            put("usd", summary(Currency.USD).total)
        }
        writableDatabase.insertWithOnConflict("value_history", null, values, Db.CONFLICT_REPLACE)
    }

    /** Copia los precios actuales de cada carta como los de [day]. */
    fun recordPrices(day: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO price_history SELECT card_id, ?, eur, eur_foil, eur_low, eur_low_foil, " +
                "eur_avg30, eur_avg30_foil, usd, usd_foil FROM prices WHERE updated_at > 0",
            arrayOf(day),
        )
    }

    data class ValuePoint(val day: String, val cards: Int, val value: Double)

    /** Valor de la colección día a día desde [since] (yyyy-MM-dd; null = desde el principio). */
    fun valueHistory(currency: Currency, kind: PriceKind, since: String? = null): List<ValuePoint> {
        val col = historyColumn(currency, kind, "")
        return readableDatabase.rawQuery(
            "SELECT day, cards, $col FROM value_history WHERE day >= ? AND $col IS NOT NULL ORDER BY day",
            arrayOf(since ?: ""),
        ).use { c -> buildList { while (c.moveToNext()) add(ValuePoint(c.getString(0), c.getInt(1), c.getDouble(2))) } }
    }

    /** Cambio de precio de una carta de la colección entre dos días (por copia y por todas las que se tienen). */
    data class Mover(
        val cardId: String, val name: String, val setCode: String, val foil: Boolean, val quantity: Int,
        val before: Double, val now: Double,
    ) {
        val change get() = now - before
        val smallImageUrl get() = "https://cards.scryfall.io/small/front/${cardId[0]}/${cardId[1]}/$cardId.jpg"
    }

    /**
     * Cartas cuyo precio más ha cambiado desde [since]: se compara el precio actual
     * con el guardado el primer día desde entonces que tenga datos.
     */
    fun movers(currency: Currency, kind: PriceKind, since: String): List<Mover> {
        val now = historyColumn(currency, kind, "p.", foilAware = true)
        val before = historyColumn(currency, kind, "h.", foilAware = true)
        return readableDatabase.rawQuery(
            """
            SELECT e.card_id, e.name, e.set_code, e.foil, COUNT(*), $before, $now
            FROM entries e
            JOIN prices p ON p.card_id = e.card_id
            JOIN price_history h ON h.card_id = e.card_id AND h.day = (
                SELECT MIN(day) FROM price_history WHERE card_id = e.card_id AND day >= ?)
            GROUP BY e.card_id, e.foil
            """,
            arrayOf(since),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.isNull(5) || c.isNull(6)) continue
                    add(Mover(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) != 0, c.getInt(4),
                        c.getDouble(5), c.getDouble(6)))
                }
            }
        }.filter { it.change != 0.0 }
    }

    /** Columna del precio elegido en value_history ([prefix] vacío) o en prices/price_history. */
    private fun historyColumn(currency: Currency, kind: PriceKind, prefix: String, foilAware: Boolean = false): String {
        if (!foilAware) {
            return when {
                currency == Currency.USD -> "usd"
                kind == PriceKind.LOW -> "eur_low"
                kind == PriceKind.AVG30 -> "eur_avg30"
                else -> "eur_trend"
            }
        }
        val base = when {
            currency == Currency.USD -> "usd"
            kind == PriceKind.LOW -> "eur_low"
            kind == PriceKind.AVG30 -> "eur_avg30"
            else -> "eur"
        }
        return "CASE WHEN e.foil = 1 THEN COALESCE($prefix${base}_foil, $prefix$base) ELSE COALESCE($prefix$base, $prefix${base}_foil) END"
    }

    // ------------------------------------------------------------------ lista de deseos

    data class Wish(
        val name: String,
        val cardId: String,
        val setCode: String,
        val collectorNumber: String,
        val quantity: Int,
        val unitPrice: Double?,
    ) {
        val smallImageUrl get() = "https://cards.scryfall.io/small/front/${cardId[0]}/${cardId[1]}/$cardId.jpg"
    }

    fun wishlist(currency: Currency, kind: PriceKind = PriceKind.TREND): List<Wish> {
        // Mismo precio que una copia normal (no foil) en la colección.
        val price = priceExpr(currency, kind).replace("e.foil", "0")
        return readableDatabase.rawQuery(
            "SELECT w.name, w.card_id, w.set_code, w.collector_number, w.quantity, $price " +
                "FROM wishlist w LEFT JOIN prices p ON p.card_id = w.card_id ORDER BY w.name COLLATE NOCASE", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Wish(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4),
                    if (c.isNull(5)) null else c.getDouble(5)))
            }
        }
    }

    /**
     * Quiere tener al menos [quantity] copias más de la carta: si ya estaba en la lista
     * se queda la cantidad mayor (comprobar el mismo mazo dos veces no la duplica).
     */
    fun addWish(card: CardPrinting, quantity: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.rawQuery("SELECT quantity FROM wishlist WHERE name = ?", arrayOf(card.name)).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            db.insertWithOnConflict("wishlist", null, ContentValues().apply {
                put("name", card.name)
                put("card_id", card.id)
                put("set_code", card.setCode)
                put("collector_number", card.collectorNumber)
                put("quantity", maxOf(current, quantity))
                put("added_at", nowMillis())
            }, Db.CONFLICT_REPLACE)
            seedPrice(db, card)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    fun setWishQuantity(name: String, quantity: Int) {
        if (quantity <= 0) {
            writableDatabase.delete("wishlist", "name = ?", arrayOf(name))
        } else {
            writableDatabase.update("wishlist", ContentValues().apply { put("quantity", quantity) }, "name = ?", arrayOf(name))
        }
        changed()
    }

    /** Se ha conseguido una copia: resta una de la lista. Devuelve si la carta estaba en ella. */
    fun fulfillWish(name: String): Boolean {
        val db = writableDatabase
        val left = db.rawQuery("SELECT quantity FROM wishlist WHERE name = ?", arrayOf(name)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else return false
        }
        setWishQuantity(name, left - 1)
        return true
    }

    /** Deshacer [fulfillWish]: vuelve a sumar una copia (o a añadir la carta). */
    fun unfulfillWish(card: CardPrinting) {
        val current = readableDatabase.rawQuery("SELECT quantity FROM wishlist WHERE name = ?", arrayOf(card.name)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        addWish(card, current + 1)
    }

    /** Copias de cada carta (por nombre) en la colección. */
    fun ownedByName(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT name, COUNT(*) FROM entries GROUP BY name", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    fun updatePrices(prices: Map<String, Prices>) {
        val db = writableDatabase
        val now = nowMillis()
        db.beginTransaction()
        try {
            for ((id, p) in prices) {
                // REPLACE borraría el id y los precios de Cardmarket de la fila.
                if (db.update("prices", p.toUpdateValues(now), "card_id = ?", arrayOf(id)) == 0) {
                    db.insert("prices", null, p.toValues(id, now))
                }
            }
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
    fun summary(currency: Currency, filter: CollectionFilter = CollectionFilter(), kind: PriceKind = PriceKind.TREND): Summary {
        val (where, args) = filterSql(filter)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(${valueExpr(currency, kind)}), 0), COALESCE(SUM(e.needs_review), 0) " +
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
    fun groups(
        currency: Currency, filter: CollectionFilter = CollectionFilter(), sort: Sort = Sort.RECENT,
        kind: PriceKind = PriceKind.TREND,
    ): List<CollectionGroup> {
        val price = valueExpr(currency, kind)
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
                while (c.moveToNext()) add(CollectionGroup(
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

    /** Valor de una copia (`entries e`): su precio y, si se pide, ajustado por su estado. */
    private fun valueExpr(currency: Currency, kind: PriceKind): String {
        val price = priceExpr(currency, kind)
        val factors = conditionFactors ?: return price
        val cases = factors.entries.joinToString(" ") { (c, pct) -> "WHEN '${c.filter(Char::isLetter)}' THEN ${pct / 100.0}" }
        return "($price) * (CASE e.condition $cases ELSE 1 END)"
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
        f.format?.let { parts += Legality.sqlPlayable("e.legal", it) }
        if (f.colors.isNotEmpty()) {
            val any = f.colors.filter { it in "WUBRGC" }.map { c ->
                if (c == 'C') "COALESCE(e.colors, '') = ''" else "e.colors LIKE '%$c%'"
            }
            parts += any.joinToString(" OR ", "(", ")")
        }
        return (parts.joinToString(" AND ").ifEmpty { "1" }) to args.toTypedArray()
    }

    private fun seedPrice(db: Db, card: CardPrinting) {
        // Precio del catálogo como punto de partida; la actualización online lo sustituye.
        db.insertWithOnConflict("prices", null, card.prices.toValues(card.id, 0).apply { put("cm_id", card.cardmarketId) },
            Db.CONFLICT_IGNORE)
    }

    private fun Prices.toValues(id: String, updatedAt: Long) = ContentValues().apply {
        put("card_id", id)
        put("eur", eur); put("eur_foil", eurFoil); put("usd", usd); put("usd_foil", usdFoil)
        put("updated_at", updatedAt)
    }

    /** Precios de Scryfall: sin tocar los de la guía de Cardmarket ni su id. */
    private fun Prices.toUpdateValues(updatedAt: Long) = ContentValues().apply {
        put("eur", eur); put("eur_foil", eurFoil); put("usd", usd); put("usd_foil", usdFoil)
        put("updated_at", updatedAt)
    }

    private fun changed() {
        _version.value++
    }

    companion object {
        /** El día de hoy como se guarda en la evolución ("2026-09-27"). */
        fun day(millis: Long = nowMillis()): String = localDay(millis)

        /** Versión del esquema (PRAGMA user_version), como la de SQLiteOpenHelper. */
        const val VERSION = 8

        /** Valor de [CollectionFilter.deckId] para "cartas que no están en ningún mazo". */
        const val NO_DECK = 0L

        private const val GROUP_WHERE =
            "card_id = ? AND foil = ? AND condition = ? AND lang = ? AND needs_review = ? AND COALESCE(deck_id, 0) = CAST(? AS INTEGER)"

        /**
         * Mismo criterio que [Prices.of]: si falta el precio del acabado, se usa el otro.
         * Sin precio de la guía de Cardmarket (aún no descargada), el de tendencia.
         */
        private fun priceExpr(currency: Currency, kind: PriceKind = PriceKind.TREND) = when (currency) {
            Currency.USD -> "CASE WHEN e.foil = 1 THEN COALESCE(p.usd_foil, p.usd) ELSE COALESCE(p.usd, p.usd_foil) END"
            Currency.EUR -> when (kind) {
                PriceKind.TREND -> "CASE WHEN e.foil = 1 THEN COALESCE(p.eur_foil, p.eur) ELSE COALESCE(p.eur, p.eur_foil) END"
                PriceKind.LOW -> "CASE WHEN e.foil = 1 THEN COALESCE(p.eur_low_foil, p.eur_low, p.eur_foil, p.eur) " +
                    "ELSE COALESCE(p.eur_low, p.eur_low_foil, p.eur, p.eur_foil) END"
                PriceKind.AVG30 -> "CASE WHEN e.foil = 1 THEN COALESCE(p.eur_avg30_foil, p.eur_avg30, p.eur_foil, p.eur) " +
                    "ELSE COALESCE(p.eur_avg30, p.eur_avg30_foil, p.eur, p.eur_foil) END"
            }
        }
    }
}
