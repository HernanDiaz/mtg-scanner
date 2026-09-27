package com.hernan.mtgscanner

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hernan.mtgscanner.data.CardmarketPrices
import com.hernan.mtgscanner.data.Catalog
import com.hernan.mtgscanner.data.PriceKind
import com.hernan.mtgscanner.data.Prices
import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.CollectionFilter
import com.hernan.mtgscanner.data.CollectionImport
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La colección sobre SQLite real (en un fichero aparte para no tocar la del emulador). */
@RunWith(AndroidJUnit4::class)
class CollectionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: CollectionStore
    private val catalog by lazy { Catalog(context) }

    @Before fun setUp() {
        context.deleteDatabase(DB)
        store = CollectionStore(context, DB)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(DB)
    }

    @Test fun confirmingTheSuggestedPrintingClearsReview() {
        val swamp = catalog.printingsOf("Swamp").first()
        store.add(swamp, foil = false, condition = "NM", needsReview = true, lang = "es")
        val group = store.groups(Currency.EUR).single()
        assertTrue(group.needsReview)

        store.confirm(group)

        assertFalse(store.groups(Currency.EUR).single().needsReview)
        assertEquals(0, store.summary(Currency.EUR).pendingReview)
    }

    @Test fun newPendingCopyDoesNotUnconfirmReviewedOnes() {
        val swamp = catalog.printingsOf("Swamp").first()
        store.add(swamp, false, "NM", needsReview = true)
        store.add(swamp, false, "NM", needsReview = true)
        store.confirm(store.groups(Currency.EUR).single())
        store.add(swamp, false, "NM", needsReview = true) // se escanea otro pantano

        val groups = store.groups(Currency.EUR)
        assertEquals(2, groups.size)
        assertEquals(1, groups.single { it.needsReview }.quantity)
        assertEquals(2, groups.single { !it.needsReview }.quantity)
    }

    @Test fun reassignMovesOnlyThatRowAndKeepsPhotoArt() {
        val swamps = catalog.printingsOf("Swamp")
        val art = ByteArray(288) { it.toByte() }
        store.add(swamps[0], false, "NM", needsReview = true, art = art)
        val group = store.groups(Currency.EUR).single()
        assertArrayEquals(art, group.art)

        store.reassign(group, swamps[1])

        val after = store.groups(Currency.EUR).single()
        assertEquals(swamps[1].id, after.cardId)
        assertFalse(after.needsReview)
    }

    @Test fun searchFilterAndSort() {
        val bolt = catalog.printingsOf("Lightning Bolt").first()
        val swamp = catalog.printingsOf("Swamp").first()
        store.add(bolt, false, "NM", needsReview = false)
        store.add(swamp, false, "NM", needsReview = true)

        assertEquals(listOf("Lightning Bolt"), store.groups(Currency.EUR, CollectionFilter(query = "bolt")).map { it.name })
        assertEquals(listOf("Swamp"), store.groups(Currency.EUR, CollectionFilter(onlyPending = true)).map { it.name })
        assertEquals(listOf("Lightning Bolt", "Swamp"),
            store.groups(Currency.EUR, sort = CollectionStore.Sort.NAME).map { it.name })
    }

    @Test fun decksMoveSomeCopiesFilterAndDelete() {
        val bolt = catalog.printingsOf("Lightning Bolt").first()
        repeat(4) { store.add(bolt, false, "NM", needsReview = false) }
        val goblins = store.createDeck("Goblins")
        store.moveToDeck(store.groups(Currency.EUR).single(), goblins, count = 2)

        assertEquals(2, store.groups(Currency.EUR, CollectionFilter(deckId = goblins)).single().quantity)
        assertEquals(2, store.groups(Currency.EUR, CollectionFilter(deckId = CollectionStore.NO_DECK)).single().quantity)
        assertEquals(4, store.summary(Currency.EUR).cards)
        assertEquals(2, store.summary(Currency.EUR, CollectionFilter(deckId = goblins)).cards)
        assertEquals(listOf(CollectionStore.Deck(goblins, "Goblins", 2)), store.decks())

        store.deleteDeck(goblins) // las cartas vuelven a la colección sin mazo
        assertEquals(4, store.groups(Currency.EUR, CollectionFilter(deckId = CollectionStore.NO_DECK)).sumOf { it.quantity })
        assertTrue(store.decks().isEmpty())
    }

    @Test fun colorFilter() {
        store.add(catalog.printingsOf("Lightning Bolt").first(), false, "NM", needsReview = false)   // R
        store.add(catalog.printingsOf("Counterspell").first(), false, "NM", needsReview = false)     // U
        store.add(catalog.printingsOf("Fire // Ice").first(), false, "NM", needsReview = false)      // UR
        store.add(catalog.printingsOf("Sol Ring").first(), false, "NM", needsReview = false)         // incolora

        fun names(vararg c: Char) = store.groups(Currency.EUR, CollectionFilter(colors = c.toSet()), CollectionStore.Sort.NAME).map { it.name }
        assertEquals(listOf("Fire // Ice", "Lightning Bolt"), names('R'))
        assertEquals(listOf("Counterspell", "Fire // Ice", "Lightning Bolt"), names('R', 'U'))
        assertEquals(listOf("Sol Ring"), names('C'))
    }

    /** Copia de seguridad completa: exportar, sustituir la colección con el fichero y quedar igual. */
    @Test fun backupAndReplaceRestoresEverything() {
        val bolt = catalog.printingsOf("Lightning Bolt").first()
        val swamp = catalog.printingsOf("Swamp").first()
        repeat(3) { store.add(bolt, false, "EX", needsReview = false, lang = "es") }
        store.add(swamp, true, "NM", needsReview = true)
        val deck = store.createDeck("Rojo")
        store.moveToDeck(store.groups(Currency.EUR, CollectionFilter(query = "bolt")).single(), deck, 2)
        val before = snapshot()

        val names = store.decks().associate { it.id to it.name }
        val csv = CollectionExport.csv(
            CollectionExport.lines(store.groups(Currency.EUR), catalog::setName, { names[it] }, detailed = true), Currency.EUR)
        store.add(catalog.printingsOf("Counterspell").first(), false, "NM", needsReview = false) // cambios posteriores
        store.createDeck("Otro")

        val result = CollectionImport.resolve(CollectionImport.parse(csv), lookup)
        assertTrue(result.failed.isEmpty())
        store.import(result.items, replace = true)

        assertEquals(before, snapshot())
        assertEquals(listOf("Rojo"), store.decks().map { it.name })
    }

    /** Añadir no borra nada; los mazos se reutilizan por nombre y lo que no trae mazo va al que se ve. */
    @Test fun addKeepsCollectionAndReusesDecks() {
        store.add(catalog.printingsOf("Swamp").first(), false, "NM", needsReview = false)
        val red = store.createDeck("Rojo")
        val target = store.createDeck("Destino")
        val text = "Cantidad,Nombre,Código,Número,Mazo" + NL + "2,Lightning Bolt,M11,149,rojo" + NL + "1,Counterspell,,," + NL
        val result = CollectionImport.resolve(CollectionImport.parse(text), lookup)

        assertEquals(3, store.import(result.items, replace = false, defaultDeck = target))

        assertEquals(4, store.summary(Currency.EUR).cards)
        assertEquals(listOf("Destino", "Rojo"), store.decks().map { it.name })
        assertEquals("m11", store.groups(Currency.EUR, CollectionFilter(deckId = red)).single().setCode)
        assertEquals("Counterspell", store.groups(Currency.EUR, CollectionFilter(deckId = target)).single().name)
    }

    @Test fun catalogFindsNamesAndSetsWrittenByPeople() {
        assertEquals("Lightning Bolt", catalog.printingsOfAnyName("lightning bolt").first().name)
        assertEquals("Lightning Bolt", catalog.printingsOfAnyName("Relámpago").first().name)
        assertEquals("Delver of Secrets // Insectile Aberration", catalog.printingsOfAnyName("Delver of Secrets").first().name)
        assertTrue(catalog.printingsOfAnyName("Carta que no existe").isEmpty())
        assertTrue(catalog.isForeignName("Relámpago"))
        assertFalse(catalog.isForeignName("lightning bolt"))
        assertFalse(catalog.isForeignName("Insectile Aberration"))
        assertEquals("m11", catalog.setCodeFor("M11"))
        assertEquals("m11", catalog.setCodeFor("Magic 2011"))
        assertEquals(null, catalog.setCodeFor("Edición inventada"))
    }

    private val lookup by lazy {
        object : CollectionImport.Lookup {
            override fun byId(id: String) = catalog.byId(id)
            override fun bySetAndNumber(setCode: String, numberNorm: String) = catalog.bySetAndNumber(setCode, numberNorm)
            override fun printingsOf(name: String) = catalog.printingsOfAnyName(name)
            override fun setCode(value: String) = catalog.setCodeFor(value)
            override fun isForeignName(name: String) = catalog.isForeignName(name)
        }
    }

    /** Todo lo que se guarda de cada fila, con el nombre del mazo en vez de su id. */
    private fun snapshot(): Set<List<Any?>> {
        val names = store.decks().associate { it.id to it.name }
        return store.groups(Currency.EUR).map {
            listOf(it.cardId, it.foil, it.condition, it.lang, it.needsReview, it.quantity, names[it.deckId], it.colors)
        }.toSet()
    }

    @Test fun cardmarketGuideIsParsedStreaming() {
        val json = """{"version":1,"priceGuides":[
            {"idProduct":1,"idCategory":1,"avg":0.09,"low":0.02,"trend":0.07,"avg30":0.09,"low-foil":0.05,"trend-foil":0.38,"avg30-foil":0.36},
            {"idProduct":2,"low":0.5,"trend":0,"avg30":null},
            {"idProduct":3,"low":9.99}]}"""
        val prices = CardmarketPrices.parse(json.byteInputStream(), setOf(1, 2))
        assertEquals(setOf(1, 2), prices.keys)
        assertEquals(CardmarketPrices.Guide(trend = 0.07, trendFoil = 0.38, low = 0.02, lowFoil = 0.05, avg30 = 0.09, avg30Foil = 0.36), prices[1])
        assertEquals(CardmarketPrices.Guide(low = 0.5), prices[2]) // 0 y null = sin precio
    }

    /** El valor de la colección según el precio elegido; sin datos de la guía, el de tendencia. */
    @Test fun priceKinds() {
        val bolt = catalog.printingsOf("Lightning Bolt").first { it.cardmarketId != null }
        val swamp = catalog.printingsOf("Swamp").first { it.cardmarketId != null && it.cardmarketId != bolt.cardmarketId }
        store.add(bolt, false, "NM", needsReview = false)
        store.add(swamp, false, "NM", needsReview = false)
        store.updatePrices(mapOf(bolt.id to Prices(2.0, 3.0, null, null), swamp.id to Prices(0.2, null, null, null)))
        store.updateCardmarketPrices(mapOf(bolt.cardmarketId!! to CardmarketPrices.Guide(trend = 2.5, low = 1.0, avg30 = 2.2)))

        assertEquals(setOf(bolt.cardmarketId, swamp.cardmarketId), store.cardmarketIds())
        assertEquals(2.7, store.summary(Currency.EUR).total, 1e-9)                       // 2.5 (tendencia de hoy) + 0.2
        assertEquals(1.2, store.summary(Currency.EUR, kind = PriceKind.LOW).total, 1e-9)    // 1.0 + 0.2 (sin guía)
        assertEquals(2.4, store.summary(Currency.EUR, kind = PriceKind.AVG30).total, 1e-9)
        // Scryfall no borra lo de Cardmarket.
        store.updatePrices(mapOf(bolt.id to Prices(2.1, 3.0, 1.9, null)))
        assertEquals(1.2, store.summary(Currency.EUR, kind = PriceKind.LOW).total, 1e-9)
    }

    /** Desde la versión 4 (la actual del móvil): se conservan los precios y se añade la guía. */
    @Test fun upgradesFromVersion4() {
        store.close()
        context.deleteDatabase(DB)
        val bolt = catalog.printingsOf("Lightning Bolt").first { it.cardmarketId != null }
        val path = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""
                CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, card_id TEXT NOT NULL, name TEXT NOT NULL,
                  set_code TEXT NOT NULL, collector_number TEXT NOT NULL, foil INTEGER NOT NULL, condition TEXT NOT NULL,
                  lang TEXT NOT NULL DEFAULT 'en', needs_review INTEGER NOT NULL, added_at INTEGER NOT NULL, art BLOB,
                  deck_id INTEGER, colors TEXT)
            """)
            db.execSQL("CREATE TABLE prices (card_id TEXT PRIMARY KEY, eur REAL, eur_foil REAL, usd REAL, usd_foil REAL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE decks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
            db.execSQL("INSERT INTO entries (card_id, name, set_code, collector_number, foil, condition, needs_review, added_at, colors) " +
                "VALUES ('${bolt.id}', 'Lightning Bolt', '${bolt.setCode}', '${bolt.collectorNumber}', 0, 'NM', 0, 0, 'R')")
            db.execSQL("INSERT INTO prices VALUES ('${bolt.id}', 1.5, NULL, NULL, NULL, 0)")
            db.version = 4
        }
        store = CollectionStore(context, DB)
        assertEquals(1.5, store.summary(Currency.EUR).total, 1e-9)
        assertTrue(store.cardmarketIds().isEmpty())
        store.backfillCardmarketIds { id -> catalog.byId(id)?.cardmarketId }
        assertEquals(setOf(bolt.cardmarketId), store.cardmarketIds())
        assertTrue(store.wishlist(Currency.EUR).isEmpty()) // v6: la lista de deseos se crea
    }

    /** Ficha de carta: cambiar estado y foil, copias de la carta y precios guardados. */
    @Test fun cardSheetEdits() {
        val bolts = catalog.printingsOf("Lightning Bolt").filter { it.cardmarketId != null }
        repeat(2) { store.add(bolts[0], false, "NM", needsReview = false) }
        store.add(bolts[1], false, "NM", needsReview = false)
        assertEquals(3, store.copiesOf("Lightning Bolt"))

        val g = store.groups(Currency.EUR).single { it.cardId == bolts[0].id }
        store.setCondition(g, "EX")
        val ex = store.groups(Currency.EUR).single { it.cardId == bolts[0].id }
        assertEquals("EX", ex.condition)
        assertEquals(2, ex.quantity)
        store.setFoil(ex, true)
        assertTrue(store.groups(Currency.EUR).single { it.cardId == bolts[0].id }.foil)

        store.updatePrices(mapOf(bolts[0].id to Prices(1.0, 2.0, 3.0, 4.0)))
        store.updateCardmarketPrices(mapOf(bolts[0].cardmarketId!! to CardmarketPrices.Guide(trend = 1.1, low = 0.5, avg30Foil = 2.5)))
        val p = store.storedPrices(bolts[0].id)!!
        assertEquals(1.1, p.eur!!, 1e-9)
        assertEquals(0.5, p.eurLow!!, 1e-9)
        assertEquals(2.5, p.eurAvg30Foil!!, 1e-9)
        assertEquals(3.0, p.usd!!, 1e-9)
        assertTrue(p.updatedAt > 0)
    }

    @Test fun wishlist() {
        val bolt = catalog.printingsOf("Lightning Bolt").first()
        store.addWish(bolt, 3)
        store.addWish(bolt, 2)   // comprobar otra vez el mismo mazo no suma
        assertEquals(3, store.wishlist(Currency.EUR).single().quantity)
        assertTrue(bolt.id in store.distinctCardIds()) // sus precios se actualizan

        assertTrue(store.fulfillWish("Lightning Bolt"))
        assertEquals(2, store.wishlist(Currency.EUR).single().quantity)
        store.unfulfillWish(bolt)
        assertEquals(3, store.wishlist(Currency.EUR).single().quantity)
        store.setWishQuantity("Lightning Bolt", 0)
        assertTrue(store.wishlist(Currency.EUR).isEmpty())
        assertFalse(store.fulfillWish("Lightning Bolt"))

        store.add(bolt, false, "NM", needsReview = false)
        store.add(catalog.printingsOf("Lightning Bolt")[1], false, "NM", needsReview = false)
        assertEquals(mapOf("Lightning Bolt" to 2), store.ownedByName())
    }

    @Test fun cardDetailsFromScryfallJson() {
        val json = org.json.JSONObject("""{"name":"Delver of Secrets // Insectile Aberration",
            "legalities":{"modern":"legal","vintage":"restricted","standard":"not_legal"},
            "purchase_uris":{"cardmarket":"https://cardmarket.example/delver"},
            "scryfall_uri":"https://scryfall.example/delver","artist":"Nils Hamm",
            "card_faces":[
              {"name":"Delver of Secrets","mana_cost":"{U}","type_line":"Creature — Human Wizard","oracle_text":"At the beginning...","power":"1","toughness":"1"},
              {"name":"Insectile Aberration","mana_cost":"","type_line":"Creature — Human Insect","oracle_text":"Flying","power":"3","toughness":"2"}]}""")
        val d = com.hernan.mtgscanner.data.CardDetails.parse(json)
        assertEquals(listOf("Delver of Secrets", "Insectile Aberration"), d.faces.map { it.name })
        assertEquals("{U}", d.faces[0].manaCost)
        assertEquals(null, d.faces[1].manaCost) // "" = sin coste
        assertEquals("Nils Hamm", d.faces[1].artist)
        assertEquals("restricted", d.legalities["vintage"])
        assertEquals("https://cardmarket.example/delver", d.cardmarketUrl)
    }

    /** El móvil con la versión anterior (base de datos v3) conserva sus cartas y recupera los colores. */
    @Test fun upgradesFromVersion3AndBackfillsColors() {
        store.close()
        context.deleteDatabase(DB)
        val bolt = catalog.printingsOf("Lightning Bolt").first()
        val path = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""
                CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, card_id TEXT NOT NULL, name TEXT NOT NULL,
                  set_code TEXT NOT NULL, collector_number TEXT NOT NULL, foil INTEGER NOT NULL, condition TEXT NOT NULL,
                  lang TEXT NOT NULL DEFAULT 'en', needs_review INTEGER NOT NULL, added_at INTEGER NOT NULL, art BLOB)
            """)
            db.execSQL("CREATE TABLE prices (card_id TEXT PRIMARY KEY, eur REAL, eur_foil REAL, usd REAL, usd_foil REAL, updated_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO entries (card_id, name, set_code, collector_number, foil, condition, needs_review, added_at) " +
                "VALUES ('${bolt.id}', 'Lightning Bolt', '${bolt.setCode}', '${bolt.collectorNumber}', 0, 'NM', 0, 0)")
            db.version = 3
        }
        store = CollectionStore(context, DB)
        assertTrue(store.groups(Currency.EUR, CollectionFilter(colors = setOf('R'))).isEmpty()) // aún sin colores
        store.backfillColors { id -> catalog.byId(id)?.colors }
        assertEquals("Lightning Bolt", store.groups(Currency.EUR, CollectionFilter(colors = setOf('R'))).single().name)
        assertTrue(store.decks().isEmpty())
    }

    /** Los móviles con la versión anterior (base de datos v2) conservan sus cartas. */
    @Test fun upgradesFromVersion2() {
        store.close()
        context.deleteDatabase(DB)
        val path = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""
                CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, card_id TEXT NOT NULL, name TEXT NOT NULL,
                  set_code TEXT NOT NULL, collector_number TEXT NOT NULL, foil INTEGER NOT NULL, condition TEXT NOT NULL,
                  lang TEXT NOT NULL DEFAULT 'en', needs_review INTEGER NOT NULL, added_at INTEGER NOT NULL)
            """)
            db.execSQL("CREATE TABLE prices (card_id TEXT PRIMARY KEY, eur REAL, eur_foil REAL, usd REAL, usd_foil REAL, updated_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO entries (card_id, name, set_code, collector_number, foil, condition, needs_review, added_at) " +
                "VALUES ('x', 'Old Card', 'lea', '1', 0, 'NM', 1, 0)")
            db.version = 2
        }
        store = CollectionStore(context, DB)
        val g = store.groups(Currency.EUR).single()
        assertEquals("Old Card", g.name)
        assertEquals(null, g.art)
    }

    private companion object {
        const val DB = "test_collection.db"
        const val NL = "\n"
    }
}
