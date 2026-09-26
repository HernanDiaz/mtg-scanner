package com.hernan.mtgscanner

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hernan.mtgscanner.data.Catalog
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
