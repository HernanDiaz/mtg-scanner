package com.hernan.mtgscanner

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hernan.mtgscanner.data.Catalog
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

        assertEquals(listOf("Lightning Bolt"), store.groups(Currency.EUR, query = "bolt").map { it.name })
        assertEquals(listOf("Swamp"), store.groups(Currency.EUR, onlyPending = true).map { it.name })
        assertEquals(listOf("Lightning Bolt", "Swamp"),
            store.groups(Currency.EUR, sort = CollectionStore.Sort.NAME).map { it.name })
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
    }
}
